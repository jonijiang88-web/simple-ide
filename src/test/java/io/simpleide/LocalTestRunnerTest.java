package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 LocalTestRunner 的测试结果判定：
 * 必须准确区分「用例失败」「容器初始化/收尾失败」「合理跳过」「未执行」「未发现测试」。
 *
 * <p>夹具测试类位于 {@code io.simpleide.fixtures}，不参与 Gradle 常规测试扫描，
 * 由本测试通过 JUnit Platform 主动执行，覆盖 JUnit 4（Vintage）与 JUnit 5 两条链路。
 */
public final class LocalTestRunnerTest {

  /** 验证 JUnit 5 全部通过时判定为成功。 */
  @Test
  void junit5_all_passing_is_success() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.PassingFixtureTest", null);
    // 验证：全部通过时 success 为 true
    assertEquals(Boolean.TRUE, result.get("success"));
    // 验证：发现并执行了 2 个用例
    assertEquals(2L, result.get("total"));
    assertEquals(2L, result.get("passed"));
    // 验证：无失败与无容器失败
    assertEquals(0L, result.get("failed"));
    assertEquals(0L, result.get("containersFailed"));
    // 验证：成功时不携带 errorCode
    assertNull(result.get("errorCode"));
    // 验证：成功时退出码为 0
    assertEquals(0, result.get("exitCode"));
  }

  /** 验证 JUnit 5 用例断言失败时判定为失败并带源码定位。 */
  @Test
  void junit5_failing_case_is_failure() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.FailingFixtureTest", null);
    // 验证：存在用例失败时 success 为 false
    assertEquals(Boolean.FALSE, result.get("success"));
    // 验证：失败数为 1
    assertEquals(1L, result.get("failed"));
    // 验证：错误码为 TEST_FAILED
    assertEquals("TEST_FAILED", result.get("errorCode"));
    // 验证：失败明细非空
    List<?> failures = (List<?>) result.get("failures");
    assertNotNull(failures);
    assertEquals(1, failures.size());
    Map<?, ?> first = (Map<?, ?>) failures.get(0);
    // 验证：能从堆栈中提取到夹具测试类的源码定位
    assertTrue(String.valueOf(first.get("location")).contains("FailingFixtureTest.java"));
    // 验证：失败时退出码为 1
    assertEquals(1, result.get("exitCode"));
  }

  /** 【核心回归】验证 @BeforeAll 抛异常时不再伪装成成功。 */
  @Test
  void junit5_before_all_failure_is_not_success() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.BeforeAllFailsFixtureTest", null);
    // 验证：容器初始化失败必须判为失败，而不是 success=true
    assertEquals(Boolean.FALSE, result.get("success"));
    // 验证：容器失败数被显式统计
    assertTrue(((Number) result.get("containersFailed")).longValue() >= 1);
    // 验证：没有用例真正执行
    assertEquals(0L, result.get("passed"));
    assertEquals(0L, result.get("failed"));
    assertEquals(0L, result.get("executed"));
    // 验证：发现但未执行的用例数为 1
    assertEquals(1L, result.get("notExecuted"));
    // 验证：错误码为 CONTAINER_FAILED
    assertEquals("CONTAINER_FAILED", result.get("errorCode"));
    // 验证：失败明细标记为容器级失败
    List<?> failures = (List<?>) result.get("failures");
    assertNotNull(failures);
    assertFalse(failures.isEmpty());
    Map<?, ?> first = (Map<?, ?>) failures.get(0);
    assertEquals(Boolean.TRUE, first.get("container"));
    // 验证：失败时退出码为非零
    assertEquals(1, result.get("exitCode"));
    // 验证：控制台输出明确指出容器初始化失败
    assertTrue(String.valueOf(result.get("output")).contains("container"));
  }

  /** 验证 @AfterAll 收尾失败同样被判为失败。 */
  @Test
  void junit5_after_all_failure_is_failure() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.AfterAllFailsFixtureTest", null);
    // 验证：收尾失败必须判为失败
    assertEquals(Boolean.FALSE, result.get("success"));
    // 验证：容器失败数被显式统计
    assertTrue(((Number) result.get("containersFailed")).longValue() >= 1);
    // 验证：错误码为 CONTAINER_FAILED
    assertEquals("CONTAINER_FAILED", result.get("errorCode"));
  }

  /** 验证合理跳过（@Disabled）不判为失败，且跳过数与执行数被区分统计。 */
  @Test
  void junit5_disabled_case_is_not_failure() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.DisabledFixtureTest", null);
    // 验证：仅存在合理跳过时不判失败
    assertEquals(Boolean.TRUE, result.get("success"));
    // 验证：跳过数被单独统计
    assertEquals(1L, result.get("skipped"));
    assertEquals(1L, result.get("ignored"));
    // 验证：真实执行数为 1
    assertEquals(1L, result.get("executed"));
    assertEquals(1L, result.get("passed"));
    // 验证：无容器失败与未执行用例
    assertEquals(0L, result.get("containersFailed"));
    assertEquals(0L, result.get("notExecuted"));
  }

  /** 验证 JUnit 4（Vintage）通过链路。 */
  @Test
  void junit4_passing_is_success() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.JUnit4PassingFixtureTest", null);
    // 验证：JUnit 4 用例通过时判定为成功
    assertEquals(Boolean.TRUE, result.get("success"));
    // 验证：JUnit 4 用例被正确执行
    assertEquals(1L, result.get("passed"));
  }

  /** 【核心回归】验证 JUnit 4 @BeforeClass 抛异常时不再伪装成成功。 */
  @Test
  void junit4_before_class_failure_is_not_success() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.JUnit4BeforeClassFailsFixtureTest", null);
    // 验证：JUnit 4 容器初始化失败必须判为失败
    assertEquals(Boolean.FALSE, result.get("success"));
    // 验证：JUnit 4 容器失败被显式统计
    assertTrue(((Number) result.get("containersFailed")).longValue() >= 1);
    // 验证：没有用例真正执行
    assertEquals(0L, result.get("passed"));
    assertEquals(0L, result.get("executed"));
  }

  /** 验证未发现任何测试时给出显式错误码而非模糊失败。 */
  @Test
  void no_tests_found_is_explicit_failure() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.NoTestsFixture", null);
    // 验证：未发现测试时判为失败
    assertEquals(Boolean.FALSE, result.get("success"));
    // 验证：发现测试数为 0
    assertEquals(0L, result.get("total"));
    // 验证：错误码明确为 NO_TESTS_FOUND
    assertEquals("NO_TESTS_FOUND", result.get("errorCode"));
  }

  /** 验证按方法名精确执行时只运行目标用例。 */
  @Test
  void runs_single_method_only() {
    Map<String, Object> result = LocalTestRunner.executeTests("io.simpleide.fixtures.FailingFixtureTest", "passes");
    // 验证：仅执行指定方法且通过
    assertEquals(Boolean.TRUE, result.get("success"));
    assertEquals(1L, result.get("passed"));
    // 验证：结果中回传被选中的方法名
    assertEquals("passes", result.get("method"));
  }
}
