package io.simpleide.hotswap.integration.mybatis;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MyBatis 热替换插件。
 *
 * <p>agent 由系统类加载器加载，MyBatis 由应用类加载器加载，因此这里全部通过反射访问
 * MyBatis，不给 hotswap-agent 增加 MyBatis 编译依赖。
 *
 * <p>处理范围：
 * <ul>
 *   <li>ClassName 命中 mapper 命名空间（或 facade 是已注册的 mapper 接口）时，清理该
 *       namespace 下旧的 MappedStatement、ResultMap、sqlFragment 与二级缓存；</li>
 *   <li>用 MapperAnnotationBuilder 重新解析 mapper 注解；</li>
 *   <li>从 classloader 重新读取同名 mapper XML，并用 XMLMapperBuilder 重新解析；</li>
 *   <li>清理 MapperProxy/MapperProxyFactory 的 methodCache 以及 Reflector/MetaClass 缓存。</li>
 * </ul>
 *
 * <p>支持边界：
 * <ul>
 *   <li>当前 CLI 只提交 class 文件，XML 资源本身的变化不会触发 reload；插件只在任意
 *       mapper 类 reload 时顺带重读一次 XML，因此 XML 单独修改需要额外触发一次该类
 *       reload（或重启）。</li>
 *   <li>Mapper 接口新增/删除方法属于 JVM 结构限制，必须重启应用，插件不处理。</li>
 * </ul>
 */
public final class MyBatisPlugin implements HotswapPlugin {

  private static final String SQL_SESSION_FACTORY =
      "org.apache.ibatis.session.SqlSessionFactory";
  private static final String MAPPER_ANNOTATION_BUILDER =
      "org.apache.ibatis.builder.annotation.MapperAnnotationBuilder";
  private static final String XML_MAPPER_BUILDER =
      "org.apache.ibatis.builder.xml.XMLMapperBuilder";
  private static final String REFLECTOR = "org.apache.ibatis.reflection.Reflector";

  /** 元数据字段名：namespace 下的 MappedStatement。 */
  private static final String FIELD_MAPPED_STATEMENTS = "mappedStatements";
  /** 元数据字段名：namespace 下的 ResultMap。 */
  private static final String FIELD_RESULT_MAPS = "resultMaps";
  /** 元数据字段名：namespace 下的 sql 片段。 */
  private static final String FIELD_SQL_FRAGMENTS = "sqlFragments";
  /** 元数据字段名：namespace 下的二级缓存。 */
  private static final String FIELD_CACHES = "caches";
  /** 元数据字段名：namespace 的 cache-ref。 */
  private static final String FIELD_CACHE_REF_MAP = "cacheRefMap";
  /** 元数据字段名：已加载资源标记。 */
  private static final String FIELD_LOADED_RESOURCES = "loadedResources";
  /** 元数据字段名：MapperProxy/MapperProxyFactory 的方法缓存。 */
  private static final String FIELD_METHOD_CACHE = "methodCache";
  /** 元数据字段名：MapperRegistry 中已注册的 mapper 工厂。 */
  private static final String FIELD_KNOWN_MAPPERS = "knownMappers";

  /**
   * 接收一次类替换事件，刷新所有已捕获 Spring context 中的 MyBatis 元数据。
   *
   * @param event 替换事件；为空表示无可用信息
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null || event.facadeClass() == null) {
      return;
    }
    for (final Object context : SpringContextBridge.contexts()) {
      guarded("refresh context for " + event.className(), new ReloadAction() {
        @Override
        public void run() throws Exception {
          refreshContext(context, event);
        }
      });
    }
  }

  /**
   * 刷新单个 Spring context 中的 SqlSessionFactory。
   *
   * @param context Spring context
   * @param event reload 事件
   * @throws Exception 反射失败
   */
  private void refreshContext(Object context, HotswapReloadEvent event) throws Exception {
    ClassLoader loader = applicationLoader(context);
    Class<?> factoryType = loadClass(SQL_SESSION_FACTORY, loader);
    if (factoryType == null) {
      // 应用未使用 MyBatis 时静默跳过
      return;
    }
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    for (String name : beanNamesForType(beanFactory, factoryType)) {
      Object factory = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      if (factory == null) {
        continue;
      }
      Object configuration = invokeNoArg(factory, "getConfiguration");
      if (configuration == null) {
        continue;
      }
      refreshConfiguration(beanFactory, configuration, event);
    }
  }

  /**
   * 刷新一个 Configuration 中受本次 reload 影响的命名空间。
   *
   * @param beanFactory BeanFactory，用于清理已有 mapper bean 的代理缓存
   * @param configuration MyBatis Configuration
   * @param event reload 事件
   * @throws Exception 反射失败
   */
  private void refreshConfiguration(Object beanFactory, Object configuration,
                                    HotswapReloadEvent event) throws Exception {
    Set<String> namespaces = namespacesOf(configuration, event.facadeClass());
    if (namespaces.isEmpty()) {
      return;
    }
    // MyBatis 自身不对 Configuration 元数据的并发修改加锁，这里串行化插件内部刷新
    synchronized (configuration) {
      for (String namespace : namespaces) {
        refreshNamespace(configuration, namespace, event.facadeClass());
        guarded("clear mapper proxy for " + namespace, new BeanCacheAction(beanFactory,
          namespace, event.facadeClass()));
      }
    }
  }

  /**
   * 解析本次事件需要刷新的 MyBatis 命名空间。
   *
   * @param configuration MyBatis Configuration
   * @param facadeClass 变更类
   * @return 命名空间集合，未命中返回空集合
   * @throws Exception 反射失败
   */
  private Set<String> namespacesOf(Object configuration, Class<?> facadeClass)
      throws Exception {
    Set<String> result = new LinkedHashSet<String>();
    String className = facadeClass.getName();
    for (String name : keySet(configuration, FIELD_MAPPED_STATEMENTS)) {
      int index = name.lastIndexOf('.');
      if (index <= 0) {
        continue;
      }
      String namespace = name.substring(0, index);
      if (namespaceMatches(namespace, className)) {
        result.add(namespace);
      }
    }
    if (facadeClass.isInterface() && isKnownMapper(configuration, facadeClass)) {
      result.add(className);
    }
    return result;
  }

  /**
   * 判断 facade 类是否已注册为 MyBatis mapper。
   *
   * @param configuration MyBatis Configuration
   * @param facadeClass 变更类
   * @return 已注册返回 true
   * @throws Exception 反射失败
   */
  private boolean isKnownMapper(Object configuration, Class<?> facadeClass) throws Exception {
    Object registry = invokeNoArg(configuration, "getMapperRegistry");
    if (registry == null) {
      return false;
    }
    Object value = invokeMethod(registry, "hasMapper", new Class<?>[]{Class.class}, facadeClass);
    return Boolean.TRUE.equals(value);
  }

  /**
   * 刷新单个命名空间：清理旧元数据、重读 XML、重解析注解并清理代理/反射缓存。
   *
   * @param configuration MyBatis Configuration
   * @param namespace mapper 命名空间
   * @param mapperClass mapper 类型
   * @throws Exception 反射失败
   */
  private void refreshNamespace(Object configuration, String namespace, Class<?> mapperClass)
      throws Exception {
    clearNamespaceMetadata(configuration, namespace);
    clearMapperFactoryCache(configuration, namespace);
    clearReflectionCaches(configuration, mapperClass.getClassLoader(), namespace);
    reloadXml(configuration, namespace, mapperClass);
    reloadAnnotations(configuration, namespace, mapperClass);
  }

  /**
   * 清理命名空间下的 MyBatis 旧元数据。
   *
   * @param configuration MyBatis Configuration
   * @param namespace mapper 命名空间
   * @throws Exception 反射失败
   */
  private void clearNamespaceMetadata(Object configuration, String namespace)
      throws Exception {
    removeNamespaceKeys(configuration, FIELD_MAPPED_STATEMENTS, namespace);
    removeNamespaceKeys(configuration, FIELD_RESULT_MAPS, namespace);
    removeNamespaceKeys(configuration, FIELD_SQL_FRAGMENTS, namespace);
    Map<?, ?> caches = mapField(configuration, FIELD_CACHES);
    if (caches != null && caches.containsKey(namespace)) {
      Object cache = caches.get(namespace);
      if (cache != null) {
        clearCache(cache);
        caches.remove(namespace);
      }
    }
    Map<?, ?> cacheRefs = mapField(configuration, FIELD_CACHE_REF_MAP);
    if (cacheRefs != null) {
      cacheRefs.remove(namespace);
    }
    Set<?> resources = setField(configuration, FIELD_LOADED_RESOURCES);
    if (resources != null) {
      for (String resource : loadedResourcesFor(namespace)) {
        resources.remove(resource);
      }
    }
  }

  /**
   * 清理已注册 MapperProxyFactory 的 methodCache。
   *
   * @param configuration MyBatis Configuration
   * @param namespace mapper 命名空间
   * @throws Exception 反射失败
   */
  private void clearMapperFactoryCache(Object configuration, String namespace)
      throws Exception {
    Object registry = invokeNoArg(configuration, "getMapperRegistry");
    if (registry == null) {
      return;
    }
    Map<?, ?> known = mapField(registry, FIELD_KNOWN_MAPPERS);
    if (known == null) {
      return;
    }
    for (Map.Entry<?, ?> entry : known.entrySet()) {
      Object key = entry.getKey();
      if (key instanceof Class && namespaceMatches(namespace, ((Class<?>) key).getName())) {
        clearFieldsNamed(entry.getValue(), FIELD_METHOD_CACHE);
      }
    }
  }

  /**
   * 清理指定类型的 Reflector/MetaClass 缓存。
   *
   * @param configuration MyBatis Configuration
   * @param loader 应用类加载器
   * @param namespace mapper 命名空间
   * @throws Exception 反射失败
   */
  private void clearReflectionCaches(Object configuration, ClassLoader loader, String namespace)
      throws Exception {
    Set<String> typeNames = new LinkedHashSet<String>();
    typeNames.add(namespace);
    typeNames.add(namespace.replace('$', '.'));
    Object reflectorFactory = invokeNoArg(configuration, "getReflectorFactory");
    if (reflectorFactory != null) {
      clearTypeCaches(reflectorFactory, typeNames, false);
    }
    Class<?> reflectorType = loadClass(REFLECTOR, loader);
    if (reflectorType != null) {
      clearTypeCaches(reflectorType, typeNames, true);
    }
  }

  /**
   * 按字段名扫描对象中的 Map 缓存，并删除指定类型的缓存项。
   *
   * @param owner 缓存持有者，或静态字段所属类
   * @param typeNames 需要清理的类型名
   * @param staticFields 是否扫描静态字段
   * @throws Exception 反射失败
   */
  private void clearTypeCaches(Object owner, Set<String> typeNames, boolean staticFields)
      throws Exception {
    Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        boolean isStatic = (field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0;
        if (isStatic != staticFields || !isCacheFieldName(field.getName())) {
          continue;
        }
        if (!Map.class.isAssignableFrom(field.getType())) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(staticFields ? null : owner);
        if (!(value instanceof Map)) {
          continue;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        List<Object> staleKeys = new ArrayList<Object>();
        for (Object key : map.keySet()) {
          if (key instanceof Class && typeNames.contains(((Class<?>) key).getName())) {
            staleKeys.add(key);
          }
        }
        for (Object key : staleKeys) {
          map.remove(key);
        }
      }
    }
  }

  /**
   * 从 classloader 重新读取 mapper XML 并解析；XML 不存在时静默跳过。
   *
   * @param configuration MyBatis Configuration
   * @param namespace mapper 命名空间
   * @param mapperClass mapper 类型
   */
  private void reloadXml(final Object configuration, final String namespace,
                         final Class<?> mapperClass) {
    guarded("reload mapper xml for " + namespace, new ReloadAction() {
      @Override
      public void run() throws Exception {
        String resource = xmlResourceFor(namespace);
        Class<?> builderType = loadClass(XML_MAPPER_BUILDER, mapperClass.getClassLoader());
        if (builderType == null) {
          return;
        }
        InputStream stream = openResource(mapperClass, resource);
        if (stream == null) {
          // mapper 可能只使用注解，XML 不是必需资源
          return;
        }
        try {
          Object builder = newXMLMapperBuilder(builderType, stream, configuration, resource,
            mapField(configuration, FIELD_SQL_FRAGMENTS), namespace);
          if (builder == null) {
            throw new NoSuchMethodException(XML_MAPPER_BUILDER + " constructor");
          }
          invokeNoArg(builder, "parse");
        }
        finally {
          closeQuietly(stream);
        }
      }
    });
  }

  /**
   * 用 MapperAnnotationBuilder 重新解析 mapper 注解。
   *
   * @param configuration MyBatis Configuration
   * @param namespace mapper 命名空间
   * @param mapperClass mapper 类型
   */
  private void reloadAnnotations(final Object configuration, final String namespace,
                                 final Class<?> mapperClass) {
    guarded("reload mapper annotations for " + namespace, new ReloadAction() {
      @Override
      public void run() throws Exception {
        Class<?> builderType = loadClass(MAPPER_ANNOTATION_BUILDER,
          mapperClass.getClassLoader());
        if (builderType == null) {
          return;
        }
        Object builder = newMapperAnnotationBuilder(builderType, configuration, mapperClass);
        if (builder == null) {
          throw new NoSuchMethodException(MAPPER_ANNOTATION_BUILDER + " constructor");
        }
        invokeNoArg(builder, "parse");
      }
    });
  }

  /**
   * 构造 XMLMapperBuilder，兼容 3.5.x 的 5 参与 4 参构造。
   *
   * @param builderType XMLMapperBuilder 类型
   * @param stream XML 输入流
   * @param configuration Configuration
   * @param resource 资源路径
   * @param sqlFragments sql 片段集合
   * @param namespace 命名空间
   * @return builder 实例；无兼容构造返回 null
   * @throws Exception 构造失败
   */
  private Object newXMLMapperBuilder(Class<?> builderType, InputStream stream,
                                     Object configuration, String resource,
                                     Object sqlFragments, String namespace) throws Exception {
    Object fiveArg = null;
    Object fourArg = null;
    for (Constructor<?> constructor : builderType.getDeclaredConstructors()) {
      Class<?>[] types = constructor.getParameterTypes();
      if (types.length == 5 && types[0].isInstance(stream)
        && types[1].isInstance(configuration) && types[2] == String.class
        && Map.class.isAssignableFrom(types[3]) && types[4] == String.class) {
        constructor.setAccessible(true);
        fiveArg = constructor.newInstance(stream, configuration, resource, sqlFragments,
          namespace);
        break;
      }
      if (types.length == 4 && types[0].isInstance(stream)
        && types[1].isInstance(configuration) && types[2] == String.class
        && Map.class.isAssignableFrom(types[3])) {
        constructor.setAccessible(true);
        fourArg = constructor.newInstance(stream, configuration, resource, sqlFragments);
      }
    }
    return fiveArg != null ? fiveArg : fourArg;
  }

  /**
   * 构造 MapperAnnotationBuilder，兼容 (Configuration, Class) 与无参构造。
   *
   * @param builderType MapperAnnotationBuilder 类型
   * @param configuration Configuration
   * @param mapperClass mapper 类型
   * @return builder 实例；无兼容构造返回 null
   * @throws Exception 构造失败
   */
  private Object newMapperAnnotationBuilder(Class<?> builderType, Object configuration,
                                            Class<?> mapperClass) throws Exception {
    for (Constructor<?> constructor : builderType.getDeclaredConstructors()) {
      Class<?>[] types = constructor.getParameterTypes();
      if (types.length == 2 && types[0].isInstance(configuration) && types[1] == Class.class) {
        constructor.setAccessible(true);
        return constructor.newInstance(configuration, mapperClass);
      }
    }
    for (Constructor<?> constructor : builderType.getDeclaredConstructors()) {
      if (constructor.getParameterTypes().length == 0) {
        constructor.setAccessible(true);
        return constructor.newInstance();
      }
    }
    return null;
  }

  /**
   * 打开 mapper XML 资源。
   *
   * @param mapperClass mapper 类型
   * @param resource XML 资源路径
   * @return 输入流；不存在返回 null
   */
  private InputStream openResource(Class<?> mapperClass, String resource) {
    ClassLoader loader = mapperClass.getClassLoader();
    if (loader == null) {
      loader = ClassLoader.getSystemClassLoader();
    }
    InputStream stream = loader.getResourceAsStream(resource);
    if (stream == null) {
      stream = mapperClass.getResourceAsStream("/" + resource);
    }
    return stream;
  }

  /**
   * 关闭输入流并忽略关闭异常。
   *
   * @param stream 输入流
   */
  private void closeQuietly(InputStream stream) {
    try {
      stream.close();
    }
    catch (Throwable ignored) {
      // 关闭失败不影响元数据刷新
    }
  }

  /**
   * 返回 MyBatis 二级缓存命名空间对应的 XML 资源路径。
   *
   * @param namespace mapper 命名空间
   * @return 资源路径，如 com/demo/Mapper.xml
   */
  static String xmlResourceFor(String namespace) {
    return namespace.replace('.', '/') + ".xml";
  }

  /**
   * 判断 MyBatis 命名空间是否匹配变更类名。
   *
   * @param namespace mapper 命名空间
   * @param className 变更类名
   * @return 匹配返回 true
   */
  static boolean namespaceMatches(String namespace, String className) {
    if (namespace == null || className == null) {
      return false;
    }
    return namespace.equals(className) || namespace.equals(className.replace('$', '.'));
  }

  /**
   * 收集 namespace 下需要清理的元数据 key。
   *
   * @param keys 全部元数据 key
   * @param namespace mapper 命名空间
   * @return 属于该命名空间的 key
   */
  static List<String> idsInNamespace(Set<String> keys, String namespace) {
    List<String> result = new ArrayList<String>();
    if (keys == null || namespace == null) {
      return result;
    }
    for (String key : keys) {
      if (key != null && (key.equals(namespace) || key.startsWith(namespace + "."))) {
        result.add(key);
      }
    }
    return result;
  }

  /**
   * 计算重新解析前需要从 loadedResources 中移除的标记。
   *
   * @param namespace mapper 命名空间
   * @return 资源标记
   */
  static List<String> loadedResourcesFor(String namespace) {
    List<String> result = new ArrayList<String>();
    result.add("mapper:" + namespace);
    result.add("namespace:" + namespace);
    result.add("interface " + namespace);
    result.add(namespace);
    result.add(xmlResourceFor(namespace));
    return result;
  }

  /**
   * 判断字段名是否是 MyBatis 反射/代理缓存字段。
   *
   * @param name 字段名
   * @return 命中返回 true
   */
  static boolean isCacheFieldName(String name) {
    return FIELD_METHOD_CACHE.equals(name) || "reflectorMap".equals(name)
      || "classCache".equals(name);
  }

  /**
   * 删除命名空间下的元数据 key，忽略不存在的字段。
   *
   * @param target 元数据持有者
   * @param fieldName 字段名
   * @param namespace mapper 命名空间
   * @throws Exception 反射失败
   */
  private void removeNamespaceKeys(Object target, String fieldName, String namespace)
      throws Exception {
    Map<?, ?> map = mapField(target, fieldName);
    if (map == null) {
      return;
    }
    Set<String> keys = new LinkedHashSet<String>();
    for (Object key : map.keySet()) {
      if (key instanceof String) {
        keys.add((String) key);
      }
    }
    for (String key : idsInNamespace(keys, namespace)) {
      map.remove(key);
    }
  }

  /**
   * 从 Configuration 字段读取 key 集合。
   *
   * @param target Configuration
   * @param fieldName 字段名
   * @return key 集合，字段不存在返回空集合
   * @throws Exception 反射失败
   */
  private Set<String> keySet(Object target, String fieldName) throws Exception {
    Map<?, ?> map = mapField(target, fieldName);
    Set<String> keys = new LinkedHashSet<String>();
    if (map != null) {
      for (Object key : map.keySet()) {
        if (key instanceof String) {
          keys.add((String) key);
        }
      }
    }
    return keys;
  }

  /**
   * 按字段名读取 Map 字段。
   *
   * @param target 目标对象
   * @param fieldName 字段名
   * @return Map；字段不存在或类型不符返回 null
   * @throws Exception 反射失败
   */
  private Map<?, ?> mapField(Object target, String fieldName) throws Exception {
    Object value = fieldValue(target, fieldName);
    return value instanceof Map ? (Map<?, ?>) value : null;
  }

  /**
   * 按字段名读取 Set 字段。
   *
   * @param target 目标对象
   * @param fieldName 字段名
   * @return Set；字段不存在或类型不符返回 null
   * @throws Exception 反射失败
   */
  private Set<?> setField(Object target, String fieldName) throws Exception {
    Object value = fieldValue(target, fieldName);
    return value instanceof Set ? (Set<?>) value : null;
  }

  /**
   * 按字段名读取字段值。
   *
   * @param target 目标对象
   * @param fieldName 字段名
   * @return 字段值；不存在返回 null
   * @throws Exception 反射失败
   */
  private Object fieldValue(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    if (field == null) {
      return null;
    }
    field.setAccessible(true);
    return field.get(target);
  }

  /**
   * 清空 MyBatis Cache 实例。
   *
   * @param cache 二级缓存
   * @throws Exception 反射失败
   */
  private void clearCache(Object cache) throws Exception {
    invokeNoArg(cache, "clear");
  }

  /**
   * 清空对象及其父类中所有指定名称的 Map 字段。
   *
   * @param target 目标对象
   * @param fieldName 字段名
   * @throws Exception 反射失败
   */
  static void clearFieldsNamed(Object target, String fieldName) throws Exception {
    if (target == null) {
      return;
    }
    for (Class<?> current = target.getClass(); current != null && current != Object.class;
         current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (!fieldName.equals(field.getName()) || !Map.class.isAssignableFrom(field.getType())) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(target);
        if (value instanceof Map) {
          ((Map<?, ?>) value).clear();
        }
      }
    }
  }

  /**
   * 清理 Spring 中已有 mapper bean 的 MapperProxy methodCache。
   */
  private final class BeanCacheAction implements ReloadAction {

    private final Object beanFactory;
    private final String namespace;
    private final Class<?> mapperClass;

    /**
     * 创建 mapper bean 缓存清理动作。
     *
     * @param beanFactory BeanFactory
     * @param namespace mapper 命名空间
     * @param mapperClass mapper 类型
     */
    private BeanCacheAction(Object beanFactory, String namespace, Class<?> mapperClass) {
      this.beanFactory = beanFactory;
      this.namespace = namespace;
      this.mapperClass = mapperClass;
    }

    /**
     * 清理匹配类型的 mapper bean 缓存。
     *
     * @throws Exception 反射失败
     */
    @Override
    public void run() throws Exception {
      Class<?> mapperType = loadClass(namespace, mapperClass.getClassLoader());
      if (mapperType == null) {
        mapperType = mapperClass;
      }
      for (String name : beanNamesForType(beanFactory, mapperType)) {
        Object bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
        if (bean == null) {
          continue;
        }
        Object target = bean;
        if (Proxy.isProxyClass(bean.getClass())) {
          InvocationHandler handler = Proxy.getInvocationHandler(bean);
          target = handler;
        }
        clearFieldsNamed(target, FIELD_METHOD_CACHE);
      }
    }
  }

  /**
   * 查找指定类型的 bean 名称。
   *
   * @param beanFactory BeanFactory
   * @param type bean 类型
   * @return bean 名称
   * @throws Exception 反射失败
   */
  private String[] beanNamesForType(Object beanFactory, Class<?> type) throws Exception {
    Object value = invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      type, Boolean.TRUE, Boolean.FALSE);
    if (value instanceof String[]) {
      return (String[]) value;
    }
    return new String[0];
  }

  /**
   * 解析 context 使用的应用类加载器。
   *
   * @param context Spring context
   * @return 类加载器
   */
  private ClassLoader applicationLoader(Object context) {
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
   * 加载类，不存在时返回 null。
   *
   * @param className 类名
   * @param loader 类加载器
   * @return 类型；不存在返回 null
   */
  private Class<?> loadClass(String className, ClassLoader loader) {
    try {
      return Class.forName(className, false, loader);
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * 调用无参实例方法。
   *
   * @param target 目标对象
   * @param name 方法名
   * @return 返回值
   * @throws Exception 反射失败
   */
  private Object invokeNoArg(Object target, String name) throws Exception {
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
  private Object invokeMethod(Object target, String name, Class<?>[] types, Object... args)
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
  static Field findField(Class<?> type, String name) {
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

  /**
   * 执行一次刷新动作并隔离异常，异常只写到 System.err。
   *
   * @param action 刷新动作
   * @param description 动作描述
   */
  static void guarded(String description, ReloadAction action) {
    try {
      action.run();
    }
    catch (Throwable error) {
      System.err.println("[simple-ide-hotswap] mybatis " + description + " failed: " + error);
    }
  }

  /**
   * 可抛异常的刷新动作。
   */
  interface ReloadAction {

    /**
     * 执行刷新。
     *
     * @throws Exception 反射或 MyBatis 解析失败
     */
    void run() throws Exception;
  }
}
