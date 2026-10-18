package io.simpleide.hotswap.facade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.simpleide.hotswap.dispatch.ReflectionMethodDispatcher;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * facade 与 dispatcher 的联合测试：加载改写后的类，真实调用转发链路。
 */
class FacadeDispatchIntegrationTest {

  private final RecordingOriginalClassStore store = new RecordingOriginalClassStore();

  private final AsmClassInstrumenter instrumenter = new AsmClassInstrumenter(store);

  private final ReflectionMethodDispatcher dispatcher = new ReflectionMethodDispatcher();

  private final FacadeSampleVersion version = new FacadeSampleVersion();

  private final List<String> dispatched = new ArrayList<String>();

  /**
   * 清理转发处理器，避免测试之间互相影响。
   */
  @AfterEach
  void clearHandler() {
    FacadeDispatchBridge.clear();
  }

  /**
   * 验证 facade 把调用、参数与返回值完整转发给版本对象。
   *
   * @throws Throwable 调用失败
   */
  @Test
  void facadeForwardsAllMethodsToVersionObject() throws Throwable {
    Class<?> facadeType = defineFacade();
    Object facade = facadeType.newInstance();
    installHandler();

    // 验证：引用返回值来自版本对象
    assertEquals("v2", call(facadeType, facade, "hello", new Class<?>[0]));
    // 验证：两个 int 参数被正确装箱并解包
    assertEquals(5, call(facadeType, facade, "add", new Class<?>[]{int.class, int.class}, 2, 3));
    // 验证：long 参数与 long 返回值正确转换
    assertEquals(15L, call(facadeType, facade, "widen", new Class<?>[]{long.class}, 5L));
    // 验证：double 参数与 double 返回值正确转换
    assertEquals(1.5D, call(facadeType, facade, "third", new Class<?>[]{double.class}, 3.0D));
    // 验证：float 参数与 float 返回值正确转换
    assertEquals(4.0F, call(facadeType, facade, "half", new Class<?>[]{float.class}, 2.0F));
    // 验证：boolean 参数与 boolean 返回值正确转换
    assertEquals(false, call(facadeType, facade, "flag", new Class<?>[]{boolean.class}, true));
    // 验证：byte 参数与 byte 返回值正确转换
    assertEquals((byte) 2, call(facadeType, facade, "byteValue", new Class<?>[]{byte.class}, (byte) 1));
    // 验证：char 参数与 char 返回值正确转换
    assertEquals('b', call(facadeType, facade, "charValue", new Class<?>[]{char.class}, 'a'));
    // 验证：short 参数与 short 返回值正确转换
    assertEquals((short) 2, call(facadeType, facade, "shortValue", new Class<?>[]{short.class}, (short) 1));
    // 验证：void 方法返回 null
    assertNull(call(facadeType, facade, "noop", new Class<?>[0]));
    // 验证：void 转发确实执行了版本对象的方法
    assertEquals(1, version.noopCount());
    // 验证：int 重载按描述符解析
    assertEquals("v2-int:9", call(facadeType, facade, "pick", new Class<?>[]{int.class}, 9));
    // 验证：String 重载按描述符解析
    assertEquals("v2-string:x", call(facadeType, facade, "pick", new Class<?>[]{String.class}, "x"));
    // 验证：数组参数与 int 返回值正确传递
    assertEquals(6, call(facadeType, facade, "arraySum", new Class<?>[]{int[].class},
      (Object) new int[]{1, 2, 3}));
    // 验证：facade 的私有方法同样被转发
    assertEquals("v2-secret", call(facadeType, facade, "secret", new Class<?>[0]));

    // 验证：转发调用携带了方法名与描述符
    assertTrue(dispatched.contains("hello()Ljava/lang/String;"));
    // 验证：primitive 描述符原样传递
    assertTrue(dispatched.contains("add(II)I"));
    // 验证：long 描述符原样传递
    assertTrue(dispatched.contains("widen(J)J"));
    // 验证：数组描述符原样传递
    assertTrue(dispatched.contains("arraySum([I)I"));

    // 验证：构造器写入的普通字段在 facade 上保留
    assertEquals(7, readField(facade, "count"));
    // 验证：构造器写入的引用字段在 facade 上保留
    assertEquals("sample", readField(facade, "name"));
    // 验证：静态方法未被转发，仍执行原方法体
    assertEquals("v1-static",
      call(facadeType, null, "staticHello", new Class<?>[0]));
    // 验证：未改写的静态方法保留分支结构并可正常执行
    assertEquals(2, call(facadeType, null, "staticSafeDivide",
      new Class<?>[]{int.class, int.class}, 6, 3));
    // 验证：未改写的静态方法保留异常处理器
    assertEquals(-1, call(facadeType, null, "staticSafeDivide",
      new Class<?>[]{int.class, int.class}, 1, 0));

    // 验证：改写期间记录了原始字节
    assertArrayEquals(TestClassFactory.sample(), store.bytesOf(TestClassFactory.SAMPLE_CLASS_NAME));
  }

  /**
   * 安装把调用交给 {@link ReflectionMethodDispatcher} 的处理器。
   */
  private void installHandler() {
    FacadeDispatchBridge.setHandler(new FacadeDispatchBridge.Handler() {
      @Override
      public Object invoke(Object facade, String methodName, String descriptor, Object[] args)
          throws Throwable {
        dispatched.add(methodName + descriptor);
        return dispatcher.invoke(version, methodName, descriptor, args);
      }
    });
  }

  /**
   * 改写夹具类并与替身 HotswapRuntime 一起加载。
   *
   * @return 加载后的 facade 类
   * @throws Exception 改写或加载失败
   */
  private Class<?> defineFacade() throws Exception {
    byte[] facadeBytes = instrumenter.instrument(TestClassFactory.sample(),
      TestClassFactory.SAMPLE_CLASS_NAME, getClass().getClassLoader());
    Map<String, byte[]> definitions = new HashMap<String, byte[]>();
    definitions.put(TestClassFactory.SAMPLE_CLASS_NAME, facadeBytes);
    definitions.put("io.simpleide.hotswap.runtime.HotswapRuntime", TestClassFactory.stubRuntime());
    MapClassLoader loader = new MapClassLoader(definitions, getClass().getClassLoader());
    return loader.loadClass(TestClassFactory.SAMPLE_CLASS_NAME);
  }

  /**
   * 通过反射调用目标方法并解包异常。
   *
   * @param type 声明类
   * @param target 目标实例，静态调用时传 null
   * @param methodName 方法名
   * @param parameterTypes 参数类型
   * @param arguments 参数
   * @return 调用结果
   * @throws Throwable 被调用方法抛出的原始异常
   */
  private static Object call(Class<?> type, Object target, String methodName,
      Class<?>[] parameterTypes, Object... arguments) throws Throwable {
    Method method;
    try {
      method = type.getDeclaredMethod(methodName, parameterTypes);
    }
    catch (NoSuchMethodException error) {
      method = type.getMethod(methodName, parameterTypes);
    }
    if (!method.isAccessible()) {
      try {
        method.setAccessible(true);
      }
      catch (RuntimeException ignored) {
        // 访问受限时保留原状态，让 invoke 抛出明确异常。
      }
    }
    try {
      return method.invoke(target, arguments);
    }
    catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      throw cause == null ? error : cause;
    }
  }

  /**
   * 读取 facade 上的字段值。
   *
   * @param target 目标实例
   * @param fieldName 字段名
   * @return 字段值
   * @throws Exception 字段不可读时抛出
   */
  private static Object readField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  /**
   * 从内存字节定义类的类加载器，父加载器优先。
   */
  private static final class MapClassLoader extends ClassLoader {

    private final Map<String, byte[]> definitions;

    /**
     * 创建类加载器。
     *
     * @param definitions 类名到字节的映射
     * @param parent 父加载器
     */
    private MapClassLoader(Map<String, byte[]> definitions, ClassLoader parent) {
      super(parent);
      this.definitions = definitions;
    }

    /**
     * 优先加载内存中的替身类（尤其 HotswapRuntime），避免父加载器中的真实运行时抢先命中。
     *
     * @param name 点分类名
     * @param resolve 是否解析
     * @return 类对象
     * @throws ClassNotFoundException 字节映射和父加载器都不存在该类时抛出
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null && definitions.containsKey(name)) {
          loaded = findClass(name);
        }
        if (loaded == null) {
          loaded = super.loadClass(name, false);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    /**
     * 从内存字节定义尚未被父加载器加载的类。
     *
     * @param name 点分类名
     * @return 类对象
     * @throws ClassNotFoundException 字节映射中不存在该类时抛出
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] bytes = definitions.get(name);
      if (bytes == null) {
        throw new ClassNotFoundException(name);
      }
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
