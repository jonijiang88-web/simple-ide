package io.simpleide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级 MCP SSE 客户端，通过 HTTP SSE 与运行中的 IDEA MCP Server 通信。
 * <p>
 * 实现 MCP 协议最小子集：initialize → tools/call。使用持久 SSE 连接完成完整会话。
 */
final class McpClient {

  private static final AtomicLong REQUEST_ID = new AtomicLong(1);
  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int READ_TIMEOUT_MS = 600_000;

  private final String baseUrl;

  McpClient(int port) {
    this.baseUrl = "http://127.0.0.1:" + port;
  }

  /**
   * 列出 IDEA MCP Server 上所有可用工具。
   */
  String listTools() throws IOException {
    URL sseUrl = URI.create(baseUrl + "/sse").toURL();
    HttpURLConnection conn = (HttpURLConnection) sseUrl.openConnection();
    conn.setRequestMethod("GET");
    conn.setRequestProperty("Accept", "text/event-stream");
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);

    int code = conn.getResponseCode();
    if (code != 200) throw new IOException("SSE 连接失败，HTTP " + code);

    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
    try {
      String sessionId = null;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
          String data = line.substring(5).trim();
          if (data.startsWith("/message?sessionId=")) {
            sessionId = data.substring("/message?sessionId=".length());
            break;
          }
        }
      }
      if (sessionId == null) throw new IOException("SSE 流中未收到 session ID");

      long initId = REQUEST_ID.getAndIncrement();
      String initRequest = jsonRpc(initId, "initialize", mapOf(
        "protocolVersion", (Object) "2024-11-05",
        "capabilities", mapOf(),
        "clientInfo", mapOf("name", (Object) "simple-ide", "version", "1.0.0")
      ));
      postMessage(sessionId, initRequest);
      waitForResponse(reader, initId);

      String initialized = jsonRpcNotification("notifications/initialized", mapOf());
      postMessage(sessionId, initialized);

      long listId = REQUEST_ID.getAndIncrement();
      String listRequest = jsonRpc(listId, "tools/list", mapOf());
      postMessage(sessionId, listRequest);
      return waitForResponse(reader, listId);
    }
    finally {
      reader.close();
    }
  }

  /**
   * 调用 IDEA MCP Server 上的工具并返回结果 JSON。
   */
  String callToolViaSse(String toolName, Map<String, Object> arguments) throws IOException {
    URL sseUrl = URI.create(baseUrl + "/sse").toURL();
    HttpURLConnection conn = (HttpURLConnection) sseUrl.openConnection();
    conn.setRequestMethod("GET");
    conn.setRequestProperty("Accept", "text/event-stream");
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);

    int code = conn.getResponseCode();
    if (code != 200) throw new IOException("SSE 连接失败，HTTP " + code);

    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
    try {
      // Step 1: 读取 endpoint 事件获取 session ID
      String sessionId = null;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) {
          String data = line.substring(5).trim();
          if (data.startsWith("/message?sessionId=")) {
            sessionId = data.substring("/message?sessionId=".length());
            break;
          }
        }
      }
      if (sessionId == null) throw new IOException("SSE 流中未收到 session ID");

      // Step 2: 发送 initialize 请求
      long initId = REQUEST_ID.getAndIncrement();
      String initRequest = jsonRpc(initId, "initialize", mapOf(
        "protocolVersion", (Object) "2024-11-05",
        "capabilities", mapOf(),
        "clientInfo", mapOf("name", (Object) "simple-ide", "version", "1.0.0")
      ));
      postMessage(sessionId, initRequest);

      // Step 3: 读取 initialize 响应
      waitForResponse(reader, initId);

      // Step 3.5: 发送 initialized 通知
      String initialized = jsonRpcNotification("notifications/initialized", mapOf());
      postMessage(sessionId, initialized);

      // Step 4: 发送 tools/call 请求
      long callId = REQUEST_ID.getAndIncrement();
      String toolCall = jsonRpc(callId, "tools/call", mapOf(
        "name", (Object) toolName,
        "arguments", (Object) arguments
      ));
      postMessage(sessionId, toolCall);

      // Step 5: 读取 tool call 响应
      return waitForResponse(reader, callId);
    }
    finally {
      reader.close();
    }
  }

  /** 向 MCP server 发送 JSON-RPC 消息。 */
  private void postMessage(String sessionId, String jsonRpc) throws IOException {
    URL messageUrl = URI.create(baseUrl + "/message?sessionId=" + sessionId).toURL();
    HttpURLConnection conn = (HttpURLConnection) messageUrl.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setDoOutput(true);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);

    byte[] body = jsonRpc.getBytes(StandardCharsets.UTF_8);
    conn.setFixedLengthStreamingMode(body.length);
    OutputStream out = conn.getOutputStream();
    try { out.write(body); } finally { out.close(); }

    int respCode = conn.getResponseCode();
    if (respCode != 200 && respCode != 202) {
      throw new IOException("MCP message POST 返回 " + respCode + ": " + readAll(conn));
    }
  }

  /** 从 SSE 流中读取匹配目标 request ID 的 JSON-RPC 响应。 */
  private String waitForResponse(BufferedReader reader, long targetId) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.startsWith("data:")) continue;
      String data = line.substring(5).trim();
      if (data.isEmpty()) continue;
      if (data.contains("\"id\":" + targetId)) return extractResult(data);
    }
    throw new IOException("SSE 流关闭，未收到 id=" + targetId + " 的响应");
  }

  /** 从 JSON-RPC 响应中提取 result 部分。 */
  private String extractResult(String jsonRpc) throws IOException {
    if (jsonRpc.contains("\"error\"")) {
      int errorStart = jsonRpc.indexOf("\"error\"");
      int colonPos = jsonRpc.indexOf(':', errorStart);
      String errorJson = extractJsonObject(jsonRpc, colonPos + 1);
      throw new IOException("MCP 调用错误: " + errorJson);
    }
    int resultStart = jsonRpc.indexOf("\"result\"");
    if (resultStart < 0) throw new IOException("MCP 响应中无 result 字段: " + jsonRpc);
    int colonPos = jsonRpc.indexOf(':', resultStart);
    return extractJsonObject(jsonRpc, colonPos + 1);
  }

  /** 从指定位置提取 JSON 对象或数组。 */
  private String extractJsonObject(String text, int start) {
    int pos = start;
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    if (pos >= text.length()) return "";
    char open = text.charAt(pos);
    char close = open == '{' ? '}' : open == '[' ? ']' : 0;
    if (close == 0) {
      int end = pos;
      while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != '}') end++;
      return text.substring(pos, end).trim();
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = pos; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) { escaped = false; continue; }
      if (c == '\\') { escaped = true; continue; }
      if (c == '"') { inString = !inString; continue; }
      if (inString) continue;
      if (c == open) depth++;
      else if (c == close) { depth--; if (depth == 0) return text.substring(pos, i + 1); }
    }
    return text.substring(pos);
  }

  /** 构造 JSON-RPC 2.0 请求。 */
  private static String jsonRpc(long id, String method, Map<String, Object> params) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + escapeJson(method) + "\",\"params\":" + toJson(params) + "}";
  }

  /** 构造 JSON-RPC 2.0 通知。 */
  private static String jsonRpcNotification(String method, Map<String, Object> params) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"" + escapeJson(method) + "\",\"params\":" + toJson(params) + "}";
  }

  /** 将 Map 序列化为 JSON。 */
  static String toJson(Object value) {
    if (value == null) return "null";
    if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) value;
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) sb.append(',');
        sb.append('"').append(escapeJson(entry.getKey().toString())).append("\":").append(toJson(entry.getValue()));
        first = false;
      }
      return sb.append('}').toString();
    }
    if (value instanceof List) {
      List<?> list = (List<?>) value;
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(',');
        sb.append(toJson(item));
        first = false;
      }
      return sb.append(']').toString();
    }
    return "\"" + escapeJson(value.toString()) + "\"";
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private static String readAll(HttpURLConnection conn) {
    try {
      java.io.InputStream stream = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (stream == null) return "";
      byte[] buf = new byte[4096];
      StringBuilder sb = new StringBuilder();
      int n;
      while ((n = stream.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
      return sb.toString();
    }
    catch (IOException e) { return ""; }
  }

  static boolean isAvailable(int port) {
    return SimpleIde.isPortOpen(port);
  }

  static boolean ensureIdeaRunning(Path projectPath, int port, int timeoutSec) throws IOException, InterruptedException {
    if (isAvailable(port)) return true;
    new ProcessBuilder("idea", projectPath.toString()).start();
    for (int i = 0; i < timeoutSec; i++) {
      Thread.sleep(1000);
      if (isAvailable(port)) return true;
    }
    return false;
  }

  /** 构造 Map 的便捷方法（JDK 8 兼容）。 */
  private static Map<String, Object> mapOf(Object... keyValuePairs) {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return map;
  }
}
