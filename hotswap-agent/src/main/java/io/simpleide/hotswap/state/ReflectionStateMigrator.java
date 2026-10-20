package io.simpleide.hotswap.state;

import io.simpleide.hotswap.api.StateMigrator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于反射的实例字段迁移实现。
 *
 * <p>按字段名匹配旧版本对象和新版本对象的实例字段，并复制字段值。字段类型必须完全
 * 一致；新版本新增字段保持 JVM 默认值；静态字段由 {@link SharedStaticState} 单独
 * 处理，这里直接跳过。
 *
 * <p>final 实例字段无法通过反射稳定写入，优先使用 {@code sun.misc.Unsafe} 的字段
 * 偏移写入；Unsafe 不可用时抛出明确异常，绝不静默跳过。
 */
public final class ReflectionStateMigrator implements StateMigrator {

  /**
   * 调用前补齐框架在 facade 上后注入的依赖，不覆盖版本中的普通业务状态或新增依赖。
   *
   * @param facade 框架持有并完成注入的原对象
   * @param version 当前版本对象
   * @throws Exception 字段访问或写入失败
   */
  public void refreshInjectedFields(Object facade, Object version) throws Exception {
    Map<String, Field> targetFields = instanceFields(version.getClass());
    for (Map.Entry<String, Field> entry : instanceFields(facade.getClass()).entrySet()) {
      Field source = entry.getValue();
      boolean injected = false;
      for (java.lang.annotation.Annotation annotation : source.getDeclaredAnnotations()) {
        String name = annotation.annotationType().getName();
        if ("org.springframework.beans.factory.annotation.Autowired".equals(name)
          || "org.springframework.beans.factory.annotation.Value".equals(name)
          || "javax.inject.Inject".equals(name) || "jakarta.inject.Inject".equals(name)
          || "javax.annotation.Resource".equals(name) || "jakarta.annotation.Resource".equals(name)) {
          injected = true;
          break;
        }
      }
      Field target = targetFields.get(entry.getKey());
      if (!injected || target == null || !source.getType().equals(target.getType())) {
        continue;
      }
      source.setAccessible(true);
      Object value = source.get(facade);
      // facade 未注入时不能清空 schema reload 后仅存在于版本对象的依赖。
      if (value != null) {
        writeField(target, version, value);
      }
    }
  }

  /**
   * 复制旧版本对象到新版本对象的全部可匹配实例字段。
   *
   * @param from 旧版本对象
   * @param to 新版本对象
   * @throws Exception 字段类型不一致、final 字段无法写入或字段访问失败时抛出
   */
  @Override
  public void copy(Object from, Object to) throws Exception {
    if (from == null) {
      throw new IllegalArgumentException("Cannot migrate state: source object is null");
    }
    if (to == null) {
      throw new IllegalArgumentException("Cannot migrate state: target object is null");
    }
    if (from == to) {
      return;
    }
    Map<String, Field> sourceFields = instanceFields(from.getClass());
    Map<String, Field> targetFields = instanceFields(to.getClass());
    Map<Field, Field> matches = new LinkedHashMap<Field, Field>();
    boolean writesFinal = false;
    for (Map.Entry<String, Field> entry : sourceFields.entrySet()) {
      String fieldName = entry.getKey();
      Field sourceField = entry.getValue();
      Field targetField = targetFields.get(fieldName);
      if (targetField == null) {
        // 新版本已删除的字段直接丢弃，不做隐式保留。
        continue;
      }
      if (!sourceField.getType().equals(targetField.getType())) {
        throw new IllegalStateException("Cannot migrate field '" + fieldName + "' from "
          + sourceField.getDeclaringClass().getName() + " to "
          + targetField.getDeclaringClass().getName() + ": type changed from "
          + sourceField.getType().getName() + " to " + targetField.getType().getName());
      }
      matches.put(sourceField, targetField);
      writesFinal = writesFinal || Modifier.isFinal(targetField.getModifiers());
    }
    // 先完成全部校验，避免中途失败时迁移了部分字段。
    if (writesFinal && UnsafeAccess.instance() == null) {
      throw new IllegalStateException(
        "Cannot migrate final fields: sun.misc.Unsafe is unavailable");
    }
    for (Map.Entry<Field, Field> entry : matches.entrySet()) {
      Field sourceField = entry.getKey();
      sourceField.setAccessible(true);
      Object value = sourceField.get(from);
      writeField(entry.getValue(), to, value);
    }
  }

  /**
   * 收集一个类及其父类中声明的全部非静态实例字段，子类同名字段优先。
   *
   * @param type 目标类型
   * @return 字段名到字段的映射
   */
  private Map<String, Field> instanceFields(Class<?> type) {
    Map<String, Field> fields = new HashMap<String, Field>();
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        if (!fields.containsKey(field.getName())) {
          fields.put(field.getName(), field);
        }
      }
    }
    return fields;
  }

  /**
   * 把字段值写入目标对象，final 字段走 Unsafe，普通字段走反射。
   *
   * @param field 目标字段
   * @param target 目标对象
   * @param value 字段值
   * @throws Exception 写入失败时抛出
   */
  private void writeField(Field field, Object target, Object value) throws Exception {
    boolean isFinal = Modifier.isFinal(field.getModifiers());
    if (!isFinal) {
      field.setAccessible(true);
      field.set(target, value);
      return;
    }
    UnsafeAccess unsafe = UnsafeAccess.instance();
    if (unsafe == null) {
      throw new IllegalStateException("Cannot migrate final field '"
        + field.getDeclaringClass().getName() + "." + field.getName()
        + "': sun.misc.Unsafe is unavailable");
    }
    unsafe.put(field, target, value);
  }

  /**
   * 通过反射访问 {@code sun.misc.Unsafe}，避免在编译期依赖内部 API。
   */
  private static final class UnsafeAccess {

    /** 单例；初始化失败时为 null。 */
    private static final UnsafeAccess INSTANCE = create();

    /** Unsafe 实例。 */
    private final Object unsafe;

    /** objectFieldOffset 方法。 */
    private final Method objectFieldOffset;

    /** 基础类型到 putXxx 方法的映射。 */
    private final Map<Class<?>, Method> primitivePuts;

    /** 引用类型写入方法。 */
    private final Method putObject;

    /**
     * 保存已经解析好的 Unsafe 访问入口。
     *
     * @param unsafe Unsafe 实例
     * @param objectFieldOffset 字段偏移方法
     * @param primitivePuts 基础类型写入方法
     * @param putObject 引用类型写入方法
     */
    private UnsafeAccess(Object unsafe, Method objectFieldOffset,
                         Map<Class<?>, Method> primitivePuts, Method putObject) {
      this.unsafe = unsafe;
      this.objectFieldOffset = objectFieldOffset;
      this.primitivePuts = primitivePuts;
      this.putObject = putObject;
    }

    /**
     * 返回懒加载的 Unsafe 访问器。
     *
     * @return 访问器，Unsafe 不可用时返回 null
     */
    private static UnsafeAccess instance() {
      return INSTANCE;
    }

    /**
     * 反射解析 Unsafe 及其字段写入方法。
     *
     * @return 访问器，解析失败时返回 null
     */
    private static UnsafeAccess create() {
      try {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method objectFieldOffset = unsafeType.getMethod("objectFieldOffset", Field.class);
        Map<Class<?>, Method> primitivePuts = new HashMap<Class<?>, Method>();
        primitivePuts.put(boolean.class,
          unsafeType.getMethod("putBoolean", Object.class, long.class, boolean.class));
        primitivePuts.put(byte.class,
          unsafeType.getMethod("putByte", Object.class, long.class, byte.class));
        primitivePuts.put(short.class,
          unsafeType.getMethod("putShort", Object.class, long.class, short.class));
        primitivePuts.put(char.class,
          unsafeType.getMethod("putChar", Object.class, long.class, char.class));
        primitivePuts.put(int.class,
          unsafeType.getMethod("putInt", Object.class, long.class, int.class));
        primitivePuts.put(long.class,
          unsafeType.getMethod("putLong", Object.class, long.class, long.class));
        primitivePuts.put(float.class,
          unsafeType.getMethod("putFloat", Object.class, long.class, float.class));
        primitivePuts.put(double.class,
          unsafeType.getMethod("putDouble", Object.class, long.class, double.class));
        Method putObject = unsafeType.getMethod("putObject", Object.class, long.class,
          Object.class);
        return new UnsafeAccess(unsafe, objectFieldOffset, primitivePuts, putObject);
      }
      catch (Throwable ignored) {
        return null;
      }
    }

    /**
     * 使用 Unsafe 的字段偏移覆盖 final 字段值。
     *
     * @param field 目标字段
     * @param target 目标对象
     * @param value 字段值
     * @throws Exception 偏移计算或写入失败时抛出
     */
    private void put(Field field, Object target, Object value) throws Exception {
      try {
        long offset = ((Long) objectFieldOffset.invoke(unsafe, field)).longValue();
        Class<?> type = field.getType();
        if (!type.isPrimitive()) {
          putObject.invoke(unsafe, target, Long.valueOf(offset), value);
          return;
        }
        Method put = primitivePuts.get(type);
        if (put == null) {
          throw new IllegalStateException("Cannot migrate final field '"
            + field.getDeclaringClass().getName() + "." + field.getName()
            + "': unsupported primitive type " + type.getName());
        }
        put.invoke(unsafe, target, Long.valueOf(offset), value);
      }
      catch (IllegalStateException error) {
        throw error;
      }
      catch (Exception error) {
        throw new IllegalStateException("Cannot migrate final field '"
          + field.getDeclaringClass().getName() + "." + field.getName()
          + "' using Unsafe: " + error, error);
      }
    }
  }
}
