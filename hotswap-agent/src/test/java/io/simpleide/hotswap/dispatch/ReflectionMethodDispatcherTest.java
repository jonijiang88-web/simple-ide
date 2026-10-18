package io.simpleide.hotswap.dispatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * {@link ReflectionMethodDispatcher} 的行为测试。
 */
class ReflectionMethodDispatcherTest {

  private static final Object[] NO_ARGUMENTS = new Object[0];

  private final ReflectionMethodDispatcher dispatcher = new ReflectionMethodDispatcher();

  /**
   * 验证基本类型、多参数与 void 方法的调用。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void invokesPrimitiveAndVoidMethods() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：int 方法按 (II)I 解析并返回结果
    assertEquals(5, dispatcher.invoke(target, "add", "(II)I", new Object[]{2, 3}));
    // 验证：long 参数与返回值正确传递
    assertEquals(10L, dispatcher.invoke(target, "widen", "(J)J", new Object[]{9L}));
    // 验证：double 参数与返回值正确传递
    assertEquals(2.0D, dispatcher.invoke(target, "half", "(D)D", new Object[]{4.0D}));
    // 验证：float 参数与返回值正确传递
    assertEquals(4.0F, dispatcher.invoke(target, "twice", "(F)F", new Object[]{2.0F}));
    // 验证：boolean 参数与返回值正确传递
    assertEquals(false, dispatcher.invoke(target, "negate", "(Z)Z", new Object[]{true}));
    // 验证：char 参数与返回值正确传递
    assertEquals('b', dispatcher.invoke(target, "nextChar", "(C)C", new Object[]{'a'}));
    // 验证：byte 参数与返回值正确传递
    assertEquals((byte) 2, dispatcher.invoke(target, "nextByte", "(B)B", new Object[]{(byte) 1}));
    // 验证：short 参数与返回值正确传递
    assertEquals((short) 2, dispatcher.invoke(target, "nextShort", "(S)S", new Object[]{(short) 1}));

    // 验证：void 方法返回 null
    assertNull(dispatcher.invoke(target, "bump", "()V", NO_ARGUMENTS));
    // 验证：void 方法确实修改了目标对象状态
    assertEquals(1, target.counter());
  }

  /**
   * 验证引用类型、多参数、null 参数与数组参数/返回值的调用。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void invokesReferenceAndArrayMethods() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：多参数按描述符顺序传递
    assertEquals("a-b7", dispatcher.invoke(target, "concat",
      "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;", new Object[]{"a-", "b", 7}));
    // 验证：null 引用参数被原样传递
    assertEquals("null", dispatcher.invoke(target, "nullEcho",
      "(Ljava/lang/String;)Ljava/lang/String;", new Object[]{null}));
    // 验证：int 数组参数可以正常传入
    assertEquals(6, dispatcher.invoke(target, "sum", "([I)I", new Object[]{new int[]{1, 2, 3}}));
    // 验证：int 数组返回值可以正常返回
    assertArrayEquals(new int[]{2, 3, 4}, (int[]) dispatcher.invoke(target, "increment", "([I)[I",
      new Object[]{new int[]{1, 2, 3}}));
  }

  /**
   * 验证同名重载方法按描述符区分。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void resolvesOverloadsByDescriptor() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：int 重载被选中
    assertEquals("int:7", dispatcher.invoke(target, "pick", "(I)Ljava/lang/String;", new Object[]{7}));
    // 验证：String 重载被选中
    assertEquals("string:x", dispatcher.invoke(target, "pick",
      "(Ljava/lang/String;)Ljava/lang/String;", new Object[]{"x"}));
  }

  /**
   * 验证私有方法与继承方法可以调用。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void invokesPrivateAndInheritedMethods() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：私有方法通过反射可以调用
    assertEquals("hidden", dispatcher.invoke(target, "hidden", "()Ljava/lang/String;", NO_ARGUMENTS));
    // 验证：父类声明的方法可以沿类层次解析
    assertEquals("base", dispatcher.invoke(target, "base", "()Ljava/lang/String;", NO_ARGUMENTS));
  }

  /**
   * 验证调用缓存命中后结果一致。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void reusesCachedMethodResolution() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：第一次调用解析方法并缓存
    assertEquals(3, dispatcher.invoke(target, "add", "(II)I", new Object[]{1, 2}));
    // 验证：缓存命中后结果仍然正确
    assertEquals(30, dispatcher.invoke(target, "add", "(II)I", new Object[]{10, 20}));
  }

  /**
   * 验证异常被解包为原始异常。
   */
  @Test
  void unwrapsInvocationTargetException() {
    DispatchTarget target = new DispatchTarget();

    IllegalStateException runtime = assertThrows(IllegalStateException.class,
      () -> dispatcher.invoke(target, "failRuntime", "()V", NO_ARGUMENTS));
    // 验证：非受检异常消息来自原始异常
    assertEquals("boom", runtime.getMessage());

    IOException checked = assertThrows(IOException.class,
      () -> dispatcher.invoke(target, "failChecked", "()V", NO_ARGUMENTS));
    // 验证：受检异常被原样抛出
    assertEquals("io-boom", checked.getMessage());
  }

  /**
   * 验证未知方法报错，并且不缓存未命中结果，后续版本新增的方法仍可解析。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void reportsUnknownMethodsWithoutNegativeCaching() throws Throwable {
    DispatchTarget v1 = new DispatchTarget();

    NoSuchMethodException missing = assertThrows(NoSuchMethodException.class,
      () -> dispatcher.invoke(v1, "extra", "()Ljava/lang/String;", NO_ARGUMENTS));
    // 验证：错误信息包含类型名、方法名与描述符
    assertEquals(DispatchTarget.class.getName() + ".extra()Ljava/lang/String;", missing.getMessage());

    DispatchTargetV2 v2 = new DispatchTargetV2();
    // 验证：后续版本新增的方法可以正常解析
    assertEquals("extra", dispatcher.invoke(v2, "extra", "()Ljava/lang/String;", NO_ARGUMENTS));

    // 验证：旧版本上再次调用仍未命中，未缓存负结果
    assertThrows(NoSuchMethodException.class,
      () -> dispatcher.invoke(v1, "extra", "()Ljava/lang/String;", NO_ARGUMENTS));
  }

  /**
   * 验证参数个数与描述符不一致时报错而不是静默成功。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void rejectsArgumentsThatDoNotMatchDescriptor() throws Throwable {
    DispatchTarget target = new DispatchTarget();

    // 验证：描述符与实际参数不匹配时抛出反射参数异常
    assertThrows(IllegalArgumentException.class,
      () -> dispatcher.invoke(target, "add", "(II)I", new Object[]{1}));
  }
}
