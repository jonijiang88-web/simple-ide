package org.jetbrains.jps.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String customClasspathCacheDirPath = null;
        String jdkPath = System.getProperty("java.home");
        boolean clean = false;
        boolean includeTests = true;
        boolean verbose = false;
        List<String> requestedModules = new ArrayList<>();
        String onCorruptCache = "rebuild";
        String buildId = null;
        String lockFilePath = null;
        String incompleteMarkerPath = null;
        String outputRootPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--pom".equals(arg) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--project".equals(arg) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--cache-dir".equals(arg) && i + 1 < args.length) {
                customCacheDirPath = args[++i];
            } else if ("--classpath-cache-dir".equals(arg) && i + 1 < args.length) {
                customClasspathCacheDirPath = args[++i];
            } else if ("--module".equals(arg) && i + 1 < args.length) {
                requestedModules.add(args[++i]);
            } else if ("--on-corrupt-cache".equals(arg) && i + 1 < args.length) {
                onCorruptCache = args[++i].trim().toLowerCase();
            } else if ("--build-id".equals(arg) && i + 1 < args.length) {
                buildId = args[++i];
            } else if ("--lock-file".equals(arg) && i + 1 < args.length) {
                lockFilePath = args[++i];
            } else if ("--incomplete-marker".equals(arg) && i + 1 < args.length) {
                incompleteMarkerPath = args[++i];
            } else if ("--output-root".equals(arg) && i + 1 < args.length) {
                outputRootPath = args[++i];
            } else if ("--jdk".equals(arg) && i + 1 < args.length) {
                jdkPath = args[++i];
            } else if ("--clean".equals(arg)) {
                clean = true;
            } else if ("--no-tests".equals(arg)) {
                includeTests = false;
            } else if ("--verbose".equals(arg) || "-v".equals(arg)) {
                verbose = true;
            } else if ("--version".equals(arg)) {
                System.out.println("jps-cli " + MavenPomResolver.TOOL_VERSION + " (cache format " + MavenPomResolver.CACHE_FORMAT_VERSION + ")");
                System.exit(0);
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

        // 依赖 Classpath 缓存必须独立于 JPS 增量数据目录：
        // JPS 检测到存储损坏时会调用 FileUtil.delete(dataStorageRoot) 递归删除整个 cacheDir，
        // 若把 classpath-*.cache 放在该目录内会被连带删除，导致后续 run 拿不到依赖 Classpath。
        File classpathCacheDir = (customClasspathCacheDirPath != null)
                ? new File(customClasspathCacheDirPath).getAbsoluteFile()
                : new File(projectBaseDir, ".jps-deps");

        File jdkHome = new File(jdkPath).getAbsoluteFile();
        if ("jre".equals(jdkHome.getName()) && new File(jdkHome.getParentFile(), "lib/tools.jar").exists()) {
            jdkHome = jdkHome.getParentFile();
        }

        System.out.println("==================================================");
        System.out.println("  JPS Incremental Builder CLI");
        System.out.println("  Project:   " + projectBaseDir.getAbsolutePath());
        System.out.println("  POM:       " + pomFile.getAbsolutePath());
        System.out.println("  Cache:     " + cacheDir.getAbsolutePath());
        System.out.println("  Deps:      " + classpathCacheDir.getAbsolutePath());
        System.out.println("  JDK:       " + jdkHome.getAbsolutePath());
        System.out.println("  Mode:      " + (clean ? "CLEAN & REBUILD" : "INCREMENTAL"));
        System.out.println("==================================================");

        // 子进程侧项目构建锁：父命令被强杀后仍能阻断新构建并发写同一缓存
        ChildBuildLock childLock = null;
        if (lockFilePath != null && !lockFilePath.trim().isEmpty()) {
            try {
                childLock = ChildBuildLock.acquire(new File(lockFilePath.trim()));
                System.out.println("[INFO] 已持有项目构建锁: " + new File(lockFilePath.trim()).getAbsolutePath());
            } catch (IOException lockError) {
                System.err.println("[BUILD_LOCKED] " + lockError.getMessage());
                System.exit(5);
            }
        }

        final File incompleteMarker = (incompleteMarkerPath != null && !incompleteMarkerPath.trim().isEmpty())
                ? new File(incompleteMarkerPath.trim()) : null;
        final String currentBuildId = buildId;
        WriteMarker.write(incompleteMarker, currentBuildId, "in_progress", System.currentTimeMillis());
        final boolean[] buildCompleted = new boolean[]{false};
        // 子进程被终止（TERM/INT）时保留未完整构建标记，避免不完整缓存被当作有效
        Thread markerHook = new Thread(() -> {
            if (!buildCompleted[0]) {
                WriteMarker.write(incompleteMarker, currentBuildId, "jps_terminated", System.currentTimeMillis());
            }
        }, "jps-cli-incomplete-marker");
        try {
            Runtime.getRuntime().addShutdownHook(markerHook);
        } catch (IllegalStateException ignored) {
        }

        long start = System.currentTimeMillis();
        try {
            // 1. 解析 Maven 拓扑与依赖（依赖 Classpath 缓存写入独立的 .jps-deps 目录）
            MavenPomResolver resolver = MavenPomResolver.withClasspathCacheDir(pomFile, cacheDir, classpathCacheDir);
            List<MavenModuleInfo> modules;
            try {
                modules = resolver.resolve();
            } catch (MavenPomResolver.DependencyResolutionException dependencyError) {
                System.err.println("[DEPENDENCY_RESOLUTION_FAILED] " + dependencyError.getMessage());
                WriteMarker.write(incompleteMarker, currentBuildId, "dependency_resolution_failed", System.currentTimeMillis());
                System.exit(4);
                return;
            }
            if (modules.isEmpty()) {
                System.err.println("[ERROR] 未解析到任何有效的 Maven 模块!");
                System.exit(1);
            }
            System.out.println("[INFO] 解析到 " + modules.size() + " 个模块:");
            for (MavenModuleInfo m : modules) {
                System.out.println("       * " + m.getModuleName() + " (依赖 Jar: " + m.getDependencyJars().size() + " 个)");
            }

            // 模块构建作用域：显式 --module 时才限定；未知模块直接报错，避免静默构建了别的目标
            List<String> buildModules = new ArrayList<>();
            if (!requestedModules.isEmpty()) {
                Map<String, MavenModuleInfo> moduleMap = new LinkedHashMap<>();
                for (MavenModuleInfo m : modules) {
                    moduleMap.put(m.getModuleName(), m);
                }
                List<String> unknown = new ArrayList<>();
                for (String name : requestedModules) {
                    if (!moduleMap.containsKey(name)) {
                        unknown.add(name);
                    }
                }
                if (!unknown.isEmpty()) {
                    System.err.println("[ERROR] 指定的模块不存在: " + unknown);
                    System.err.println("[ERROR] 可用模块: " + new ArrayList<>(moduleMap.keySet()));
                    System.exit(2);
                }
                // 展开依赖闭包：确保被依赖模块一起编译，避免只编目标模块导致依赖缺失
                Set<String> closure = new LinkedHashSet<>();
                java.util.Deque<String> pending = new java.util.ArrayDeque<>(requestedModules);
                while (!pending.isEmpty()) {
                    String name = pending.poll();
                    if (!closure.add(name)) {
                        continue;
                    }
                    MavenModuleInfo info = moduleMap.get(name);
                    if (info != null) {
                        pending.addAll(info.getModuleDependencies());
                    }
                }
                buildModules.addAll(closure);
                System.out.println("[INFO] 构建模块作用域（含依赖闭包）: " + buildModules);
            }

            // 编译输出重定向：把 classes/test-classes 放到独立根目录，避免触碰共享 target
            if (outputRootPath != null && !outputRootPath.trim().isEmpty()) {
                File outputRoot = new File(outputRootPath.trim()).getAbsoluteFile();
                for (MavenModuleInfo m : modules) {
                    File classes = new File(outputRoot, m.getModuleName() + "/classes");
                    File testClasses = new File(outputRoot, m.getModuleName() + "/test-classes");
                    m.setOutputDir(classes);
                    m.setTestOutputDir(testClasses);
                    System.out.println("[OUTPUT-MAP] " + m.getModuleName() + "=" + classes.getAbsolutePath()
                            + "|" + testClasses.getAbsolutePath());
                }
                System.out.println("[INFO] 编译输出已重定向到 " + outputRoot.getAbsolutePath()
                        + "（共享 target 不会被本进程改动）");
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

            // 3. 执行增量编译（未授权时不执行破坏性恢复）
            JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
            boolean success;
            try {
                success = compiler.compile(loader, cacheDir, clean, includeTests, verbose, buildModules,
                        !"fail".equals(onCorruptCache));
            } catch (JpsIncrementalCompiler.CacheRecoveryNotAllowedException recoveryError) {
                WriteMarker.write(incompleteMarker, currentBuildId, "cache_corruption_rebuild_required", System.currentTimeMillis());
                System.exit(3);
                return;
            }

            long cost = System.currentTimeMillis() - start;
            if (success) {
                // 限定模块时校验产出，避免「目标模块实际未编译」被当成成功
                List<String> missingOutputs = verifyModuleOutputs(modules, buildModules);
                if (!missingOutputs.isEmpty()) {
                    System.err.println("[ERROR] 以下模块在构建成功后仍未产出任何 class 文件: " + missingOutputs);
                    System.err.println("[FAILED] 构建未完整完成! 耗时: " + cost + " ms");
                    System.exit(1);
                }
                System.out.println("[SUCCESS] 构建成功! 耗时: " + cost + " ms");
                buildCompleted[0] = true;
                WriteMarker.clearIfOwned(incompleteMarker, currentBuildId);
                if (childLock != null) childLock.close();
                System.exit(0);
            } else {
                System.err.println("[FAILED] 构建失败! 耗时: " + cost + " ms");
                WriteMarker.write(incompleteMarker, currentBuildId, "build_failed", System.currentTimeMillis());
                if (childLock != null) childLock.close();
                System.exit(1);
            }
        } catch (Throwable t) {
            System.err.println("[FATAL] 构建异常退出: " + t.getMessage());
            t.printStackTrace();
            WriteMarker.write(incompleteMarker, currentBuildId, "build_error", System.currentTimeMillis());
            if (childLock != null) childLock.close();
            System.exit(2);
        }
    }

    /**
     * 校验限定模块构建后是否真的产出了 class 文件。
     *
     * @param modules 全部模块
     * @param buildModules 本次构建的模块作用域；为空表示全模块（不校验）
     * @return 未产出任何 class 的模块名列表
     */
    private static List<String> verifyModuleOutputs(List<MavenModuleInfo> modules, List<String> buildModules) {
        List<String> missing = new ArrayList<>();
        if (buildModules == null || buildModules.isEmpty()) {
            return missing;
        }
        for (MavenModuleInfo info : modules) {
            if (!buildModules.contains(info.getModuleName())) {
                continue;
            }
            if (!hasJavaSources(info.getSourceDir())) {
                continue;
            }
            if (!hasClassFiles(info.getOutputDir())) {
                missing.add(info.getModuleName());
            }
        }
        return missing;
    }

    /** 判断目录下是否存在 .java 源文件。 */
    private static boolean hasJavaSources(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (hasJavaSources(f)) {
                    return true;
                }
            } else if (f.getName().endsWith(".java")) {
                return true;
            }
        }
        return false;
    }

    /** 判断目录下是否存在 .class 文件。 */
    private static boolean hasClassFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (hasClassFiles(f)) {
                    return true;
                }
            } else if (f.getName().endsWith(".class")) {
                return true;
            }
        }
        return false;
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
        System.out.println("  --classpath-cache-dir <目录路径>  指定依赖 Classpath 缓存目录 (默认: 项目目录/.jps-deps)");
        System.out.println("  --module <模块名>       限定构建模块（artifactId，可重复；自动包含其依赖模块）");
        System.out.println("  --on-corrupt-cache <策略>  存储损坏时的策略：rebuild(默认，清理重建) | fail(拒绝破坏性恢复并报告)");
        System.out.println("  --build-id <ID>         本次构建 ID（写入未完整构建标记，便于追溯）");
        System.out.println("  --lock-file <路径>      项目构建锁文件（子进程持有，阻断父进程被强杀后的并发构建）");
        System.out.println("  --incomplete-marker <路径>  未完整构建标记文件路径（成功才清除）");
        System.out.println("  --output-root <目录路径>  把 modules 的 classes/test-classes 重定向到该根目录（与共享 target 隔离）");
        System.out.println("  --jdk <JDK路径>         指定 JDK 根目录 (默认: 环境变量 JDK)");
        System.out.println("  --clean                清理并强制全量重新编译");
        System.out.println("  --no-tests             跳过测试代码编译");
        System.out.println("  --verbose, -v          打印详细增量构建信息");
        System.out.println("  --version              打印工具与缓存格式版本");
        System.out.println("  --help, -h             显示帮助信息");
    }
}
