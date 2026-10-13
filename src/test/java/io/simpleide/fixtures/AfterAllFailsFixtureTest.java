package io.simpleide.fixtures;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** LocalTestRunner 夹具测试：@AfterAll 失败，验证收尾失败也会被识别（JUnit 5）。 */
public class AfterAllFailsFixtureTest {

  /** 夹具：正常通过。 */
  @Test
  void passes_then_teardown_fails() {
  }

  /** 夹具：收尾失败。 */
  @AfterAll
  static void afterAll() {
    throw new IllegalStateException("teardown failed");
  }
}
