package io.simpleide.hotswap.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 invokedynamic bootstrap 能为常见描述符完成适配。
 */
class HotswapBootstrapTest {

  /** 引用返回值调用点。 */
  @Test
  void adaptsObjectReturn() throws Throwable {
    CallSite site = HotswapBootstrap.bootstrap(MethodHandles.lookup(), "hello",
      MethodType.methodType(String.class, Object.class), "com/demo/Owner", "hello",
      "()Ljava/lang/String;");
    // 验证：调用点类型保持原 receiver + 返回类型
    assertEquals(String.class, site.getTarget().type().returnType());
    // 验证：调用点保留 receiver 参数
    assertEquals(1, site.getTarget().type().parameterCount());
  }

  /** void 和基本类型参数调用点。 */
  @Test
  void adaptsPrimitiveArgumentsAndVoid() throws Throwable {
    CallSite site = HotswapBootstrap.bootstrap(MethodHandles.lookup(), "run",
      MethodType.methodType(void.class, Object.class, int.class, boolean.class),
      "com/demo/Owner", "run", "(IZ)V");
    // 验证：void 返回值适配成功
    assertEquals(void.class, site.getTarget().type().returnType());
    // 验证：receiver 与两个参数都保留
    assertEquals(3, site.getTarget().type().parameterCount());
  }
}
