package io.simpleide;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * JPS 增量存储（{@code .jps-cache}）的启动前预检、删除探针与隔离缓存。
 *
 * <p>为什么需要它：JPS 的 {@code BuildRunner.load} 在打开存储失败时会**先删除整个
 * dataStorageRoot 再全量重建**，该行为在 JPS 内部且没有开关可以关闭（已通过字节码确认）。
 *
 * <p>本类提供两种互补手段：
 * <ol>
 *   <li><b>识别性预检</b>：在启动 JPS 之前识别可判定的损坏特征（枚举器文件截断/不可读/头部不一致、
 *       零字节存储文件、version.dat 异常）。识别到损坏时可在未授权情况下直接拒绝启动；</li>
 *   <li><b>删除探针</b>：在存储目录内放置哨兵文件，构建后检查其是否消失 —— 这是「JPS 真的删除了
 *       存储根目录」的直接证据，<b>不依赖 mtime、条目数量或目录 inode</b>，因此不会把正常的
 *       首次构建、增量编译误判为内部重建。</li>
 * </ol>
 *
 * <p>能力边界（如实声明）：JPS 的存储格式不止一种，若某个损坏落在「本类判定为健康」的布局内，
 * 预检无法识别，此时 JPS 仍可能自行删除重建。此时通过删除探针如实上报，并可用
 * {@code --isolate-cache} 强制走隔离缓存（见 {@link #prepareIsolatedCache}）。
 */
final class JpsCacheValidator {

  /** 已知的 JPS 枚举器（PersistentEnumeratorBase）头部：0x07 0x3c 0xbb 0x0e。 */
  private static final String KNOWN_ENUMERATOR_HEADER = "073cbb0e";
  /** *.tab 文件被认为完整的最小长度。 */
  private static final long MIN_TAB_BYTES = 12;
  /** 删除探针文件名（放在存储目录内，JPS 删除存储根目录时会一并消失）。 */
  static final String PROBE_NAME = ".simple-ide-cache-probe";
  /** 隔离缓存的最大复制体积（默认 512 MiB，可通过系统属性覆盖）。 */
  private static final long DEFAULT_MAX_SHADOW_COPY_BYTES = 512L * 1024 * 1024;
  /** 隔离缓存目录名前缀（位于 .simple-ide 下，避免与 .jps-cache 混用）。 */
  static final String SHADOW_PREFIX = "jps-cache-shadow-";
  /** JPS 日志类文件不参与零字节完整性检查。 */
  private static final String[] LOG_SUFFIXES = {".log", ".properties"};

  /** 预检结论。 */
  static final class Report {
    /** 是否识别到存储损坏。 */
    boolean corrupt;
    /** 是否无法确证健康（格式未知、只能事后观察）。 */
    boolean uncertain;
    /** 损坏/不确定原因（仅路径与特征，不含凭据）。 */
    String reason;
    /** 参与的 *.tab 文件数量。 */
    int tabFiles;

    /** 是否可确证健康（既非损坏也非不确定）。 */
    boolean isHealthy() {
      return !corrupt && !uncertain;
    }

    /** 完整描述，便于写入错误信息。 */
    String describe() {
      if (reason != null) return reason;
      return "ok";
    }
  }

  /** 禁止实例化。 */
  private JpsCacheValidator() {
  }

  /** 项目默认存储目录。 */
  static Path projectCacheDir(Path project) {
    return project.resolve(".jps-cache");
  }

  /**
   * 校验指定存储目录。
   *
   * @param project 项目根目录
   * @return 预检结论
   */
  static Report validate(Path project) {
    return validateStorage(projectCacheDir(project));
  }

  /**
   * 校验存储目录。
   *
   * @param cacheDir 存储目录
   * @return 预检结论
   */
  static Report validateStorage(Path cacheDir) {
    Report report = new Report();
    if (!Files.isDirectory(cacheDir)) {
      return report;
    }

    // 1) 零字节/不可读存储文件：JPS 存储容器不会合法地为空，出现即视为损坏
    List<String> emptyOrUnreadable = new ArrayList<String>();
    collectEmptyOrUnreadable(cacheDir, emptyOrUnreadable);
    if (!emptyOrUnreadable.isEmpty()) {
      report.corrupt = true;
      report.reason = "JPS 存储存在零字节或不可读文件: " + preview(emptyOrUnreadable);
      return report;
    }

    // 2) version.dat 是数据格式版本（4 字节），存在但大小异常说明存储被破坏
    Path versionFile = cacheDir.resolve("version.dat");
    if (Files.isRegularFile(versionFile)) {
      try {
        long size = Files.size(versionFile);
        if (size != 4) {
          report.corrupt = true;
          report.reason = "JPS version.dat 大小异常: " + size + " 字节";
          return report;
        }
      }
      catch (Exception e) {
        report.corrupt = true;
        report.reason = "JPS version.dat 不可读: " + e.getMessage();
        return report;
      }
    }

    // 3) 枚举器存储（*.tab）：截断/不可读/头部不一致都是明确的损坏
    List<Path> tabFiles = listTabFiles(cacheDir);
    report.tabFiles = tabFiles.size();
    Map<String, Integer> headerCounts = new LinkedHashMap<String, Integer>();
    List<String> truncated = new ArrayList<String>();
    List<String> unreadable = new ArrayList<String>();
    Map<Path, String> headers = new LinkedHashMap<Path, String>();

    for (Path tab : tabFiles) {
      try {
        long size = Files.size(tab);
        if (size < MIN_TAB_BYTES) {
          truncated.add(tab.toString());
          continue;
        }
        String header = readHeader(tab);
        if (header == null) {
          unreadable.add(tab.toString());
          continue;
        }
        headers.put(tab, header);
        Integer count = headerCounts.get(header);
        headerCounts.put(header, count == null ? 1 : count + 1);
      }
      catch (Exception e) {
        unreadable.add(tab + " (" + e.getMessage() + ")");
      }
    }

    if (!truncated.isEmpty()) {
      report.corrupt = true;
      report.reason = "JPS 枚举器存储被截断: " + preview(truncated);
      return report;
    }
    if (!unreadable.isEmpty()) {
      report.corrupt = true;
      report.reason = "JPS 枚举器存储不可读: " + preview(unreadable);
      return report;
    }

    String majority = null;
    int majorityCount = 0;
    for (Map.Entry<String, Integer> entry : headerCounts.entrySet()) {
      if (entry.getValue() > majorityCount) {
        majority = entry.getKey();
        majorityCount = entry.getValue();
      }
    }
    if (majorityCount >= 2) {
      for (Map.Entry<Path, String> entry : headers.entrySet()) {
        if (!majority.equals(entry.getValue())) {
          report.corrupt = true;
          report.reason = "JPS 枚举器存储头部不一致: " + entry.getKey() + " (" + entry.getValue() + " != " + majority + ")";
          return report;
        }
      }
      if (!KNOWN_ENUMERATOR_HEADER.equals(majority)) {
        // 头部自洽但不是已知版本：无法确证健康，交由隔离机制处理
        report.uncertain = true;
        report.reason = "JPS 枚举器存储版本未知（头部自洽）: " + majority;
        return report;
      }
      return report;
    }
    if (majorityCount == 1) {
      if (!KNOWN_ENUMERATOR_HEADER.equals(majority)) {
        report.uncertain = true;
        report.reason = "JPS 枚举器存储版本未知（单一文件）: " + headers.keySet().iterator().next() + " (" + majority + ")";
      }
      return report;
    }

    // 4) 没有 *.tab 但存在其它存储内容：格式未知，无法确证健康
    if (hasOtherStorageContent(cacheDir)) {
      report.uncertain = true;
      report.reason = "JPS 存储布局无法识别（无枚举器文件但有其它存储内容）";
    }
    return report;
  }

  // ==================== 删除探针 ====================

  /** 指定存储目录内的探针文件路径。 */
  static Path deletionProbe(Path storageDir) {
    return storageDir.resolve(PROBE_NAME);
  }

  /**
   * 在存储目录内放置删除探针。
   *
   * @param storageDir 存储目录；不存在时返回 false（首次构建无需探针，也不存在被删除的缓存）
   * @return 是否成功放置
   */
  static boolean placeDeletionProbe(Path storageDir) {
    if (storageDir == null || !Files.isDirectory(storageDir)) return false;
    try {
      AtomicFiles.write(deletionProbe(storageDir), "probe=" + System.nanoTime());
      return true;
    }
    catch (Exception e) {
      return false;
    }
  }

  /** 探针是否仍在（true 表示存储根目录没有被整体删除）。 */
  static boolean probeSurvived(Path storageDir) {
    return storageDir != null && Files.isRegularFile(deletionProbe(storageDir));
  }

  /** 移除探针。 */
  static void removeProbe(Path storageDir) {
    if (storageDir == null) return;
    try {
      Files.deleteIfExists(deletionProbe(storageDir));
    }
    catch (Exception ignored) {
    }
  }

  // ==================== 隔离缓存 ====================

  /**
   * 准备项目级稳定隔离缓存（受保护构建使用）。
   *
   * <p>关键：**不能**从共享 {@code .jps-cache} 复制播种。JPS 的增量存储里记录了「上一次构建的产物路径」，
   * 若把共享存储的副本当作隔离存储，JPS 在强制/全量重建时会按记录删除**共享 target 下**的产物，
   * 反而破坏运行中应用所用的 class。所以隔离缓存必须从空状态开始：JPS 看不到共享产物记录，
   * 只会把新产物写到隔离输出目录。首次受保护构建因此是全量编译，之后复用同一隔离缓存保持增量。
   *
   * @param project 项目根目录
   * @return 隔离缓存目录
   * @throws IOException 无法准备隔离目录
   */
  static Path prepareIsolatedCache(Path project) throws IOException {
    Path isolated = project.resolve(".simple-ide/isolated-build/cache");
    if (Files.isDirectory(isolated)) {
      return isolated;
    }
    Files.createDirectories(isolated);
    return isolated;
  }

  /** 计算存储目录总字节数。 */
  static long storageSize(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return 0;
    final long[] total = new long[]{0};
    try (Stream<Path> stream = Files.walk(dir, 32)) {
      stream.filter(Files::isRegularFile).forEach(path -> {
        try {
          total[0] += Files.size(path);
        }
        catch (IOException ignored) {
        }
      });
    }
    catch (IOException ignored) {
    }
    return total[0];
  }

  // ==================== 内部工具 ====================

  /** 收集零字节或不可读的存储文件（忽略日志类文件与探针）。 */
  private static void collectEmptyOrUnreadable(Path cacheDir, List<String> problems) {
    try (Stream<Path> stream = Files.walk(cacheDir, 32)) {
      stream.filter(Files::isRegularFile).forEach(path -> {
        String name = path.getFileName().toString();
        if (PROBE_NAME.equals(name)) return;
        for (String suffix : LOG_SUFFIXES) {
          if (name.endsWith(suffix)) return;
        }
        try {
          if (!Files.isReadable(path)) {
            problems.add(path + " (不可读)");
            return;
          }
          if (Files.size(path) == 0) {
            problems.add(path + " (零字节)");
          }
        }
        catch (IOException ignored) {
        }
      });
    }
    catch (IOException ignored) {
    }
  }

  /** 是否还有其它存储内容（timestamps/targets/mappings 等）。 */
  private static boolean hasOtherStorageContent(Path cacheDir) {
    String[] storageDirs = {"timestamps", "targets", "mappings", "out-target", "src-form"};
    for (String dir : storageDirs) {
      Path candidate = cacheDir.resolve(dir);
      if (!Files.isDirectory(candidate)) continue;
      try (Stream<Path> stream = Files.walk(candidate, 8)) {
        if (stream.anyMatch(Files::isRegularFile)) return true;
      }
      catch (IOException ignored) {
      }
    }
    return false;
  }

  /** 递归收集 *.tab 文件。 */
  private static List<Path> listTabFiles(Path cacheDir) {
    List<Path> result = new ArrayList<Path>();
    try (Stream<Path> stream = Files.walk(cacheDir, 16)) {
      stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".tab"))
        .forEach(result::add);
    }
    catch (IOException ignored) {
    }
    return result;
  }

  /** 读取文件头 4 字节的十六进制。 */
  private static String readHeader(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      byte[] header = new byte[4];
      if (raf.read(header) != 4) return null;
      StringBuilder sb = new StringBuilder();
      for (byte b : header) sb.append(String.format("%02x", b));
      return sb.toString();
    }
    catch (Exception e) {
      return null;
    }
  }

  /** 递归复制目录。 */
  private static void copyRecursively(final Path source, final Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path destination = target.resolve(source.relativize(file).toString());
        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** 递归删除目录。 */
  private static void deleteRecursively(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) return;
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.deleteIfExists(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
        Files.deleteIfExists(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** 生成最多 3 项的可读预览。 */
  private static String preview(List<String> items) {
    List<String> shown = items.size() > 3 ? items.subList(0, 3) : items;
    return String.join(", ", shown) + (items.size() > shown.size() ? " 等 " + items.size() + " 个" : "");
  }

  /** 判断目录下是否存在给定的直接子文件（便于诊断）。 */
  static boolean hasFile(Path dir, String name) {
    return new File(dir.toFile(), name).isFile();
  }
}
