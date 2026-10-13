package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证受保护构建的隔离缓存与隔离输出：
 * 隔离缓存必须从空状态开始（不得复制共享存储，否则 JPS 会按共享产物记录删除共享 target），
 * 且隔离目录可稳定复用、绝不改动共享 .jps-cache。
 */
public final class ShadowCacheTest {

  /** 创建带内容的共享存储目录。 */
  private static void seedSharedCache(Path project) throws Exception {
    Path cacheDir = project.resolve(".jps-cache/mappings");
    Files.createDirectories(cacheDir);
    Files.write(cacheDir.resolve("a.tab"), "shared-storage".getBytes(StandardCharsets.UTF_8));
    Files.write(project.resolve(".jps-cache/version.dat"), new byte[]{1, 0, 0, 0});
  }

  /** 【R4 核心】隔离缓存必须是空的：不得携带共享存储中的「产物路径记录」。 */
  @Test
  void isolated_cache_starts_empty_and_never_copies_shared_storage(@TempDir Path project) throws Exception {
    seedSharedCache(project);
    Path isolated = JpsCacheValidator.prepareIsolatedCache(project);

    // 验证：隔离缓存目录被创建
    assertTrue(Files.isDirectory(isolated));
    // 验证：没有复制共享存储内容（复制会带来共享产物记录，导致 JPS 删除共享 target 产物）
    assertFalse(Files.exists(isolated.resolve("mappings/a.tab")));
    assertFalse(Files.exists(isolated.resolve("version.dat")));
    // 验证：共享缓存未被改动
    assertTrue(Files.isRegularFile(project.resolve(".jps-cache/mappings/a.tab")));
    assertTrue(Files.isRegularFile(project.resolve(".jps-cache/version.dat")));
  }

  /** 验证隔离缓存可稳定复用（允许在受保护构建之间保持增量）。 */
  @Test
  void isolated_cache_is_stable_across_calls(@TempDir Path project) throws Exception {
    Path first = JpsCacheValidator.prepareIsolatedCache(project);
    Files.write(first.resolve("marker"), "isolated-state".getBytes(StandardCharsets.UTF_8));
    Path second = JpsCacheValidator.prepareIsolatedCache(project);
    // 验证：同一目录被复用
    assertEquals(first, second);
    // 验证：已有隔离状态被保留
    assertTrue(Files.isRegularFile(second.resolve("marker")));
  }

  /** 验证隔离输出根位于 .simple-ide 下，与共享 target 完全分离。 */
  @Test
  void isolated_outputs_are_outside_shared_target(@TempDir Path project) throws Exception {
    Path isolatedRoot = SimpleIde.isolatedBuildRoot(project);
    // 验证：隔离构建根目录位于 .simple-ide 下
    assertTrue(isolatedRoot.toString().contains(".simple-ide"));
    // 验证：不与共享 target 重叠
    assertFalse(isolatedRoot.toAbsolutePath().startsWith(project.resolve("target").toAbsolutePath()));
  }

  /** 验证无共享缓存时也能准备隔离缓存（首次受保护构建）。 */
  @Test
  void isolated_cache_works_without_shared_cache(@TempDir Path project) throws Exception {
    Path isolated = JpsCacheValidator.prepareIsolatedCache(project);
    // 验证：仅创建隔离目录，不创建共享缓存
    assertTrue(Files.isDirectory(isolated));
    assertFalse(Files.exists(project.resolve(".jps-cache")));
  }
}
