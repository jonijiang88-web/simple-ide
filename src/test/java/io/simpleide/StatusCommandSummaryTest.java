package io.simpleide;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 status/list 的命令输出不再内嵌超长 classpath：
 * 完整命令保留在状态文件，JSON 只输出「折叠摘要」，避免终端与 Agent 上下文被刷屏。
 */
public final class StatusCommandSummaryTest {

  /** 构造一个带超长 classpath 的状态对象。 */
  private static SimpleIde.RunState stateWithLongClasspath() {
    StringBuilder classpath = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      if (i > 0) classpath.append(':');
      classpath.append("/home/user/.m2/repository/dep/dep-").append(i).append(".jar");
    }
    List<String> command = new ArrayList<String>(Arrays.asList(
      "setsid", "java", "-Xms2g", "-cp", classpath.toString(), "com.demo.Main", "--server.port=19099"));
    return new SimpleIde.RunState(1234L, Paths.get("/tmp/app.log"), 19099, -1, "/tmp/project", command);
  }

  /** JSON 命令字段必须是折叠摘要，不得包含完整 classpath。 */
  @Test
  public void summaryFoldsClasspathInsteadOfDumpingIt() {
    SimpleIde.RunState state = stateWithLongClasspath();

    String summary = state.commandSummary();
    Map<String, Object> map = state.toMap("RUNNING");

    // 验证：摘要包含折叠标记与条目数
    assertTrue(summary.contains("<classpath: 500 项"));
    // 验证：摘要不含 classpath 里的任何依赖路径
    assertFalse(summary.contains("/home/user/.m2/repository"));
    // 验证：摘要长度受控（不随 classpath 增长而膨胀）
    assertTrue(summary.length() < 200, "摘要长度应远小于完整命令: " + summary.length());
    // 验证：JSON 的 command 字段同样被折叠
    assertFalse(String.valueOf(map.get("command")).contains("/home/user/.m2/repository"));
    // 验证：保留关键启动信息（java 可执行、主类、端口参数）
    assertTrue(summary.contains("java"));
    assertTrue(summary.contains("com.demo.Main"));
    assertTrue(summary.contains("--server.port=19099"));
    // 验证：给出完整命令的获取位置提示
    assertTrue(String.valueOf(map.get("commandNote")).contains(".simple-ide/runs"));
  }

  /** classpath 条目统计需兼容 ':' 与 ';' 分隔符及空串。 */
  @Test
  public void countsClasspathEntries() {
    // 验证：Linux 风格分隔符
    assertEquals(3, SimpleIde.RunState.classpathEntryCount("a:b:c"));
    // 验证：Windows 风格分隔符
    assertEquals(2, SimpleIde.RunState.classpathEntryCount("a;b"));
    // 验证：空串返回 0
    assertEquals(0, SimpleIde.RunState.classpathEntryCount(""));
    assertEquals(0, SimpleIde.RunState.classpathEntryCount(null));
  }
}
