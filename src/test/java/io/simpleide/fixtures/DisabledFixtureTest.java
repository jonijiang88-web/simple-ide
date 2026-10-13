package io.simpleide.fixtures;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** LocalTestRunner 夹具测试：合理跳过不应被判为失败（JUnit 5）。 */
public class DisabledFixtureTest {

  /** 夹具：通过。 */
  @Test
  void runs() {
  }

  /** 夹具：被显式禁用，属于合理跳过。 */
  @Disabled("夹具：合理跳过")
  @Test
  void intentionally_disabled() {
  }
}
