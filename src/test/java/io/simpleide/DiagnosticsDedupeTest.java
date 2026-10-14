package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证构建诊断的去重与汇总：同一 (severity, message) 只输出一条并附出现次数，
 * 避免单个文件的数百条过期 API 警告把构建 JSON 刷成几百 KB。
 */
public final class DiagnosticsDedupeTest {

  /** 构造一条诊断。 */
  private static Map<String, Object> diagnostic(String severity, String message, String file, int line) {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("severity", severity);
    item.put("message", message);
    item.put("file", file);
    item.put("line", Integer.valueOf(line));
    return item;
  }

  /** 同规则重复警告必须合并为一条并统计出现次数。 */
  @Test
  public void mergesRepeatedWarningsAndCounts() {
    List<Map<String, Object>> raw = new ArrayList<Map<String, Object>>();
    raw.add(diagnostic("WARN", "StringUtils.isEmpty 已过时", "A.java", 10));
    raw.add(diagnostic("WARN", "StringUtils.isEmpty 已过时", "A.java", 20));
    raw.add(diagnostic("WARN", "StringUtils.isEmpty 已过时", "A.java", 30));
    raw.add(diagnostic("WARN", "I18nMessageUtils.getMessage 已过时", "A.java", 40));
    raw.add(diagnostic("ERROR", "找不到符号", "B.java", 5));
    Map<String, Object> summary = new LinkedHashMap<String, Object>();

    List<Map<String, Object>> unique = SimpleIde.dedupeDiagnostics(raw, summary);

    // 验证：3 条同规则警告合并为 1 条
    assertEquals(3, unique.size());
    // 验证：合并条目带出现次数
    assertEquals(3, unique.get(0).get("count"));
    // 验证：汇总保留原始总条数
    assertEquals(5, summary.get("diagnosticsTotal"));
    // 验证：汇总按 severity 计数
    @SuppressWarnings("unchecked")
    Map<String, Object> bySeverity = (Map<String, Object>) summary.get("diagnosticsSummary");
    assertEquals(4, bySeverity.get("WARN"));
    assertEquals(1, bySeverity.get("ERROR"));
  }

  /** 唯一诊断超过输出上限时截断并标记，避免超大 JSON。 */
  @Test
  public void truncatesBeyondLimitAndFlagsIt() {
    List<Map<String, Object>> raw = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 260; i++) {
      raw.add(diagnostic("WARN", "警告 " + i, "A.java", i));
    }
    Map<String, Object> summary = new LinkedHashMap<String, Object>();

    List<Map<String, Object>> unique = SimpleIde.dedupeDiagnostics(raw, summary);

    // 验证：输出被截断到上限
    assertEquals(200, unique.size());
    // 验证：截断数量显式标记，调用方不会误判为全部诊断
    assertEquals(60, summary.get("diagnosticsTruncated"));
    assertEquals(260, summary.get("diagnosticsTotal"));
  }

  /** 空诊断列表不应产生任何汇总噪声。 */
  @Test
  public void handlesEmptyDiagnostics() {
    Map<String, Object> summary = new LinkedHashMap<String, Object>();

    List<Map<String, Object>> unique = SimpleIde.dedupeDiagnostics(new ArrayList<Map<String, Object>>(), summary);

    // 验证：无诊断时输出为空且总数为 0
    assertTrue(unique.isEmpty());
    assertEquals(0, summary.get("diagnosticsTotal"));
  }
}
