package io.simpleide.hotswap.integration.jackson;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Jackson 热替换插件：清理 Spring 容器中 ObjectMapper / XmlMapper 的元数据缓存。
 *
 * <p>agent 由系统类加载器加载，Jackson 由应用类加载器加载，因此本插件只通过反射和
 * bean 类型名访问 Jackson，不在 agent 编译期引入 Jackson 依赖。
 *
 * <p>当前负责清理 Jackson 2.13.5 的序列化器/反序列化器元数据缓存：
 * <ul>
 *   <li>ObjectMapper 的 root 反序列化器、SerializerProvider、DeserializationContext；</li>
 *   <li>DefaultSerializerProvider 的序列化器缓存；</li>
 *   <li>DeserializerCache 的反序列化器缓存；</li>
 *   <li>TypeFactory 的类型缓存。</li>
 * </ul>
 *
 * <p>支持边界：本插件只清理 Jackson 已缓存的元数据，使已有 DTO 的方法体或注解变化生效；
 * facade 上反射不可见的新增 DTO 字段（例如版本类新增字段）需要后续 effective class 适配，
 * 不在本插件范围内。
 *
 * <p>所有反射操作均容错：字段名或方法在目标 Jackson 版本中不存在时静默跳过，单个 context
 * 或 bean 失败只记录到 {@code System.err}，不影响热替换主流程。
 */
public class JacksonPlugin implements HotswapPlugin {

  /** Jackson 数据绑定 ObjectMapper 类名。 */
  private static final String OBJECT_MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";

  /** Jackson XML 扩展 XmlMapper 类名；未引入 jackson-dataformat-xml 时忽略。 */
  private static final String XML_MAPPER = "com.fasterxml.jackson.dataformat.xml.XmlMapper";

  /** ObjectMapper 的公共缓存清理方法名。 */
  private static final String[] MAPPER_FLUSH_METHODS = {"flushCaches"};

  /** SerializerProvider 的公共缓存清理方法名。 */
  private static final String[] SERIALIZER_FLUSH_METHODS = {"flushCaches"};

  /** SerializerCache 的公共缓存清理方法名。 */
  private static final String[] SERIALIZER_CACHE_FLUSH_METHODS = {"flush", "flushCaches"};

  /** DeserializerCache 的公共缓存清理方法名。 */
  private static final String[] DESERIALIZER_FLUSH_METHODS = {"flushCaches"};

  /** TypeFactory 的公共缓存清理方法名。 */
  private static final String[] TYPE_FLUSH_METHODS = {"clearCache", "flushCaches"};

  /**
   * 接收一次类替换完成事件，刷新所有已捕获 Spring context 中的 Jackson 缓存。
   *
   * @param event 替换事件
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null) {
      return;
    }
    for (Object context : springContexts()) {
      try {
        refresh(context);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] jackson cache flush failed for "
          + event.className() + ": " + error);
      }
    }
  }

  /**
   * 返回已捕获的 Spring context；测试可覆写以注入 context。
   *
   * @return context 列表
   */
  List<Object> springContexts() {
    return SpringContextBridge.contexts();
  }

  /**
   * 刷新单个 Spring context 中的 Jackson 缓存。
   *
   * @param context Spring ApplicationContext
   */
  void refresh(Object context) {
    if (context == null) {
      return;
    }
    Object beanFactory;
    try {
      beanFactory = invokeNoArg(context, "getBeanFactory");
    }
    catch (Throwable error) {
      throw new IllegalStateException("cannot access bean factory", error);
    }
    if (beanFactory == null) {
      return;
    }
    ClassLoader loader = classLoaderOf(context);
    List<Object> mappers = new ArrayList<Object>();
    for (String typeName : new String[]{OBJECT_MAPPER, XML_MAPPER}) {
      Class<?> type = loadClass(typeName, loader);
      if (type != null) {
        collectBeans(beanFactory, type, typeName, mappers);
      }
    }
    for (Object mapper : mappers) {
      try {
        flushMapper(mapper);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] jackson cache flush failed for "
          + mapper.getClass().getName() + ": " + error);
      }
    }
  }

  /**
   * 查找并收集指定类型的 Jackson bean。
   *
   * @param beanFactory Spring BeanFactory
   * @param type Jackson bean 类型
   * @param typeName 用于兼容 CGLIB/子类的类型名
   * @param collected 已收集的 bean，跨类型去重
   */
  void collectBeans(Object beanFactory, Class<?> type, String typeName, List<Object> collected) {
    String[] names;
    try {
      names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
        new Class<?>[]{Class.class, boolean.class, boolean.class},
        type, Boolean.TRUE, Boolean.FALSE);
    }
    catch (Throwable error) {
      System.err.println("[simple-ide-hotswap] jackson bean lookup failed for "
        + typeName + ": " + error);
      return;
    }
    if (names == null || names.length == 0) {
      return;
    }
    for (String name : names) {
      Object bean;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] jackson bean lookup failed for "
          + name + ": " + error);
        continue;
      }
      if (bean == null || !matchesType(bean.getClass(), type, typeName)
        || contains(collected, bean)) {
        continue;
      }
      collected.add(bean);
    }
  }

  /**
   * 清理单个 ObjectMapper 实例的 Jackson 2.13.5 缓存。
   *
   * <p>优先调用公开的 {@code flushCaches()} 等方法，再按字段名清理作为兜底；
   * 字段或方法缺失不会抛出异常。
   *
   * @param mapper ObjectMapper 或其子类实例
   */
  void flushMapper(Object mapper) {
    invokeFlushMethods(mapper, MAPPER_FLUSH_METHODS);
    clearMapField(mapper, "_rootDeserializers");

    Object serializerProvider = readField(mapper, "_serializerProvider");
    if (serializerProvider != null) {
      invokeFlushMethods(serializerProvider, SERIALIZER_FLUSH_METHODS);
      clearMapField(serializerProvider, "_serializerCache");
      clearMapField(serializerProvider, "_knownSerializers");
      clearMapField(serializerProvider, "_rootDeserializers");

      Object serializerCache = readField(serializerProvider, "_serializerCache");
      if (serializerCache != null) {
        invokeFlushMethods(serializerCache, SERIALIZER_CACHE_FLUSH_METHODS);
        clearMapFields(serializerCache);
      }
    }

    Object deserializationContext = readField(mapper, "_deserializationContext");
    if (deserializationContext != null) {
      Object deserializerCache = readField(deserializationContext, "_cache");
      if (deserializerCache == null) {
        deserializerCache = readField(deserializationContext, "_deserializerCache");
      }
      if (deserializerCache != null) {
        invokeFlushMethods(deserializerCache, DESERIALIZER_FLUSH_METHODS);
        clearMapField(deserializerCache, "_cachedDeserializers");
        clearMapField(deserializerCache, "_incompleteDeserializers");
        // 兼容 Jackson 版本间的字段改名：清理该缓存持有者上的所有 Map/Collection 字段
        clearMapFields(deserializerCache);
      }
    }

    Object typeFactory = readField(mapper, "_typeFactory");
    if (typeFactory != null) {
      invokeFlushMethods(typeFactory, TYPE_FLUSH_METHODS);
      clearMapField(typeFactory, "_typeCache");
      clearMapField(typeFactory, "_recursiveTypeCache");
    }
  }

  /**
   * 按类层级查找字段。
   *
   * @param type 起始类型
   * @param name 字段名
   * @return 字段；未找到返回 null
   */
  static Field findField(Class<?> type, String name) {
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        return current.getDeclaredField(name);
      }
      catch (NoSuchFieldException ignored) {
        // 继续父类
      }
      catch (Throwable ignored) {
        // 字段不可见时按缺失处理
      }
    }
    return null;
  }

  /**
   * 反射读取字段值，字段缺失时返回 null。
   *
   * @param target 目标对象
   * @param name 字段名
   * @return 字段值；缺失或不可访问返回 null
   */
  static Object readField(Object target, String name) {
    if (target == null) {
      return null;
    }
    Field field = findField(target.getClass(), name);
    if (field == null) {
      return null;
    }
    try {
      field.setAccessible(true);
      return field.get(target);
    }
    catch (Throwable ignored) {
      // 字段不可访问时按缺失处理，避免中断 reload
      return null;
    }
  }

  /**
   * 清空指定字段引用的 Map/Collection，字段缺失或类型不符时返回 false。
   *
   * @param target 目标对象
   * @param name 字段名
   * @return 已清空返回 true
   */
  static boolean clearMapField(Object target, String name) {
    if (target == null) {
      return false;
    }
    Field field = findField(target.getClass(), name);
    if (field == null) {
      return false;
    }
    try {
      field.setAccessible(true);
      return clearValue(field.get(target));
    }
    catch (Throwable ignored) {
      // 字段不可访问时按未清理处理，避免中断 reload
      return false;
    }
  }

  /**
   * 清空对象所有 Map/Collection 字段，用于字段名可能变化的缓存持有者。
   *
   * @param target 目标对象
   * @return 已清理的字段数量
   */
  static int clearMapFields(Object target) {
    if (target == null) {
      return 0;
    }
    int cleared = 0;
    for (Class<?> current = target.getClass(); current != null && current != Object.class;
         current = current.getSuperclass()) {
      Field[] fields;
      try {
        fields = current.getDeclaredFields();
      }
      catch (Throwable ignored) {
        continue;
      }
      for (Field field : fields) {
        try {
          field.setAccessible(true);
          if (clearValue(field.get(target))) {
            cleared++;
          }
        }
        catch (Throwable ignored) {
          // 单个字段不可访问时跳过
        }
      }
    }
    return cleared;
  }

  /**
   * 清空 Map/Collection 值。
   *
   * @param value 字段值
   * @return 已清空返回 true
   */
  private static boolean clearValue(Object value) {
    if (value instanceof Map) {
      ((Map<?, ?>) value).clear();
      return true;
    }
    if (value instanceof Collection) {
      ((Collection<?>) value).clear();
      return true;
    }
    return false;
  }

  /**
   * 依次尝试调用无参缓存清理方法；方法缺失或调用失败时忽略。
   *
   * @param target 目标对象
   * @param names 候选方法名
   */
  private static void invokeFlushMethods(Object target, String[] names) {
    if (target == null) {
      return;
    }
    for (String name : names) {
      try {
        Method method = findMethod(target.getClass(), name, new Class<?>[0]);
        if (method != null) {
          method.setAccessible(true);
          method.invoke(target);
        }
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] jackson cache flush method failed for "
          + name + ": " + error);
      }
    }
  }

  /**
   * 判断 bean 类型是否命中目标类型，兼容子类和 CGLIB 代理。
   *
   * @param beanType bean 实际类型
   * @param type 目标类型
   * @param typeName 目标类型名
   * @return 命中返回 true
   */
  private static boolean matchesType(Class<?> beanType, Class<?> type, String typeName) {
    return type.isAssignableFrom(beanType) || hasTypeName(beanType, typeName);
  }

  /**
   * 判断类型层级中是否包含指定类名，兼容反射不可见的代理类。
   *
   * @param type 类型
   * @param name 目标类名
   * @return 命中返回 true
   */
  static boolean hasTypeName(Class<?> type, String name) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (current.getName().equals(name)) {
        return true;
      }
      for (Class<?> iface : current.getInterfaces()) {
        if (iface.getName().equals(name)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 判断列表中是否已包含同一 bean。
   *
   * @param beans bean 列表
   * @param candidate 候选 bean
   * @return 已包含返回 true
   */
  private static boolean contains(List<Object> beans, Object candidate) {
    for (Object bean : beans) {
      if (bean == candidate) {
        return true;
      }
    }
    return false;
  }

  /**
   * 返回 context 的类加载器，缺失时回退到线程上下文类加载器。
   *
   * @param context Spring context
   * @return 类加载器
   */
  private static ClassLoader classLoaderOf(Object context) {
    ClassLoader loader = context.getClass().getClassLoader();
    if (loader == null) {
      loader = Thread.currentThread().getContextClassLoader();
    }
    if (loader == null) {
      loader = ClassLoader.getSystemClassLoader();
    }
    return loader;
  }

  /**
   * 按类名加载可选类型，未引入对应依赖时返回 null。
   *
   * @param className 类名
   * @param loader 类加载器
   * @return 类型；不存在返回 null
   */
  private static Class<?> loadClass(String className, ClassLoader loader) {
    try {
      return Class.forName(className, false, loader);
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * 调用无参实例方法，方法缺失时返回 null。
   *
   * @param target 目标对象
   * @param name 方法名
   * @return 返回值
   * @throws Exception 方法调用失败
   */
  private static Object invokeNoArg(Object target, String name) throws Exception {
    return invokeMethod(target, name, new Class<?>[0]);
  }

  /**
   * 调用实例方法，方法缺失时返回 null。
   *
   * @param target 目标对象
   * @param name 方法名
   * @param types 参数类型
   * @param args 参数
   * @return 返回值；方法缺失返回 null
   * @throws Exception 方法调用失败
   */
  private static Object invokeMethod(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = findMethod(target.getClass(), name, types);
    if (method == null) {
      return null;
    }
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  /**
   * 在类层级中查找方法。
   *
   * @param type 起始类型
   * @param name 方法名
   * @param types 参数类型
   * @return 方法；未找到返回 null
   */
  private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        return current.getDeclaredMethod(name, types);
      }
      catch (NoSuchMethodException ignored) {
        // 继续父类
      }
      catch (Throwable ignored) {
        // 方法不可见时按缺失处理
      }
    }
    return null;
  }
}
