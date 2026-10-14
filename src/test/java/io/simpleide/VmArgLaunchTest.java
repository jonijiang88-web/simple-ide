package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证自定义 JVM 参数（--vm-arg）的透传契约：必须原样保留且插在 -cp 之前，
 * 否则 profiler agent（-agentpath）等启动期参数会被 JVM 拒绝或丢失。
 */
public final class VmArgLaunchTest {

  /** 自定义 JVM 参数必须原样保留并位于 classpath 之前。 */
  @Test
  public void passesCustomVmArgsBeforeClasspath() {
    String agent = "-agentpath:/opt/async-profiler/lib/libasyncProfiler.so=start,event=wall,interval=1ms,timeout=120,file=/tmp/boot.html,threads,simple";
    List<String> vmParams = new ArrayList<String>();
    vmParams.add("-Xms2g");
    vmParams.add(agent);

    List<String> command = SimpleIde.buildJavaCommandLine("java", vmParams, -1, "/tmp/classes:/tmp/lib.jar",
      "com.demo.Main", 19099, new ArrayList<String>());

    // 验证：agentpath 参数原样出现在启动命令行中
    assertTrue(command.contains(agent));
    // 验证：JVM 参数必须位于 -cp 之前（否则不会被 JVM 当作启动参数）
    assertTrue(command.indexOf(agent) < command.indexOf("-cp"));
    // 验证：classpath 与主类顺序未被破坏
    assertEquals("/tmp/classes:/tmp/lib.jar", command.get(command.indexOf("-cp") + 1));
    assertEquals("com.demo.Main", command.get(command.indexOf("-cp") + 2));
  }

  /** 不传自定义参数时命令行保持原有形态。 */
  @Test
  public void keepsDefaultFormWhenNoCustomVmArgs() {
    List<String> vmParams = new ArrayList<String>();
    vmParams.add("-Xms2g");

    List<String> command = SimpleIde.buildJavaCommandLine("java", vmParams, -1, "/tmp/classes",
      "com.demo.Main", -1, new ArrayList<String>());

    // 验证：默认参数仍然透传
    assertTrue(command.contains("-Xms2g"));
    // 验证：未请求调试时不注入 JDWP 参数
    assertTrue(command.stream().noneMatch((arg) -> arg.startsWith("-agentlib:jdwp")));
  }
}
