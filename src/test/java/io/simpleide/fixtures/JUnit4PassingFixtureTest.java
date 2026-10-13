package io.simpleide.fixtures;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** LocalTestRunner 夹具测试：JUnit 4 全部通过（经 Vintage 引擎执行）。 */
public class JUnit4PassingFixtureTest {

  /** 夹具：JUnit 4 用例通过。 */
  @Test
  public void always_passes() {
    assertTrue(true);
  }
}
