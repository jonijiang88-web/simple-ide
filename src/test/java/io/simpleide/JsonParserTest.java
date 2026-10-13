package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证测试结果与构建状态所依赖的真实 JSON 解析能力。 */
public final class JsonParserTest {

  /** 验证对象、数组与嵌套结构解析正确。 */
  @Test
  void parses_nested_structures() {
    Object value = JsonParser.parse("{\"success\":true,\"total\":3,\"nested\":{\"a\":[1,2,\"x\"]},\"none\":null,\"pi\":1.5}");
    assertTrue(value instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) value;

    // 验证：布尔值被解析为 Boolean
    assertEquals(Boolean.TRUE, map.get("success"));
    // 验证：整数被解析为 Long
    assertEquals(3L, map.get("total"));
    // 验证：浮点数被解析为 Double
    assertEquals(1.5, map.get("pi"));
    // 验证：null 被保留
    assertNull(map.get("none"));
    // 验证：嵌套数组被解析为 List
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) map.get("nested");
    List<?> a = (List<?>) nested.get("a");
    assertEquals(3, a.size());
    assertEquals(1L, a.get(0));
    assertEquals("x", a.get(2));
  }

  /** 验证字符串转义被正确还原。 */
  @Test
  void parses_escaped_strings() {
    Map<String, Object> map = JsonParser.parseObject("{\"msg\":\"a\\\"b\\nc\\\\d\\u4e2d\"}");
    // 验证：引号、换行、反斜杠与 Unicode 转义被还原
    assertEquals("a\"b\nc\\d中", map.get("msg"));
  }

  /** 验证顶层非对象时 parseObject 报错。 */
  @Test
  void rejects_non_object_toplevel() {
    assertThrows(IllegalArgumentException.class, () -> JsonParser.parseObject("[1,2]"));
  }

  /** 验证非法 JSON 被拒绝，而不是给出错误结果。 */
  @Test
  void rejects_malformed_json() {
    // 验证：缺少闭合括号的 JSON 解析失败
    assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":"));
    // 验证：尾部多余内容被拒绝
    assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{} extra"));
  }

  /** 验证 tryParseObject 只接受合法对象，供协议判定使用。 */
  @Test
  void try_parse_is_strict() {
    // 验证：合法对象可解析
    assertEquals(Boolean.TRUE, JsonParser.tryParseObject("{\"success\":true}").get("success"));
    // 验证：普通日志文本不被当作 JSON
    assertNull(JsonParser.tryParseObject("BUILD SUCCESSFUL"));
    // 验证：伪造的 JSON 片段不被接受
    assertNull(JsonParser.tryParseObject("{\"success\":true"));
  }

  /** 验证 success 判定基于真实解析而非字符串包含。 */
  @Test
  void success_detection_is_parse_based() {
    // 验证：字段顺序变化时仍能正确判定
    assertEquals(Boolean.TRUE, JsonParser.tryParseObject("{\"total\":1, \"success\":true}").get("success"));
    // 验证：success=false 不会被 contains 误判为成功
    assertEquals(Boolean.FALSE, JsonParser.tryParseObject("{\"success\":false}").get("success"));
    // 验证：消息正文里出现 success:true 也不会误判
    Map<String, Object> map = JsonParser.tryParseObject("{\"success\":false,\"message\":\"success:true\"}");
    assertEquals(Boolean.FALSE, map.get("success"));
    // 验证：数组元素中的字符串不会被当作顶层成功标记
    assertNull(JsonParser.tryParseObject("[{\"success\":true}]"));
  }

  /** 验证 lastJsonObjectLine 只接受可解析的 JSON 对象行。 */
  @Test
  void selects_last_parsable_json_line() {
    String stdout = "some log\n{\"success\":false,\"error\":\"x\"}\nnot json {\"a\"\n{\"success\":true,\"total\":2}\n";
    // 验证：取最后一个可解析的 JSON 对象
    assertEquals("{\"success\":true,\"total\":2}", SimpleIde.lastJsonObjectLine(stdout));
    // 验证：没有 JSON 行时返回 null
    assertNull(SimpleIde.lastJsonObjectLine("plain text only\n"));
  }

  /** 验证解析器可处理多行格式化 JSON。 */
  @Test
  void parses_pretty_printed_json() {
    String text = "{\n  \"success\": true,\n  \"tests\": [\n    {\"name\":\"a\",\"status\":\"PASSED\"}\n  ]\n}";
    Map<String, Object> map = JsonParser.parseObject(text);
    // 验证：多行 JSON 也能正确解析
    assertEquals(Boolean.TRUE, map.get("success"));
    // 验证：嵌套数组解析正确
    assertEquals(1, ((List<?>) map.get("tests")).size());
  }

  /** 验证序列化与解析可往返。 */
  @Test
  void roundtrips_with_serializer() {
    Map<String, Object> original = SimpleIde.mapOf("success", true, "count", 2,
      "items", Arrays.asList("a", "b"), "nested", SimpleIde.mapOf("k", "v"));
    String jsonText = SimpleIde.json(original);
    Map<String, Object> parsed = JsonParser.parseObject(jsonText);
    // 验证：序列化结果可被解析回等价结构
    assertEquals(Boolean.TRUE, parsed.get("success"));
    assertEquals(2L, parsed.get("count"));
    assertEquals("v", ((Map<?, ?>) parsed.get("nested")).get("k"));
  }
}
