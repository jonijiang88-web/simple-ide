package io.simpleide.hotswap.integration.mybatis;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.plugin.HotswapPlugin;
import io.simpleide.hotswap.plugin.HotswapPluginContext;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * MyBatis effective class 适配插件。
 *
 * <p>MyBatis 通过 ObjectWrapper/MetaObject 反射读写结果对象。facade DTO 的字段
 * schema 不会随版本类增加，所以这里在 Configuration 上安装版本感知的
 * ObjectWrapperFactory：结果对象的读写转发到当前版本对象，从而支持新增 DTO 字段。
 *
 * <p>该插件运行在应用类加载器中，直接使用 MyBatis 3.5.13 API。
 */
public final class MyBatisAdapterPlugin implements HotswapPlugin {

  /** 已安装过适配器的 Configuration，按对象身份去重。 */
  private static final Map<Object, Boolean> INSTALLED =
      Collections.synchronizedMap(new IdentityHashMap<Object, Boolean>());

  @Override
  public void initialize(HotswapPluginContext context) {
    // 无需 transformer；首次 reload 时安装 ObjectWrapperFactory。
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
        System.err.println("[simple-ide-hotswap] mybatis adapter failed for "
          + event.className() + ": " + error);
      }
    }
  }

  /**
   * 给 context 中所有 SqlSessionFactory 安装版本感知 wrapper。
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
      SqlSessionFactory.class, Boolean.TRUE, Boolean.FALSE);
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
      if (bean instanceof SqlSessionFactory) {
        install(((SqlSessionFactory) bean).getConfiguration());
      }
    }
  }

  /**
   * 安装 ObjectWrapperFactory。
   *
   * @param configuration MyBatis Configuration
   */
  private void install(Configuration configuration) {
    synchronized (INSTALLED) {
      if (INSTALLED.containsKey(configuration)) {
        return;
      }
      if (!(configuration.getObjectWrapperFactory() instanceof VersionAwareWrapperFactory)) {
        configuration.setObjectWrapperFactory(new VersionAwareWrapperFactory());
      }
      INSTALLED.put(configuration, Boolean.TRUE);
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
   * 判断对象是否是 facade。
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

  /** 版本感知 ObjectWrapperFactory。 */
  private static final class VersionAwareWrapperFactory implements ObjectWrapperFactory {

    @Override
    public boolean hasWrapperFor(Object object) {
      return object != null && isFacade(object.getClass());
    }

    @Override
    public ObjectWrapper getWrapperFor(MetaObject metaObject, Object object) {
      Object version;
      try {
        version = HotswapRuntime.currentVersionOrSelf(object);
      }
      catch (Throwable ignored) {
        return metaObject.getObjectWrapper();
      }
      if (version == null || version == object) {
        return metaObject.getObjectWrapper();
      }
      MetaObject versionMeta = MetaObject.forObject(version, metaObject.getObjectFactory(),
        metaObject.getObjectWrapperFactory(), metaObject.getReflectorFactory());
      return new DelegatingWrapper(versionMeta.getObjectWrapper());
    }
  }

  /** 把 ObjectWrapper 调用委托给版本对象 wrapper。 */
  private static final class DelegatingWrapper implements ObjectWrapper {

    private final ObjectWrapper delegate;

    /**
     * 创建委托 wrapper。
     *
     * @param delegate 版本对象 wrapper
     */
    private DelegatingWrapper(ObjectWrapper delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object get(PropertyTokenizer property) {
      return delegate.get(property);
    }

    @Override
    public void set(PropertyTokenizer property, Object value) {
      delegate.set(property, value);
    }

    @Override
    public String findProperty(String name, boolean useCamelCaseMapping) {
      return delegate.findProperty(name, useCamelCaseMapping);
    }

    @Override
    public String[] getGetterNames() {
      return delegate.getGetterNames();
    }

    @Override
    public String[] getSetterNames() {
      return delegate.getSetterNames();
    }

    @Override
    public Class<?> getSetterType(String name) {
      return delegate.getSetterType(name);
    }

    @Override
    public Class<?> getGetterType(String name) {
      return delegate.getGetterType(name);
    }

    @Override
    public boolean hasSetter(String name) {
      return delegate.hasSetter(name);
    }

    @Override
    public boolean hasGetter(String name) {
      return delegate.hasGetter(name);
    }

    @Override
    public MetaObject instantiatePropertyValue(String name, PropertyTokenizer property,
                                               ObjectFactory objectFactory) {
      return delegate.instantiatePropertyValue(name, property, objectFactory);
    }

    @Override
    public boolean isCollection() {
      return delegate.isCollection();
    }

    @Override
    public void add(Object element) {
      delegate.add(element);
    }

    @Override
    public <E> void addAll(List<E> elements) {
      delegate.addAll(elements);
    }
  }
}
