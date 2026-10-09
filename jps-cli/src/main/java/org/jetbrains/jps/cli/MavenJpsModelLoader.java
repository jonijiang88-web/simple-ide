package org.jetbrains.jps.cli;

import org.jetbrains.jps.cmdline.JpsModelLoader;
import org.jetbrains.jps.model.JpsDummyElement;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.java.*;
import org.jetbrains.jps.model.java.compiler.JpsJavaCompilerConfiguration;
import org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile;
import org.jetbrains.jps.model.library.JpsOrderRootType;
import org.jetbrains.jps.model.library.JpsTypedLibrary;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.util.JpsPathUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实现 JPS 模型的动态装配加载器，负责将 Maven 模块拓扑和依赖转化为内存中的 JpsModel
 */
public class MavenJpsModelLoader implements JpsModelLoader {

    private final List<MavenModuleInfo> modules;
    private final File jdkHome;

    /**
     * 构造 Maven-JPS 模型装配器
     *
     * @param modules 解析后的 Maven 模块元信息列表
     * @param jdkHome 用于编译的 JDK 主目录
     */
    public MavenJpsModelLoader(List<MavenModuleInfo> modules, File jdkHome) {
        this.modules = modules;
        this.jdkHome = jdkHome;
    }

    /**
     * 构建并返回装配好的 JPS 项目模型
     *
     * @return 完整的 JpsModel 实例
     * @throws IOException 构建或 IO 异常
     */
    @Override
    public JpsModel loadModel() throws IOException {
        JpsModel model = JpsElementFactory.getInstance().createModel();
        JpsProject project = model.getProject();

        // 1. 注册 JDK
        JpsTypedLibrary<JpsSdk<JpsDummyElement>> jdkLib = JpsJavaExtensionService.getInstance().addJavaSdk(
                model.getGlobal(), "1.8", jdkHome.getAbsolutePath()
        );

        // 2. 第一轮：在 project 中声明所有 module，以便后续建立互相依赖
        Map<String, JpsModule> jpsModuleMap = new HashMap<>();
        for (MavenModuleInfo info : modules) {
            JpsModule module = project.addModule(info.getModuleName(), JpsJavaModuleType.INSTANCE);
            jpsModuleMap.put(info.getModuleName(), module);
        }

        // 收集所有专用 annotation processors 路径
        List<String> detectedProcessorJars = new ArrayList<>();

        // 3. 第二轮：装配每个 module 的源码目录、输出目录及依赖
        for (MavenModuleInfo info : modules) {
            JpsModule module = jpsModuleMap.get(info.getModuleName());

            // 绑定模块自身源码依赖与 SDK 依赖
            module.getDependenciesList().addModuleSourceDependency();
            module.getSdkReferencesTable().setSdkReference(JpsJavaSdkType.INSTANCE, jdkLib.getProperties().createReference());
            module.getDependenciesList().addSdkDependency(JpsJavaSdkType.INSTANCE);

            // 绑定模块内容根目录 (Content Root)，确保注解处理器相对路径准确解析到项目根
            module.getContentRootsList().addUrl(JpsPathUtil.pathToUrl(info.getBaseDir().getAbsolutePath()));

            // 注册源码与资源根目录
            if (info.getSourceDir().exists()) {
                module.addSourceRoot(JpsPathUtil.pathToUrl(info.getSourceDir().getAbsolutePath()), JavaSourceRootType.SOURCE);
            }
            if (info.getResourceDir().exists()) {
                module.addSourceRoot(JpsPathUtil.pathToUrl(info.getResourceDir().getAbsolutePath()), JavaResourceRootType.RESOURCE);
            }
            if (info.getTestSourceDir().exists()) {
                module.addSourceRoot(JpsPathUtil.pathToUrl(info.getTestSourceDir().getAbsolutePath()), JavaSourceRootType.TEST_SOURCE);
            }
            if (info.getTestResourceDir().exists()) {
                module.addSourceRoot(JpsPathUtil.pathToUrl(info.getTestResourceDir().getAbsolutePath()), JavaResourceRootType.TEST_RESOURCE);
            }

            // 设置编译输出路径 target/classes 与 target/test-classes
            JpsJavaModuleExtension outputExt = JpsJavaExtensionService.getInstance().getOrCreateModuleExtension(module);
            outputExt.setInheritOutput(false);
            outputExt.setOutputUrl(JpsPathUtil.pathToUrl(info.getOutputDir().getAbsolutePath()));
            outputExt.setTestOutputUrl(JpsPathUtil.pathToUrl(info.getTestOutputDir().getAbsolutePath()));

            // 注册外部 Jar 依赖
            for (File jarFile : info.getDependencyJars()) {
                org.jetbrains.jps.model.library.JpsLibrary lib = project.addLibrary(jarFile.getName(), JpsJavaLibraryType.INSTANCE);
                lib.addRoot(jarFile, JpsOrderRootType.COMPILED);
                module.getDependenciesList().addLibraryDependency(lib);

                String name = jarFile.getName().toLowerCase();
                if (name.contains("lombok") || name.contains("mapstruct-processor")) {
                    if (!detectedProcessorJars.contains(jarFile.getAbsolutePath())) {
                        detectedProcessorJars.add(jarFile.getAbsolutePath());
                    }
                }
            }

            // 注册项目内模块间相互依赖
            for (String depModuleName : info.getModuleDependencies()) {
                JpsModule depModule = jpsModuleMap.get(depModuleName);
                if (depModule != null) {
                    module.getDependenciesList().addModuleDependency(depModule);
                }
            }
        }

        // 4. 配置注解处理器与编译器选项
        configureAnnotationProcessing(project, detectedProcessorJars);

        return model;
    }

    /**
     * 配置项目级注解处理器（精确处理 Lombok 与 MapStruct 协同构建）
     *
     * @param project JPS 项目对象
     * @param processorJars 扫描到的注解处理器 Jar 绝对路径列表
     */
    private void configureAnnotationProcessing(JpsProject project, List<String> processorJars) {
        JpsJavaCompilerConfiguration compilerConfig = JpsJavaExtensionService.getInstance().getOrCreateCompilerConfiguration(project);
        ProcessorConfigProfile defaultProfile = compilerConfig.getDefaultAnnotationProcessingProfile();
        defaultProfile.setEnabled(true);
        defaultProfile.setOutputRelativeToContentRoot(true);
        defaultProfile.setGeneratedSourcesDirectoryName("target/generated-sources/annotations", false);
        defaultProfile.setGeneratedSourcesDirectoryName("target/generated-test-sources/test-annotations", true);

        if (!processorJars.isEmpty()) {
            // 严格指定 processor 依赖，避免全 classpath 扫描异常
            StringBuilder sb = new StringBuilder();
            for (String jarPath : processorJars) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(jarPath);
            }
            defaultProfile.setProcessorPath(sb.toString());
            defaultProfile.setObtainProcessorsFromClasspath(false);
        } else {
            defaultProfile.setObtainProcessorsFromClasspath(true);
        }

        // 5. 设置编译器子进程最大堆内存（默认 4096MB，若存在 .idea/compiler.xml 则读取对应数值）
        int heapSize = detectCompilerHeapSize(modules.get(0).getBaseDir(), 4096);
        compilerConfig.getCurrentCompilerOptions().MAXIMUM_HEAP_SIZE = heapSize;

        // 6. 注册标准资源复制模式（确保 xml、properties、yml 等非 Java 资源被正常复制到 target/classes）
        List<String> defaultPatterns = Arrays.asList("!?*.java", "!?*.form", "!?*.class", "!?*.groovy", "!?*.scala", "!?*.flex", "!?*.kt", "!?*.clj", "!?*.aj");
        for (String pattern : defaultPatterns) {
            compilerConfig.addResourcePattern(pattern);
        }
    }

    /**
     * 检测项目配置的编译器堆内存大小 (MB)
     *
     * @param projectBaseDir 项目根目录
     * @param defaultSize 默认堆内存 (MB)
     * @return 堆内存大小 (MB)
     */
    private int detectCompilerHeapSize(File projectBaseDir, int defaultSize) {
        File compilerXml = new File(projectBaseDir, ".idea/compiler.xml");
        if (compilerXml.exists()) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(compilerXml))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("BUILD_PROCESS_HEAP_SIZE")) {
                        int idx = line.indexOf("value=\"");
                        if (idx != -1) {
                            int endIdx = line.indexOf("\"", idx + 7);
                            if (endIdx != -1) {
                                String val = line.substring(idx + 7, endIdx).trim();
                                return Integer.parseInt(val);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return defaultSize;
    }
}
