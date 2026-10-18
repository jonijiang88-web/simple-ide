package io.simpleide.hotswap.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.simpleide.hotswap.api.HotswapNames;
import io.simpleide.hotswap.version.fixtures.Bar;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证版本类定义在原类同一个 ClassLoader 与同一个包中。
 */
class RuntimeClassDefinerTest {

  /**
   * 验证在测试类自身的 ClassLoader 中定义版本类，并能正常实例化与调用。
   *
   * @throws Exception 定义或反射调用失败
   */
  @Test
  @DisplayName("在同一 ClassLoader 同一包中定义版本类并可实例化")
  void definesVersionClassInSameLoader() throws Exception {
    String versionName = AsmVersionClassGenerator.versionName(Bar.class.getName(), 1);
    byte[] versionBytes = generateBytes(Bar.class.getName(), versionName);
    Class<?> version = new RuntimeClassDefiner().define(Bar.class, versionName, versionBytes);

    // 验证：定义出的类名是版本类名
    assertEquals(versionName, version.getName());
    // 验证：ClassLoader 与原类完全一致
    assertSame(Bar.class.getClassLoader(), version.getClassLoader());
    // 验证：运行时包名与原类一致
    assertEquals(Bar.class.getPackage().getName(), version.getPackage().getName());
    // 验证：版本类是独立的新类
    assertNotSame(Bar.class, version);

    Object instance = version.getConstructor(int.class).newInstance(5);
    Method hello = version.getMethod("hello");
    // 验证：版本类可以正常执行方法体
    assertEquals("hello-5-1", hello.invoke(instance));
    Method copy = version.getMethod("copy");
    // 验证：返回自引用类型的方法返回的是版本类实例
    assertSame(version, copy.invoke(instance).getClass());
  }

  /**
   * 验证为子 ClassLoader 加载的原类定义版本类时，版本类落在同一个子 ClassLoader。
   *
   * @throws Exception 类加载、定义或反射调用失败
   */
  @Test
  @DisplayName("为子 ClassLoader 中的原类定义版本类时沿用该 ClassLoader")
  void definesVersionClassInOriginalChildLoader() throws Exception {
    URL location = Bar.class.getProtectionDomain().getCodeSource().getLocation();
    try (URLClassLoader childLoader = new URLClassLoader(new URL[] {location}, null)) {
      Class<?> childBar = Class.forName(Bar.class.getName(), true, childLoader);
      String versionName = AsmVersionClassGenerator.versionName(Bar.class.getName(), 2);
      byte[] versionBytes = generateBytes(Bar.class.getName(), versionName);
      Class<?> version = new RuntimeClassDefiner().define(childBar, versionName, versionBytes);

      // 验证：版本类落在原类所在的子 ClassLoader
      assertSame(childBar.getClassLoader(), version.getClassLoader());
      // 验证：子 ClassLoader 确实是本次创建的实例
      assertSame(childLoader, version.getClassLoader());
      // 验证：版本类包名与原类一致
      assertEquals(childBar.getPackage().getName(), version.getPackage().getName());
      Object instance = version.getConstructor(int.class).newInstance(9);
      // 验证：子 ClassLoader 中的版本类可以正常执行
      assertEquals("hello-9-1", version.getMethod("hello").invoke(instance));
    }
  }

  /**
   * 验证同包约束：版本类与原类不在同一个包时拒绝定义。
   */
  @Test
  @DisplayName("拒绝定义到不同的包")
  void rejectsDifferentPackage() {
    String versionName = "io.simpleide.hotswap.version.other.Bar$$SIDEV4";
    byte[] versionBytes = generateBytes(Bar.class.getName(), versionName);

    // 验证：跨包定义抛出 IllegalArgumentException
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
      () -> new RuntimeClassDefiner().define(Bar.class, versionName, versionBytes));
    // 验证：错误信息说明同包约束
    assertTrue(error.getMessage().contains("同包"));
  }

  /**
   * 验证重复定义同名版本类时明确失败。
   *
   * @throws Exception 第一次定义失败
   */
  @Test
  @DisplayName("拒绝重复定义同名版本类")
  void rejectsDuplicateDefinition() throws Exception {
    String versionName = AsmVersionClassGenerator.versionName(Bar.class.getName(), 3);
    byte[] versionBytes = generateBytes(Bar.class.getName(), versionName);
    RuntimeClassDefiner definer = new RuntimeClassDefiner();
    definer.define(Bar.class, versionName, versionBytes);

    // 验证：第二次定义同名版本类抛出 IllegalStateException
    IllegalStateException error = assertThrows(IllegalStateException.class,
      () -> definer.define(Bar.class, versionName, versionBytes));
    // 验证：错误信息说明版本类已存在
    assertTrue(error.getMessage().contains("已存在"));
  }

  /**
   * 验证非法参数被拒绝。
   */
  @Test
  @DisplayName("拒绝空参数与非法版本类名")
  void rejectsInvalidArguments() {
    RuntimeClassDefiner definer = new RuntimeClassDefiner();
    byte[] versionBytes = generateBytes(Bar.class.getName(),
      Bar.class.getName() + HotswapNames.VERSION_PREFIX + 5);

    // 验证：原类为空时抛出 IllegalArgumentException
    assertThrows(IllegalArgumentException.class,
      () -> definer.define(null, Bar.class.getName() + "$$SIDEV5", versionBytes));
    // 验证：版本类名为空时抛出 IllegalArgumentException
    assertThrows(IllegalArgumentException.class,
      () -> definer.define(Bar.class, "", versionBytes));
    // 验证：字节码为空时抛出 IllegalArgumentException
    assertThrows(IllegalArgumentException.class,
      () -> definer.define(Bar.class, Bar.class.getName() + "$$SIDEV5", new byte[0]));
    // 验证：包含内部名分隔符的版本类名被拒绝
    assertThrows(IllegalArgumentException.class,
      () -> definer.define(Bar.class, Bar.class.getName().replace('.', '/') + "$$SIDEV5",
        versionBytes));
  }

  /**
   * 生成版本类字节码。
   *
   * @param originalName 原类点分名
   * @param versionName 版本类点分名
   * @return 版本类字节码
   * @throws Exception 生成失败
   */
  private static byte[] generateBytes(String originalName, String versionName) {
    try {
      byte[] originalBytes = AsmVersionClassGeneratorTest.readClassBytes(Bar.class);
      return new AsmVersionClassGenerator().generate(originalBytes, originalName, versionName);
    }
    catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
