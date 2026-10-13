package org.jetbrains.jps.cli;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证子进程侧构建锁与「未完整构建」标记的语义。
 *
 * <p>这两者是「父命令被强杀后不让新构建与遗留 JPS 并发写同一缓存」的基础。
 */
public class ChildBuildLockAndMarkerTest {

    /**
     * 验证构建锁互斥：同一文件第二次获取失败，释放后可再次获取
     *
     * @throws Exception 测试异常
     */
    @Test
    public void build_lock_is_exclusive_and_reusable() throws Exception {
        File dir = Files.createTempDirectory("jps-lock").toFile();
        File lockFile = new File(dir, "jps-build.lock");

        ChildBuildLock first = ChildBuildLock.acquire(lockFile);
        try {
            // 验证：锁文件写入持有者 PID
            String content = new String(Files.readAllBytes(lockFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(content.contains("PID: "));
            // 验证：已被持有时再次获取必须失败（阻断并发写同一缓存）
            try {
                ChildBuildLock second = ChildBuildLock.acquire(lockFile);
                second.close();
                fail("锁已被持有时不应获取成功");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("已被其它构建持有"));
            }
        } finally {
            first.close();
        }

        // 验证：释放后可再次获取
        ChildBuildLock third = ChildBuildLock.acquire(lockFile);
        assertTrue(third != null);
        third.close();
    }

    /**
     * 验证标记写入与「按所有权清除」
     *
     * @throws Exception 测试异常
     */
    @Test
    public void marker_write_and_owned_clear() throws Exception {
        File dir = Files.createTempDirectory("jps-marker").toFile();
        File marker = new File(dir, "incomplete.json");

        WriteMarker.write(marker, "build-A", "in_progress", 123L);
        // 验证：标记被原子写入且包含构建 ID 与原因
        String content = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("build-A"));
        assertTrue(content.contains("in_progress"));
        assertTrue(content.contains("123"));

        // 验证：非本次构建不清除标记
        WriteMarker.clearIfOwned(marker, "build-B");
        assertTrue(marker.isFile());
        // 验证：本次构建清除标记
        WriteMarker.clearIfOwned(marker, "build-A");
        assertFalse(marker.isFile());
        // 验证：没有残留临时文件
        File[] leftovers = dir.listFiles((d, name) -> name.contains(".tmp-"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    /**
     * 验证标记目录不存在时会自动创建
     *
     * @throws Exception 测试异常
     */
    @Test
    public void marker_creates_parent_dirs() throws Exception {
        File dir = Files.createTempDirectory("jps-marker-nested").toFile();
        File marker = new File(dir, "nested/deeper/incomplete.json");
        WriteMarker.write(marker, "build-C", "in_progress", 1L);
        // 验证：父目录被创建且文件存在
        assertTrue(marker.isFile());
        WriteMarker.clearIfOwned(marker, "build-C");
        assertFalse(marker.isFile());
    }

    /**
     * 验证 WriteMarker 在 marker 为 null 时安全无操作
     */
    @Test
    public void marker_null_is_noop() {
        // 验证：未提供标记路径时不做任何事也不报错
        WriteMarker.write(null, "build-D", "in_progress", 1L);
        WriteMarker.clearIfOwned(null, "build-D");
    }
}
