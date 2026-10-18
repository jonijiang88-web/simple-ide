package io.simpleide.hotswap.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.simpleide.hotswap.state.fixtures.StaticAccessFixture;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * {@link StaticAccessRewriter} 的行为测试。
 */
class StaticAccessRewriterTest {

  /** 被测改写器。 */
  private final StaticAccessRewriter rewriter = new StaticAccessRewriter();

  /**
   * 验证改写后的类读写共享存储，而原类保持独立。
   *
   * @throws Exception 读取字节或反射调用失败时测试失败
   */
  @Test
  void redirectsStaticFieldAccessToSharedState() throws Exception {
    String className = StaticAccessFixture.class.getName();
    byte[] original = readClassBytes(className);
    byte[] rewritten = rewriter.rewrite(original, className);
    Class<?> type = new SingleClassLoader(className, rewritten).loadClass(className);
    SharedStaticState state = SharedStaticState.getInstance();

    // 验证：改写后的类初始化时把静态初值写进共享存储
    assertEquals(5, invokeInt(type, "readCount"));
    // 验证：共享存储中的 int 初值来自改写后的 <clinit>
    assertEquals(Integer.valueOf(5), state.get(className, "count"));

    invoke(type, "writeCount", new Class<?>[] {int.class}, new Object[] {Integer.valueOf(77)});

    // 验证：改写后的方法读取共享存储中的新值
    assertEquals(77, invokeInt(type, "readCount"));
    // 验证：写入直接落到共享存储
    assertEquals(Integer.valueOf(77), state.get(className, "count"));
    // 验证：原始类自己的静态字段没有被改写后的类影响
    assertEquals(5, StaticAccessFixture.count);

    invoke(type, "writeTotal", new Class<?>[] {long.class}, new Object[] {Long.valueOf(123L)});

    // 验证：long 静态字段完成装箱/拆箱改写
    assertEquals(123L, ((Long) invoke(type, "readTotal", new Class<?>[0],
      new Object[0])).longValue());
    // 验证：long 静态字段写入共享存储
    assertEquals(Long.valueOf(123L), state.get(className, "total"));

    Object sameLabel = new String("shared");
    invoke(type, "writeLabel", new Class<?>[] {String.class}, new Object[] {sameLabel});

    // 验证：引用静态字段保持同一对象引用
    assertSame(sameLabel, invoke(type, "readLabel", new Class<?>[0], new Object[0]));
    // 验证：引用静态字段写入共享存储
    assertSame(sameLabel, state.get(className, "label"));

    invoke(type, "writeLabel", new Class<?>[] {String.class}, new Object[] {null});

    // 验证：引用静态字段可以写入 null 并读回 null
    assertNull(invoke(type, "readLabel", new Class<?>[0], new Object[0]));

    // 验证：final 静态字段不被改写，读取的仍是常量
    assertEquals(42, invokeInt(type, "readConstant"));
    // 验证：final 静态字段没有进入共享存储
    assertThrows(IllegalStateException.class, () -> state.get(className, "CONSTANT"));
  }

  /**
   * 验证类中没有可共享静态字段时返回原始字节。
   *
   * @throws Exception 读取字节失败时测试失败
   */
  @Test
  void returnsOriginalBytesWhenNothingToRewrite() throws Exception {
    byte[] original = readClassBytes(StatelessFixture.class.getName());

    byte[] rewritten = rewriter.rewrite(original);

    // 验证：没有非 final 静态字段时字节内容不变
    assertSame(original, rewritten);
  }

  /**
   * 读取测试类自身的 class 字节。
   *
   * @param className 点号类名
   * @return class 文件字节
   * @throws Exception 资源不存在或读取失败时抛出
   */
  private byte[] readClassBytes(String className) throws Exception {
    String resource = className.substring(className.lastIndexOf('.') + 1) + ".class";
    InputStream input = Class.forName(className, false,
      StaticAccessRewriterTest.class.getClassLoader()).getResourceAsStream(resource);
    if (input == null) {
      throw new IllegalStateException("Missing class resource: " + resource);
    }
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read = input.read(buffer);
      while (read >= 0) {
        output.write(buffer, 0, read);
        read = input.read(buffer);
      }
      return output.toByteArray();
    }
    finally {
      input.close();
    }
  }

  /**
   * 调用返回 int 的静态方法。
   *
   * @param type 目标类
   * @param methodName 方法名
   * @return 返回值
   * @throws Exception 调用失败时抛出
   */
  private int invokeInt(Class<?> type, String methodName) throws Exception {
    return ((Integer) invoke(type, methodName, new Class<?>[0], new Object[0])).intValue();
  }

  /**
   * 调用静态方法。
   *
   * @param type 目标类
   * @param methodName 方法名
   * @param parameterTypes 参数类型
   * @param arguments 参数数组
   * @return 返回值
   * @throws Exception 调用失败时抛出
   */
  private Object invoke(Class<?> type, String methodName, Class<?>[] parameterTypes,
                        Object[] arguments) throws Exception {
    Method method = type.getMethod(methodName, parameterTypes);
    return method.invoke(null, arguments);
  }

  /**
   * 在子 ClassLoader 中定义改写后的同名类，避免与父加载器中的原类冲突。
   */
  private static final class SingleClassLoader extends ClassLoader {

    /** 需要替换的类名。 */
    private final String targetName;

    /** 改写后的类字节。 */
    private final byte[] classBytes;

    /**
     * 创建单类加载器。
     *
     * @param targetName 目标类名
     * @param classBytes 改写后的字节
     */
    private SingleClassLoader(String targetName, byte[] classBytes) {
      super(StaticAccessFixture.class.getClassLoader());
      this.targetName = targetName;
      this.classBytes = classBytes;
    }

    /**
     * 目标类由本加载器定义，其余类委托父加载器。
     *
     * @param name 类名
     * @param resolve 是否解析
     * @return 加载后的类
     * @throws ClassNotFoundException 类不存在时抛出
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!targetName.equals(name)) {
        return super.loadClass(name, resolve);
      }
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        loaded = defineClass(name, classBytes, 0, classBytes.length);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  /** 没有静态字段的对照类。 */
  static final class StatelessFixture {

    /** 实例字段不参与静态改写。 */
    private int value;

    /**
     * 读取实例字段。
     *
     * @return 实例字段值
     */
    int value() {
      return value;
    }
  }
}
