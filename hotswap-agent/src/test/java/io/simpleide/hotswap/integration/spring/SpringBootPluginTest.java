package io.simpleide.hotswap.integration.spring;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring Boot 插件的注册、无 context 行为、异常隔离与 context 桥。
 */
class SpringBootPluginTest {

  /** 每个用例前清空全局 context 桥，避免用例互相影响。 */
  @BeforeEach
  void clearBefore() {
    clearBridge();
  }

  /** 每个用例后清空全局 context 桥，避免污染其他测试类。 */
  @AfterEach
  void clearAfter() {
    clearBridge();
  }

  /** 初始化时应注册 SpringApplication.run 的 context 捕获 transformer。 */
  @Test
  void initializeRegistersContextTransformer() {
    final List<Object> transformers = new ArrayList<Object>();
    Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
      getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
      new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
          if ("addTransformer".equals(method.getName()) && args != null && args.length > 0) {
            transformers.add(args[0]);
            return null;
          }
          return defaultValue(method.getReturnType());
        }
      });

    new SpringBootPlugin().initialize(new HotswapPluginContext(instrumentation));

    // 验证：插件注册了一个 transformer
    assertEquals(1, transformers.size());
    // 验证：注册的是 Spring context 捕获 transformer
    assertTrue(transformers.get(0) instanceof SpringContextTransformer);
  }

  /** 没有捕获到 context 时 reload 应静默返回。 */
  @Test
  void reloadWithoutContextDoesNotThrow() {
    SpringBootPlugin plugin = new SpringBootPlugin();

    // 验证：无 context 时插件不向调用方抛异常
    assertDoesNotThrow(() -> plugin.onReload(
      new HotswapReloadEvent("com.demo.Sample", Object.class, null, ChangeKind.SCHEMA)));
  }

  /** 单个 context 刷新失败不得逃出插件，且应输出错误日志。 */
  @Test
  void reloadIsolatesContextFailure() {
    SpringContextBridge.capture(new BrokenContext());
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true));
    try {
      // 验证：context 内部异常不会逃出插件
      assertDoesNotThrow(() -> new SpringBootPlugin().onReload(
        new HotswapReloadEvent("com.demo.Sample", Object.class, null, ChangeKind.SCHEMA)));
    }
    finally {
      System.setErr(original);
    }
    String log = captured.toString();
    // 验证：失败信息写入标准错误
    assertTrue(log.contains("spring-boot refresh failed"));
    // 验证：日志包含底层失败原因
    assertTrue(log.contains("boom"));
  }

  /** 只有 schema/signature 变更才触发 bean 工厂访问。 */
  @Test
  void methodBodyChangeSkipsBeanFactoryAccess() {
    CountingContext context = new CountingContext();
    SpringContextBridge.capture(context);

    new SpringBootPlugin().onReload(
      new HotswapReloadEvent("com.demo.Sample", Object.class, null, ChangeKind.METHOD_BODY));

    // 验证：方法体变更不访问 bean 工厂，避免无谓覆盖注入状态
    assertEquals(0, context.beanFactoryCalls);
  }

  /** context 桥应去重、忽略 null 并返回独立快照。 */
  @Test
  void bridgeCapturesOnceAndReturnsSnapshot() {
    Object context = new Object();
    SpringContextBridge.capture(context);
    SpringContextBridge.capture(context);
    SpringContextBridge.capture(null);

    List<Object> first = SpringContextBridge.contexts();
    // 验证：重复捕获同一个 context 只保留一份
    assertEquals(1, first.size());
    // 验证：返回的仍是捕获的 context 实例
    assertSame(context, first.get(0));

    first.clear();
    // 验证：修改快照不影响桥内部状态
    assertEquals(1, SpringContextBridge.contexts().size());
  }

  /**
   * 清空 SpringContextBridge 的内部列表，保证用例隔离。
   */
  private static void clearBridge() {
    try {
      Field field = SpringContextBridge.class.getDeclaredField("CONTEXTS");
      field.setAccessible(true);
      ((List<?>) field.get(null)).clear();
    }
    catch (Exception error) {
      throw new IllegalStateException("cannot clear context bridge", error);
    }
  }

  /**
   * 返回反射代理方法的默认返回值。
   *
   * @param type 返回类型
   * @return 默认值
   */
  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return Boolean.FALSE;
    }
    if (type == int.class) {
      return Integer.valueOf(0);
    }
    if (type == long.class) {
      return Long.valueOf(0L);
    }
    return null;
  }

  /**
   * bean 工厂访问即抛异常的 context，用于验证异常隔离。
   */
  public static final class BrokenContext {

    /**
     * 模拟刷新失败的 bean 工厂访问。
     *
     * @return 不会返回
     */
    public Object getBeanFactory() {
      throw new IllegalStateException("boom");
    }
  }

  /**
   * 记录 bean 工厂访问次数的 context。
   */
  public static final class CountingContext {

    private int beanFactoryCalls;

    /**
     * 记录访问次数并返回空工厂。
     *
     * @return null
     */
    public Object getBeanFactory() {
      beanFactoryCalls++;
      return null;
    }
  }
}
