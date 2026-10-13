package org.jetbrains.jps.cli;

import org.jetbrains.jps.api.CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope;
import org.jetbrains.jps.build.Standalone;
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType;
import org.jetbrains.jps.cmdline.LogSetup;
import org.jetbrains.jps.incremental.MessageHandler;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/**
 * 驱动 JPS 增量编译执行的核心服务
 */
public class JpsIncrementalCompiler {

    /**
     * 执行项目编译（默认全模块范围）
     *
     * @param loader JPS 模型加载器
     * @param dataStorageRoot 增量编译数据与缓存存放目录
     * @param forceBuild 是否全量强制编译（若为 false 则为增量编译）
     * @param includeTests 是否编译测试代码
     * @param verbose 是否输出详细进度信息
     * @return 编译是否成功（无错误返回 true，有错误返回 false）
     * @throws Exception 编译过程中抛出的异常
     */
    public boolean compile(MavenJpsModelLoader loader, File dataStorageRoot, boolean forceBuild, boolean includeTests, boolean verbose) throws Exception {
        return compile(loader, dataStorageRoot, forceBuild, includeTests, verbose, new ArrayList<String>());
    }

    /**
     * 执行项目编译，可限定模块范围（模块名即 Maven artifactId，同时会包含其依赖模块）
     *
     * @param loader JPS 模型加载器
     * @param dataStorageRoot 增量编译数据与缓存存放目录
     * @param forceBuild 是否全量强制编译（若为 false 则为增量编译）
     * @param includeTests 是否编译测试代码
     * @param verbose 是否输出详细进度信息
     * @param moduleNames 目标模块名列表；为空表示全模块
     * @return 编译是否成功（无错误返回 true，有错误返回 false）
     * @throws Exception 编译过程中抛出的异常
     */
    public boolean compile(MavenJpsModelLoader loader, File dataStorageRoot, boolean forceBuild, boolean includeTests,
                           boolean verbose, List<String> moduleNames) throws Exception {
        return compile(loader, dataStorageRoot, forceBuild, includeTests, verbose, moduleNames, true);
    }

    /**
     * 执行项目编译，可限定模块范围，并显式控制是否允许 JPS 内部的破坏性恢复
     *
     * @param loader JPS 模型加载器
     * @param dataStorageRoot 增量编译数据与缓存存放目录
     * @param forceBuild 是否全量强制编译（若为 false 则为增量编译）
     * @param includeTests 是否编译测试代码
     * @param verbose 是否输出详细进度信息
     * @param moduleNames 目标模块名列表；为空表示全模块
     * @param allowDestructiveRecovery 是否允许在检测到存储损坏时删除缓存并重建；
     *                                 为 false 时向上抛出 {@link CacheRecoveryNotAllowedException}
     * @return 编译是否成功（无错误返回 true，有错误返回 false）
     * @throws CacheRecoveryNotAllowedException 检测到存储损坏但未授权破坏性恢复
     * @throws Exception 编译过程中抛出的异常
     */
    public boolean compile(MavenJpsModelLoader loader, File dataStorageRoot, boolean forceBuild, boolean includeTests,
                           boolean verbose, List<String> moduleNames, boolean allowDestructiveRecovery) throws Exception {
        if (!dataStorageRoot.exists()) {
            dataStorageRoot.mkdirs();
        }
        System.setProperty(org.jetbrains.jps.api.GlobalOptions.LOG_DIR_OPTION, dataStorageRoot.getAbsolutePath());
        LogSetup.initLoggers();

        boolean scoped = moduleNames != null && !moduleNames.isEmpty();
        List<TargetTypeBuildScope> scopes = new ArrayList<>();
        for (JavaModuleBuildTargetType type : JavaModuleBuildTargetType.ALL_TYPES) {
            if (includeTests || !type.isTests()) {
                scopes.add(buildScope(type.getTypeId(), forceBuild, moduleNames));
            }
        }
        for (org.jetbrains.jps.builders.java.ResourcesTargetType type : org.jetbrains.jps.builders.java.ResourcesTargetType.ALL_TYPES) {
            if (includeTests || !type.isTests()) {
                scopes.add(buildScope(type.getTypeId(), forceBuild, moduleNames));
            }
        }
        if (scoped) {
            System.out.println("[INFO] 构建范围限定为模块: " + moduleNames);
        }

        CliMessageHandler handler = new CliMessageHandler(verbose);
        try {
            Standalone.runBuild(loader, dataStorageRoot, handler, scopes, true);
        } catch (Throwable t) {
            // JPS 增量存储损坏（如 EOF 读取缓存失败）时会抛出 IO/解析异常；
            // 未授权破坏性恢复时只报告，不删除缓存，也不静默重建
            if (isStorageCorruption(t)) {
                if (!allowDestructiveRecovery) {
                    System.err.println("[CACHE_CORRUPT_REBUILD_REQUIRED] JPS 增量存储损坏，"
                            + "但当前未授权清理重建（--on-corrupt-cache fail）："
                            + (t.getMessage() != null ? t.getMessage() : t.toString()));
                    throw new CacheRecoveryNotAllowedException(
                            "JPS 增量存储损坏且未授权清理重建: " + (t.getMessage() != null ? t.getMessage() : t.toString()), t);
                }
                System.err.println("[CACHE_REBUILT] JPS 增量存储损坏，已清理 ".concat(dataStorageRoot.getAbsolutePath()).concat(" 并执行全量重建"));
                deleteRecursively(dataStorageRoot);
                dataStorageRoot.mkdirs();
                List<TargetTypeBuildScope> rebuildScopes = new ArrayList<>();
                for (JavaModuleBuildTargetType type : JavaModuleBuildTargetType.ALL_TYPES) {
                    if (includeTests || !type.isTests()) {
                        rebuildScopes.add(buildScope(type.getTypeId(), true, moduleNames));
                    }
                }
                for (org.jetbrains.jps.builders.java.ResourcesTargetType type : org.jetbrains.jps.builders.java.ResourcesTargetType.ALL_TYPES) {
                    if (includeTests || !type.isTests()) {
                        rebuildScopes.add(buildScope(type.getTypeId(), true, moduleNames));
                    }
                }
                CliMessageHandler rebuildHandler = new CliMessageHandler(verbose);
                Standalone.runBuild(loader, dataStorageRoot, rebuildHandler, rebuildScopes, true);
                if (rebuildHandler.hasErrors()) {
                    for (String message : rebuildHandler.getErrorMessages()) {
                        System.err.println("[ERROR] " + message);
                    }
                }
                return !rebuildHandler.hasErrors();
            }
            throw t;
        }
        return !handler.hasErrors();
    }

    /** 检测到存储损坏但未授权破坏性恢复时抛出。 */
    public static class CacheRecoveryNotAllowedException extends Exception {
        /**
         * 构造未授权恢复异常
         *
         * @param message 说明
         * @param cause 原始损坏异常
         */
        public CacheRecoveryNotAllowedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 构造构建范围：限定模块时设置目标模块 id，否则为全目标。 */
    private static TargetTypeBuildScope buildScope(String typeId, boolean forceBuild, List<String> moduleNames) {
        TargetTypeBuildScope.Builder builder = TargetTypeBuildScope.newBuilder()
                .setTypeId(typeId)
                .setForceBuild(forceBuild);
        if (moduleNames == null || moduleNames.isEmpty()) {
            builder.setAllTargets(true);
        } else {
            builder.setAllTargets(false);
            builder.addAllTargetId(moduleNames);
        }
        return builder.build();
    }

    /** 判断异常是否属于增量存储损坏特征。 */
    static boolean isStorageCorruption(Throwable t) {
        Set<String> markers = new HashSet<>();
        markers.add("EOFException");
        markers.add("Unexpected end of");
        markers.add("invalid stream header");
        markers.add("corrupt");
        markers.add("Storage");
        markers.add("Cache");
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof java.io.EOFException || current instanceof java.util.zip.ZipException) {
                return true;
            }
            String text = current.getClass().getName() + ": " + current.getMessage();
            if (text.contains("EOFException") || text.contains("Unexpected end of")
                    || text.contains("invalid stream header") || text.toLowerCase().contains("corrupt")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 递归删除目录。 */
    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try {
            java.nio.file.Files.walk(dir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        } catch (Exception ignored) {
        }
    }

    /**
     * 控制台编译消息收集与处理器
     */
    public static class CliMessageHandler implements MessageHandler {
        private final boolean verbose;
        private boolean hasErrors = false;
        private final List<String> errorMessages = new ArrayList<>();

        /**
         * 构造消息处理器
         *
         * @param verbose 是否输出详细信息
         */
        public CliMessageHandler(boolean verbose) {
            this.verbose = verbose;
        }

        /**
         * 接收并打印 JPS 构建事件和错误消息
         *
         * @param msg JPS 构建消息对象
         */
        @Override
        public void processMessage(BuildMessage msg) {
            String messageText;
            if (msg instanceof CompilerMessage) {
                CompilerMessage cm = (CompilerMessage) msg;
                if (cm.getSourcePath() == null) {
                    messageText = msg.getMessageText();
                } else if (cm.getLine() < 0) {
                    messageText = cm.getSourcePath() + ": " + msg.getMessageText();
                } else {
                    messageText = cm.getSourcePath() + "(" + cm.getLine() + ":" + cm.getColumn() + "): " + msg.getMessageText();
                }
            } else {
                messageText = msg.getMessageText();
            }

            if (messageText == null || messageText.trim().isEmpty()) {
                return;
            }

            if (msg.getKind() == BuildMessage.Kind.ERROR) {
                hasErrors = true;
                errorMessages.add(messageText);
                System.err.println("[ERROR] " + messageText);
            } else if (msg.getKind() == BuildMessage.Kind.WARNING) {
                System.out.println("[WARN]  " + messageText);
            } else if (verbose || msg.getKind() == BuildMessage.Kind.INFO) {
                System.out.println("[INFO]  " + messageText);
            }
        }

        /**
         * 检查编译期间是否存在错误
         *
         * @return 存在错误返回 true，否则 false
         */
        public boolean hasErrors() {
            return hasErrors;
        }

        /**
         * 获取收集到的错误消息列表
         *
         * @return 错误消息列表
         */
        public List<String> getErrorMessages() {
            return errorMessages;
        }
    }
}
