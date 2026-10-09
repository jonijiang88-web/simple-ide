package org.jetbrains.jps.cli;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 解析 Maven POM 文件及其子模块拓扑与依赖关系的解析器
 */
public class MavenPomResolver {

    private final File rootPomFile;
    private final File localRepositoryDir;
    private final File cacheDir;

    /**
     * 构造解析器，默认从用户主目录 .m2/repository 寻找本地 Jar
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param cacheDir 构建缓存目录
     */
    public MavenPomResolver(File rootPomFile, File cacheDir) {
        this(rootPomFile, new File(System.getProperty("user.home"), ".m2/repository"), cacheDir);
    }

    /**
     * 构造解析器并显式指定本地 Maven 仓库目录与构建缓存目录
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param localRepositoryDir 本地 Maven 仓库目录
     * @param cacheDir 构建缓存目录
     */
    public MavenPomResolver(File rootPomFile, File localRepositoryDir, File cacheDir) {
        this.rootPomFile = rootPomFile;
        this.localRepositoryDir = localRepositoryDir;
        this.cacheDir = cacheDir;
    }

    /**
     * 解析根 POM 及其包含的所有子模块，输出模块元数据列表
     *
     * @return 模块元数据列表
     * @throws Exception 解析异常
     */
    public List<MavenModuleInfo> resolve() throws Exception {
        Map<String, MavenModuleInfo> moduleMap = new LinkedHashMap<>();
        Map<String, List<Dependency>> moduleDependenciesMap = new HashMap<>();

        resolveModuleRecursive(rootPomFile, moduleMap, moduleDependenciesMap);

        // 统一关联内部模块依赖与外部 Jar 依赖
        Set<String> internalModuleNames = moduleMap.keySet();
        for (Map.Entry<String, List<Dependency>> entry : moduleDependenciesMap.entrySet()) {
            String moduleName = entry.getKey();
            MavenModuleInfo moduleInfo = moduleMap.get(moduleName);
            if (moduleInfo == null) continue;

            for (Dependency dep : entry.getValue()) {
                String depArtifactId = dep.getArtifactId();
                if (internalModuleNames.contains(depArtifactId)) {
                    // 同一项目内的子模块依赖
                    moduleInfo.addModuleDependency(depArtifactId);
                } else {
                    // 外部第三方依赖，从本地仓寻找 jar
                    File jarFile = findDependencyJar(dep);
                    if (jarFile != null && jarFile.exists()) {
                        moduleInfo.addDependencyJar(jarFile);
                    }
                }
            }

            // 若项目依赖继承自 parent/BOM 导致静态 jar 缺失或不足，尝试从完整 Classpath 缓存中加载
            ensureCompleteDependencies(moduleInfo);
        }

        return new ArrayList<>(moduleMap.values());
    }

    /**
     * 确保模块具备完整依赖（优先读取持久化缓存，缺失时通过 Maven 快速导出并缓存）
     *
     * @param moduleInfo 模块信息
     */
    private void ensureCompleteDependencies(MavenModuleInfo moduleInfo) {
        File classpathCacheFile = new File(cacheDir, "classpath-" + moduleInfo.getModuleName() + ".cache");
        File pomFile = new File(moduleInfo.getBaseDir(), "pom.xml");

        // 1. 若缓存文件存在且更新时间晚于 pom.xml，直接从缓存加载
        if (classpathCacheFile.exists() && classpathCacheFile.lastModified() >= pomFile.lastModified()) {
            List<File> cachedJars = readClasspathCache(classpathCacheFile);
            if (!cachedJars.isEmpty()) {
                for (File jar : cachedJars) {
                    moduleInfo.addDependencyJar(jar);
                }
                return;
            }
        }

        // 2. 若当前静态解析到的依赖少于 3 个，或存在复杂 parent 继承，自动调用快速导出并写入缓存
        if (moduleInfo.getDependencyJars().size() < 3 || isComplexProject(pomFile)) {
            List<File> resolvedJars = resolveViaMavenBuildClasspath(moduleInfo.getBaseDir(), classpathCacheFile);
            for (File jar : resolvedJars) {
                moduleInfo.addDependencyJar(jar);
            }
        }
    }

    /**
     * 判断 POM 文件是否引用了外部 parent 或 spring-boot 等复杂脚手架
     *
     * @param pomFile pom 文件
     * @return 是否为复杂项目
     */
    private boolean isComplexProject(File pomFile) {
        if (!pomFile.exists()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(pomFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<parent>") || line.contains("spring-boot") || line.contains("dependencyManagement")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 通过外部 Maven 快速解析导出当前模块的完整 Classpath
     *
     * @param baseDir 模块根目录
     * @param cacheOutputFile 缓存输出文件
     * @return 解析出的 Jar 文件列表
     */
    private List<File> resolveViaMavenBuildClasspath(File baseDir, File cacheOutputFile) {
        List<File> jars = new ArrayList<>();
        File tempCpFile = new File(cacheOutputFile.getParentFile(), "temp-cp-" + System.currentTimeMillis() + ".txt");
        cacheOutputFile.getParentFile().mkdirs();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-q", "-DskipTests",
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + tempCpFile.getAbsolutePath(),
                    "-DincludeScope=test"
            );
            pb.directory(baseDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0 && tempCpFile.exists()) {
                String cpContent = new String(java.nio.file.Files.readAllBytes(tempCpFile.toPath()), StandardCharsets.UTF_8).trim();
                if (!cpContent.isEmpty()) {
                    String[] paths = cpContent.split(File.pathSeparator);
                    try (PrintWriter pw = new PrintWriter(new FileWriter(cacheOutputFile))) {
                        for (String p : paths) {
                            File f = new File(p.trim());
                            if (f.exists() && f.getName().endsWith(".jar")) {
                                jars.add(f);
                                pw.println(f.getAbsolutePath());
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (tempCpFile.exists()) {
                tempCpFile.delete();
            }
        }
        return jars;
    }

    /**
     * 从本地缓存文件中读取 Jar 路径列表
     *
     * @param cacheFile 缓存文件
     * @return Jar 文件列表
     */
    private List<File> readClasspathCache(File cacheFile) {
        List<File> jars = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    File f = new File(line);
                    if (f.exists()) {
                        jars.add(f);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return jars;
    }

    /**
     * 递归解析 POM 文件及可能声明的子模块
     *
     * @param pomFile 当前处理的 pom.xml 文件
     * @param moduleMap 收集的模块映射表
     * @param moduleDependenciesMap 收集的依赖原始声明映射表
     * @throws Exception 读取与解析异常
     */
    private void resolveModuleRecursive(File pomFile, Map<String, MavenModuleInfo> moduleMap,
                                        Map<String, List<Dependency>> moduleDependenciesMap) throws Exception {
        if (!pomFile.exists() || !pomFile.isFile()) {
            return;
        }

        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model;
        try (InputStreamReader isr = new InputStreamReader(new FileInputStream(pomFile), StandardCharsets.UTF_8)) {
            model = reader.read(isr);
        }

        File baseDir = pomFile.getParentFile();
        String artifactId = model.getArtifactId();
        if (artifactId == null || artifactId.trim().isEmpty()) {
            artifactId = baseDir.getName();
        }

        // 解析构建目录
        File sourceDir = new File(baseDir, "src/main/java");
        File resourceDir = new File(baseDir, "src/main/resources");
        File testSourceDir = new File(baseDir, "src/test/java");
        File testResourceDir = new File(baseDir, "src/test/resources");
        File outputDir = new File(baseDir, "target/classes");
        File testOutputDir = new File(baseDir, "target/test-classes");

        if (model.getBuild() != null) {
            if (model.getBuild().getSourceDirectory() != null) {
                sourceDir = resolvePath(baseDir, model.getBuild().getSourceDirectory());
            }
            if (model.getBuild().getTestSourceDirectory() != null) {
                testSourceDir = resolvePath(baseDir, model.getBuild().getTestSourceDirectory());
            }
            if (model.getBuild().getOutputDirectory() != null) {
                outputDir = resolvePath(baseDir, model.getBuild().getOutputDirectory());
            }
            if (model.getBuild().getTestOutputDirectory() != null) {
                testOutputDir = resolvePath(baseDir, model.getBuild().getTestOutputDirectory());
            }
        }

        // 如果该 POM 包含源码或不是单纯的 pom packaging，注册为编译模块
        boolean isPomPackaging = "pom".equalsIgnoreCase(model.getPackaging());
        if (!isPomPackaging || sourceDir.exists()) {
            MavenModuleInfo info = new MavenModuleInfo(
                    artifactId, baseDir, sourceDir, resourceDir,
                    testSourceDir, testResourceDir, outputDir, testOutputDir
            );
            moduleMap.put(artifactId, info);
            moduleDependenciesMap.put(artifactId, new ArrayList<>(model.getDependencies()));
        }

        // 递归处理子模块
        List<String> modules = model.getModules();
        if (modules != null) {
            for (String subModuleRelative : modules) {
                File subModuleDir = new File(baseDir, subModuleRelative);
                File subPomFile = subModuleDir.isDirectory() ? new File(subModuleDir, "pom.xml") : subModuleDir;
                resolveModuleRecursive(subPomFile, moduleMap, moduleDependenciesMap);
            }
        }
    }

    /**
     * 将相对路径解析为基于 baseDir 的绝对文件对象
     *
     * @param baseDir 基准目录
     * @param path 待解析路径
     * @return 绝对或解析后文件对象
     */
    private File resolvePath(File baseDir, String path) {
        File f = new File(path);
        if (f.isAbsolute()) {
            return f;
        }
        return new File(baseDir, path);
    }

    /**
     * 根据 groupId/artifactId/version 在本地 Maven 仓库定位对应的 Jar 文件
     *
     * @param dep Maven 依赖对象
     * @return 本地 Jar 文件，若未定位到则返回 null
     */
    private File findDependencyJar(Dependency dep) {
        if (dep.getGroupId() == null || dep.getArtifactId() == null || dep.getVersion() == null) {
            return null;
        }
        if (dep.getVersion().startsWith("${")) {
            return null;
        }
        String groupSubPath = dep.getGroupId().replace('.', '/');
        String jarName = dep.getArtifactId() + "-" + dep.getVersion() + ".jar";
        File jarFile = new File(localRepositoryDir, groupSubPath + "/" + dep.getArtifactId() + "/" + dep.getVersion() + "/" + jarName);
        return jarFile.exists() ? jarFile : null;
    }
}
