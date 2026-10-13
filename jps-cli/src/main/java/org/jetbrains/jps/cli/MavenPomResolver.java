package org.jetbrains.jps.cli;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 解析 Maven POM 文件及其子模块拓扑与依赖关系的解析器
 */
public class MavenPomResolver {

    /** 依赖解析失败：无法得到可确证完整的依赖集合（禁止用残缺依赖继续构建）。 */
    public static class DependencyResolutionException extends Exception {
        /**
         * 构造依赖解析失败异常
         *
         * @param message 失败说明（包含模块与原因）
         */
        public DependencyResolutionException(String message) {
            super(message);
        }
    }

    /** 依赖 Classpath 缓存格式版本；格式变化时必须递增，使旧缓存显式失效。 */
    public static final String CACHE_FORMAT_VERSION = "2";
    /** jps-cli 工具版本，参与缓存指纹，保证工具升级后缓存可追溯失效。 */
    public static final String TOOL_VERSION = "1.0.0";
    /** Maven 依赖解析默认超时（秒），可通过 -Dsimple.ide.maven.timeout.sec 覆盖。 */
    public static final int DEFAULT_MAVEN_TIMEOUT_SEC = 300;

    private final File rootPomFile;
    private final File localRepositoryDir;
    private final File classpathCacheDir;

    /** 依赖 Classpath 缓存校验结论。 */
    public enum CacheStatus {
        /** 缓存文件不存在。 */
        ABSENT,
        /** 缓存内容为空。 */
        EMPTY,
        /** 缓存中列出的 Jar 有缺失，不能当作完整缓存接受。 */
        MISSING_JARS,
        /** 旧格式（无指纹元数据）但结构完整，可迁移采纳。 */
        LEGACY_ADOPTABLE,
        /** 元数据格式版本变化，需要重新解析。 */
        FORMAT_CHANGED,
        /** 输入指纹变化（模块/父 POM、构建配置、JDK 或工具版本变化）。 */
        FINGERPRINT_MISMATCH,
        /** 条目数与元数据不一致。 */
        ENTRY_COUNT_MISMATCH,
        /** 缓存有效。 */
        VALID
    }

    /** 依赖解析结果（区分「成功但无依赖」与「解析失败」）。 */
    public static final class Resolution {
        /** 是否成功完成解析。 */
        public final boolean resolved;
        /** 解析出的 Jar 列表（失败时为空）。 */
        public final List<File> jars;
        /** 失败原因；成功时为 null。 */
        public final String error;

        Resolution(boolean resolved, List<File> jars, String error) {
            this.resolved = resolved;
            this.jars = jars;
            this.error = error;
        }
    }

    /**
     * 构造解析器，默认从用户主目录 .m2/repository 寻找本地 Jar；
     * 依赖 Classpath 缓存默认落在 JPS 缓存目录的兄弟目录 .jps-deps
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param cacheDir JPS 增量构建数据目录（JPS 在存储损坏时会整体删除该目录，故不可用于存放依赖缓存）
     */
    public MavenPomResolver(File rootPomFile, File cacheDir) {
        this(rootPomFile, new File(System.getProperty("user.home"), ".m2/repository"), cacheDir, defaultClasspathCacheDir(rootPomFile, cacheDir));
    }

    /**
     * 使用默认本地 Maven 仓库（~/.m2/repository）创建解析器，并显式指定依赖 Classpath 缓存目录
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param cacheDir JPS 增量构建数据目录
     * @param classpathCacheDir 依赖 Classpath 缓存目录（须独立于 cacheDir，避免被 JPS 清理误删）
     * @return 解析器实例
     */
    public static MavenPomResolver withClasspathCacheDir(File rootPomFile, File cacheDir, File classpathCacheDir) {
        return new MavenPomResolver(rootPomFile, new File(System.getProperty("user.home"), ".m2/repository"), cacheDir, classpathCacheDir);
    }

    /**
     * 构造解析器并显式指定本地 Maven 仓库目录与 JPS 缓存目录；
     * 依赖 Classpath 缓存默认落在 JPS 缓存目录的兄弟目录 .jps-deps
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param localRepositoryDir 本地 Maven 仓库目录
     * @param cacheDir JPS 增量构建数据目录
     */
    public MavenPomResolver(File rootPomFile, File localRepositoryDir, File cacheDir) {
        this(rootPomFile, localRepositoryDir, cacheDir, defaultClasspathCacheDir(rootPomFile, cacheDir));
    }

    /**
     * 构造解析器并显式指定本地 Maven 仓库目录、JPS 缓存目录与依赖 Classpath 缓存目录
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param localRepositoryDir 本地 Maven 仓库目录
     * @param cacheDir JPS 增量构建数据目录
     * @param classpathCacheDir 依赖 Classpath 缓存目录
     */
    public MavenPomResolver(File rootPomFile, File localRepositoryDir, File cacheDir, File classpathCacheDir) {
        this.rootPomFile = rootPomFile;
        this.localRepositoryDir = localRepositoryDir;
        this.classpathCacheDir = classpathCacheDir;
    }

    /**
     * 计算默认的依赖 Classpath 缓存目录：与 JPS 缓存目录同级的 .jps-deps 目录；
     * 之所以与 JPS 缓存目录分离，是因为 JPS 检测到存储损坏时会递归删除整个 dataStorageRoot
     *
     * @param rootPomFile 根 pom.xml 文件
     * @param cacheDir JPS 增量构建数据目录
     * @return 默认依赖 Classpath 缓存目录
     */
    private static File defaultClasspathCacheDir(File rootPomFile, File cacheDir) {
        File parent = cacheDir != null ? cacheDir.getParentFile() : null;
        if (parent == null && rootPomFile != null) {
            parent = rootPomFile.getParentFile();
        }
        if (parent == null) {
            parent = new File(".");
        }
        return new File(parent, ".jps-deps");
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
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<Dependency>> entry : moduleDependenciesMap.entrySet()) {
            String moduleName = entry.getKey();
            MavenModuleInfo moduleInfo = moduleMap.get(moduleName);
            if (moduleInfo == null) continue;

            for (Dependency dep : entry.getValue()) {
                String depArtifactId = dep.getArtifactId();
                if (internalModuleNames.contains(depArtifactId)) {
                    // 同一项目内的子模块依赖：由 reactor 模块图直接提供，天然完整
                    moduleInfo.addModuleDependency(depArtifactId);
                } else {
                    // 外部第三方依赖，从本地仓寻找 jar
                    File jarFile = findDependencyJar(dep);
                    if (jarFile != null && jarFile.exists()) {
                        moduleInfo.addDependencyJar(jarFile);
                    }
                }
            }

            // 依赖必须可确证完整（缓存指纹有效 / Maven 解析成功 / 静态 reactor 证明完整），否则硬失败
            String failure = ensureCompleteDependencies(moduleInfo, moduleMap.keySet());
            if (failure != null) {
                failures.add(moduleName + ": " + failure);
            }
        }

        if (!failures.isEmpty()) {
            throw new DependencyResolutionException("依赖解析失败，禁止使用残缺依赖继续构建: " + failures);
        }

        return new ArrayList<>(moduleMap.values());
    }

    /**
     * 确保模块具备完整依赖（优先读取通过指纹校验的持久化缓存，缺失或失效时通过 Maven 快速导出并原子写入缓存）；
     * 缓存文件写入独立的 classpathCacheDir，避免被 JPS 在存储损坏时清空 JPS 缓存目录而误删
     *
     * @param moduleInfo 模块信息
     */
    private String ensureCompleteDependencies(MavenModuleInfo moduleInfo, Set<String> allModuleNames) {
        File cacheFile = classpathCacheFile(moduleInfo);
        File pomFile = new File(moduleInfo.getBaseDir(), "pom.xml");
        String fingerprint = computeFingerprint(pomFile, localRepositoryDir);
        CacheStatus status = validateClasspathCache(cacheFile, pomFile, localRepositoryDir);

        // 1. 缓存有效：直接采纳（已通过指纹与「全部 Jar 存在」校验）
        if (status == CacheStatus.VALID) {
            for (File jar : existingJars(readCacheEntries(cacheFile))) {
                moduleInfo.addDependencyJar(jar);
            }
            return null;
        }

        // 2. 旧格式缓存：结构完整时迁移采纳，写入指纹元数据，保证后续变更能正确失效
        if (status == CacheStatus.LEGACY_ADOPTABLE) {
            List<File> cachedJars = existingJars(readCacheEntries(cacheFile));
            if (!cachedJars.isEmpty()) {
                System.err.println("[INFO] 依赖缓存缺少指纹元数据（旧格式），已迁移采纳并补写 v"
                        + CACHE_FORMAT_VERSION + " 元数据: " + cacheFile.getAbsolutePath());
                writeClasspathCacheMeta(cacheFile, fingerprint, cachedJars.size());
                for (File jar : cachedJars) {
                    moduleInfo.addDependencyJar(jar);
                }
                return null;
            }
        }

        // 3. 尝试通过 Maven 解析完整依赖（含传递依赖与 parent/BOM）
        Resolution resolution = resolveViaMavenBuildClasspath(moduleInfo.getBaseDir(), cacheFile, pomFile, allModuleNames);
        if (resolution.resolved) {
            for (File jar : resolution.jars) {
                moduleInfo.addDependencyJar(jar);
            }
            return null;
        }

        // 4. Maven 解析不可用/失败：只有在能够「证明」静态解析完整时才允许继续，
        //    否则必须硬失败，不允许把残缺依赖当成可用结果
        StaticProof proof = proveStaticCompleteness(moduleInfo, pomFile, allModuleNames);
        if (proof.provable) {
            System.err.println("[INFO] Maven 依赖解析不可用（" + resolution.error + "），"
                    + "但静态解析已被证明完整（" + proof.reason + "），继续使用已验证的静态依赖集合: "
                    + moduleInfo.getModuleName());
            // 已确证完整：按完整结果落缓存，后续构建无需重复调用 Maven
            List<File> provenJars = new ArrayList<>();
            for (File jar : moduleInfo.getDependencyJars()) {
                provenJars.add(jar);
            }
            writeClasspathCache(cacheFile, provenJars, fingerprint);
            return null;
        }

        System.err.println("[ERROR] 依赖解析失败且无法确认完整（按保守策略拒绝继续）: " + moduleInfo.getModuleName()
                + " 原因=" + resolution.error + " 判定=" + proof.reason);
        return "依赖解析失败（" + resolution.error + "）；" + proof.reason;
    }

    /** 静态解析完整性证明结果。 */
    static final class StaticProof {
        /** 是否已证明完整。 */
        final boolean provable;
        /** 证明依据或失败原因。 */
        final String reason;

        StaticProof(boolean provable, String reason) {
            this.provable = provable;
            this.reason = reason;
        }
    }

    /**
     * 证明「静态解析结果」覆盖了模块全部依赖。
     *
     * <p>可证明的条件（全部满足）：
     * <ul>
     *   <li>模块 pom 及其父 POM 链中不存在 dependencyManagement（会引入不可见版本）与 profiles；</li>
     *   <li>父 POM（非模块自身）不声明 dependencies（会继承外部依赖）；</li>
     *   <li>每个非 reactor 依赖都使用字面版本且能在本地仓库定位到 Jar；</li>
     *   <li>reactor 内部依赖由模块图直接提供。</li>
     * </ul>
     * 任一条件不满足即视为「无法证明完整」，调用方必须硬失败。
     *
     * @param moduleInfo 模块信息
     * @param modulePom 模块 pom
     * @param allModuleNames reactor 模块名集合
     * @return 证明结果
     */
    StaticProof proveStaticCompleteness(MavenModuleInfo moduleInfo, File modulePom, Set<String> allModuleNames) {
        List<Dependency> dependencies = readDeclaredDependencies(modulePom);
        if (dependencies == null) {
            return new StaticProof(false, "无法读取模块依赖声明: " + modulePom.getName());
        }
        if (dependencies.isEmpty()) {
            // 零外部依赖才可能不需要 Maven 解析；仍需排除父 POM 继承带来的隐式依赖
            for (File pom : parentPomChain(modulePom)) {
                if (!pom.equals(modulePom) && containsTag(pom, "dependencies")) {
                    return new StaticProof(false, "父 POM 声明了会被继承的 dependencies: " + pom.getName());
                }
            }
            return new StaticProof(true, "模块声明的依赖为空且父 POM 无继承依赖");
        }

        // 有依赖声明时：必须全部是 reactor 内部模块，且不存在会让外部依赖「隐形进入」的语义
        for (File pom : parentPomChain(modulePom)) {
            if (containsTag(pom, "dependencyManagement")) {
                return new StaticProof(false, "存在 dependencyManagement（可能引入不可见依赖/版本）: " + pom.getName());
            }
            if (containsTag(pom, "profiles")) {
                return new StaticProof(false, "存在 profiles（可能按环境引入依赖）: " + pom.getName());
            }
            if (!pom.equals(modulePom) && containsTag(pom, "dependencies")) {
                return new StaticProof(false, "父 POM 声明了会被继承的 dependencies: " + pom.getName());
            }
        }
        List<String> external = new ArrayList<>();
        for (Dependency dep : dependencies) {
            String artifactId = dep.getArtifactId();
            if (artifactId == null || artifactId.trim().isEmpty()) {
                return new StaticProof(false, "存在缺少 artifactId 的依赖声明");
            }
            if (!allModuleNames.contains(artifactId)) {
                external.add(artifactId);
            }
        }
        if (!external.isEmpty()) {
            // 关键：静态解析只加入直接 Jar，无法构造传递依赖闭包（依赖自身 POM/profile/BOM/scope/
            // optional/exclusion/冲突调解都不在覆盖范围内），因此不能声称「证明完整」。
            return new StaticProof(false,
                    "存在未由 Maven 完成解析的外部依赖（静态解析只含直接 Jar，无法证明传递闭包完整）: " + external);
        }
        return new StaticProof(true, "全部 " + dependencies.size() + " 个依赖均由 reactor 模块图提供，无需外部解析");
    }

    /** 判断 pom 中是否存在指定标签。 */
    private static boolean containsTag(File pom, String tag) {
        if (pom == null || !pom.isFile()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(pom))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<" + tag + ">") || line.contains("<" + tag + " ")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 读取模块声明的直接依赖（无法读取返回 null）。 */
    static List<Dependency> readDeclaredDependencies(File pom) {
        try (InputStreamReader isr = new InputStreamReader(new FileInputStream(pom), StandardCharsets.UTF_8)) {
            Model model = new MavenXpp3Reader().read(isr);
            return new ArrayList<>(model.getDependencies());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算模块对应的依赖 Classpath 缓存文件路径。
     *
     * @param moduleInfo 模块信息
     * @return 缓存文件
     */
    File classpathCacheFile(MavenModuleInfo moduleInfo) {
        return new File(classpathCacheDir, "classpath-" + moduleInfo.getModuleName() + ".cache");
    }

    /**
     * 校验依赖 Classpath 缓存是否可采纳：要求元数据指纹匹配、格式版本一致、条目数一致，
     * 且缓存中列出的 Jar 全部存在（缺失 Jar 不得被静默当作部分缓存接受）。
     *
     * @param cacheFile 缓存文件
     * @param pomFile 模块 pom.xml
     * @param localRepositoryDir 本地 Maven 仓库目录
     * @return 校验结论
     */
    static CacheStatus validateClasspathCache(File cacheFile, File pomFile, File localRepositoryDir) {
        if (cacheFile == null || !cacheFile.isFile()) return CacheStatus.ABSENT;
        File metaFile = classpathCacheMetaFile(cacheFile);
        List<String> entries = readCacheEntries(cacheFile);
        if (entries.isEmpty()) {
            // 允许「已确证无外部依赖」的空缓存：仅当存在匹配指纹的元数据且条目数为 0 时才有效
            if (!metaFile.isFile()) return CacheStatus.EMPTY;
            Properties zeroMeta = readMeta(metaFile);
            if (!CACHE_FORMAT_VERSION.equals(zeroMeta.getProperty("formatVersion"))) return CacheStatus.FORMAT_CHANGED;
            if (!"0".equals(zeroMeta.getProperty("entryCount"))) return CacheStatus.EMPTY;
            if (!computeFingerprint(pomFile, localRepositoryDir).equals(zeroMeta.getProperty("fingerprint"))) {
                return CacheStatus.FINGERPRINT_MISMATCH;
            }
            return CacheStatus.VALID;
        }
        if (!metaFile.isFile()) {
            // 旧格式缓存：仍必须先确认条目齐全，缺失 Jar 不得被采纳为部分缓存
            for (String entry : entries) {
                if (!new File(entry).exists()) return CacheStatus.MISSING_JARS;
            }
            return CacheStatus.LEGACY_ADOPTABLE;
        }
        for (String entry : entries) {
            if (!new File(entry).exists()) return CacheStatus.MISSING_JARS;
        }
        Properties meta = readMeta(metaFile);
        if (!CACHE_FORMAT_VERSION.equals(meta.getProperty("formatVersion"))) return CacheStatus.FORMAT_CHANGED;
        String expected = computeFingerprint(pomFile, localRepositoryDir);
        if (!expected.equals(meta.getProperty("fingerprint"))) return CacheStatus.FINGERPRINT_MISMATCH;
        String count = meta.getProperty("entryCount");
        if (count == null || !count.equals(Integer.toString(entries.size()))) return CacheStatus.ENTRY_COUNT_MISMATCH;
        return CacheStatus.VALID;
    }

    /**
     * 计算依赖缓存输入指纹：模块 pom 与整条父 POM 链的内容、Maven settings 文件内容、
     * 活动 profiles、JDK 与工具版本、缓存格式版本。
     *
     * <p>安全约束：只对 settings 文件做摘要，绝不读取、记录或暴露其中的凭据内容。
     *
     * @param modulePom 模块 pom.xml
     * @param localRepositoryDir 本地 Maven 仓库目录（参与指纹，避免换仓库后误用旧缓存）
     * @return SHA-256 十六进制指纹
     */
    static String computeFingerprint(File modulePom, File localRepositoryDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("format=").append(CACHE_FORMAT_VERSION).append('\n');
        sb.append("tool=").append(TOOL_VERSION).append('\n');
        sb.append("java=").append(System.getProperty("java.version", "?")).append(';')
                .append(System.getProperty("java.home", "?")).append('\n');
        sb.append("repo=").append(localRepositoryDir != null ? localRepositoryDir.getAbsolutePath() : "?").append('\n');
        sb.append("profiles=").append(System.getProperty("simple.ide.maven.profiles", "")).append('\n');
        for (File pom : parentPomChain(modulePom)) {
            sb.append("pom=").append(pom.getAbsolutePath()).append(';').append(sha256Hex(pom)).append('\n');
        }
        for (File settings : settingsFiles()) {
            sb.append("settings=").append(settings.getAbsolutePath()).append(';').append(sha256Hex(settings)).append('\n');
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 收集模块 pom 及其父 POM 链（跟随 &lt;parent&gt; 的 relativePath，默认 ../pom.xml）。
     *
     * @param modulePom 模块 pom.xml
     * @return 从模块 pom 到最顶层父 POM 的文件列表
     */
    static List<File> parentPomChain(File modulePom) {
        List<File> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        File current = modulePom;
        while (current != null && current.isFile() && visited.add(current.getAbsolutePath())) {
            chain.add(current);
            current = resolveParentPom(current);
        }
        return chain;
    }

    /** 解析 pom 中声明的 parent pom 文件（用于缓存指纹）。 */
    private static File resolveParentPom(File pomFile) {
        try (InputStreamReader isr = new InputStreamReader(new FileInputStream(pomFile), StandardCharsets.UTF_8)) {
            Model model = new MavenXpp3Reader().read(isr);
            Parent parent = model.getParent();
            if (parent == null) return null;
            String relative = parent.getRelativePath();
            if (relative == null || relative.trim().isEmpty()) {
                relative = "../pom.xml";
            }
            File candidate = new File(relative);
            if (!candidate.isAbsolute()) {
                candidate = new File(pomFile.getParentFile(), relative);
            }
            candidate = candidate.getCanonicalFile();
            return candidate.isFile() ? candidate : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 返回参与指纹的 Maven settings 文件（用户级与全局）。 */
    private static List<File> settingsFiles() {
        List<File> files = new ArrayList<>();
        String userSettings = System.getProperty("simple.ide.maven.user.settings", null);
        if (userSettings == null || userSettings.trim().isEmpty()) {
            userSettings = new File(System.getProperty("user.home", "."), ".m2/settings.xml").getAbsolutePath();
        }
        files.add(new File(userSettings));
        String globalSettings = System.getProperty("simple.ide.maven.global.settings", null);
        if (globalSettings != null && !globalSettings.trim().isEmpty()) {
            files.add(new File(globalSettings));
        }
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.trim().isEmpty()) {
            files.add(new File(mavenHome, "conf/settings.xml"));
        }
        return files;
    }

    /** 计算文件 SHA-256；文件不存在时返回固定标记，保证指纹稳定。 */
    private static String sha256Hex(File file) {
        if (file == null || !file.isFile()) return "missing";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file.toPath());
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }

    /** 计算字节数组 SHA-256。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }

    /** 缓存指纹元数据文件路径（与缓存文件同级，命名规则明确便于识别与清理）。 */
    static File classpathCacheMetaFile(File cacheFile) {
        return new File(cacheFile.getParentFile(), cacheFile.getName() + ".meta");
    }

    /** 读取元数据文件为 Properties。 */
    private static Properties readMeta(File metaFile) {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(metaFile)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return properties;
    }

    /** 读取缓存文件中的所有条目（不做存在性过滤），用于完整性校验。 */
    static List<String> readCacheEntries(File cacheFile) {
        List<String> entries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // 注释行（例如「已确证无外部依赖」的标记）不参与条目计数
                if (!line.isEmpty() && !line.startsWith("#")) entries.add(line);
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    /** 把路径列表过滤为真实存在的 Jar 文件。 */
    private static List<File> existingJars(List<String> entries) {
        List<File> jars = new ArrayList<>();
        for (String entry : entries) {
            File f = new File(entry);
            if (f.exists()) jars.add(f);
        }
        return jars;
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
     * 通过外部 Maven 快速解析导出当前模块的完整 Classpath（仅依赖解析，不执行 Maven 编译）。
     *
     * <p>执行约束：有边界超时、并发消费输出、失败保留证据、超时/失败绝不写入残缺缓存。
     *
     * @param baseDir 模块根目录
     * @param cacheFile 缓存文件
     * @param pomFile 模块 pom.xml
     * @return 解析结果（成功/失败与原因）
     */
    Resolution resolveViaMavenBuildClasspath(File baseDir, File cacheFile, File pomFile, Set<String> reactorModuleNames) {
        File tempCpFile = new File(classpathCacheDir, "temp-cp-" + System.nanoTime() + ".txt");
        File evidenceFile = new File(classpathCacheDir, "maven-resolve-" + baseDir.getName() + ".log");
        if (!classpathCacheDir.exists() && !classpathCacheDir.mkdirs()) {
            return new Resolution(false, new ArrayList<File>(), "无法创建依赖缓存目录: " + classpathCacheDir.getAbsolutePath());
        }

        List<String> command = Arrays.asList(
                mvnBinary(), "-q", "-DskipTests",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + tempCpFile.getAbsolutePath(),
                "-DincludeScope=test"
        );
        try {
            int timeoutSec = mavenTimeoutSec();
            BoundedProcessRunner.Result result = BoundedProcessRunner.run(command, baseDir.toPath(), timeoutSec, 1024 * 1024);
            String evidence = buildEvidence(baseDir, command, timeoutSec, result);

            if (!result.started) {
                writeEvidence(evidenceFile, evidence);
                return new Resolution(false, new ArrayList<File>(), result.error);
            }
            if (result.timedOut || !result.finished) {
                writeEvidence(evidenceFile, evidence);
                String reason = "Maven 依赖解析超时 (" + timeoutSec + "s)"
                        + (result.killComplete ? "" : "，且未能确认子进程已完全退出");
                return new Resolution(false, new ArrayList<File>(), reason);
            }
            if (result.exitCode != 0) {
                writeEvidence(evidenceFile, evidence);
                return new Resolution(false, new ArrayList<File>(), "Maven 依赖解析失败，退出码 " + result.exitCode);
            }
            if (!tempCpFile.isFile()) {
                writeEvidence(evidenceFile, evidence);
                return new Resolution(false, new ArrayList<File>(), "Maven 未生成 classpath 输出文件");
            }
            String cpContent = new String(Files.readAllBytes(tempCpFile.toPath()), StandardCharsets.UTF_8).trim();
            if (cpContent.isEmpty()) {
                writeEvidence(evidenceFile, evidence);
                return new Resolution(false, new ArrayList<File>(), "Maven 导出的 classpath 为空");
            }
            List<File> jars = new ArrayList<>();
            for (String path : cpContent.split(File.pathSeparator)) {
                File f = new File(path.trim());
                if (!f.exists() || !f.getName().endsWith(".jar")) continue;
                // reactor 内部依赖不能使用本地仓库中的（可能过期的）构件；
                // 它们由模块图输出目录提供，避免用旧版本 Jar 覆盖本次编译结果
                if (isReactorArtifact(f, reactorModuleNames)) continue;
                jars.add(f);
            }
            if (jars.isEmpty()) {
                writeEvidence(evidenceFile, evidence);
                return new Resolution(false, new ArrayList<File>(), "Maven 导出的 classpath 中没有任何存在的 Jar");
            }
            writeClasspathCache(cacheFile, jars, computeFingerprint(pomFile, localRepositoryDir));
            deleteQuietly(evidenceFile);
            return new Resolution(true, jars, null);
        } catch (Exception e) {
            writeEvidence(evidenceFile, "Maven 依赖解析异常: " + e);
            return new Resolution(false, new ArrayList<File>(), "Maven 依赖解析异常: " + e.getMessage());
        } finally {
            deleteQuietly(tempCpFile);
        }
    }

    /** 判断给定 Jar 是否属于本项目的 reactor 模块（避免使用本地仓库中的过期构件）。 */
    private static boolean isReactorArtifact(File jar, Set<String> reactorModuleNames) {
        if (reactorModuleNames == null || reactorModuleNames.isEmpty()) return false;
        String name = jar.getName();
        for (String moduleName : reactorModuleNames) {
            if (moduleName != null && !moduleName.isEmpty() && name.startsWith(moduleName + "-") && name.endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    /** 组装依赖解析失败证据文本（含命令行、退出码、超时与有界输出）。 */
    private static String buildEvidence(File baseDir, List<String> command, int timeoutSec, BoundedProcessRunner.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("moduleDir=").append(baseDir.getAbsolutePath()).append('\n');
        sb.append("command=").append(String.join(" ", command)).append('\n');
        sb.append("timeoutSec=").append(timeoutSec).append('\n');
        sb.append("started=").append(result.started).append('\n');
        sb.append("finished=").append(result.finished).append('\n');
        sb.append("timedOut=").append(result.timedOut).append('\n');
        sb.append("killComplete=").append(result.killComplete).append('\n');
        sb.append("exitCode=").append(result.exitCode).append('\n');
        sb.append("durationMs=").append(result.durationMs).append('\n');
        if (result.error != null) sb.append("error=").append(result.error).append('\n');
        sb.append("outputTruncated=").append(result.outputTruncated).append('\n');
        sb.append("--- output ---\n").append(result.output).append('\n');
        return sb.toString();
    }

    /** 原子写入证据文件。 */
    private static void writeEvidence(File evidenceFile, String content) {
        try {
            atomicWrite(evidenceFile, content);
        } catch (Exception ignored) {
        }
    }

    /**
     * 原子写入依赖 Classpath 缓存（临时文件 + 原子替换），避免进程中断留下被当成有效缓存的半截文件。
     *
     * @param cacheFile 目标缓存文件
     * @param jars Jar 列表
     * @param fingerprint 输入指纹
     */
    void writeClasspathCache(File cacheFile, List<File> jars, String fingerprint) {
        StringBuilder sb = new StringBuilder();
        if (jars.isEmpty()) {
            sb.append("# no external dependencies (proven complete)\n");
        }
        for (File jar : jars) {
            sb.append(jar.getAbsolutePath()).append('\n');
        }
        try {
            atomicWrite(cacheFile, sb.toString());
        } catch (Exception ignored) {
        }
        writeClasspathCacheMeta(cacheFile, fingerprint, jars.size());
    }

    /** 原子写入缓存指纹元数据；不包含任何凭据或环境 token。 */
    void writeClasspathCacheMeta(File cacheFile, String fingerprint, int entryCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("formatVersion=").append(CACHE_FORMAT_VERSION).append('\n');
        sb.append("toolVersion=").append(TOOL_VERSION).append('\n');
        sb.append("fingerprint=").append(fingerprint).append('\n');
        sb.append("entryCount=").append(entryCount).append('\n');
        sb.append("createdAt=").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date())).append('\n');
        try {
            atomicWrite(classpathCacheMetaFile(cacheFile), sb.toString());
        } catch (Exception ignored) {
        }
    }

    /** 临时文件 + 原子替换写入文本；原子移动不被支持时退化为替换移动。 */
    static void atomicWrite(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File temp = new File(parent, target.getName() + ".tmp-" + System.nanoTime());
        Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Path targetPath = target.toPath();
        try {
            Files.move(temp.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            Files.move(temp.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Maven 可执行文件，支持系统属性覆盖（便于受限环境与确定性测试）。 */
    static String mvnBinary() {
        String value = System.getProperty("simple.ide.maven.binary");
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return "mvn";
    }

    /** Maven 依赖解析超时（秒），支持系统属性覆盖，便于测试与受限环境调整。 */
    static int mavenTimeoutSec() {
        String value = System.getProperty("simple.ide.maven.timeout.sec");
        if (value != null && !value.trim().isEmpty()) {
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_MAVEN_TIMEOUT_SEC;
    }

    /** 静默删除文件。 */
    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
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
