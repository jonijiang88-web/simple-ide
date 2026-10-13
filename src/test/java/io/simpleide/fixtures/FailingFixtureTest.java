package io.simpleide.fixtures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LocalTestRunner 夹具测试：含真实用例失败（JUnit 5）。 */
public class FailingFixtureTest {

  /** 夹具：通过。 */
  @Test
  void passes() {
  }

  /** 夹具：失败，用于验证失败统计与源码定位提取。 */
  @Test
  void fails_with_assertion() {
    // 夹具故意断言失败，触发真实失败路径
    assertEquals("expected", "actual", "夹具断言失败");
  }
}
