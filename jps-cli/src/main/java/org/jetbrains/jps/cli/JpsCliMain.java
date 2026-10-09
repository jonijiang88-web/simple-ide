package org.jetbrains.jps.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

/**
 * JPS 命令行构建工具入口类
 */
public class JpsCliMain {

    /**
     * 命令行主函数入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        String inputPath = null;
        String customCacheDirPath = null;
        String jdkPath = System.getProperty("java.home");
        boolean clean = false;
        boolean includeTests = true;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--pom".equals(arg) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--project".equals(arg) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--cache-dir".equals(arg) && i + 1 < args.length) {
                customCacheDirPath = args[++i];
            } else if ("--jdk".equals(arg) && i + 1 < args.length) {
                jdkPath = args[++i];
            } else if ("--clean".equals(arg)) {
                clean = true;
            } else if ("--no-tests".equals(arg)) {
                includeTests = false;
            } else if ("--verbose".equals(arg) || "-v".equals(arg)) {
                verbose = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                System.exit(0);
            } else if (!arg.startsWith("-") && inputPath == null) {
                inputPath = arg;
            }
        }

        if (inputPath == null || inputPath.trim().isEmpty()) {
            inputPath = ".";
        }

        File targetLocation = new File(inputPath).getAbsoluteFile();
        File pomFile;
        File projectBaseDir;

        if (targetLocation.isDirectory()) {
            projectBaseDir = targetLocation;
            pomFile = new File(targetLocation, "pom.xml");
        } else if (targetLocation.isFile()) {
            pomFile = targetLocation;
            projectBaseDir = pomFile.getParentFile();
        } else {
            System.err.println("[ERROR] 指定的路径不存在: " + targetLocation.getAbsolutePath());
            System.exit(1);
            return;
        }

        if (!pomFile.exists() || !pomFile.isFile()) {
            System.err.println("[ERROR] 未在路径中找到 pom.xml: " + pomFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        File cacheDir = (customCacheDirPath != null)
                ? new File(customCacheDirPath).getAbsoluteFile()
                : new File(projectBaseDir, ".jps-cache");

        File jdkHome = new File(jdkPath).getAbsoluteFile();
        if ("jre".equals(jdkHome.getName()) && new File(jdkHome.getParentFile(), "lib/tools.jar").exists()) {
            jdkHome = jdkHome.getParentFile();
        }

        System.out.println("==================================================");
        System.out.println("  JPS Incremental Builder CLI");
        System.out.println("  Project:   " + projectBaseDir.getAbsolutePath());
        System.out.println("  POM:       " + pomFile.getAbsolutePath());
        System.out.println("  Cache:     " + cacheDir.getAbsolutePath());
        System.out.println("  JDK:       " + jdkHome.getAbsolutePath());
        System.out.println("  Mode:      " + (clean ? "CLEAN & REBUILD" : "INCREMENTAL"));
        System.out.println("==================================================");

        long start = System.currentTimeMillis();
        try {
            // 1. 解析 Maven 拓扑与依赖
            MavenPomResolver resolver = new MavenPomResolver(pomFile, cacheDir);
            List<MavenModuleInfo> modules = resolver.resolve();
            if (modules.isEmpty()) {
                System.err.println("[ERROR] 未解析到任何有效的 Maven 模块!");
                System.exit(1);
            }
            System.out.println("[INFO] 解析到 " + modules.size() + " 个模块:");
            for (MavenModuleInfo m : modules) {
                System.out.println("       * " + m.getModuleName() + " (依赖 Jar: " + m.getDependencyJars().size() + " 个)");
            }

            // 若要求 clean，清理各模块输出目录与缓存
            if (clean) {
                System.out.println("[INFO] 清理模式: 清空编译产物及构建缓存...");
                for (MavenModuleInfo m : modules) {
                    deleteDirectoryRecursively(m.getOutputDir());
                    deleteDirectoryRecursively(m.getTestOutputDir());
                }
                deleteDirectoryRecursively(cacheDir);
            }

            // 2. 装配 JPS 运行时模型
            MavenJpsModelLoader loader = new MavenJpsModelLoader(modules, jdkHome);

            // 3. 执行增量编译
            JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
            boolean success = compiler.compile(loader, cacheDir, clean, includeTests, verbose);

            long cost = System.currentTimeMillis() - start;
            if (success) {
                System.out.println("[SUCCESS] 构建成功! 耗时: " + cost + " ms");
                System.exit(0);
            } else {
                System.err.println("[FAILED] 构建失败! 耗时: " + cost + " ms");
                System.exit(1);
            }
        } catch (Throwable t) {
            System.err.println("[FATAL] 构建异常退出: " + t.getMessage());
            t.printStackTrace();
            System.exit(2);
        }
    }

    /**
     * 递归删除指定目录及其所有子文件
     *
     * @param dir 待删除目录
     */
    private static void deleteDirectoryRecursively(File dir) {
        if (dir != null && dir.exists()) {
            try {
                Files.walk(dir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(File::delete);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 打印命令行帮助说明
     */
    private static void printHelp() {
        System.out.println("用法: java -jar jps-cli.jar [项目路径或pom.xml] [选项]");
        System.out.println("示例:");
        System.out.println("  java -jar jps-cli.jar ~/code/my-app");
        System.out.println("  java -jar jps-cli.jar ~/code/my-app/pom.xml");
        System.out.println("选项:");
        System.out.println("  --pom <文件路径>        指定 pom.xml 文件路径");
        System.out.println("  --project <目录路径>    指定项目根目录路径");
        System.out.println("  --cache-dir <目录路径>  指定 JPS 依赖图与缓存目录 (默认: 项目目录/.jps-cache)");
        System.out.println("  --jdk <JDK路径>         指定 JDK 根目录 (默认: 环境变量 JDK)");
        System.out.println("  --clean                清理并强制全量重新编译");
        System.out.println("  --no-tests             跳过测试代码编译");
        System.out.println("  --verbose, -v          打印详细增量构建信息");
        System.out.println("  --help, -h             显示帮助信息");
    }
}
