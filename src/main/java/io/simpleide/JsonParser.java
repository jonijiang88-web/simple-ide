package io.simpleide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器。
 *
 * <p>存在的意义：测试结果与构建状态的判定必须基于真实 JSON 解析，
 * 不能再用 {@code contains("\"success\":true")} 或「最后一行看起来像 JSON」这种字符串猜测，
 * 否则字段顺序、空白、嵌套文案变化都会导致误判。
 *
 * <p>支持标准 JSON 值：对象、数组、字符串、数字、布尔与 null。
 */
final class JsonParser {

  /** 禁止实例化。 */
  private JsonParser() {
  }

  /**
   * 解析 JSON 文本。
   *
   * @param text JSON 文本
   * @return 解析后的对象（Map/List/String/Long/Double/Boolean/null）
   * @throws IllegalArgumentException 文本不是合法 JSON
   */
  static Object parse(String text) {
    if (text == null) throw new IllegalArgumentException("JSON 文本为 null");
    Cursor cursor = new Cursor(text);
    cursor.skipWhitespace();
    Object value = cursor.parseValue();
    cursor.skipWhitespace();
    if (!cursor.atEnd()) {
      throw new IllegalArgumentException("JSON 尾部存在多余内容，位置 " + cursor.index);
    }
    return value;
  }

  /**
   * 解析 JSON 对象为 Map。
   *
   * @param text JSON 文本
   * @return 键值映射
   * @throws IllegalArgumentException 顶层不是对象
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> parseObject(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) throw new IllegalArgumentException("JSON 顶层不是对象");
    return (Map<String, Object>) value;
  }

  /** 宽松解析：失败时返回 null，用于「可能是 JSON 也可能是普通日志」的场景。 */
  static Map<String, Object> tryParseObject(String text) {
    if (text == null) return null;
    String trimmed = text.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null;
    try {
      return parseObject(trimmed);
    }
    catch (RuntimeException e) {
      return null;
    }
  }

  /** 解析光标。 */
  private static final class Cursor {
    private final String text;
    private int index;

    Cursor(String text) {
      this.text = text;
    }

    boolean atEnd() {
      return index >= text.length();
    }

    void skipWhitespace() {
      while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
    }

    Object parseValue() {
      if (atEnd()) throw new IllegalArgumentException("JSON 意外结束");
      char c = text.charAt(index);
      if (c == '{') return parseMap();
      if (c == '[') return parseList();
      if (c == '"') return parseString();
      if (c == 't' || c == 'f') return parseBoolean();
      if (c == 'n') return parseNull();
      return parseNumber();
    }

    Map<String, Object> parseMap() {
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      expect('{');
      skipWhitespace();
      if (peek() == '}') { index++; return map; }
      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        map.put(key, parseValue());
        skipWhitespace();
        char c = next();
        if (c == '}') return map;
        if (c != ',') throw new IllegalArgumentException("JSON 对象缺少逗号，位置 " + (index - 1));
      }
    }

    List<Object> parseList() {
      List<Object> list = new ArrayList<Object>();
      expect('[');
      skipWhitespace();
      if (peek() == ']') { index++; return list; }
      while (true) {
        skipWhitespace();
        list.add(parseValue());
        skipWhitespace();
        char c = next();
        if (c == ']') return list;
        if (c != ',') throw new IllegalArgumentException("JSON 数组缺少逗号，位置 " + (index - 1));
      }
    }

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') return sb.toString();
        if (c == '\\') {
          char esc = next();
          switch (esc) {
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'u':
              if (index + 4 > text.length()) throw new IllegalArgumentException("JSON \\u 转义不完整");
              sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
              index += 4;
              break;
            default: throw new IllegalArgumentException("JSON 非法转义 \\" + esc);
          }
        }
        else {
          sb.append(c);
        }
      }
    }

    Object parseNumber() {
      int start = index;
      while (index < text.length()) {
        char c = text.charAt(index);
        if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') index++;
        else break;
      }
      if (start == index) throw new IllegalArgumentException("JSON 非法值，位置 " + index);
      String number = text.substring(start, index);
      if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
        try {
          return Long.valueOf(number);
        }
        catch (NumberFormatException ignored) {
          return Double.valueOf(number);
        }
      }
      return Double.valueOf(number);
    }

    Object parseBoolean() {
      if (text.startsWith("true", index)) { index += 4; return Boolean.TRUE; }
      if (text.startsWith("false", index)) { index += 5; return Boolean.FALSE; }
      throw new IllegalArgumentException("JSON 非法字面量，位置 " + index);
    }

    Object parseNull() {
      if (text.startsWith("null", index)) { index += 4; return null; }
      throw new IllegalArgumentException("JSON 非法字面量，位置 " + index);
    }

    char peek() {
      return index < text.length() ? text.charAt(index) : '\0';
    }

    char next() {
      if (index >= text.length()) throw new IllegalArgumentException("JSON 意外结束");
      return text.charAt(index++);
    }

    void expect(char expected) {
      char c = next();
      if (c != expected) throw new IllegalArgumentException("JSON 期望 '" + expected + "' 但得到 '" + c + "'，位置 " + (index - 1));
    }
  }
}
