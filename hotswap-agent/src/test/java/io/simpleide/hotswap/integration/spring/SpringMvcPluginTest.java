package io.simpleide.hotswap.integration.spring;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpringMvcPlugin 纯反射辅助逻辑与异常隔离测试。
 *
 * <p>测试不引入 Spring 运行时依赖，只验证插件自身的筛选、反射签名兼容和异常隔离。
 */
class SpringMvcPluginTest {

  /** 注解、方法签名和字段/方法结构变更都可能改变请求映射。 */
  @Test
  void refreshesStructuralChanges() {
    // 验证：注解变更触发 MVC 刷新
    assertTrue(SpringMvcPlugin.supports(ChangeKind.ANNOTATION));
    // 验证：方法签名变更触发 MVC 刷新
    assertTrue(SpringMvcPlugin.supports(ChangeKind.SIGNATURE));
    // 验证：方法体变更不重建映射
    assertFalse(SpringMvcPlugin.supports(ChangeKind.METHOD_BODY));
    // 验证：字段与方法同时变化时仍需重建映射
    assertTrue(SpringMvcPlugin.supports(ChangeKind.SCHEMA));
    // 验证：变更类型缺失时不刷新
    assertFalse(SpringMvcPlugin.supports(null));
  }

  /** 空 context 列表不产生任何动作，也不抛异常。 */
  @Test
  void ignoresEmptyContextList() {
    SpringMvcPlugin plugin = new SpringMvcPlugin();
    // 验证：空 context 列表下 reload 正常结束
    plugin.reload(Collections.emptyList(), event("com.demo.Controller", ChangeKind.ANNOTATION));
  }

  /** 方法体变更不访问任何 context。 */
  @Test
  void skipsContextsForMethodBodyChange() {
    SpringMvcPlugin plugin = new SpringMvcPlugin();
    RecordingContext context = new RecordingContext();
    // 验证：方法体变更不读取 BeanFactory
    plugin.reload(Collections.singletonList(context), event("com.demo.Controller", ChangeKind.METHOD_BODY));
    // 验证：context 未被访问
    assertFalse(context.touched);
  }

  /** 非 Spring context 的反射失败只记录日志，不逃出插件。 */
  @Test
  void isolatesReflectionFailure() {
    SpringMvcPlugin plugin = new SpringMvcPlugin();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(buffer));
    try {
      // 验证：非 Spring context 不抛异常
      plugin.reload(Collections.singletonList(new Object()),
          event("com.demo.Controller", ChangeKind.ANNOTATION));
    }
    finally {
      System.setErr(original);
    }
    String output = buffer.toString();
    // 验证：失败原因写入 System.err 并包含变更类名
    assertTrue(output.contains("com.demo.Controller"));
  }

  /** 只筛出 getBeanType 与变更类名一致的旧映射。 */
  @Test
  void collectsStaleMappingsOfChangedController() {
    Map<Object, Object> handlers = new LinkedHashMap<Object, Object>();
    handlers.put("mapping-a", new FakeHandlerMethod(FakeController.class));
    handlers.put("mapping-version", new FakeHandlerMethod(FakeController$$SIDEV1.class));
    handlers.put("mapping-b", new FakeHandlerMethod(String.class));
    handlers.put(null, null);
    List<Object> stale = SpringMvcPlugin.staleMappings(handlers, FakeController.class.getName());
    // 验证：返回变更 Controller 自身及其版本类的映射键
    assertEquals(Arrays.asList("mapping-a", "mapping-version"), stale);
    // 验证：空映射表返回空结果
    assertTrue(SpringMvcPlugin.staleMappings(null, FakeController.class.getName()).isEmpty());
    // 验证：类名为空时不匹配任何映射
    assertTrue(SpringMvcPlugin.staleMappings(handlers, null).isEmpty());
  }

  /** 泛型擦除后 unregisterMapping 的参数类型是 Object。 */
  @Test
  void findsErasedUnregisterMappingMethod() {
    Method method = SpringMvcPlugin.findMethod(ErasedMapping.class, "unregisterMapping",
        new Class<?>[]{Object.class});
    // 验证：按 Object 参数能找到擦除后的 unregisterMapping
    assertNotNull(method);
    // 验证：返回的是声明的擦除签名
    assertEquals(Object.class, method.getParameterTypes()[0]);
  }

  /** registerHandlerMethod 的 RequestMappingInfo 参数允许传入运行时子类。 */
  @Test
  void findsRegisterHandlerMethodByAssignableMappingInfo() {
    Method method = SpringMvcPlugin.findMethod(ErasedMapping.class, "registerHandlerMethod",
        new Class<?>[]{Object.class, Method.class, SubMappingInfo.class});
    // 验证：参数运行时类型为声明类型的子类时仍能定位方法
    assertNotNull(method);
    // 验证：定位到的是父类型声明的方法
    assertEquals(MappingInfo.class, method.getParameterTypes()[2]);
  }

  /** 未声明的方法返回 null，调用方自行降级。 */
  @Test
  void returnsNullWhenMethodMissing() {
    // 验证：不存在的方法返回 null
    assertNull(SpringMvcPlugin.findMethod(ErasedMapping.class, "missing", new Class<?>[0]));
  }

  /**
   * 构造一个注解变更事件。
   *
   * @param className 变更类名
   * @param kind 变更类型
   * @return 替换事件
   */
  private static HotswapReloadEvent event(String className, ChangeKind kind) {
    return new HotswapReloadEvent(className, FakeController.class, null, kind);
  }

  /** 记录是否被访问的伪 context。 */
  static final class RecordingContext {

    /** 是否读取过 BeanFactory。 */
    boolean touched;

    /**
     * 模拟 ApplicationContext.getBeanFactory。
     *
     * @return 恒为 null
     */
    public Object getBeanFactory() {
      touched = true;
      return null;
    }
  }

  /** 被热替换的 Controller 类型占位。 */
  static final class FakeController {
  }

  /** 模拟 facade 对应的版本类，类名符合 {@code 原名$$SIDEVn} 约定。 */
  static final class FakeController$$SIDEV1 {
  }

  /** 模拟 Spring 的 HandlerMethod。 */
  static final class FakeHandlerMethod {

    /** handler 声明的类型。 */
    private final Class<?> beanType;

    /**
     * 创建伪 HandlerMethod。
     *
     * @param beanType handler 类型
     */
    FakeHandlerMethod(Class<?> beanType) {
      this.beanType = beanType;
    }

    /**
     * 模拟 HandlerMethod.getBeanType。
     *
     * @return handler 类型
     */
    public Class<?> getBeanType() {
      return beanType;
    }
  }

  /** 模拟 Spring 5.3 中泛型擦除后的映射注册方法。 */
  static class ErasedMapping {

    /**
     * 模拟 AbstractHandlerMethodMapping.unregisterMapping。
     *
     * @param mapping 映射信息
     */
    void unregisterMapping(Object mapping) {
      // 测试替身，无实际行为
    }

    /**
     * 模拟 AbstractHandlerMethodMapping.registerHandlerMethod。
     *
     * @param handler handler 对象
     * @param method handler 方法
     * @param mapping 映射信息
     */
    void registerHandlerMethod(Object handler, Method method, MappingInfo mapping) {
      // 测试替身，无实际行为
    }
  }

  /** 映射信息声明类型。 */
  static class MappingInfo {
  }

  /** 映射信息运行时子类型。 */
  static final class SubMappingInfo extends MappingInfo {
  }
}
