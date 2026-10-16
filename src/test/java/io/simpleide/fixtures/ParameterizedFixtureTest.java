package io.simpleide.fixtures;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数化测试夹具，由兼容性测试主动执行，不参与常规测试扫描。 */
public class ParameterizedFixtureTest {
  /** 验证每个输入参数都被内置运行器执行。 */
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void accepts_positive_values(int value) {
    // 验证：每个参数均为正数。
    assertTrue(value > 0);
  }
}
