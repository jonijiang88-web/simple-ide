package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证未显式指定 --port 时，simple-ide 能按进程监听套接字自动判定应用主端口，
 * 且不会把 DevTools LiveReload、JDWP 调试端口误判为业务端口。
 */
public final class PortDetectorTest {

  /** 构造候选端口集合。 */
  private static Set<Integer> portsOf(Integer... values) {
    return new LinkedHashSet<Integer>(Arrays.asList(values));
  }

  /** 剔除 DevTools LiveReload 与 JDWP 调试端口，只保留业务端口。 */
  @Test
  public void selectMainPortFiltersNoiseAndDebugPorts() {
    // 验证：堆叠业务端口、LiveReload（35729）与调试端口时，只返回业务端口
    assertEquals(9090, PortDetector.selectMainPort(portsOf(35729, 9090, 5005), null, 5005));
  }

  /** 多候选端口时优先采信启动日志里框架自报的主端口。 */
  @Test
  public void selectMainPortPrefersLogPort() {
    // 验证：日志自报 13199 时，即使存在更小的候选端口也返回日志端口
    assertEquals(13199, PortDetector.selectMainPort(portsOf(8080, 13199), 13199, -1));
  }

  /** 无日志线索时退回最小候选端口，保证结果稳定可复现。 */
  @Test
  public void selectMainPortFallsBackToSmallestPort() {
    // 验证：无日志端口时按最小端口裁决
    assertEquals(8080, PortDetector.selectMainPort(portsOf(18080, 8080), null, -1));
  }

  /** 日志端口不在实际监听集合内时不得采信（避免采信历史启动残留）。 */
  @Test
  public void selectMainPortIgnoresLogPortNotListening() {
    // 验证：日志端口与候选不一致时退回监听中的最小端口
    assertEquals(9090, PortDetector.selectMainPort(portsOf(9090), 8080, -1));
  }

  /** 全部候选都被过滤时返回 -1，交由调用方按原逻辑处理。 */
  @Test
  public void selectMainPortReturnsMinusOneWhenNoBusinessPort() {
    // 验证：只有 LiveReload 与调试端口时判定为「未探测到端口」
    assertEquals(-1, PortDetector.selectMainPort(portsOf(35729, 5005), null, 5005));
    // 验证：空候选集同样返回 -1
    assertEquals(-1, PortDetector.selectMainPort(portsOf(), null, -1));
  }

  /** 日志端口解析必须与实际监听端口交叉校验。 */
  @Test
  public void portFromLogMatchesListeningPorts(@TempDir Path tempDir) throws Exception {
    Path log = tempDir.resolve("app.log");
    Files.write(log, Arrays.asList(
      "Tomcat started on port(s): 9090 (http)",
      "Tomcat started on port(s): 35729"),
      StandardCharsets.UTF_8);

    // 验证：只采信确实在监听集合内的日志端口
    assertEquals(Integer.valueOf(9090), PortDetector.portFromLog(log, portsOf(9090)));
    // 验证：日志端口不在监听集合内时返回 null
    assertEquals(null, PortDetector.portFromLog(log, portsOf(8080)));
  }

  /** 同一日志出现多轮启动时取最近一次自报端口。 */
  @Test
  public void portFromLogPrefersLatestRestart(@TempDir Path tempDir) throws Exception {
    Path log = tempDir.resolve("app.log");
    Files.write(log, Arrays.asList(
      "Netty started on port 8080",
      "Netty started on port 13199"),
      StandardCharsets.UTF_8);

    // 验证：取日志中最后一次出现且正在监听的端口
    assertEquals(Integer.valueOf(13199), PortDetector.portFromLog(log, portsOf(8080, 13199)));
  }

  /** 兼容「访问地址」形态的日志（自定义启动日志）与 npm/vite 输出。 */
  @Test
  public void portFromLogMatchesAccessUrl(@TempDir Path tempDir) throws Exception {
    Path log = tempDir.resolve("app.log");
    Files.write(log, Arrays.asList(
      "Local: \t\thttp://127.0.0.1:9090",
      "    Local:   http://localhost:5173/"),
      StandardCharsets.UTF_8);

    // 验证：从 http://host:port 形态中解析端口
    assertEquals(Integer.valueOf(9090), PortDetector.portFromLog(log, portsOf(9090)));
  }

  /** 进程树 PID 收集必须包含根进程自身。 */
  @Test
  public void processTreePidsContainsRootPid() {
    long currentPid = ProcessTree.currentPid();

    List<Long> pids = PortDetector.processTreePids(currentPid);

    // 验证：根进程自身在结果中
    assertTrue(pids.contains(currentPid));
  }

  /** Linux 下能从 /proc 反查到本测试进程自己绑定的监听端口。 */
  @Test
  public void listeningPortsFindsBoundSocket() throws Exception {
    String os = System.getProperty("os.name", "").toLowerCase();
    org.junit.jupiter.api.Assumptions.assumeTrue(!os.contains("win"),
      "Windows 平台的 netstat 探测口径不同，此用例只覆盖 Linux/macOS");

    try (ServerSocket socket = new ServerSocket(0)) {
      Set<Integer> ports = PortDetector.listeningPorts(ProcessTree.currentPid());

      // 验证：本进程监听的端口能被探测到
      assertNotNull(ports);
      assertTrue(ports.contains(socket.getLocalPort()),
        "应探测到本进程监听端口 " + socket.getLocalPort() + "，实际: " + ports);
    }
  }
}
