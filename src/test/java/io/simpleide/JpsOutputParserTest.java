package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 JPS 构建输出解析：结构化诊断、缓存损坏特征识别与阶段推断。 */
public final class JpsOutputParserTest {

  /** 验证编译诊断被解析为 file/line/column/severity，而不是一段字符串。 */
  @Test
  void parses_structured_diagnostics() {
    String output = "[ERROR] /tmp/demo/src/main/java/com/Demo.java(42:17): cannot find symbol: method foo()\n"
      + "[WARN]  /tmp/demo/src/main/java/com/Demo.java(10:3): deprecated API\n"
      + "[INFO] 解析到 2 个模块\n";
    List<Map<String, Object>> diagnostics = JpsOutputParser.parseDiagnostics(output);

    // 验证：解析出 2 条带定位的诊断
    assertEquals(2, diagnostics.size());
    Map<String, Object> first = diagnostics.get(0);
    // 验证：严重级别被识别
    assertEquals("ERROR", first.get("severity"));
    // 验证：文件路径被单独提取
    assertEquals("/tmp/demo/src/main/java/com/Demo.java", first.get("file"));
    // 验证：行号被单独提取
    assertEquals(42L, first.get("line"));
    // 验证：列号被单独提取
    assertEquals(17L, first.get("column"));
    // 验证：消息被单独提取
    assertEquals("cannot find symbol: method foo()", first.get("message"));
    // 验证：warning 级别被识别
    assertEquals("WARN", diagnostics.get(1).get("severity"));
  }

  /** 验证真实源码错误不会被误判为缓存损坏。 */
  @Test
  void real_source_errors_are_not_cache_corruption() {
    String output = "[ERROR] /tmp/demo/src/main/java/com/Demo.java(1:1): cannot find symbol\n";
    // 验证：识别出源码级错误
    assertTrue(JpsOutputParser.hasRealSourceError(output));
    // 验证：不命中缓存损坏特征
    assertFalse(JpsOutputParser.hasCacheCorruptionEvidence(output));
  }

  /** 验证 Lombok builder 缺失不会触发重建。 */
  @Test
  void lombok_builder_missing_is_not_cache_corruption() {
    String output = "[ERROR] /tmp/demo/src/main/java/com/Demo.java(9:20): cannot find symbol\n"
      + "  symbol:   method builder()\n  location: class com.Demo\n";
    // 验证：不命中缓存损坏特征，避免用重建掩盖配置问题
    assertFalse(JpsOutputParser.hasCacheCorruptionEvidence(output));
    // 验证：被识别为源码错误
    assertTrue(JpsOutputParser.hasRealSourceError(output));
  }

  /** 验证明确的缓存损坏特征会触发重建。 */
  @Test
  void cache_corruption_evidence_is_detected() {
    // 验证：JPS 内部缓存重建标记被识别
    assertTrue(JpsOutputParser.hasCacheCorruptionEvidence("[CACHE_REBUILT] JPS 增量存储损坏"));
    // 验证：EOF 缓存读取失败被识别
    assertTrue(JpsOutputParser.hasCacheCorruptionEvidence("java.io.EOFException: null"));
    // 验证：class 文件损坏被识别
    assertTrue(JpsOutputParser.hasCacheCorruptionEvidence("[ERROR] bad class file: cache corrupted"));
  }

  /** 验证构建耗时被解析。 */
  @Test
  void parses_reported_duration() {
    // 验证：从输出中解析出毫秒耗时
    assertEquals(1234L, JpsOutputParser.parseReportedDurationMs("[SUCCESS] 构建成功! 耗时: 1234 ms", 0));
    // 验证：解析失败时回退到默认值
    assertEquals(99L, JpsOutputParser.parseReportedDurationMs("no duration here", 99));
  }

  /** 验证阶段推断基于可观测证据。 */
  @Test
  void infers_phases_from_observable_lines() {
    // 验证：清理模式推断为重建阶段
    assertEquals(BuildTracker.PHASE_REBUILD, JpsOutputParser.inferPhase("[INFO] 清理模式: 清空编译产物及构建缓存..."));
    // 验证：模块解析行推断为依赖解析阶段
    assertEquals(BuildTracker.PHASE_DEPENDENCY_RESOLVE, JpsOutputParser.inferPhase("[INFO] 解析到 3 个模块:"));
    // 验证：测试产物相关行推断为测试编译阶段
    assertEquals(BuildTracker.PHASE_COMPILE_TEST, JpsOutputParser.inferPhase("[INFO] compiling /x/src/test/java/A.java"));
    // 验证：无法判断时返回 null，不臆造阶段
    assertEquals(null, JpsOutputParser.inferPhase("[INFO] 普通日志"));
  }
}
