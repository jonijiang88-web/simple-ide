package io.simpleide.fixtures;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** LocalTestRunner 夹具测试：@BeforeAll 抛异常，@Test 不会执行（JUnit 5）。 */
public class BeforeAllFailsFixtureTest {

  /** 夹具：容器初始化失败，模拟安装版复现的 setup failed 场景。 */
  @BeforeAll
  static void beforeAll() {
    throw new IllegalStateException("setup failed");
  }

  /** 夹具：因容器初始化失败而不会被执行。 */
  @Test
  void never_runs() {
    throw new AssertionError("该用例不应被执行");
  }
}
