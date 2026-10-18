package io.simpleide.hotswap.integration.spring.tx;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring 事务热替换插件。
 *
 * <p>所有枚举型事务元数据（{@code @Transactional}、{@code @Rollback}、{@code @TransactionalEventListener}
 * 等）都由 Spring 在首次解析后写入缓存，因此注解或方法签名变化必须清掉这些缓存才能让已存在的事务方法
 * 按新注解执行。插件通过反射访问 Spring，不引入任何 Spring 编译期依赖。
 *
 * <p>当前负责：
 * <ul>
 *   <li>遍历 {@link SpringContextBridge#contexts()} 中的 BeanFactory，定位事务基础设施 bean；</li>
 *   <li>清空 {@code AbstractFallbackTransactionAttributeSource.attributeCache}；</li>
 *   <li>递归处理 {@code TransactionInterceptor.transactionAttributeSource}，兼容自定义或包装型属性源；</li>
 *   <li>清空可安全重建的 AOP advisor 缓存，但不触碰代理本身。</li>
 * </ul>
 *
 * <p>支持边界：
 * <ul>
 *   <li>已有事务方法的注解变化通过清缓存生效；</li>
 *   <li>新增事务方法、修改事务方法的签名，或把普通 bean 变成事务 bean，需要重建代理，
 *       不在本插件范围内，只能依赖 Spring 自身的代理重建流程；</li>
 *   <li>自定义 {@code TransactionAttributeSource} 若使用私有缓存且没有公开的 {@code clear()}，
 *       插件无法清理，会在保持原状的同时记录失败信息。</li>
 * </ul>
 */
public final class TransactionPlugin implements HotswapPlugin {

  private static final String TRANSACTION_INTERCEPTOR =
      "org.springframework.transaction.interceptor.TransactionInterceptor";
  private static final String TRANSACTION_ATTRIBUTE_SOURCE =
      "org.springframework.transaction.interceptor.TransactionAttributeSource";
  private static final String ABSTRACT_FALLBACK_TRANSACTION_ATTRIBUTE_SOURCE =
      "org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource";
  private static final String ANNOTATION_TRANSACTION_ATTRIBUTE_SOURCE =
      "org.springframework.transaction.interceptor.AnnotationTransactionAttributeSource";
  private static final String SPRING6_ANNOTATION_TRANSACTION_ATTRIBUTE_SOURCE =
      "org.springframework.transaction.annotation.AnnotationTransactionAttributeSource";
  private static final String BEAN_FACTORY_TRANSACTION_ATTRIBUTE_SOURCE_ADVISOR =
      "org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor";

  private static final String AOP_ABSTRACT_AUTO_PROXY_CREATOR =
      "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator";
  private static final String AOP_ABSTRACT_ADVISOR_AUTO_PROXY_CREATOR =
      "org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator";
  private static final String AOP_INFRASTRUCTURE_ADVISOR_AUTO_PROXY_CREATOR =
      "org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator";

  /** 事务相关 bean 的类名集合，按类名匹配以兼容 CGLIB 代理。 */
  private static final Set<String> TRANSACTION_BEAN_TYPES = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          TRANSACTION_INTERCEPTOR,
          TRANSACTION_ATTRIBUTE_SOURCE,
          ABSTRACT_FALLBACK_TRANSACTION_ATTRIBUTE_SOURCE,
          ANNOTATION_TRANSACTION_ATTRIBUTE_SOURCE,
          SPRING6_ANNOTATION_TRANSACTION_ATTRIBUTE_SOURCE,
          BEAN_FACTORY_TRANSACTION_ATTRIBUTE_SOURCE_ADVISOR)));

  /** 允许安全清空的 AOP 缓存持有者类名集合。 */
  private static final Set<String> AOP_CACHE_HOLDER_TYPES = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          AOP_ABSTRACT_AUTO_PROXY_CREATOR,
          AOP_ABSTRACT_ADVISOR_AUTO_PROXY_CREATOR,
          AOP_INFRASTRUCTURE_ADVISOR_AUTO_PROXY_CREATOR)));

  /** 事务属性缓存字段名。 */
  private static final String ATTRIBUTE_CACHE_FIELD = "attributeCache";

  /** 事务属性源字段名，TransactionInterceptor 与 advisor 都通过该字段引用属性源。 */
  private static final String TRANSACTION_ATTRIBUTE_SOURCE_FIELD = "transactionAttributeSource";

  /** 可安全清空或置空的 AOP 缓存字段名。 */
  private static final String[] AOP_CACHE_FIELDS = {
      "advisorsCache",
      "cachedAdvisorBeanNames",
      "candidateAdvisors"
  };

  /**
   * 初始化插件。
   *
   * <p>事务缓存清理只依赖运行期反射，无需注册 transformer。
   *
   * @param context 插件上下文
   */
  @Override
  public void initialize(HotswapPluginContext context) {
    // 无需 transformer：事务元数据只在替换完成后通过反射清理
  }

  /**
   * 处理一次类替换事件，清理所有已捕获 Spring context 中的事务缓存。
   *
   * <p>所有变更类型都执行同一套清理逻辑：方法体变化虽然不影响注解，但清理成本低且可避免
   * 旧缓存残留在批量替换场景中；注解、签名和字段变化则必须清理。
   *
   * @param event 替换事件
   */
  @Override
  public void onReload(HotswapReloadEvent event) {
    if (event == null) {
      return;
    }
    try {
      refreshContexts(SpringContextBridge.contexts());
    }
    catch (Throwable error) {
      report(event.className(), error);
    }
  }

  /**
   * 刷新一组 Spring context，逐个隔离异常。
   *
   * @param contexts Spring context 列表，允许为 null
   */
  void refreshContexts(List<Object> contexts) {
    if (contexts == null) {
      return;
    }
    for (Object context : contexts) {
      try {
        refreshContext(context);
      }
      catch (Throwable error) {
        report(context, error);
      }
    }
  }

  /**
   * 刷新单个 Spring context 中的事务缓存。
   *
   * @param context Spring ApplicationContext
   * @throws Exception 反射访问 BeanFactory 失败
   */
  private void refreshContext(Object context) throws Exception {
    if (context == null) {
      return;
    }
    Object beanFactory = invokeNoArg(context, "getBeanFactory");
    if (beanFactory == null) {
      return;
    }
    for (String beanName : beanDefinitionNames(beanFactory)) {
      Class<?> beanType = beanType(beanFactory, beanName);
      if (beanType == null) {
        continue;
      }
      if (!isTransactionBean(beanType) && !isAopCacheHolder(beanType)) {
        continue;
      }
      Object bean;
      try {
        bean = invokeMethod(beanFactory, "getBean", new Class<?>[]{String.class}, beanName);
      }
      catch (Throwable error) {
        report(beanName, error);
        continue;
      }
      clearTransactionCaches(bean);
    }
  }

  /**
   * 清理单个事务 bean 及其属性源、AOP 缓存。
   *
   * @param root 事务 bean
   */
  static void clearTransactionCaches(Object root) {
    if (root == null) {
      return;
    }
    installVersionAwareSource(root);
    Set<Object> visited =
        Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    clearAttributeCaches(root, visited);
    clearAopCaches(root);
  }

  /**
   * 为 TransactionInterceptor 安装版本感知的事务属性源。
   *
   * <p>Spring 代理拦截的是 facade 方法；直接把 facade 方法交给事务属性源会读到旧
   * {@code @Transactional} 注解。包装器在解析属性时把方法映射到当前版本类。
   *
   * @param root 事务 bean
   */
  private static void installVersionAwareSource(Object root) {
    if (root == null || !TRANSACTION_INTERCEPTOR.equals(root.getClass().getName())
      && !matchesTypeName(root.getClass(), Collections.singleton(TRANSACTION_INTERCEPTOR))) {
      return;
    }
    Field field = findField(root.getClass(), TRANSACTION_ATTRIBUTE_SOURCE_FIELD);
    if (field == null) {
      return;
    }
    try {
      field.setAccessible(true);
      Object source = field.get(root);
      if (source == null || Proxy.isProxyClass(source.getClass())) {
        return;
      }
      ClassLoader loader = root.getClass().getClassLoader();
      Class<?> sourceType = Class.forName(TRANSACTION_ATTRIBUTE_SOURCE, false, loader);
      if (sourceType == null || !sourceType.isInstance(source)) {
        return;
      }
      Object wrapped = Proxy.newProxyInstance(loader, new Class<?>[]{sourceType},
        new VersionAwareTransactionSource(source));
      field.set(root, wrapped);
    }
    catch (Throwable error) {
      report("transactionAttributeSource", error);
    }
  }

  /**
   * 版本感知事务属性源代理。
   */
  private static final class VersionAwareTransactionSource implements InvocationHandler {

    private final Object delegate;

    /**
     * 创建代理。
     *
     * @param delegate 原属性源
     */
    private VersionAwareTransactionSource(Object delegate) {
      this.delegate = delegate;
    }

    /**
     * 映射方法后委托。
     *
     * @param proxy 代理
     * @param method 接口方法
     * @param args 参数
     * @return 结果
     * @throws Throwable 委托异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Object[] actual = args == null ? new Object[0] : args;
      if ("getTransactionAttribute".equals(method.getName()) && actual.length == 2
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
     * 调用原属性源。
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
     * 解析当前版本类。
     *
     * @param targetClass 原类型
     * @return 版本类或原类型
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
     * 查找同名同参数方法。
     *
     * @param type 类型
     * @param source 原方法
     * @return 方法或原方法
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
   * 递归清理事务属性缓存。
   *
   * <p>TransactionInterceptor 自身没有 {@code attributeCache}，其属性源挂在
   * {@code transactionAttributeSource} 字段上，因此需要沿字段链递归；使用身份集合避免循环引用。
   *
   * @param source 事务属性源或拦截器
   * @param visited 已访问对象集合
   */
  private static void clearAttributeCaches(Object source, Set<Object> visited) {
    if (source == null || !visited.add(source)) {
      return;
    }
    clearField(findField(source.getClass(), ATTRIBUTE_CACHE_FIELD), source);
    Field nested = findField(source.getClass(), TRANSACTION_ATTRIBUTE_SOURCE_FIELD);
    if (nested != null) {
      clearAttributeCaches(readField(nested, source), visited);
    }
  }

  /**
   * 清理可安全重建的 AOP advisor 缓存，不触碰代理对象。
   *
   * @param target AOP 缓存持有者
   */
  private static void clearAopCaches(Object target) {
    if (target == null) {
      return;
    }
    for (String fieldName : AOP_CACHE_FIELDS) {
      clearField(findField(target.getClass(), fieldName), target);
    }
  }

  /**
   * 清空字段指向的缓存容器；数组字段置空，其他类型尝试调用无参 {@code clear()}。
   *
   * @param field 缓存字段，允许为 null
   * @param target 字段所属对象
   */
  static void clearField(Field field, Object target) {
    if (field == null || target == null) {
      return;
    }
    try {
      field.setAccessible(true);
      Object value = field.get(target);
      if (value instanceof Map) {
        ((Map<?, ?>) value).clear();
      }
      else if (value instanceof Collection) {
        ((Collection<?>) value).clear();
      }
      else if (value != null && value.getClass().isArray()) {
        field.set(target, null);
      }
      else if (value != null) {
        Method clear = findMethod(value.getClass(), "clear", new Class<?>[0]);
        if (clear != null) {
          clear.setAccessible(true);
          clear.invoke(value);
        }
      }
    }
    catch (Throwable error) {
      report(field.getName(), error);
    }
  }

  /**
   * 判断类型是否属于事务基础设施 bean。
   *
   * @param type bean 类型
   * @return 命中返回 true
   */
  static boolean isTransactionBean(Class<?> type) {
    return matchesTypeName(type, TRANSACTION_BEAN_TYPES);
  }

  /**
   * 判断类型是否属于可安全清理缓存的 AOP 缓存持有者。
   *
   * @param type bean 类型
   * @return 命中返回 true
   */
  static boolean isAopCacheHolder(Class<?> type) {
    return matchesTypeName(type, AOP_CACHE_HOLDER_TYPES);
  }

  /**
   * 沿父类和接口层级匹配类名，兼容 CGLIB 代理。
   *
   * @param type 类型
   * @param names 目标类名集合
   * @return 命中返回 true
   */
  private static boolean matchesTypeName(Class<?> type, Set<String> names) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (names.contains(current.getName())) {
        return true;
      }
      if (matchesInterface(current.getInterfaces(), names)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 递归匹配接口及其父接口。
   *
   * @param interfaces 接口数组
   * @param names 目标类名集合
   * @return 命中返回 true
   */
  private static boolean matchesInterface(Class<?>[] interfaces, Set<String> names) {
    for (Class<?> iface : interfaces) {
      if (names.contains(iface.getName()) || matchesInterface(iface.getInterfaces(), names)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 读取 bean 定义名称。
   *
   * @param beanFactory BeanFactory
   * @return bean 名称数组，失败时返回空数组
   */
  private static String[] beanDefinitionNames(Object beanFactory) {
    try {
      Object value = invokeNoArg(beanFactory, "getBeanDefinitionNames");
      return value instanceof String[] ? (String[]) value : new String[0];
    }
    catch (Throwable error) {
      report("getBeanDefinitionNames", error);
      return new String[0];
    }
  }

  /**
   * 读取 bean 声明类型，不主动实例化 FactoryBean。
   *
   * @param beanFactory BeanFactory
   * @param beanName bean 名称
   * @return bean 类型，无法解析时返回 null
   */
  private static Class<?> beanType(Object beanFactory, String beanName) {
    try {
      Object type = invokeMethod(beanFactory, "getType",
          new Class<?>[]{String.class, boolean.class}, beanName, Boolean.FALSE);
      if (type instanceof Class) {
        return (Class<?>) type;
      }
    }
    catch (Throwable ignored) {
      // 老版本 Spring 没有两参 getType，退化到单参版本
    }
    try {
      Object type = invokeMethod(beanFactory, "getType", new Class<?>[]{String.class}, beanName);
      return type instanceof Class ? (Class<?>) type : null;
    }
    catch (Throwable error) {
      return null;
    }
  }

  /**
   * 读取字段值。
   *
   * @param field 字段
   * @param target 字段所属对象
   * @return 字段值，读取失败返回 null
   */
  private static Object readField(Field field, Object target) {
    try {
      field.setAccessible(true);
      return field.get(target);
    }
    catch (Throwable error) {
      report(field.getName(), error);
      return null;
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
   * @return 方法；未找到返回 null
   */
  private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
    for (Class<?> current = type; current != null && current != Object.class;
         current = current.getSuperclass()) {
      try {
        Method method = current.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
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
   * 输出失败信息，避免单个缓存清理失败影响其他 bean 或事务。
   *
   * @param subject 失败对象描述
   * @param error 异常
   */
  private static void report(Object subject, Throwable error) {
    String label;
    try {
      label = String.valueOf(subject);
    }
    catch (Throwable ignored) {
      label = "<unknown>";
    }
    System.err.println("[simple-ide-hotswap] transaction cache refresh failed for " + label
      + ": " + error);
  }
}
