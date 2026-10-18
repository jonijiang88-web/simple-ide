package io.simpleide.hotswap.integration.spring.cache;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Cache 元数据热替换插件。
 *
 * <p>Spring 的缓存注解在首次调用时会被解析并放入
 * {@code AbstractFallbackCacheOperationSource.attributeCache}，类替换后该缓存不会自动失效。
 * 本插件在每次 reload 后清空注解元数据缓存，使已有缓存方法的注解变化（如 value、key、
 * condition、unless 变更）在下次调用时生效。
 *
 * <p>插件只清理注解元数据，不清理 {@code CacheManager} 中的实际缓存数据，避免误清业务缓存。
 *
 * <p>支持边界：
 * <ul>
 *   <li>支持：已有缓存方法的注解变化，通过清空元数据缓存生效；</li>
 *   <li>不支持：新增缓存方法、新增 {@code @Cacheable} 方法或重建缓存代理，这些需要重建
 *       Spring 代理，不在本插件范围内。</li>
 * </ul>
 *
 * <p>Spring 由应用类加载器加载，本插件通过反射按类名访问，不引入 Spring 编译依赖。
 */
public final class CachePlugin implements HotswapPlugin {

  private static final String CACHE_INTERCEPTOR =
    "org.springframework.cache.interceptor.CacheInterceptor";
  private static final String CACHE_OPERATION_SOURCE =
    "org.springframework.cache.interceptor.CacheOperationSource";
  private static final String ABSTRACT_FALLBACK_SOURCE =
    "org.springframework.cache.interceptor.AbstractFallbackCacheOperationSource";
  private static final String ANNOTATION_SOURCE =
    "org.springframework.cache.interceptor.AnnotationCacheOperationSource";

  private static final String ATTRIBUTE_CACHE_FIELD = "attributeCache";
  private static final String OPERATION_SOURCES_FIELD = "cacheOperationSources";
  private static final String OPERATION_SOURCE_FIELD = "cacheOperationSource";

  /**
   * 处理一次类替换事件。
   *
   * <p>所有 {@link io.simpleide.hotswap.api.ChangeKind} 都统一清理解析元数据缓存；
   * 注解变化是主要目标，其余变更清理一次也无副作用。
   *
   * @param event 替换事件
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null) {
      return;
    }
    refreshAll(SpringContextBridge.contexts(), event);
  }

  /**
   * 遍历 context 列表逐个清理缓存元数据，单个 context 失败不影响其他 context。
   *
   * @param contexts Spring context 快照
   * @param event 替换事件
   */
  static void refreshAll(List<Object> contexts, HotswapReloadEvent event) {
    if (contexts == null || contexts.isEmpty()) {
      return;
    }
    for (Object context : contexts) {
      if (context == null) {
        continue;
      }
      try {
        refresh(context);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] cache metadata refresh failed for "
          + event.className() + " in " + context.getClass().getName() + ": "
          + describe(error));
      }
    }
  }

  /**
   * 清理单个 Spring context 中的缓存注解元数据。
   *
   * @param context Spring ApplicationContext
   * @throws Exception 反射失败
   */
  private static void refresh(Object context) throws Exception {
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    ClassLoader loader = context.getClass().getClassLoader();
    Set<Object> visited = newIdentitySet();
    List<String> typeNames = new ArrayList<String>();
    typeNames.add(CACHE_INTERCEPTOR);
    typeNames.add(CACHE_OPERATION_SOURCE);
    typeNames.add(ABSTRACT_FALLBACK_SOURCE);
    typeNames.add(ANNOTATION_SOURCE);
    for (String typeName : typeNames) {
      Class<?> type = loadClass(typeName, loader);
      if (type == null) {
        continue;
      }
      for (Object bean : beansOfType(beanFactory, type)) {
        try {
          if (CACHE_INTERCEPTOR.equals(type.getName())) {
            installVersionAwareSource(bean, loader);
          }
          clearOperationSource(bean, visited);
        }
        catch (Throwable error) {
          System.err.println("[simple-ide-hotswap] cannot clear cache metadata of "
            + bean.getClass().getName() + ": " + describe(error));
        }
      }
    }
  }

  /**
   * 为 CacheInterceptor 安装版本感知的 CacheOperationSource 包装器。
   *
   * <p>CacheInterceptor 创建时缓存了 facade 方法上的注解；版本类新增或修改
   * {@code @Cacheable} 等注解后，直接把 facade 方法交给 Spring 仍然读到旧注解。
   * 包装器在查询缓存操作时把 facade 方法映射到当前版本类方法。
   *
   * @param interceptor CacheInterceptor
   * @param loader 应用类加载器
   * @throws Exception 反射失败
   */
  private static void installVersionAwareSource(Object interceptor, ClassLoader loader)
      throws Exception {
    Field field = findField(interceptor.getClass(), OPERATION_SOURCE_FIELD);
    if (field == null) {
      return;
    }
    field.setAccessible(true);
    Object source = field.get(interceptor);
    if (source == null || Proxy.isProxyClass(source.getClass())) {
      return;
    }
    Class<?> sourceType = loadClass(CACHE_OPERATION_SOURCE, loader);
    if (sourceType == null || !sourceType.isInstance(source)) {
      return;
    }
    Object wrapped = Proxy.newProxyInstance(loader, new Class<?>[]{sourceType},
      new VersionAwareCacheOperationHandler(source));
    field.set(interceptor, wrapped);
    try {
      invokeNoArg(interceptor, "clearMetadataCache");
    }
    catch (Throwable ignored) {
      // 老版本没有该方法时仅依赖属性缓存清理
    }
  }

  /**
   * 版本感知缓存操作源处理器。
   */
  private static final class VersionAwareCacheOperationHandler implements InvocationHandler {

    private final Object delegate;

    /**
     * 创建处理器。
     *
     * @param delegate 原 CacheOperationSource
     */
    private VersionAwareCacheOperationHandler(Object delegate) {
      this.delegate = delegate;
    }

    /**
     * 把 facade 方法/类型映射到版本类后委托原操作源。
     *
     * @param proxy 代理对象
     * @param method 被调方法
     * @param args 参数
     * @return 结果
     * @throws Throwable 委托异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object[] actual = args == null ? new Object[0] : args;
      if ("getCacheOperations".equals(method.getName()) && actual.length == 2
        && actual[0] instanceof Method && actual[1] instanceof Class) {
        Method original = (Method) actual[0];
        Class<?> targetClass = (Class<?>) actual[1];
        Class<?> effectiveClass = effectiveClass(targetClass);
        Method effectiveMethod = findCompatibleMethod(effectiveClass, original);
        return invokeDelegate(method, new Object[]{effectiveMethod, effectiveClass});
      }
      return invokeDelegate(method, actual);
    }

    /**
     * 调用原操作源。
     *
     * @param method 接口方法
     * @param args 参数
     * @return 结果
     * @throws Throwable 委托异常
     */
    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(delegate, args);
      }
      catch (InvocationTargetException error) {
        Throwable cause = error.getCause();
        throw cause == null ? error : cause;
      }
    }

    /**
     * 解析 facade 对应的当前版本类。
     *
     * @param targetClass 原类型
     * @return 当前版本类或原类型
     */
    private Class<?> effectiveClass(Class<?> targetClass) {
      Class<?> effective = HotswapRuntime.effectiveClass(targetClass);
      if (effective != null && effective != targetClass) {
        return effective;
      }
      for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
        effective = HotswapRuntime.effectiveClass(current);
        if (effective != null && effective != current) {
          return effective;
        }
      }
      return targetClass;
    }

    /**
     * 在目标类型中查找同名同参数方法。
     *
     * @param type 目标类型
     * @param source 原方法
     * @return 方法；找不到返回原方法
     */
    private Method findCompatibleMethod(Class<?> type, Method source) {
      for (Class<?> current = type; current != null && current != Object.class;
           current = current.getSuperclass()) {
        try {
          Method candidate = current.getDeclaredMethod(source.getName(), source.getParameterTypes());
          candidate.setAccessible(true);
          return candidate;
        }
        catch (NoSuchMethodException ignored) {
          // 继续父类
        }
      }
      return source;
    }
  }

  /**
   * 递归清理一个缓存操作源对象上的注解元数据。
   *
   * <p>兼容 {@code AbstractFallbackCacheOperationSource} 的 {@code attributeCache}、
   * {@code CompositeCacheOperationSource} 的 {@code cacheOperationSources}，以及
   * {@code CacheAspectSupport} 的 {@code cacheOperationSource} 字段。
   *
   * @param source 缓存操作源或缓存拦截器
   */
  static void clearOperationSource(Object source) {
    if (source == null) {
      return;
    }
    try {
      clearOperationSource(source, newIdentitySet());
    }
    catch (Exception error) {
      System.err.println("[simple-ide-hotswap] cannot clear cache metadata of "
        + source.getClass().getName() + ": " + describe(error));
    }
  }

  /**
   * 递归清理一个缓存操作源对象上的注解元数据，使用 visited 防止循环引用。
   *
   * @param source 缓存操作源或缓存拦截器
   * @param visited 已访问对象集合，按引用去重
   * @throws Exception 反射失败
   */
  private static void clearOperationSource(Object source, Set<Object> visited) throws Exception {
    if (source == null || !visited.add(source)) {
      return;
    }
    Field attributeCache = findField(source.getClass(), ATTRIBUTE_CACHE_FIELD);
    if (attributeCache != null && Map.class.isAssignableFrom(attributeCache.getType())) {
      attributeCache.setAccessible(true);
      Object value = attributeCache.get(source);
      if (value instanceof Map) {
        ((Map<?, ?>) value).clear();
      }
    }
    Field operationSources = findField(source.getClass(), OPERATION_SOURCES_FIELD);
    if (operationSources != null) {
      operationSources.setAccessible(true);
      Object value = operationSources.get(source);
      if (value instanceof Collection) {
        for (Object item : (Collection<?>) value) {
          clearOperationSource(item, visited);
        }
      }
      else if (value instanceof Object[]) {
        for (Object item : (Object[]) value) {
          clearOperationSource(item, visited);
        }
      }
    }
    Field operationSource = findField(source.getClass(), OPERATION_SOURCE_FIELD);
    if (operationSource != null) {
      operationSource.setAccessible(true);
      clearOperationSource(operationSource.get(source), visited);
    }
  }

  /**
   * 查找指定类型的 Spring bean，兼容 CGLIB 代理与按类名匹配。
   *
   * @param beanFactory BeanFactory
   * @param type 目标类型
   * @return bean 实例
   * @throws Exception 反射失败
   */
  private static List<Object> beansOfType(Object beanFactory, Class<?> type) throws Exception {
    List<Object> result = new ArrayList<Object>();
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      type, Boolean.TRUE, Boolean.FALSE);
    if (names == null) {
      return result;
    }
    for (String name : names) {
      Object bean = null;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable ignored) {
        // FactoryBean 创建失败或非单例时跳过
      }
      if (bean != null && hasTypeName(bean.getClass(), type.getName())) {
        result.add(bean);
      }
    }
    return result;
  }

  /**
   * 判断类型层级（含接口）中是否包含指定类名，避免跨类加载器的 Class 身份差异。
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
   * 将反射包装异常展开为真实原因，便于定位失败。
   *
   * @param error 原始异常
   * @return 异常描述
   */
  private static String describe(Throwable error) {
    if (error instanceof InvocationTargetException && error.getCause() != null) {
      return String.valueOf(error.getCause());
    }
    return String.valueOf(error);
  }

  /**
   * 加载应用类加载器下的 Spring 类型，缺失时返回 null。
   *
   * @param className 类名
   * @param loader 应用类加载器
   * @return 类型；未找到返回 null
   */
  private static Class<?> loadClass(String className, ClassLoader loader) {
    try {
      return Class.forName(className, false, loader);
    }
    catch (Throwable error) {
      // 非 Spring Cache 应用或版本差异时插件保持静默
      return null;
    }
  }

  /**
   * 创建按引用去重的集合。
   *
   * @return 集合
   */
  private static Set<Object> newIdentitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
  }

  /**
   * 调用无参实例方法。
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
    }
    return null;
  }

  /**
   * 在类层级中查找字段。
   *
   * @param type 类型
   * @param name 字段名
   * @return 字段；未找到返回 null
   */
  private static Field findField(Class<?> type, String name) {
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        return current.getDeclaredField(name);
      }
      catch (NoSuchFieldException ignored) {
        // 继续父类
      }
    }
    return null;
  }
}
