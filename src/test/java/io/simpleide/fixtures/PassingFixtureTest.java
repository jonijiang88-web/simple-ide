package io.simpleide.fixtures;

import org.junit.jupiter.api.Test;

/**
 * LocalTestRunner 夹具测试：全部通过（JUnit 5）。
 *
 * <p>该类不参与 Gradle 常规测试扫描（由 build.gradle.kts 的 filter 排除），
 * 仅由 {@code LocalTestRunnerTest} 通过 JUnit Platform 主动执行，用于验证测试结果判定逻辑。
 */
public class PassingFixtureTest {

  /** 夹具：始终通过。 */
  @Test
  void always_passes_first() {
  }

  /** 夹具：始终通过。 */
  @Test
  void always_passes_second() {
  }
}
