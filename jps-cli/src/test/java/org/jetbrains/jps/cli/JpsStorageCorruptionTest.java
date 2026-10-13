package org.jetbrains.jps.cli;

import org.junit.Test;

import java.io.EOFException;
import java.util.zip.ZipException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证 JPS 增量存储损坏的识别，保证受控重建路径可被触发且不误判真实构建错误。 */
public class JpsStorageCorruptionTest {

    /**
     * 验证 EOF/压缩格式损坏被识别为存储损坏
     */
    @Test
    public void detectsEofAndZipCorruption() {
        // 验证：EOFException 被识别
        assertTrue(JpsIncrementalCompiler.isStorageCorruption(new EOFException()));
        // 验证：ZipException 被识别
        assertTrue(JpsIncrementalCompiler.isStorageCorruption(new ZipException("invalid stream header")));
        // 验证：包装在外层异常中的损坏也能被识别
        assertTrue(JpsIncrementalCompiler.isStorageCorruption(
                new RuntimeException("build failed", new EOFException("unexpected end of stream"))));
    }

    /**
     * 验证普通构建异常不会被误判为存储损坏
     */
    @Test
    public void doesNotMisclassifyOrdinaryFailures() {
        // 验证：普通语义错误不被误判
        assertFalse(JpsIncrementalCompiler.isStorageCorruption(new IllegalStateException("未解析到任何有效的 Maven 模块")));
        // 验证：空消息异常不被误判
        assertFalse(JpsIncrementalCompiler.isStorageCorruption(new RuntimeException()));
    }

    /**
     * 验证缓存格式版本与工具版本可追溯（缓存失效与升级路径依赖该版本）
     */
    @Test
    public void exposesCacheFormatVersion() {
        // 验证：缓存格式版本非空
        assertTrue(MavenPomResolver.CACHE_FORMAT_VERSION != null && MavenPomResolver.CACHE_FORMAT_VERSION.length() > 0);
        // 验证：工具版本非空
        assertTrue(MavenPomResolver.TOOL_VERSION != null && MavenPomResolver.TOOL_VERSION.length() > 0);
    }
}
