package io.simpleide.hotswap.state;

import io.simpleide.hotswap.api.StaticStateStore;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 跨类版本共享静态字段值的默认实现。
 *
 * <p>使用 {@code className + "#" + fieldName} 作为全局 key，值以装箱对象保存，
 * 因此 int/long/boolean 等基础类型和引用类型共用同一套读写入口。
 *
 * <p>所有读写都通过 {@link ConcurrentHashMap} 完成，可被多个版本类和多个线程同时访问。
 */
public final class SharedStaticState implements StaticStateStore {

  /** 全局共享实例，供运行时和改写后的字节码统一使用。 */
  private static final SharedStaticState GLOBAL = new SharedStaticState();

  /** 静态字段值存储；使用包装对象以支持值为 null 的字段。 */
  private final ConcurrentMap<String, Slot> values =
    new ConcurrentHashMap<String, Slot>();

  /**
   * 返回进程级共享实例。
   *
   * @return 全局静态字段存储
   */
  public static SharedStaticState getInstance() {
    return GLOBAL;
  }

  /**
   * 读取一个静态字段值。
   *
   * @param className 字段声明类的点号类名
   * @param fieldName 字段名
   * @return 装箱后的字段值，字段值为 null 时返回 null
   * @throws IllegalArgumentException 参数为空时抛出
   * @throws IllegalStateException 字段从未 capture 且从未 set 时抛出
   */
  @Override
  public Object get(String className, String fieldName) {
    String key = key(className, fieldName);
    Slot slot = values.get(key);
    if (slot == null) {
      throw new IllegalStateException("Static field was not captured: " + className + "."
        + fieldName + ". Call capture() before defining a new class version");
    }
    return slot.value;
  }

  /**
   * 写入一个静态字段值，基础类型由调用方装箱后传入。
   *
   * @param className 字段声明类的点号类名
   * @param fieldName 字段名
   * @param value 新的字段值，允许为 null
   * @throws IllegalArgumentException 类名或字段名为空时抛出
   */
  @Override
  public void set(String className, String fieldName, Object value) {
    values.put(key(className, fieldName), new Slot(value));
  }

  /**
   * 捕获一个类及其父类声明的所有非 final 静态字段初始值。
   *
   * <p>已经存在的 key 不会被覆盖，因此多次调用 capture 不会把运行期修改过的值重置为
   * 类初始化时的值。
   *
   * @param type 原始类
   * @throws Exception 静态字段不可访问时抛出
   */
  @Override
  public void capture(Class<?> type) throws Exception {
    if (type == null) {
      throw new IllegalArgumentException("Cannot capture static state: class is null");
    }
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(null);
        values.putIfAbsent(key(current.getName(), field.getName()), new Slot(value));
      }
    }
  }

  /**
   * 构造共享 Map 的 key。
   *
   * @param className 字段声明类的点号类名
   * @param fieldName 字段名
   * @return 组合 key
   * @throws IllegalArgumentException 类名或字段名为空时抛出
   */
  private String key(String className, String fieldName) {
    if (className == null || className.isEmpty()) {
      throw new IllegalArgumentException("Static field class name must not be empty");
    }
    if (fieldName == null || fieldName.isEmpty()) {
      throw new IllegalArgumentException("Static field name must not be empty");
    }
    return className + "#" + fieldName;
  }

  /**
   * 保存静态字段值的包装对象，使 ConcurrentHashMap 可以存放 null 值。
   */
  private static final class Slot {

    /** 装箱后的字段值，允许为 null。 */
    private final Object value;

    /**
     * 创建字段值包装。
     *
     * @param value 装箱后的字段值
     */
    private Slot(Object value) {
      this.value = value;
    }
  }
}
