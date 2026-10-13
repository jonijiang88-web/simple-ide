package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JPS 增量存储的启动前预检与存储身份快照。
 *
 * <p>这些特征对应实测可触发 JPS 内部「删除 dataStorageRoot 再全量重建」的存储损坏场景。
 */
public final class JpsCacheValidatorTest {

  /** 构造合法的枚举器存储头部（与内置 JPS 版本一致）。 */
  private static void writeHealthyTab(Path file, int size) throws IOException {
    byte[] bytes = new byte[Math.max(size, 64)];
    bytes[0] = 0x07;
    bytes[1] = 0x3c;
    bytes[2] = (byte) 0xbb;
    bytes[3] = 0x0e;
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  /** 验证健康存储不会被误判为损坏。 */
  @Test
  void healthy_storage_is_not_corrupt(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    writeHealthyTab(project.resolve(".jps-cache/mappings/b.tab"), 4096);
    Files.write(project.resolve(".jps-cache/version.dat"), new byte[]{1, 0, 0, 0});
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：健康存储不判定为损坏
    assertFalse(report.corrupt, report.describe());
  }

  /** 验证不存在缓存目录时不判定为损坏（全新项目）。 */
  @Test
  void absent_cache_is_not_corrupt(@TempDir Path project) {
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：全新项目不应被误判
    assertFalse(report.corrupt);
  }

  /** 验证被截断的枚举器文件被识别为损坏。 */
  @Test
  void truncated_tab_is_corrupt(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    Files.write(project.resolve(".jps-cache/mappings/b.tab"), new byte[]{0x07});
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：截断文件被识别为损坏
    assertTrue(report.corrupt);
    // 验证：原因指出截断
    assertTrue(report.describe().contains("截断"), report.describe());
  }

  /** 验证多文件头部不一致时被识别为损坏（实测可触发 JPS 内部重建）。 */
  @Test
  void inconsistent_headers_are_corrupt(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    writeHealthyTab(project.resolve(".jps-cache/mappings/b.tab"), 4096);
    writeHealthyTab(project.resolve(".jps-cache/mappings/c.tab"), 4096);
    // 破坏其中一个文件的头部，模拟 PersistentEnumerator storage corrupted
    byte[] broken = new byte[4096];
    broken[0] = (byte) 0xff;
    broken[1] = (byte) 0xff;
    Files.write(project.resolve(".jps-cache/mappings/a.tab"), broken);

    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：头部不一致被识别为损坏
    assertTrue(report.corrupt);
    assertTrue(report.describe().contains("头部不一致"), report.describe());
  }

  /** 验证单文件且版本未知时判为「无法确证健康」（不武断判定损坏）。 */
  @Test
  void unknown_single_header_is_uncertain(@TempDir Path project) throws Exception {
    byte[] broken = new byte[4096];
    broken[0] = 0x11;
    broken[1] = 0x22;
    broken[2] = 0x33;
    broken[3] = 0x44;
    Files.createDirectories(project.resolve(".jps-cache/mappings"));
    Files.write(project.resolve(".jps-cache/mappings/only.tab"), broken);
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：无法识别的枚举器版本不武断判定为损坏
    assertFalse(report.corrupt);
    // 验证：但也不认为健康（需要隔离或事后观察）
    assertTrue(report.uncertain);
  }

  /** 验证 version.dat 大小异常被识别为损坏。 */
  @Test
  void bad_version_file_is_corrupt(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    Files.write(project.resolve(".jps-cache/version.dat"), new byte[]{1, 0, 0});
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：version.dat 大小异常被识别
    assertTrue(report.corrupt);
    assertTrue(report.describe().contains("version.dat"), report.describe());
  }

  /** 递归删除目录。 */
  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
        }
      });
    }
  }

  /** 【R3-1 回归】普通构建（含 mtime/条目变化）不会导致检测到删除 */
  @Test
  void deletion_probe_survives_normal_build_mutations(@TempDir Path project) throws Exception {
    Path cacheDir = project.resolve(".jps-cache");
    writeHealthyTab(cacheDir.resolve("mappings/a.tab"), 4096);
    // 验证：缓存存在时探针可被放置
    assertTrue(JpsCacheValidator.placeDeletionProbe(cacheDir));
    // 模拟正常增量构建：新增条目、更新 mtime（旧实现会据此误判为删除重建）
    Thread.sleep(20);
    writeHealthyTab(cacheDir.resolve("mappings/b.tab"), 8192);
    Files.setLastModifiedTime(cacheDir.resolve("mappings/a.tab"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));
    Files.write(cacheDir.resolve("version.dat"), new byte[]{1, 0, 0, 0});
    // 验证：探针仍在 → 未检测到存储根目录被删除
    assertTrue(JpsCacheValidator.probeSurvived(cacheDir));
  }

  /** 【R3-1 回归】存储根目录被整体删除时探针消失（真实删除证据） */
  @Test
  void deletion_probe_detects_root_deletion(@TempDir Path project) throws Exception {
    Path cacheDir = project.resolve(".jps-cache");
    writeHealthyTab(cacheDir.resolve("mappings/a.tab"), 4096);
    assertTrue(JpsCacheValidator.placeDeletionProbe(cacheDir));
    // 模拟 JPS 内部删除 dataStorageRoot 后重建
    deleteRecursively(cacheDir);
    writeHealthyTab(cacheDir.resolve("mappings/a.tab"), 4096);
    // 验证：探针消失 → 确认发生过整根删除
    assertFalse(JpsCacheValidator.probeSurvived(cacheDir));
  }

  /** 【R3-1 回归】缓存目录不存在时不放置探针，且不视为删除 */
  @Test
  void deletion_probe_not_placed_for_absent_cache(@TempDir Path project) {
    Path cacheDir = project.resolve(".jps-cache");
    // 验证：首次构建（缓存不存在）不会放置探针
    assertFalse(JpsCacheValidator.placeDeletionProbe(cacheDir));
    // 验证：也不会据此判定为删除
    assertFalse(JpsCacheValidator.probeSurvived(cacheDir));
  }

  /** 验证零字节存储文件被判为损坏（正常存储容器不会合法为空） */
  @Test
  void zero_byte_storage_file_is_corrupt(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    Files.write(project.resolve(".jps-cache/mappings/a.tab.values"), new byte[0]);
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：零字节存储文件被识别为损坏
    assertTrue(report.corrupt);
    assertTrue(report.describe().contains("零字节"), report.describe());
  }

  /** 验证头部自洽但版本未知时判为「无法确证健康」而不是直接判损坏 */
  @Test
  void unknown_but_consistent_header_is_uncertain(@TempDir Path project) throws Exception {
    byte[] bytes = new byte[4096];
    bytes[0] = 0x22;
    bytes[1] = 0x33;
    bytes[2] = 0x44;
    bytes[3] = 0x55;
    Files.createDirectories(project.resolve(".jps-cache/mappings"));
    Files.write(project.resolve(".jps-cache/mappings/a.tab"), bytes);
    Files.write(project.resolve(".jps-cache/mappings/b.tab"), bytes);
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：不直接判定为损坏
    assertFalse(report.corrupt);
    // 验证：但也不认为健康（需要隔离或事后观察）
    assertTrue(report.uncertain);
    assertFalse(report.isHealthy());
  }

  /** 验证健康存储的 isHealthy 语义 */
  @Test
  void healthy_storage_reports_healthy(@TempDir Path project) throws Exception {
    writeHealthyTab(project.resolve(".jps-cache/mappings/a.tab"), 4096);
    writeHealthyTab(project.resolve(".jps-cache/mappings/b.tab"), 4096);
    JpsCacheValidator.Report report = JpsCacheValidator.validate(project);
    // 验证：识别为标准布局时视为健康
    assertTrue(report.isHealthy(), report.describe());
  }

  /** 验证注释行不会让 Classpath 解析报出缺失 Jar 告警（空依赖模块的已确证缓存）。 */
  @Test
  void comment_only_cache_produces_no_warning(@TempDir Path project) throws Exception {
    Files.createDirectories(project.resolve(".jps-deps"));
    Files.write(project.resolve(".jps-deps/classpath-solo.cache"),
      "# no external dependencies (proven complete)\n".getBytes(StandardCharsets.UTF_8));
    Files.write(project.resolve("pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>solo</artifactId>\n</project>\n")
        .getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(project.resolve("src/main/java"));
    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(project, null, false);
    // 验证：注释行不被当成缺失 Jar，不产生告警
    assertTrue(plan.warnings.isEmpty(), "不应有告警: " + plan.warnings);
  }
}
