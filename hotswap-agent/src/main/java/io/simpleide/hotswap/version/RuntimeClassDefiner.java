package io.simpleide.hotswap.version;

import io.simpleide.hotswap.api.ClassDefiner;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

/**
 * 在原件同一个 ClassLoader、同一个运行时包中定义版本类。
 *
 * <p>Java 9+ 优先使用 {@code MethodHandles.privateLookupIn(original, lookup)} 的
 * {@code defineClass}，失败时回退到 {@code sun.misc.Unsafe#defineClass}；
 * Java 8 直接使用 Unsafe。定义后校验类名、ClassLoader 与包名。
 */
public final class RuntimeClassDefiner implements ClassDefiner {

  /** 当前 JVM 是否提供 MethodHandles.privateLookupIn（Java 9+）。 */
  private static final boolean PRIVATE_LOOKUP_SUPPORTED = privateLookupAvailable();

  /**
   * 定义版本类。
   *
   * @param original 原 facade 类
   * @param versionName 版本类点分名
   * @param versionBytes 版本类字节码
   * @return 已定义的版本类
   * @throws Exception 参数非法、类已存在或定义失败时抛出
   */
  @Override
  public Class<?> define(Class<?> original, String versionName, byte[] versionBytes) throws Exception {
    if (original == null) {
      throw new IllegalArgumentException("原类不能为空");
    }
    if (versionBytes == null || versionBytes.length == 0) {
      throw new IllegalArgumentException("版本类字节码不能为空");
    }
    validateBinaryName(versionName);
    String originalPackage = packageOf(original.getName());
    String versionPackage = packageOf(versionName);
    if (!originalPackage.equals(versionPackage)) {
      throw new IllegalArgumentException("版本类必须与原类同包: 原类包=" + originalPackage
        + ", 版本类包=" + versionPackage);
    }
    ClassLoader loader = original.getClassLoader();
    if (isAlreadyLoaded(loader, versionName)) {
      throw new IllegalStateException("版本类已存在，不能重复定义: " + versionName);
    }
    Class<?> defined = defineVersionClass(original, loader, versionName, versionBytes);
    verify(original, defined, versionName);
    return defined;
  }

  /**
   * 按运行环境选择定义方式：Java 9+ 优先 privateLookupIn，失败后回退 Unsafe。
   *
   * @param original 原类
   * @param loader 原类 ClassLoader
   * @param versionName 版本类点分名
   * @param versionBytes 版本类字节码
   * @return 已定义的版本类
   * @throws Exception 两种方式都失败时抛出
   */
  private Class<?> defineVersionClass(Class<?> original, ClassLoader loader, String versionName,
                                      byte[] versionBytes) throws Exception {
    if (PRIVATE_LOOKUP_SUPPORTED) {
      try {
        return defineWithLookup(original, versionBytes);
      }
      catch (Exception lookupFailure) {
        try {
          return defineWithUnsafe(original, loader, versionName, versionBytes);
        }
        catch (Exception unsafeFailure) {
          unsafeFailure.addSuppressed(lookupFailure);
          throw new IllegalStateException("无法在同一个 ClassLoader 中定义版本类: " + versionName,
            unsafeFailure);
        }
      }
    }
    return defineWithUnsafe(original, loader, versionName, versionBytes);
  }

  /**
   * 校验定义结果：类名、ClassLoader、包名必须与原类一致。
   *
   * @param original 原类
   * @param defined 已定义的版本类
   * @param versionName 期望的版本类名
   */
  private void verify(Class<?> original, Class<?> defined, String versionName) {
    if (defined == null) {
      throw new IllegalStateException("版本类定义后为空: " + versionName);
    }
    if (!defined.getName().equals(versionName)) {
      throw new IllegalStateException("版本类名不一致: 期望=" + versionName + ", 实际=" + defined.getName());
    }
    if (defined.getClassLoader() != original.getClassLoader()) {
      throw new IllegalStateException("版本类 ClassLoader 与原类不一致: 原类="
        + original.getClassLoader() + ", 版本类=" + defined.getClassLoader());
    }
    if (!packageOf(defined.getName()).equals(packageOf(original.getName()))) {
      throw new IllegalStateException("版本类包名与原类不一致: 原类=" + packageOf(original.getName())
        + ", 版本类=" + packageOf(defined.getName()));
    }
  }

  /**
   * 使用 Java 9+ 的 privateLookupIn + Lookup#defineClass 定义版本类。
   *
   * <p>通过反射调用，保证代码可编译到 Java 8。
   *
   * @param original 原类
   * @param versionBytes 版本类字节码
   * @return 已定义的版本类
   * @throws Exception 反射调用失败时抛出
   */
  private Class<?> defineWithLookup(Class<?> original, byte[] versionBytes) throws Exception {
    Method privateLookupIn = MethodHandles.class.getMethod("privateLookupIn",
      Class.class, MethodHandles.Lookup.class);
    Object lookup = invoke(privateLookupIn, null, original, MethodHandles.lookup());
    Method defineClass = lookup.getClass().getMethod("defineClass", byte[].class);
    return (Class<?>) invoke(defineClass, lookup, (Object) versionBytes);
  }

  /**
   * 使用 sun.misc.Unsafe#defineClass 在指定 ClassLoader 中定义版本类。
   *
   * @param original 原类，用于复制 ProtectionDomain
   * @param loader 原类 ClassLoader
   * @param versionName 版本类点分名
   * @param versionBytes 版本类字节码
   * @return 已定义的版本类
   * @throws Exception 反射或定义失败时抛出
   */
  private Class<?> defineWithUnsafe(Class<?> original, ClassLoader loader, String versionName,
                                    byte[] versionBytes) throws Exception {
    Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
    theUnsafe.setAccessible(true);
    Object unsafe = theUnsafe.get(null);
    Method defineClass = unsafeType.getDeclaredMethod("defineClass", String.class, byte[].class,
      int.class, int.class, ClassLoader.class, ProtectionDomain.class);
    defineClass.setAccessible(true);
    return (Class<?>) invoke(defineClass, unsafe, versionName, versionBytes, 0,
      versionBytes.length, loader, original.getProtectionDomain());
  }

  /**
   * 反射调用并解包 InvocationTargetException，避免丢失真实错误。
   *
   * @param method 目标方法
   * @param target 调用目标，静态方法传 null
   * @param arguments 方法参数
   * @return 调用结果
   * @throws Exception 目标方法抛出的异常
   */
  private Object invoke(Method method, Object target, Object... arguments) throws Exception {
    try {
      return method.invoke(target, arguments);
    }
    catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw error;
    }
  }

  /**
   * 判断当前 JVM 是否支持 privateLookupIn。
   *
   * @return Java 9+ 返回 true
   */
  private static boolean privateLookupAvailable() {
    try {
      MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      return true;
    }
    catch (NoSuchMethodException missing) {
      return false;
    }
  }

  /**
   * 判断该 ClassLoader 中是否已经加载了同名类。
   *
   * @param loader 目标 ClassLoader
   * @param versionName 版本类点分名
   * @return 已加载返回 true
   */
  private static boolean isAlreadyLoaded(ClassLoader loader, String versionName) {
    try {
      Class.forName(versionName, false, loader);
      return true;
    }
    catch (ClassNotFoundException missing) {
      return false;
    }
  }

  /**
   * 取出点分类名所在的包名，默认包返回空串。
   *
   * @param className 点分类名
   * @return 包名
   */
  private static String packageOf(String className) {
    int index = className.lastIndexOf('.');
    return index < 0 ? "" : className.substring(0, index);
  }

  /**
   * 校验版本类名是合法的点分二进制名。
   *
   * @param versionName 版本类点分名
   */
  private static void validateBinaryName(String versionName) {
    if (versionName == null || versionName.trim().isEmpty()) {
      throw new IllegalArgumentException("版本类名不能为空");
    }
    if (versionName.startsWith(".") || versionName.endsWith(".") || versionName.contains("..")
      || versionName.indexOf('/') >= 0 || versionName.indexOf(';') >= 0
      || versionName.indexOf('[') >= 0 || versionName.indexOf('(') >= 0) {
      throw new IllegalArgumentException("非法的版本类名: " + versionName);
    }
  }
}
