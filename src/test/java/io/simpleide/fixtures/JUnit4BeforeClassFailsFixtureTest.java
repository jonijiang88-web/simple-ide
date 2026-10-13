package io.simpleide.fixtures;

import org.junit.BeforeClass;
import org.junit.Test;

/** LocalTestRunner 夹具测试：JUnit 4 @BeforeClass 抛异常（经 Vintage 引擎执行）。 */
public class JUnit4BeforeClassFailsFixtureTest {

  /** 夹具：容器初始化失败。 */
  @BeforeClass
  public static void beforeClass() {
    throw new IllegalStateException("junit4 setup failed");
  }

  /** 夹具：因容器初始化失败而不会被执行。 */
  @Test
  public void never_runs() {
    throw new AssertionError("该用例不应被执行");
  }
}
