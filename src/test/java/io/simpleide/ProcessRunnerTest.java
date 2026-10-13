package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 ProcessRunner 的有界超时、并发输出消费与子进程树回收能力。 */
public final class ProcessRunnerTest {

  /** 验证超时从进程启动时刻生效，并终止挂起且无输出的子进程。 */
  @Test
  void times_out_hanging_process_without_output(@TempDir Path dir) {
    long start = System.currentTimeMillis();
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "sleep 60"), dir, 1, true, 1024 * 1024, null, null);
    long elapsed = System.currentTimeMillis() - start;

    // 验证：子进程成功启动
    assertTrue(result.started);
    // 验证：未在超时前正常退出
    assertFalse(result.finished);
    // 验证：被判定为超时
    assertTrue(result.timedOut);
    // 验证：超时确实及时生效（远小于 sleep 的 60 秒）
    assertTrue(elapsed < 30000, "超时应及时生效，实际耗时 " + elapsed + "ms");
    // 验证：子进程树已确认退出
    assertTrue(result.killComplete, "超时后应确认子进程树已退出");
    // 验证：能拿到子进程 PID 用于诊断
    assertTrue(result.pid > 0);
  }

  /** 验证超时/取消后不遗留子进程。 */
  @Test
  void kills_child_process_tree_on_timeout(@TempDir Path dir) throws Exception {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "sleep 60 & echo $!; wait"), dir, 1, true, 1024 * 1024, null, null);

    // 验证：确实触发了超时终止
    assertTrue(result.timedOut);
    String firstLine = result.stdout.trim().split("\n")[0].trim();
    long childPid = Long.parseLong(firstLine);
    Path procEntry = java.nio.file.Paths.get("/proc", Long.toString(childPid));
    // 验证：等待一小段时间后子进程已消失（仅 Linux 有 /proc）
    for (int i = 0; i < 50 && Files.exists(procEntry); i++) {
      Thread.sleep(100);
    }
    if (Files.exists(java.nio.file.Paths.get("/proc"))) {
      assertFalse(Files.exists(procEntry), "超时后不应遗留子进程 PID=" + childPid);
    }
  }

  /** 验证输出量超过操作系统管道容量时不会死锁，且有界内存保留头尾。 */
  @Test
  void consumes_output_larger_than_pipe_capacity(@TempDir Path dir) {
    String script = "i=0; while [ $i -lt 6000 ]; do echo line-$i-0123456789012345678901234567890123456789; i=$((i+1)); done";
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", script), dir, 30, true, 256 * 1024, null, null);

    // 验证：大量输出不会导致子进程阻塞，正常退出
    assertTrue(result.finished);
    // 验证：退出码为 0
    assertEquals(0, result.exitCode);
    // 验证：保留了末尾输出（最后一行）
    assertTrue(result.stdout.contains("line-5999"), "应保留输出尾部");
    // 验证：输出超出上限时被标记为截断
    assertTrue(result.outputTruncated);
    // 验证：内存有界，不会保留全部 6000 行原始输出
    assertTrue(result.stdout.length() < 400 * 1024,
      "输出缓冲应有上限，实际长度 " + result.stdout.length());
  }

  /** 验证调用方取消令牌能终止长任务。 */
  @Test
  void cancel_token_stops_running_process(@TempDir Path dir) throws Exception {
    final ProcessRunner.CancelToken token = new ProcessRunner.CancelToken();
    Thread canceller = new Thread(() -> {
      try {
        Thread.sleep(300);
      }
      catch (InterruptedException ignored) {
      }
      token.cancel();
    });
    canceller.start();

    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "sleep 60"), dir, 120, true, 1024 * 1024, null, token);

    // 验证：被标记为取消
    assertTrue(result.cancelled);
    // 验证：未正常完成
    assertFalse(result.finished);
    // 验证：进程树已回收
    assertTrue(result.killComplete);
  }

  /** 验证正常命令的退出码与输出被正确采集。 */
  @Test
  void captures_exit_code_and_streams(@TempDir Path dir) {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "echo out; echo err 1>&2; exit 3"), dir, 10, false, 1024 * 1024, null, null);

    // 验证：正常退出
    assertTrue(result.finished);
    // 验证：退出码被正确采集
    assertEquals(3, result.exitCode);
    // 验证：stdout 与 stderr 分别采集
    assertTrue(result.stdout.contains("out"));
    assertTrue(result.stderr.contains("err"));
  }

  /** 验证实时输出监听器能收到输出片段。 */
  @Test
  void notifies_stream_listener(@TempDir Path dir) {
    final AtomicReference<String> received = new AtomicReference<String>("");
    ProcessRunner.StreamListener listener = new ProcessRunner.StreamListener() {
      @Override
      public void onChunk(String stream, byte[] data, int length) {
        received.set(stream);
      }
    };
    ProcessRunner.run(Arrays.asList("sh", "-c", "echo progress"), dir, 10, true, 1024 * 1024, listener, null);
    // 验证：监听器收到输出并标注为 stdout
    assertEquals("stdout", received.get());
  }

  /** 验证合并 stderr 模式。 */
  @Test
  void merges_stderr_when_requested(@TempDir Path dir) {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "echo merged 1>&2"), dir, 10, true, 1024 * 1024, null, null);
    // 验证：stderr 被合并进 stdout 缓冲
    assertTrue(result.stdout.contains("merged"));
  }

  /** 验证进程启动失败不会挂起，且错误信息非空。 */
  @Test
  void reports_start_failure(@TempDir Path dir) {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("definitely-not-a-real-binary-xyz"), dir, 5, true, 1024 * 1024, null, null);
    // 验证：未启动
    assertFalse(result.started);
    // 验证：返回了错误说明
    assertNotNull(result.error);
    // 验证：错误信息包含可定位线索
    assertTrue(result.error.contains("子进程启动异常"));
  }

  /** 验证结果中的 UTF-8 输出解码正确。 */
  @Test
  void decodes_utf8_output(@TempDir Path dir) throws Exception {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "printf '中文进度\\n'"), dir, 10, true, 1024 * 1024, null, null);
    // 验证：中文输出被正确解码
    assertTrue(new String(result.stdout.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).contains("中文进度"));
  }

  /** 验证 waitFor 轮询不会在长任务上过早返回。 */
  @Test
  void waits_until_process_completes(@TempDir Path dir) throws Exception {
    long start = System.currentTimeMillis();
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "sleep 1; echo done"), dir, 30, true, 1024 * 1024, null, null);
    long elapsed = System.currentTimeMillis() - start;
    // 验证：进程正常完成
    assertTrue(result.finished);
    // 验证：确实等待了约 1 秒
    assertTrue(elapsed >= 900, "应等待子进程完成，实际 " + elapsed + "ms");
    // 验证：输出被采集
    assertTrue(result.stdout.contains("done"));
    // 验证：未超时
    assertFalse(result.timedOut);
  }

  /** 【核心回归】后代忽略 TERM 且根进程先退出时，必须强杀后代并确认整棵树已退出。 */
  @Test
  void kills_term_ignoring_descendant_after_root_exits(@TempDir Path dir) throws Exception {
    String script = "bash -c 'trap \"\" TERM; exec sleep 120' & echo CHILD=$!; wait";
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("bash", "-c", script), dir, 1, true, 1024 * 1024, null, null);

    // 验证：确实触发超时终止
    assertTrue(result.timedOut);
    long childPid = parseChildPid(result.stdout);
    // 验证：整棵进程树被确认回收（修复前根进程先退出即误报 true/或不强杀后代）
    assertTrue(result.killComplete, "整棵树应被确认回收，surviving=" + result.survivingPids);
    // 验证：不存在存活后代
    assertTrue(result.survivingPids.isEmpty(), "不应有存活后代: " + result.survivingPids);
    // 验证：子进程确已消失
    assertProcessGone(childPid);
  }

  /** 【核心回归】在后代忽略 TERM 场景下，强制使用 Java 8 的 /proc 枚举策略同样成立。 */
  @Test
  void kills_descendant_using_proc_strategy(@TempDir Path dir) throws Exception {
    String previous = System.getProperty(ProcessTree.STRATEGY_PROPERTY);
    System.setProperty(ProcessTree.STRATEGY_PROPERTY, "proc");
    try {
      String script = "bash -c 'trap \"\" TERM; exec sleep 120' & echo CHILD=$!; wait";
      ProcessRunner.Result result = ProcessRunner.run(
        Arrays.asList("bash", "-c", script), dir, 1, true, 1024 * 1024, null, null);
      long childPid = parseChildPid(result.stdout);
      // 验证：/proc 策略下同样确认整棵树回收
      assertTrue(result.killComplete, "proc 策略下应确认回收，surviving=" + result.survivingPids);
      // 验证：/proc 策略确实被使用（存在身份令牌）
      assertTrue(result.treeEnumerated);
      assertProcessGone(childPid);
    }
    finally {
      restoreStrategy(previous);
    }
  }

  /** 【核心回归】孙进程（多层）也必须被回收。 */
  @Test
  void kills_grandchildren(@TempDir Path dir) throws Exception {
    String script = "bash -c \"bash -c 'trap \\\"\\\" TERM; exec sleep 120' & echo GRAND=$!; wait\" & echo CHILD=$!; wait";
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("bash", "-c", script), dir, 1, true, 1024 * 1024, null, null);

    long grandPid = parseChildPid(result.stdout);
    // 验证：多层后代都被确认回收
    assertTrue(result.killComplete, "孙进程也应被回收，surviving=" + result.survivingPids);
    assertProcessGone(grandPid);
  }

  /** 【核心回归】无法枚举进程表时必须 killComplete=false，不得夸大为完整回收。 */
  @Test
  void reports_incomplete_when_process_table_unavailable(@TempDir Path dir) throws Exception {
    String previous = System.getProperty(ProcessTree.STRATEGY_PROPERTY);
    System.setProperty(ProcessTree.STRATEGY_PROPERTY, "none");
    try {
      ProcessRunner.Result result = ProcessRunner.run(
        Arrays.asList("sh", "-c", "sleep 60"), dir, 1, true, 1024 * 1024, null, null);
      // 验证：无法枚举时不得声称完整回收
      assertFalse(result.killComplete);
      // 验证：明确标记枚举能力不足
      assertFalse(result.treeEnumerated);
    }
    finally {
      restoreStrategy(previous);
    }
  }

  /** 解析子进程 PID（从 CHILD=/GRAND= 行）。 */
  private static long parseChildPid(String stdout) {
    for (String line : stdout.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("CHILD=") || trimmed.startsWith("GRAND=")) {
        return Long.parseLong(trimmed.substring(trimmed.indexOf('=') + 1).trim());
      }
    }
    throw new AssertionError("未从输出中解析到子进程 PID: " + stdout);
  }

  /** 断言 PID 已消失（非 Linux 平台跳过 /proc 校验）。 */
  private static void assertProcessGone(long pid) throws Exception {
    Path procEntry = java.nio.file.Paths.get("/proc", Long.toString(pid));
    if (!Files.exists(java.nio.file.Paths.get("/proc"))) return;
    for (int i = 0; i < 30 && Files.exists(procEntry); i++) {
      Thread.sleep(100);
    }
    assertFalse(Files.exists(procEntry), "不应遗留子进程 PID=" + pid);
  }

  /** 恢复正常枚举策略。 */
  private static void restoreStrategy(String previous) {
    if (previous == null) System.clearProperty(ProcessTree.STRATEGY_PROPERTY);
    else System.setProperty(ProcessTree.STRATEGY_PROPERTY, previous);
  }

  /** 验证 TimeUnit 相关常量可用（防止误用无限等待）。 */
  @Test
  void timeout_unit_is_seconds(@TempDir Path dir) throws Exception {
    ProcessRunner.Result result = ProcessRunner.run(
      Arrays.asList("sh", "-c", "sleep 3"), dir, 5, true, 1024 * 1024, null, null);
    // 验证：5 秒超时足以覆盖 3 秒任务
    assertTrue(result.finished);
    // 验证：未超时且有一定耗时
    assertFalse(result.timedOut);
    assertTrue(TimeUnit.MILLISECONDS.toSeconds(result.durationMs) >= 2);
  }
}
