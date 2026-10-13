package org.jetbrains.jps.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「未完整构建」标记的原子读写。
 *
 * <p>标记必须在真正构建开始之前建立，只有构建成功才清除：
 * 这样即使父命令被强杀、或子进程被终止，下一次构建也能据此先做一次全量重建，
 * 不会把不完整缓存/产物当成有效结果。
 */
final class WriteMarker {

    private WriteMarker() {
    }

    /**
     * 原子写入标记文件
     *
     * @param marker 标记文件；为 null 时不写入
     * @param buildId 构建 ID
     * @param reason 原因码
     * @param timestamp 时间戳
     */
    static void write(File marker, String buildId, String reason, long timestamp) {
        if (marker == null) return;
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String content = "{\"buildId\":\"" + escape(buildId == null ? "unknown" : buildId)
                + "\",\"errorCode\":\"" + escape(reason) + "\",\"timestamp\":" + timestamp + "}";
        File temp = new File(parent, marker.getName() + ".tmp-" + System.nanoTime());
        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
            Path target = marker.toPath();
            try {
                Files.move(temp.toPath(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(temp.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    /**
     * 仅当标记属于本次构建时才清除（避免误删更新构建写入的标记）
     *
     * @param marker 标记文件
     * @param buildId 本次构建 ID
     */
    static void clearIfOwned(File marker, String buildId) {
        if (marker == null || !marker.isFile()) return;
        try {
            String content = readAll(marker);
            Matcher matcher = Pattern.compile("\"buildId\"\\s*:\\s*\"([^\"]*)\"").matcher(content);
            if (matcher.find()) {
                String owner = matcher.group(1);
                if (buildId != null && !buildId.equals(owner)) return;
            }
            marker.delete();
        } catch (Exception ignored) {
        }
    }

    /** 读取文件文本。 */
    private static String readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        }
    }

    /** 转义 JSON 字符串。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
