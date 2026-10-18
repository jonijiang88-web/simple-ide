package io.simpleide.hotswap.integration.spring;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.api.HotswapNames;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring MVC 请求映射热替换插件。
 *
 * <p>只负责 MVC 层的元数据刷新：Controller 注解或方法签名变化后，重建
 * {@code RequestMappingHandlerMapping} 中属于该 Controller 的映射，并清理
 * {@code ExceptionHandlerExceptionResolver} 的 advice 缓存。
 *
 * <p>所有 Spring 访问均通过反射完成，agent 不增加 Spring 编译依赖，固定版本为
 * Spring Boot 2.7.18 / Spring Framework 5.3.31。context 捕获由 Boot 插件负责，
 * 本插件不注册任何 transformer。
 */
public final class SpringMvcPlugin implements HotswapPlugin {

  /** Spring MVC 请求映射注册表。 */
  private static final String REQUEST_MAPPING_HANDLER_MAPPING =
      "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping";

  /** Spring MVC 异常处理器解析器。 */
  private static final String EXCEPTION_HANDLER_RESOLVER =
      "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";

  /** 异常处理器 advice 缓存字段名。 */
  private static final String EXCEPTION_ADVICE_CACHE = "exceptionHandlerAdviceCache";

  /** 日志前缀。 */
  private static final String LOG_PREFIX = "[simple-ide-hotswap] spring mvc ";

  /**
   * 接收热替换完成事件，只处理注解变更与方法签名变更。
   *
   * @param event 替换事件
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    reload(SpringContextBridge.contexts(), event);
  }

  /**
   * 对给定 context 列表执行 MVC 刷新，异常全部在此隔离。
   *
   * <p>独立于 {@link SpringContextBridge} 便于单元测试注入 context 列表。
   *
   * @param contexts Spring context 列表
   * @param event 替换事件
   */
  void reload(List<Object> contexts, HotswapReloadEvent event) {
    if (event == null || event.facadeClass() == null || !supports(event.changeKind())) {
      return;
    }
    if (contexts == null) {
      return;
    }
    for (Object context : contexts) {
      try {
        refresh(context, event);
      }
      catch (Throwable error) {
        log("refresh failed for " + event.className() + ": " + error);
      }
    }
  }

  /**
   * 判断变更类型是否需要重建请求映射。
   *
   * <p>方法体变化不改变映射元数据，因此不重建。
   *
   * @param kind 变更类型
   * @return 需要刷新返回 true
   */
  static boolean supports(ChangeKind kind) {
    return kind == ChangeKind.ANNOTATION || kind == ChangeKind.SIGNATURE
      || kind == ChangeKind.SCHEMA;
  }

  /**
   * 刷新单个 Spring context 中的 MVC 映射与异常处理器缓存。
   *
   * @param context Spring context
   * @param event 替换事件
   * @throws Exception 反射调用失败
   */
  private void refresh(Object context, HotswapReloadEvent event) throws Exception {
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    ClassLoader loader = context.getClass().getClassLoader();
    Class<?> mappingType = loadClass(loader, REQUEST_MAPPING_HANDLER_MAPPING);
    if (mappingType == null) {
      return;
    }
    Object handler = resolveHandler(beanFactory, event.facadeClass());
    if (handler == null) {
      // 变更类不是容器 bean 时不重建，避免删除已有映射却无法注册新映射
      return;
    }
    for (String name : beanNamesOfType(beanFactory, mappingType)) {
      try {
        Object mapping = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
        if (mapping != null) {
          rebuildMapping(mapping, event, handler);
        }
      }
      catch (Throwable error) {
        log("rebuild mapping " + name + " failed for " + event.className() + ": " + error);
      }
    }
    clearExceptionHandlerCache(loader, beanFactory);
  }

  /**
   * 查找变更类对应的容器 bean，作为新映射的 handler。
   *
   * @param beanFactory BeanFactory
   * @param facadeClass 变更的 facade 类
   * @return bean 实例；不存在返回 null
   * @throws Exception 反射调用失败
   */
  private Object resolveHandler(Object beanFactory, Class<?> facadeClass) throws Exception {
    for (String name : beanNamesOfType(beanFactory, facadeClass)) {
      Object bean = null;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable ignored) {
        // 非单例或 FactoryBean 创建失败时跳过
      }
      if (bean != null && matchesFacade(bean.getClass(), facadeClass)) {
        return bean;
      }
    }
    return null;
  }

  /**
   * 判断 bean 类型是否为变更类本身或它的代理。
   *
   * @param beanType bean 实际类型
   * @param facadeClass 变更的 facade 类
   * @return 命中返回 true
   */
  private boolean matchesFacade(Class<?> beanType, Class<?> facadeClass) {
    if (facadeClass.isAssignableFrom(beanType)) {
      return true;
    }
    String facadeName = facadeClass.getName();
    for (Class<?> current = beanType; current != null; current = current.getSuperclass()) {
      if (facadeName.equals(current.getName())) {
        return true;
      }
      for (Class<?> iface : current.getInterfaces()) {
        if (facadeName.equals(iface.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 按类型查询 bean 名称，不触发 bean 实例化。
   *
   * @param beanFactory BeanFactory
   * @param type 目标类型
   * @return bean 名称数组
   * @throws Exception 反射调用失败
   */
  private String[] beanNamesOfType(Object beanFactory, Class<?> type) throws Exception {
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
        new Class<?>[]{Class.class, boolean.class, boolean.class},
        type, Boolean.TRUE, Boolean.FALSE);
    return names == null ? new String[0] : names;
  }

  /**
   * 重建单个 RequestMappingHandlerMapping 中属于变更 Controller 的条目。
   *
   * @param mapping RequestMappingHandlerMapping
   * @param event 替换事件
   * @param handler 新的 handler 对象
   * @throws Exception 反射调用失败
   */
  private void rebuildMapping(Object mapping, HotswapReloadEvent event, Object handler)
      throws Exception {
    Map<?, ?> handlers = (Map<?, ?>) invokeNoArg(mapping, "getHandlerMethods");
    if (handlers == null) {
      return;
    }
    // 先准备新 handler，避免版本对象创建失败时已删除旧映射
    Class<?> versionClass = HotswapRuntime.effectiveClass(event.facadeClass());
    if (versionClass == null) {
      versionClass = event.facadeClass();
    }
    Object versionHandler = HotswapRuntime.currentVersionOrSelf(handler);
    for (Object oldMapping : staleMappings(handlers, event.facadeClass().getName())) {
      invokeMethod(mapping, "unregisterMapping", new Class<?>[]{Object.class}, oldMapping);
    }
    for (Method method : versionClass.getDeclaredMethods()) {
      Object mappingInfo = invokeMethod(mapping, "getMappingForMethod",
          new Class<?>[]{Method.class, Class.class}, method, versionClass);
      if (mappingInfo == null) {
        continue;
      }
      invokeMethod(mapping, "registerHandlerMethod",
          new Class<?>[]{Object.class, Method.class, mappingInfo.getClass()},
          versionHandler, method, mappingInfo);
    }
  }

  /**
   * 从 handler 映射表中筛出属于指定 Controller 的旧映射键。
   *
   * <p>同时匹配变更类本身和它的版本类（{@code 类名$$SIDEVn}），否则上一次 reload
   * 注册的版本类映射不会被清理，重复 reload 会触发映射冲突。
   *
   * @param handlerMethods {@code getHandlerMethods()} 返回的映射表
   * @param facadeName 变更类名
   * @return 旧映射键列表
   */
  static List<Object> staleMappings(Map<?, ?> handlerMethods, String facadeName) {
    List<Object> stale = new ArrayList<Object>();
    if (handlerMethods == null || facadeName == null) {
      return stale;
    }
    String versionPrefix = facadeName + HotswapNames.VERSION_PREFIX;
    for (Map.Entry<?, ?> entry : handlerMethods.entrySet()) {
      Object handlerMethod = entry.getValue();
      if (handlerMethod == null) {
        continue;
      }
      Object beanType = null;
      try {
        beanType = invokeNoArg(handlerMethod, "getBeanType");
      }
      catch (Throwable error) {
        log("read handler beanType failed: " + error);
      }
      if (beanType instanceof Class) {
        String name = ((Class<?>) beanType).getName();
        if (facadeName.equals(name) || name.startsWith(versionPrefix)) {
          stale.add(entry.getKey());
        }
      }
    }
    return stale;
  }

  /**
   * 清理 ExceptionHandlerExceptionResolver 的 advice 缓存。
   *
   * @param loader 应用类加载器
   * @param beanFactory BeanFactory
   * @throws Exception 反射调用失败
   */
  private void clearExceptionHandlerCache(ClassLoader loader, Object beanFactory) throws Exception {
    Class<?> resolverType = loadClass(loader, EXCEPTION_HANDLER_RESOLVER);
    if (resolverType == null) {
      return;
    }
    for (String name : beanNamesOfType(beanFactory, resolverType)) {
      try {
        Object resolver = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
        if (resolver == null) {
          continue;
        }
        Field cache = findField(resolver.getClass(), EXCEPTION_ADVICE_CACHE);
        if (cache == null) {
          continue;
        }
        cache.setAccessible(true);
        Object value = cache.get(resolver);
        if (value instanceof Map) {
          ((Map<?, ?>) value).clear();
        }
      }
      catch (Throwable error) {
        log("clear exception advice cache " + name + " failed: " + error);
      }
    }
  }

  /**
   * 加载应用类加载器下的类，缺失时返回 null。
   *
   * @param loader 应用类加载器
   * @param className 类名
   * @return 类型；类不可见返回 null
   */
  private static Class<?> loadClass(ClassLoader loader, String className) {
    try {
      return Class.forName(className, false, loader);
    }
    catch (Throwable error) {
      return null;
    }
  }

  /**
   * 调用无参实例方法。
   *
   * @param target 目标对象
   * @param name 方法名
   * @return 返回值
   * @throws Exception 反射调用失败
   */
  private static Object invokeNoArg(Object target, String name) throws Exception {
    return invokeMethod(target, name, new Class<?>[0]);
  }

  /**
   * 调用实例方法，参数类型不匹配时按可赋值关系回退。
   *
   * @param target 目标对象
   * @param name 方法名
   * @param types 参数类型
   * @param args 参数
   * @return 返回值
   * @throws Exception 反射调用失败
   */
  private static Object invokeMethod(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    if (target == null) {
      throw new IllegalArgumentException("target is null for " + name);
    }
    Method method = findMethod(target.getClass(), name, types);
    if (method == null) {
      throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  /**
   * 在类层级中查找方法，兼容泛型擦除后的参数类型。
   *
   * @param type 类型
   * @param name 方法名
   * @param types 期望参数类型
   * @return 方法；未找到返回 null
   */
  static Method findMethod(Class<?> type, String name, Class<?>[] types) {
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        return current.getDeclaredMethod(name, types);
      }
      catch (NoSuchMethodException ignored) {
        // 精确签名不匹配时尝试可赋值匹配
      }
      for (Method candidate : current.getDeclaredMethods()) {
        if (candidate.getName().equals(name) && isAssignable(candidate.getParameterTypes(), types)) {
          return candidate;
        }
      }
    }
    return null;
  }

  /**
   * 判断期望参数类型是否可以满足方法声明参数类型。
   *
   * @param declared 方法声明参数类型
   * @param actual 期望参数类型
   * @return 全部可赋值返回 true
   */
  private static boolean isAssignable(Class<?>[] declared, Class<?>[] actual) {
    if (declared.length != actual.length) {
      return false;
    }
    for (int index = 0; index < declared.length; index++) {
      if (actual[index] == null || !declared[index].isAssignableFrom(actual[index])) {
        return false;
      }
    }
    return true;
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

  /**
   * 输出插件诊断信息，不向调用方抛出异常。
   *
   * @param message 消息
   */
  private static void log(String message) {
    System.err.println(LOG_PREFIX + message);
  }
}
