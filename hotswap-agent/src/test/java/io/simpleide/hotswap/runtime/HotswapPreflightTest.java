package io.simpleide.hotswap.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 reload 前的保守预检。
 */
class HotswapPreflightTest {

  /**
   * 包含内部类的类应被判定为需要重启。
   *
   * @throws Exception 读取 class 失败
   */
  @Test
  void innerClassRequiresRestart() throws Exception {
    // 验证：外层类声明了内部类时返回内部类预检错误码
    assertEquals(HotswapPreflight.INNER_CLASS_REQUIRES_RESTART,
      HotswapPreflight.check(read(PreflightWithInner.class)));
  }

  /**
   * 声明 native 方法的类应被判定为需要重启。
   *
   * @throws Exception 读取 class 失败
   */
  @Test
  void nativeMethodRequiresRestart() throws Exception {
    // 验证：native 方法无法自动映射 JNI 符号
    assertEquals(HotswapPreflight.NATIVE_METHOD_REQUIRES_RESTART,
      HotswapPreflight.check(read(PreflightNative.class)));
  }

  /** 普通类不命中预检。 */
  @Test
  void plainClassPasses() throws Exception {
    // 验证：无内部类、无 native 方法的类可以通过预检
    assertNull(HotswapPreflight.check(read(PreflightPlain.class)));
  }

  /** lambda 不是内部类，不应命中预检。 */
  @Test
  void lambdaClassPasses() throws Exception {
    // 验证：使用 lambda 的普通类仍可走版本化路径
    assertNull(HotswapPreflight.check(read(PreflightLambda.class)));
  }

  /**
   * 读取指定类的 class 字节。
   *
   * @param type 类
   * @return class 字节
   * @throws Exception 读取失败
   */
  private static byte[] read(Class<?> type) throws Exception {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input = type.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("class resource not found: " + resource);
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }
}

/** 预检 fixture：包含内部类。 */
class PreflightWithInner {
  /** 内部类。 */
  class Inner {
  }
}

/** 预检 fixture：包含 native 方法。 */
class PreflightNative {
  /** 声明但不实现的 native 方法。 */
  native void work();
}

/** 预检 fixture：普通类。 */
class PreflightPlain {
  /** 普通字段。 */
  int value;

  /**
   * 返回固定值。
   *
   * @return 字符串
   */
  String hello() {
    return "v1";
  }
}

/** 预检 fixture：使用 lambda。 */
class PreflightLambda {
  /**
   * 创建 lambda。
   *
   * @return 可执行任务
   */
  Runnable make() {
    return () -> {
    };
  }
}
