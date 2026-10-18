package io.simpleide.hotswap.integration.spring;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot / Spring MVC 热替换插件。
 *
 * <p>使用反射访问 Spring，避免 agent 直接依赖 Spring 版本。当前负责：
 * <ul>
 *   <li>捕获 ApplicationContext；</li>
 *   <li>清理 Spring 注解/内省缓存；</li>
 *   <li>为版本对象补注入新增依赖字段；</li>
 *   <li>Controller 注解或方法签名变化时重建 RequestMappingHandlerMapping 条目。</li>
 * </ul>
 */
public final class SpringPlugin implements HotswapPlugin {

  private static final int AUTOWIRE_BY_TYPE = 2;

  @Override
  public void initialize(HotswapPluginContext context) {
    context.addTransformer(new SpringContextTransformer());
  }

  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null || event.facadeClass() == null) {
      return;
    }
    List<Object> contexts = SpringContextBridge.contexts();
    for (Object context : contexts) {
      try {
        refresh(context, event);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] spring refresh failed for "
          + event.className() + ": " + error);
      }
    }
  }

  /**
   * 刷新单个 Spring context。
   *
   * @param context Spring context
   * @param event reload 事件
   * @throws Exception 反射失败
   */
  private void refresh(Object context, HotswapReloadEvent event) throws Exception {
    ClassLoader loader = context.getClass().getClassLoader();
    clearCaches(loader);
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    List<Object> beans = beansOfType(beanFactory, event.facadeClass());
    if (beans.isEmpty() && event.changeKind() != ChangeKind.ANNOTATION
      && event.changeKind() != ChangeKind.SIGNATURE) {
      return;
    }
    if (event.changeKind() == ChangeKind.SCHEMA || event.changeKind() == ChangeKind.SIGNATURE) {
      for (Object bean : beans) {
        Object version = HotswapRuntime.currentVersionOrSelf(bean);
        if (version != bean) {
          invokeMethod(beanFactory, "autowireBeanProperties",
            new Class<?>[]{Object.class, int.class, boolean.class},
            version, Integer.valueOf(AUTOWIRE_BY_TYPE), Boolean.FALSE);
        }
      }
    }
    if (event.changeKind() == ChangeKind.ANNOTATION || event.changeKind() == ChangeKind.SIGNATURE) {
      refreshMvc(loader, context, event, beans);
    }
  }

  /**
   * 清理 Spring 注解和内省缓存。
   *
   * @param loader 应用类加载器
   */
  private void clearCaches(ClassLoader loader) {
    invokeStatic("org.springframework.core.annotation.AnnotationUtils", "clearCache", loader);
    invokeStatic("org.springframework.core.CachedIntrospectionResults", "clearClassLoader",
      loader, new Class<?>[]{ClassLoader.class}, new Object[]{loader});
    invokeStatic("org.springframework.core.ResolvableType", "clearCache", loader);
  }

  /**
   * 查找指定类型的 Spring bean。
   *
   * @param beanFactory BeanFactory
   * @param type 目标类型
   * @return bean 实例
   * @throws Exception 反射失败
   */
  private List<Object> beansOfType(Object beanFactory, Class<?> type) throws Exception {
    List<Object> result = new ArrayList<Object>();
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      type, Boolean.TRUE, Boolean.FALSE);
    if (names == null || names.length == 0) {
      names = namesByDefinitionType(beanFactory, type);
    }
    for (String name : names) {
      Object bean = null;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable ignored) {
        // FactoryBean 创建失败或非单例时跳过
      }
      if (bean == null) {
        continue;
      }
      if (type.isInstance(bean) || type.getName().equals(bean.getClass().getName())
        || hasTypeName(bean.getClass(), type.getName())) {
        result.add(bean);
      }
    }
    return result;
  }

  /**
   * 按 BeanDefinition 声明类型查找名称，不触发 bean 实例化。
   *
   * @param beanFactory BeanFactory
   * @param type 目标类型
   * @return bean 名称
   * @throws Exception 反射失败
   */
  private String[] namesByDefinitionType(Object beanFactory, Class<?> type) throws Exception {
    String[] all = (String[]) invokeMethod(beanFactory, "getBeanDefinitionNames", new Class<?>[0]);
    if (all == null) {
      return new String[0];
    }
    List<String> matched = new ArrayList<String>();
    for (String name : all) {
      try {
        Class<?> declared = (Class<?>) invokeMethod(beanFactory, "getType",
          new Class<?>[]{String.class}, name);
        if (declared != null && (type.isAssignableFrom(declared)
          || hasTypeName(declared, type.getName()))) {
          matched.add(name);
        }
      }
      catch (Throwable ignored) {
        // 忽略无法解析类型的 bean
      }
    }
    return matched.toArray(new String[0]);
  }

  /**
   * 判断对象类型层级中是否包含指定类名，兼容 CGLIB 代理。
   *
   * @param type 类型
   * @param name 目标类名
   * @return 命中返回 true
   */
  private boolean hasTypeName(Class<?> type, String name) {
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
   * 重建 Controller 的请求映射。
   *
   * @param loader 应用类加载器
   * @param context Spring context
   * @param event reload 事件
   * @param beans 变更类的 bean
   * @throws Exception 反射失败
   */
  private void refreshMvc(ClassLoader loader, Object context, HotswapReloadEvent event,
                          List<Object> beans) throws Exception {
    Class<?> mappingType = Class.forName(
      "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
      false, loader);
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    String[] mappingNames = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      mappingType, Boolean.TRUE, Boolean.FALSE);
    if (mappingNames == null) {
      return;
    }
    for (String mappingName : mappingNames) {
      Object mapping = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, mappingName);
      rebuildMapping(mapping, event, beans);
    }
    clearExceptionHandlerCache(loader, beanFactory);
  }

  /**
   * 重建单个 RequestMappingHandlerMapping 中属于变更 Controller 的条目。
   *
   * @param mapping handler mapping
   * @param event reload 事件
   * @param beans 变更类的 bean
   * @throws Exception 反射失败
   */
  private void rebuildMapping(Object mapping, HotswapReloadEvent event, List<Object> beans)
      throws Exception {
    Map<?, ?> handlers = (Map<?, ?>) invokeNoArg(mapping, "getHandlerMethods");
    if (handlers == null) {
      return;
    }
    String facadeName = event.facadeClass().getName();
    List<Object> oldMappings = new ArrayList<Object>();
    for (Map.Entry<?, ?> entry : handlers.entrySet()) {
      Object handlerMethod = entry.getValue();
      Object beanType = invokeNoArg(handlerMethod, "getBeanType");
      if (beanType instanceof Class && facadeName.equals(((Class<?>) beanType).getName())) {
        oldMappings.add(entry.getKey());
      }
    }
    for (Object oldMapping : oldMappings) {
      invokeMethod(mapping, "unregisterMapping", new Class<?>[]{Object.class}, oldMapping);
    }
    if (beans.isEmpty()) {
      return;
    }
    Object bean = beans.get(0);
    Object handler = HotswapRuntime.currentVersionOrSelf(bean);
    Class<?> versionClass = HotswapRuntime.effectiveClass(event.facadeClass());
    for (Method method : versionClass.getDeclaredMethods()) {
      Object mappingInfo = invokeMethod(mapping, "getMappingForMethod",
        new Class<?>[]{Method.class, Class.class}, method, versionClass);
      if (mappingInfo != null) {
        invokeMethod(mapping, "registerHandlerMethod",
          new Class<?>[]{Object.class, Method.class, mappingInfo.getClass()},
          handler, method, mappingInfo);
      }
    }
  }

  /**
   * 清理异常处理器缓存。
   *
   * @param loader 应用类加载器
   * @param beanFactory BeanFactory
   * @throws Exception 反射失败
   */
  private void clearExceptionHandlerCache(ClassLoader loader, Object beanFactory) throws Exception {
    Class<?> resolverType = Class.forName(
      "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
      false, loader);
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      resolverType, Boolean.TRUE, Boolean.FALSE);
    if (names == null) {
      return;
    }
    for (String name : names) {
      Object resolver = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      Field cache = findField(resolver.getClass(), "exceptionHandlerAdviceCache");
      if (cache != null) {
        cache.setAccessible(true);
        Object value = cache.get(resolver);
        if (value instanceof Map) {
          ((Map<?, ?>) value).clear();
        }
      }
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
   * 调用静态方法。
   *
   * @param className 类名
   * @param name 方法名
   * @param loader 类加载器
   * @param types 参数类型
   * @param args 参数
   */
  private static void invokeStatic(String className, String name, ClassLoader loader,
                                   Class<?>[] types, Object[] args) {
    try {
      Class<?> type = Class.forName(className, false, loader);
      Method method = findMethod(type, name, types);
      if (method != null) {
        method.setAccessible(true);
        method.invoke(null, args);
      }
    }
    catch (Throwable ignored) {
      // Spring 版本不支持该缓存清理 API 时忽略
    }
  }

  /**
   * 调用无参静态方法。
   *
   * @param className 类名
   * @param name 方法名
   * @param loader 类加载器
   */
  private static void invokeStatic(String className, String name, ClassLoader loader) {
    invokeStatic(className, name, loader, new Class<?>[0], new Object[0]);
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
