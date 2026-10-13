package io.simpleide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JPS 构建输出解析器。
 *
 * <p>把子进程输出转成结构化信息：编译诊断（file/line/column/severity）、
 * 报告的耗时、以及「是否命中缓存/历史 class 损坏特征」——后者决定 auto-rebuild 是否值得触发，
 * 必须与真实源码错误严格区分。
 */
final class JpsOutputParser {

  /** 形状：path(line:column): message。 */
  private static final Pattern DIAGNOSTIC_PATTERN = Pattern.compile("^(.*?)\\((\\d+):(\\d+)\\):\\s*(.*)$");
  /** 形状：path: message（无行列）。 */
  private static final Pattern FILE_ONLY_PATTERN = Pattern.compile("^([^\\s:][^:]*\\.(java|kt|groovy)):\\s*(.*)$");

  /** 禁止实例化。 */
  private JpsOutputParser() {
  }

  /**
   * 解析编译诊断，定位到 file/line/column/severity。
   *
   * @param output 构建输出
   * @return 诊断列表，字段为 severity/file/line/column/message；无法定位的行不进入结果
   */
  static List<Map<String, Object>> parseDiagnostics(String output) {
    List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
    if (output == null || output.isEmpty()) return diagnostics;
    for (String rawLine : output.split("\n")) {
      String line = rawLine.trim();
      if (line.isEmpty()) continue;
      String severity = null;
      if (line.startsWith("[ERROR]")) {
        severity = "ERROR";
        line = line.substring("[ERROR]".length()).trim();
      }
      else if (line.startsWith("[WARN]")) {
        severity = "WARN";
        line = line.substring("[WARN]".length()).trim();
      }
      else {
        continue;
      }
      if (line.startsWith("[ERROR]") || line.startsWith("错误") || line.startsWith("警告")) continue;

      Matcher matcher = DIAGNOSTIC_PATTERN.matcher(line);
      if (matcher.matches()) {
        Map<String, Object> diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("severity", severity);
        diagnostic.put("file", matcher.group(1).trim());
        diagnostic.put("line", Long.valueOf(matcher.group(2)));
        diagnostic.put("column", Long.valueOf(matcher.group(3)));
        diagnostic.put("message", matcher.group(4).trim());
        diagnostics.add(diagnostic);
        continue;
      }
      Matcher fileOnly = FILE_ONLY_PATTERN.matcher(line);
      if (fileOnly.matches()) {
        Map<String, Object> diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("severity", severity);
        diagnostic.put("file", fileOnly.group(1).trim());
        diagnostic.put("message", fileOnly.group(3).trim());
        diagnostics.add(diagnostic);
      }
    }
    return diagnostics;
  }

  /** 是否存在带源码定位的错误级诊断（用于区分真实源码错误与缓存故障）。 */
  static boolean hasSourceErrors(List<Map<String, Object>> diagnostics) {
    for (Map<String, Object> diagnostic : diagnostics) {
      if ("ERROR".equals(diagnostic.get("severity")) && diagnostic.containsKey("line")) return true;
    }
    return false;
  }

  /**
   * 判断失败是否命中缓存/历史 class 损坏特征。
   *
   * <p>只有明确的缓存损坏证据才返回 true；真实源码错误（如 cannot find symbol、Lombok builder 缺失）
   * 一律返回 false，避免用「重建」掩盖真实代码或配置问题。
   *
   * @param output 构建输出
   * @return 是否命中缓存损坏特征
   */
  static boolean hasCacheCorruptionEvidence(String output) {
    if (output == null || output.isEmpty()) return false;
    String lower = output.toLowerCase();

    // JPS 内部已触发的缓存重建标记：最可靠的证据
    if (output.contains("[CACHE_REBUILT]")) return true;
    if (lower.contains("eofexception")) return true;
    if (lower.contains("unexpected end of")) return true;
    if (lower.contains("invalid stream header")) return true;
    if (lower.contains("bad class file") && !lower.contains("cannot find symbol")) return true;
    if ((lower.contains("cache") || lower.contains("storage")) && lower.contains("corrupt")) return true;
    if (lower.contains("class file") && lower.contains("corrupt")) return true;
    // MapStruct 读取历史匿名内部类产物的缓存特征
    if (lower.contains("mapstruct") && (lower.contains("anonymous") || lower.contains("internal class") || lower.contains("no such")) ) {
      return true;
    }
    return false;
  }

  /** 是否存在真实源码级错误（带源码定位）。 */
  static boolean hasRealSourceError(String output) {
    return hasSourceErrors(parseDiagnostics(output));
  }

  /**
   * 从构建输出中解析 JPS 报告的耗时。
   *
   * @param output 构建输出
   * @param fallback 解析失败时的回退值
   * @return 耗时毫秒
   */
  static long parseReportedDurationMs(String output, long fallback) {
    if (output == null) return fallback;
    Matcher matcher = Pattern.compile("耗时:\\s*(\\d+)\\s*ms").matcher(output);
    if (matcher.find()) {
      try {
        return Long.parseLong(matcher.group(1));
      }
      catch (NumberFormatException ignored) {
      }
    }
    return fallback;
  }

  /**
   * 从输出推断 JPS 当前阶段（仅使用可观测证据，不臆造）。
   *
   * @param line 单行输出
   * @return 推断出的阶段；无法判断时返回 null
   */
  static String inferPhase(String line) {
    if (line == null) return null;
    String trimmed = line.trim();
    if (trimmed.contains("清理模式")) return BuildTracker.PHASE_REBUILD;
    if (trimmed.contains("解析到") && trimmed.contains("个模块")) return BuildTracker.PHASE_DEPENDENCY_RESOLVE;
    if (trimmed.contains("构建模块作用域")) return BuildTracker.PHASE_DEPENDENCY_RESOLVE;
    if (trimmed.contains("test-classes") || trimmed.contains("/src/test/")) return BuildTracker.PHASE_COMPILE_TEST;
    if (trimmed.startsWith("[INFO]") && trimmed.contains("构建")) return BuildTracker.PHASE_COMPILE_MAIN;
    if (trimmed.contains("Building module") || trimmed.contains("Compiling")) return BuildTracker.PHASE_COMPILE_MAIN;
    return null;
  }
}
