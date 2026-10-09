package org.jetbrains.jps.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 封装 Maven 模块的关键构建元数据，供 JPS 映射模型使用
 */
public class MavenModuleInfo {
    private final String moduleName;
    private final File baseDir;
    private final File sourceDir;
    private final File resourceDir;
    private final File testSourceDir;
    private final File testResourceDir;
    private final File outputDir;
    private final File testOutputDir;
    private final File generatedSourceDir;
    private final List<File> dependencyJars = new ArrayList<>();
    private final List<String> moduleDependencies = new ArrayList<>();

    /**
     * 构造 Maven 模块元信息对象
     *
     * @param moduleName 模块名称（通常为 artifactId）
     * @param baseDir 模块根目录
     * @param sourceDir 主源码目录
     * @param resourceDir 资源目录
     * @param testSourceDir 测试源码目录
     * @param testResourceDir 测试资源目录
     * @param outputDir 主编译产物输出目录
     * @param testOutputDir 测试产物输出目录
     */
    public MavenModuleInfo(String moduleName, File baseDir, File sourceDir, File resourceDir,
                           File testSourceDir, File testResourceDir, File outputDir, File testOutputDir) {
        this.moduleName = moduleName;
        this.baseDir = baseDir;
        this.sourceDir = sourceDir;
        this.resourceDir = resourceDir;
        this.testSourceDir = testSourceDir;
        this.testResourceDir = testResourceDir;
        this.outputDir = outputDir;
        this.testOutputDir = testOutputDir;
        this.generatedSourceDir = new File(baseDir, "target/generated-sources/annotations");
    }

    /**
     * 获取模块名称
     *
     * @return 模块名称
     */
    public String getModuleName() {
        return moduleName;
    }

    /**
     * 获取模块根目录
     *
     * @return 根目录
     */
    public File getBaseDir() {
        return baseDir;
    }

    /**
     * 获取主源码目录
     *
     * @return 主源码目录
     */
    public File getSourceDir() {
        return sourceDir;
    }

    /**
     * 获取资源目录
     *
     * @return 资源目录
     */
    public File getResourceDir() {
        return resourceDir;
    }

    /**
     * 获取测试源码目录
     *
     * @return 测试源码目录
     */
    public File getTestSourceDir() {
        return testSourceDir;
    }

    /**
     * 获取测试资源目录
     *
     * @return 测试资源目录
     */
    public File getTestResourceDir() {
        return testResourceDir;
    }

    /**
     * 获取主输出目录
     *
     * @return 主输出目录
     */
    public File getOutputDir() {
        return outputDir;
    }

    /**
     * 获取测试输出目录
     *
     * @return 测试输出目录
     */
    public File getTestOutputDir() {
        return testOutputDir;
    }

    /**
     * 获取注解处理器生成的源码目录 (target/generated-sources/annotations)
     *
     * @return 生成源码目录
     */
    public File getGeneratedSourceDir() {
        return generatedSourceDir;
    }

    /**
     * 获取外部依赖 Jar 文件列表
     *
     * @return 依赖 Jar 文件列表
     */
    public List<File> getDependencyJars() {
        return dependencyJars;
    }

    /**
     * 获取项目内部依赖的模块名列表
     *
     * @return 内部依赖模块列表
     */
    public List<String> getModuleDependencies() {
        return moduleDependencies;
    }

    /**
     * 添加外部依赖 Jar 文件
     *
     * @param jarFile Jar 文件
     */
    public void addDependencyJar(File jarFile) {
        if (jarFile != null && jarFile.exists() && !dependencyJars.contains(jarFile)) {
            dependencyJars.add(jarFile);
        }
    }

    /**
     * 添加内部模块依赖
     *
     * @param moduleName 依赖的模块名
     */
    public void addModuleDependency(String moduleName) {
        if (moduleName != null && !moduleDependencies.contains(moduleName)) {
            moduleDependencies.add(moduleName);
        }
    }
}
