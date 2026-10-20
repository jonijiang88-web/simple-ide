package io.simpleide.hotswap.integration.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson effective class 适配插件。
 *
 * <p>该插件运行在应用类加载器中，因此可以直接实现 Jackson 2.13.5 的
 * BeanSerializerModifier/BeanDeserializerModifier。它让 Jackson 对 facade DTO 的
 * 序列化和反序列化使用当前版本类的字段和注解。
 *
 * <p>核心 agent 只负责加载插件和提供版本对象桥；本适配器不修改 Jackson 源码。
 */
public final class JacksonAdapterPlugin implements HotswapPlugin {

  /** 已安装模块的 ObjectMapper，按对象身份去重。 */
  private static final Map<Object, Boolean> INSTALLED =
      Collections.synchronizedMap(new IdentityHashMap<Object, Boolean>());

  @Override
  public void initialize(HotswapPluginContext context) {
    // 无需 transformer；首次 reload 时安装 Jackson Module。
  }

  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null) {
      return;
    }
    for (Object context : SpringContextBridge.contexts()) {
      try {
        installForContext(context);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] jackson adapter failed for "
          + event.className() + ": " + error);
      }
    }
  }

  /**
   * 查找并安装 context 中所有 ObjectMapper。
   *
   * @param context Spring ApplicationContext
   * @throws Exception 反射失败
   */
  private void installForContext(Object context) throws Exception {
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      ObjectMapper.class, Boolean.TRUE, Boolean.FALSE);
    if (names == null) {
      return;
    }
    for (String name : names) {
      Object bean;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable ignored) {
        continue;
      }
      if (bean instanceof ObjectMapper) {
        install((ObjectMapper) bean);
      }
    }
  }

  /**
   * 给一个 ObjectMapper 安装 effective class 模块。
   *
   * @param mapper ObjectMapper
   */
  private void install(ObjectMapper mapper) {
    synchronized (INSTALLED) {
      if (INSTALLED.containsKey(mapper)) {
        return;
      }
      SimpleModule module = new SimpleModule("simple-ide-jackson-effective",
        Version.unknownVersion());
      module.setSerializerModifier(new EffectiveSerializerModifier());
      module.setDeserializerModifier(new EffectiveDeserializerModifier());
      mapper.registerModule(module);
      clearDeserializationCache(mapper);
      INSTALLED.put(mapper, Boolean.TRUE);
    }
  }

  /**
   * 调用无参方法。
   *
   * @param target 目标对象
   * @param name 方法名
   * @return 返回值
   * @throws Exception 反射失败
   */
  private static Object invokeNoArg(Object target, String name) throws Exception {
    return invokeMethod(target, name, new Class<?>[0]);
  }

  /**
   * 调用实例方法。
   *
   * @param target 目标对象
   * @param name 方法名
   * @param types 参数类型
   * @param args 参数
   * @return 返回值
   * @throws Exception 反射失败
   */
  private static Object invokeMethod(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = findMethod(target.getClass(), name, types);
    if (method == null) {
      throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  /**
   * 在类层级中查找方法。
   *
   * @param type 类型
   * @param name 方法名
   * @param types 参数类型
   * @return 方法或 null
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
    }
    return null;
  }

  /**
   * 清理根部及内部反序列化缓存，确保新安装的 modifier 生效，包括非 Map 的 LRU 缓存。
   *
   * @param mapper Jackson ObjectMapper
   */
  private static void clearDeserializationCache(ObjectMapper mapper) {
    try {
      Object root = readField(mapper, "_rootDeserializers");
      clearMap(root);
      Object context = readField(mapper, "_deserializationContext");
      Object cache = readField(context, "_cache");
      invokeNoArg(cache, "flushCachedDeserializers");
      clearMap(cache);
      for (java.lang.reflect.Field field : cache.getClass().getDeclaredFields()) {
        if (java.util.Map.class.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          clearMap(field.get(cache));
        }
      }
    }
    catch (Throwable ignored) {
      // Jackson 字段差异由核心缓存插件兜底
    }
  }

  /**
   * 读取字段值。
   *
   * @param target 目标对象
   * @param name 字段名
   * @return 字段值或 null
   */
  private static Object readField(Object target, String name) {
    if (target == null) {
      return null;
    }
    for (Class<?> current = target.getClass(); current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        java.lang.reflect.Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      }
      catch (NoSuchFieldException ignored) {
        // 继续父类
      }
      catch (Throwable ignored) {
        return null;
      }
    }
    return null;
  }

  /**
   * 清空 Map。
   *
   * @param value 目标对象
   */
  private static void clearMap(Object value) {
    if (value instanceof Map) {
      ((Map<?, ?>) value).clear();
    }
  }

  /**
   * 判断类型是否是带 delegate 的 facade。
   *
   * @param type 类型
   * @return facade 返回 true
   */
  static boolean isFacade(Class<?> type) {
    if (type == null) {
      return false;
    }
    try {
      type.getDeclaredField("__sid_delegate");
      return true;
    }
    catch (NoSuchFieldException ignored) {
      return false;
    }
  }

  /** Jackson 序列化 modifier。 */
  private static final class EffectiveSerializerModifier extends BeanSerializerModifier {

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                               JsonSerializer<?> serializer) {
      if (isFacade(beanDesc.getBeanClass())) {
        @SuppressWarnings("unchecked")
        JsonSerializer<Object> delegate = (JsonSerializer<Object>) serializer;
        return new EffectiveSerializer(delegate);
      }
      return serializer;
    }
  }

  /** Jackson 反序列化 modifier。 */
  private static final class EffectiveDeserializerModifier extends BeanDeserializerModifier {

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                                                   BeanDescription beanDesc,
                                                   JsonDeserializer<?> deserializer) {
      if (isFacade(beanDesc.getBeanClass())) {
        return new EffectiveDeserializer(beanDesc.getBeanClass(), deserializer);
      }
      return deserializer;
    }
  }

  /** 把 facade 序列化委托给当前版本对象。 */
  private static final class EffectiveSerializer extends JsonSerializer<Object> {

    private final JsonSerializer<Object> delegate;

    /**
     * 创建序列化适配器。
     *
     * @param delegate 原序列化器
     */
    private EffectiveSerializer(JsonSerializer<Object> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      Object current;
      try {
        current = HotswapRuntime.currentVersionOrSelf(value);
      }
      catch (Throwable ignored) {
        current = value;
      }
      if (current == value || current == null) {
        delegate.serialize(value, generator, provider);
        return;
      }
      JsonSerializer<Object> serializer = provider.findValueSerializer(current.getClass(), null);
      serializer.serialize(current, generator, provider);
    }
  }

  /** 把版本对象反序列化结果包装为 facade。 */
  private static final class EffectiveDeserializer extends JsonDeserializer<Object> {

    private final Class<?> facadeClass;
    private final JsonDeserializer<?> delegate;

    /**
     * 创建反序列化适配器。
     *
     * @param facadeClass facade 类
     * @param delegate 原反序列化器
     */
    private EffectiveDeserializer(Class<?> facadeClass, JsonDeserializer<?> delegate) {
      this.facadeClass = facadeClass;
      this.delegate = delegate;
    }

    @Override
    public Object deserialize(com.fasterxml.jackson.core.JsonParser parser,
                              DeserializationContext context) throws IOException {
      Class<?> versionClass = HotswapRuntime.effectiveClass(facadeClass);
      if (versionClass == null || versionClass == facadeClass) {
        return delegate.deserialize(parser, context);
      }
      JsonDeserializer<Object> versionDeserializer =
        context.findRootValueDeserializer(context.constructType(versionClass));
      Object version = versionDeserializer.deserialize(parser, context);
      if (version == null) {
        return null;
      }
      try {
        return HotswapRuntime.wrapFacade(facadeClass, version);
      }
      catch (Exception error) {
        throw new IOException("cannot wrap facade " + facadeClass.getName(), error);
      }
    }
  }
}
