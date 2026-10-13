package io.simpleide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 关键状态文件的原子写入工具。
 *
 * <p>构建状态、缓存元数据这类文件如果被部分写入，就会被后续流程误当成有效数据。
 * 这里统一用「临时文件 + 原子替换」写入，写入过程中失败也不会污染已有的有效文件。
 */
final class AtomicFiles {

  /** 禁止实例化。 */
  private AtomicFiles() {
  }

  /**
   * 原子写入文本文件（UTF-8）。
   *
   * @param target 目标文件
   * @param content 文本内容
   * @throws IOException 写入异常
   */
  static void write(Path target, String content) throws IOException {
    writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 原子写入字节内容；同目录临时文件 + ATOMIC_MOVE，失败时退化为 REPLACE_EXISTING。
   *
   * @param target 目标文件
   * @param content 字节内容
   * @throws IOException 写入异常
   */
  static void writeBytes(Path target, byte[] content) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temp = parent != null
      ? parent.resolve(target.getFileName().toString() + ".tmp-" + System.nanoTime())
      : target.resolveSibling(target.getFileName().toString() + ".tmp-" + System.nanoTime());
    Files.write(temp, content);
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (Exception atomicUnsupported) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * 读取文本文件内容（UTF-8）。
   *
   * @param path 文件路径
   * @return 文本内容；文件不存在时返回 null
   * @throws IOException 读取异常
   */
  static String read(Path path) throws IOException {
    if (path == null || !Files.isRegularFile(path)) return null;
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /**
   * 追加文本到文件（用于构建日志这类流式证据）。
   *
   * @param path 文件路径
   * @param content 文本内容
   * @throws IOException 写入异常
   */
  static void append(Path path, String content) throws IOException {
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(path, content.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
  }
}
