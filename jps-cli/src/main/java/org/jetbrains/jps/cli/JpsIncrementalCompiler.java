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
import java.util.List;

/**
 * 驱动 JPS 增量编译执行的核心服务
 */
public class JpsIncrementalCompiler {

    /**
     * 执行项目编译
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
        if (!dataStorageRoot.exists()) {
            dataStorageRoot.mkdirs();
        }
        System.setProperty(org.jetbrains.jps.api.GlobalOptions.LOG_DIR_OPTION, dataStorageRoot.getAbsolutePath());
        LogSetup.initLoggers();

        List<TargetTypeBuildScope> scopes = new ArrayList<>();
        for (JavaModuleBuildTargetType type : JavaModuleBuildTargetType.ALL_TYPES) {
            if (includeTests || !type.isTests()) {
                TargetTypeBuildScope.Builder builder = TargetTypeBuildScope.newBuilder()
                        .setTypeId(type.getTypeId())
                        .setForceBuild(forceBuild)
                        .setAllTargets(true);
                scopes.add(builder.build());
            }
        }
        for (org.jetbrains.jps.builders.java.ResourcesTargetType type : org.jetbrains.jps.builders.java.ResourcesTargetType.ALL_TYPES) {
            if (includeTests || !type.isTests()) {
                TargetTypeBuildScope.Builder builder = TargetTypeBuildScope.newBuilder()
                        .setTypeId(type.getTypeId())
                        .setForceBuild(forceBuild)
                        .setAllTargets(true);
                scopes.add(builder.build());
            }
        }

        CliMessageHandler handler = new CliMessageHandler(verbose);
        Standalone.runBuild(loader, dataStorageRoot, handler, scopes, true);
        return !handler.hasErrors();
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
