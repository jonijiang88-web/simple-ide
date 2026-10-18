package io.simpleide.hotswap.integration.spring;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot 核心热替换插件。
 *
 * <p>通过反射访问 Spring API，避免 agent 增加 Spring 编译依赖，按 Spring Boot 2.7 /
 * Spring Framework 5.3 的行为实现。当前负责：
 * <ul>
 *   <li>注册 {@link SpringContextTransformer} 捕获 ApplicationContext；</li>
 *   <li>清理 Spring 注解、内省与 ResolvableType 缓存，使新注解和字段类型可见；</li>
 *   <li>对 schema/signature 变更对象的当前版本执行按类型注入，补齐新增
 *       {@code @Autowired} 字段；</li>
 *   <li>存在 {@code ConfigurationPropertiesRebinder} 时触发配置属性重新绑定。</li>
 * </ul>
 *
 * <p>MVC 映射、Jackson、MyBatis、事务与缓存由其他插件负责，本插件不处理。
 */
public final class SpringBootPlugin implements HotswapPlugin {

  /** Spring AutowireCapableBeanFactory.AUTOWIRE_BY_TYPE 常量值。 */
  private static final int AUTOWIRE_BY_TYPE = 2;

  /** Spring Boot 2.7 配置属性重绑定器类名。 */
  private static final String CONFIGURATION_PROPERTIES_REBINDER =
      "org.springframework.boot.context.properties.ConfigurationPropertiesRebinder";

  /**
   * 初始化插件并注册 Spring Boot 启动完成钩子。
   *
   * @param context 插件上下文
   */
  @Override
  public void initialize(HotswapPluginContext context) {
    if (context == null) {
      return;
    }
    context.addTransformer(new SpringContextTransformer());
  }

  /**
   * 处理一次类替换事件。
   *
   * <p>逐个刷新已捕获的 Spring context；单个 context 失败只记录日志，不向调用方抛出。
   *
   * @param event 替换事件
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null || event.facadeClass() == null) {
      return;
    }
    for (Object context : SpringContextBridge.contexts()) {
      try {
        refresh(context, event);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] spring-boot refresh failed for "
          + event.className() + ": " + describe(error));
      }
    }
  }

  /**
   * 刷新单个 Spring context。
   *
   * <p>缓存清理对所有变更类型生效；字段注入与配置属性重绑定只对 schema/signature
   * 变更生效，避免每次方法体变化都覆盖已有注入状态。
   *
   * @param context Spring ApplicationContext
   * @param event 替换事件
   * @throws Exception 反射调用失败
   */
  private void refresh(Object context, HotswapReloadEvent event) throws Exception {
    ClassLoader loader = context.getClass().getClassLoader();
    clearCaches(loader);
    if (event.changeKind() != ChangeKind.SCHEMA
      && event.changeKind() != ChangeKind.SIGNATURE) {
      return;
    }
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    for (Object bean : beansOfType(beanFactory, event.facadeClass(), loader)) {
      Object version = HotswapRuntime.currentVersionOrSelf(bean);
      if (version == bean) {
        // 非版本化对象没有新增字段，保持原有注入结果
        continue;
      }
      invokeMethod(beanFactory, "autowireBeanProperties",
        new Class<?>[]{Object.class, int.class, boolean.class},
        version, Integer.valueOf(AUTOWIRE_BY_TYPE), Boolean.FALSE);
    }
    rebindConfigurationProperties(beanFactory, loader);
  }

  /**
   * 清理 Spring 注解、内省与 ResolvableType 缓存。
   *
   * <p>这些 API 在不同小版本间存在差异，缺失或失败时静默跳过。
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
   * 查找指定 facade 类型对应的 Spring bean。
   *
   * <p>先按可实例化类型查询；结果为空时退回到 BeanDefinition 声明类型匹配，
   * 兼容 FactoryBean 与代理场景。
   *
   * @param beanFactory BeanFactory
   * @param type facade 类型
   * @param loader 应用类加载器
   * @return bean 实例列表，可能为空
   * @throws Exception 反射调用失败
   */
  private List<Object> beansOfType(Object beanFactory, Class<?> type, ClassLoader loader)
      throws Exception {
    List<Object> result = new ArrayList<Object>();
    String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
      new Class<?>[]{Class.class, boolean.class, boolean.class},
      type, Boolean.TRUE, Boolean.FALSE);
    if (names == null || names.length == 0) {
      names = namesByDefinitionType(beanFactory, type, loader);
    }
    for (String name : names) {
      Object bean;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, name);
      }
      catch (Throwable ignored) {
        // FactoryBean 创建失败或非单例时跳过该 bean
        continue;
      }
      if (bean != null && matches(bean.getClass(), type)) {
        result.add(bean);
      }
    }
    return result;
  }

  /**
   * 按 BeanDefinition 声明类型查找 bean 名称，避免不必要的提前实例化。
   *
   * @param beanFactory BeanFactory
   * @param type 目标类型
   * @param loader 应用类加载器
   * @return 匹配的 bean 名称
   */
  private String[] namesByDefinitionType(Object beanFactory, Class<?> type, ClassLoader loader) {
    List<String> matched = new ArrayList<String>();
    String[] all;
    try {
      all = (String[]) invokeMethod(beanFactory, "getBeanDefinitionNames", new Class<?>[0]);
    }
    catch (Throwable error) {
      return new String[0];
    }
    if (all == null) {
      return new String[0];
    }
    for (String name : all) {
      try {
        Object definition = invokeMethod(beanFactory, "getBeanDefinition",
          new Class<?>[]{String.class}, name);
        Class<?> declared = declaredType(definition, loader);
        if (declared != null && matches(declared, type)) {
          matched.add(name);
        }
      }
      catch (Throwable ignored) {
        // 忽略无法解析声明类型的 bean
      }
    }
    return matched.toArray(new String[0]);
  }

  /**
   * 解析 BeanDefinition 的声明类型。
   *
   * <p>优先使用 {@code getResolvableType().resolve()}，无法解析时回退到
   * {@code getBeanClassName()}。
   *
   * @param definition BeanDefinition
   * @param loader 应用类加载器
   * @return 声明类型；无法解析时返回 null
   */
  private Class<?> declaredType(Object definition, ClassLoader loader) {
    if (definition == null) {
      return null;
    }
    try {
      Object resolvableType = invokeNoArg(definition, "getResolvableType");
      if (resolvableType != null) {
        Object resolved = invokeNoArg(resolvableType, "resolve");
        if (resolved instanceof Class) {
          return (Class<?>) resolved;
        }
      }
    }
    catch (Throwable ignored) {
      // 继续尝试 beanClassName
    }
    try {
      Object className = invokeNoArg(definition, "getBeanClassName");
      if (className instanceof String) {
        return Class.forName((String) className, false, loader);
      }
    }
    catch (Throwable ignored) {
      // 声明类型不可用
    }
    return null;
  }

  /**
   * 判断实际类型是否匹配目标 facade 类型，兼容 CGLIB 代理与版本类命名。
   *
   * @param actual 实际类型
   * @param target 目标类型
   * @return 匹配返回 true
   */
  private boolean matches(Class<?> actual, Class<?> target) {
    if (actual == null || target == null) {
      return false;
    }
    if (target.isAssignableFrom(actual) || target.getName().equals(actual.getName())) {
      return true;
    }
    for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
      if (target.getName().equals(current.getName())) {
        return true;
      }
      for (Class<?> iface : current.getInterfaces()) {
        if (target.getName().equals(iface.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 调用 Spring Boot 配置属性重绑定器。
   *
   * <p>仅在 Spring Boot 2.7 且容器中存在该 bean 时执行；任何失败都静默忽略。
   *
   * @param beanFactory BeanFactory
   * @param loader 应用类加载器
   */
  private void rebindConfigurationProperties(Object beanFactory, ClassLoader loader) {
    try {
      Class<?> rebinderType = Class.forName(CONFIGURATION_PROPERTIES_REBINDER, false, loader);
      String[] names = (String[]) invokeMethod(beanFactory, "getBeanNamesForType",
        new Class<?>[]{Class.class, boolean.class, boolean.class},
        rebinderType, Boolean.TRUE, Boolean.FALSE);
      if (names == null) {
        return;
      }
      for (String name : names) {
        try {
          Object rebinder = invokeMethod(beanFactory, "getBean",
            new Class<?>[]{String.class}, name);
          invokeNoArg(rebinder, "rebind");
        }
        catch (Throwable ignored) {
          // 单个重绑定器失败不影响其他 bean 刷新
        }
      }
    }
    catch (Throwable ignored) {
      // 非 Spring Boot 2.7 环境不提供该 bean 类型
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
  private static Object invokeMethod(Object target, String name, Class<?>[] types,
                                     Object... args) throws Exception {
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    Method method = findMethod(target.getClass(), name, types);
    if (method == null) {
      throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  /**
   * 调用无参静态方法。
   *
   * @param className 类名
   * @param name 方法名
   * @param loader 应用类加载器
   */
  private static void invokeStatic(String className, String name, ClassLoader loader) {
    invokeStatic(className, name, loader, new Class<?>[0], new Object[0]);
  }

  /**
   * 调用静态方法，失败时静默忽略。
   *
   * @param className 类名
   * @param name 方法名
   * @param loader 应用类加载器
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
   * 在类层级及接口中查找方法。
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
      Method fromInterface = findInterfaceMethod(current, name, types);
      if (fromInterface != null) {
        return fromInterface;
      }
    }
    return null;
  }

  /**
   * 在接口层级中查找方法。
   *
   * @param type 类型
   * @param name 方法名
   * @param types 参数类型
   * @return 方法；未找到返回 null
   */
  private static Method findInterfaceMethod(Class<?> type, String name, Class<?>[] types) {
    for (Class<?> iface : type.getInterfaces()) {
      try {
        return iface.getDeclaredMethod(name, types);
      }
      catch (NoSuchMethodException ignored) {
        // 继续父接口
      }
      Method nested = findInterfaceMethod(iface, name, types);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  /**
   * 提取便于阅读的异常描述，反射包装异常时输出根因。
   *
   * @param error 异常
   * @return 描述文本
   */
  private static String describe(Throwable error) {
    if (error instanceof InvocationTargetException
      && ((InvocationTargetException) error).getCause() != null) {
      return String.valueOf(((InvocationTargetException) error).getCause());
    }
    return String.valueOf(error);
  }
}
