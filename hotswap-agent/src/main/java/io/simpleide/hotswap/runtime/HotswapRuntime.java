package io.simpleide.hotswap.runtime;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.api.ClassDefiner;
import io.simpleide.hotswap.api.ClassInstrumenter;
import io.simpleide.hotswap.api.HotswapNames;
import io.simpleide.hotswap.api.MethodDispatcher;
import io.simpleide.hotswap.api.OriginalClassStore;
import io.simpleide.hotswap.api.SchemaClassifier;
import io.simpleide.hotswap.api.VersionClassGenerator;
import io.simpleide.hotswap.classify.AsmSchemaClassifier;
import io.simpleide.hotswap.dispatch.ReflectionMethodDispatcher;
import io.simpleide.hotswap.facade.AsmClassInstrumenter;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import io.simpleide.hotswap.plugin.PluginManager;
import io.simpleide.hotswap.state.ReflectionStateMigrator;
import io.simpleide.hotswap.state.SharedStaticState;
import io.simpleide.hotswap.state.StaticAccessRewriter;
import io.simpleide.hotswap.version.AsmVersionClassGenerator;
import io.simpleide.hotswap.version.RuntimeClassDefiner;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * simple-ide JRebel 式热替换运行时。
 *
 * <p>应用类在加载时被改写成 facade；首次调用时创建 {@code $$SIDEV0} 版本对象。
 * reload 时创建新的版本类，迁移字段并把 facade 的 delegate 指向新版本。旧对象身份
 * 保持不变，JVM 不需要修改旧类的 schema。
 */
public final class HotswapRuntime implements OriginalClassStore {

  private static final HotswapRuntime INSTANCE = new HotswapRuntime();

  private final Map<String, VersionState> states = new ConcurrentHashMap<String, VersionState>();
  private final Map<String, byte[]> unsupportedOriginalBytes =
      new ConcurrentHashMap<String, byte[]>();
  private final Map<Object, List<Object>> versionHistory =
      Collections.synchronizedMap(new WeakHashMap<Object, List<Object>>());
  private final ClassInstrumenter instrumenter = new AsmClassInstrumenter(this);
  private final MethodDispatcher dispatcher = new ReflectionMethodDispatcher();
  private final VersionClassGenerator versionGenerator = new AsmVersionClassGenerator();
  private final ClassDefiner classDefiner = new RuntimeClassDefiner();
  private final ReflectionStateMigrator stateMigrator = new ReflectionStateMigrator();
  private final SharedStaticState staticState = SharedStaticState.getInstance();
  private final StaticAccessRewriter staticRewriter = new StaticAccessRewriter();
  private final SchemaClassifier classifier = new AsmSchemaClassifier();
  private final PluginManager pluginManager = new PluginManager();
  private final Object monitor = new Object();

  private volatile Instrumentation instrumentation;
  private volatile String includePrefix = "";

  private HotswapRuntime() {
  }

  /** 返回全局运行时。 */
  public static HotswapRuntime getInstance() {
    return INSTANCE;
  }

  /**
   * 初始化运行时并注册加载期 facade transformer。
   *
   * @param inst instrumentation
   * @param directory agent 工作目录，保留给后续扩展
   * @param includePrefix 只改写该包前缀的类；空串表示所有应用目录类
   */
  public void configure(Instrumentation inst, java.nio.file.Path directory, String includePrefix) {
    if (inst == null) {
      throw new IllegalArgumentException("instrumentation must not be null");
    }
    this.instrumentation = inst;
    this.includePrefix = includePrefix == null ? "" : includePrefix.trim();
    inst.addTransformer(new FacadeTransformer(), false);
    pluginManager.initialize(inst);
  }

  /**
   * facade 方法转发入口。
   *
   * @param facade facade 对象
   * @param methodName 方法名
   * @param descriptor JVM 方法描述符
   * @param args 已装箱参数
   * @return 已装箱返回值
   * @throws Throwable 原方法异常
   */
  public static Object invoke(Object facade, String methodName, String descriptor, Object[] args)
      throws Throwable {
    if (facade == null) {
      throw new NullPointerException("facade is null");
    }
    try {
      if (!INSTANCE.isFacadeClass(facade.getClass())) {
        // 编译期调用点改写也会覆盖接口、DTO 等未版本化对象；这类对象直接反射调用。
        return INSTANCE.dispatcher.invoke(facade, methodName, descriptor, args);
      }
      return INSTANCE.dispatch(facade, methodName, descriptor, args);
    }
    catch (NoSuchMethodException missing) {
      Object target = INSTANCE.resolveSpringTarget(facade);
      if (target == null || target == facade) {
        throw missing;
      }
      if (INSTANCE.isFacadeClass(target.getClass())) {
        return INSTANCE.dispatch(target, methodName, descriptor, args);
      }
      return INSTANCE.dispatcher.invoke(target, methodName, descriptor, args);
    }
  }

  /**
   * 返回 facade 当前版本对象，供测试或运行时 API 使用。
   *
   * @param facade facade 对象
   * @return 当前版本对象
   * @throws Exception delegate 创建失败
   */
  public static Object currentVersion(Object facade) throws Exception {
    return INSTANCE.ensureDelegate(facade);
  }

  /**
   * 返回 facade 的当前版本类；未版本化时返回 facade 自身。
   *
   * @param facadeClass facade 类
   * @return 当前版本类
   */
  public static Class<?> effectiveClass(Class<?> facadeClass) {
    return INSTANCE.effectiveClassOf(facadeClass);
  }

  /**
   * 把 facade 对象转换为当前版本对象；非 facade 原样返回。
   *
   * @param value facade 或普通对象
   * @return 当前版本对象或原对象
   * @throws Exception 版本创建失败
   */
  public static Object currentVersionOrSelf(Object value) throws Exception {
    if (value == null || !INSTANCE.isFacadeClass(value.getClass())) {
      return value;
    }
    return currentVersion(value);
  }

  /**
   * 创建一个 facade 外壳并绑定指定版本对象，供 Jackson/MyBatis 等框架回填。
   *
   * @param facadeClass facade 类
   * @param version 版本对象
   * @return facade 对象
   * @throws Exception facade 或版本对象无效时抛出
   */
  public static Object wrapFacade(Class<?> facadeClass, Object version) throws Exception {
    return INSTANCE.wrapFacadeObject(facadeClass, version);
  }

  /**
   * 开始批量热替换，插件事件在批次结束后统一分发。
   */
  public static void beginBatch() {
    INSTANCE.pluginManager.beginBatch();
  }

  /**
   * 结束批量热替换并分发插件事件。
   */
  public static void endBatch() {
    INSTANCE.pluginManager.endBatch();
  }

  /**
   * 热替换一个类。
   *
   * <p>已被 facade 改写的类走版本化；未改写的类回退到 JVM redefineClasses。
   *
   * @param className 点分类名
   * @param newBytes 新编译字节
   * @throws Exception 定义、迁移或重定义失败
   */
  public void reload(String className, byte[] newBytes) throws Exception {
    if (className == null || className.trim().isEmpty() || newBytes == null || newBytes.length == 0) {
      throw new IllegalArgumentException("reload requires class name and bytes");
    }
    Class<?> original = findLoadedClass(className);
    if (original == null) {
      throw new ClassNotFoundException(className);
    }
    VersionState state = stateOf(original);
    if (state == null || state.original == null) {
      String unsupported = HotswapPreflight.check(newBytes);
      if (unsupported != null) {
        byte[] previous = unsupportedOriginalBytes.get(className);
        if (previous != null && classifier.classify(previous, newBytes) == ChangeKind.METHOD_BODY) {
          instrumentation.redefineClasses(new ClassDefinition(original, newBytes));
          firePluginEvent(className, original, null, ChangeKind.METHOD_BODY);
          return;
        }
        throw new UnsupportedOperationException(unsupported
          + ": class versioning cannot safely reload this class; restart the application");
      }
      instrumentation.redefineClasses(new ClassDefinition(original, newBytes));
      firePluginEvent(className, original, null, ChangeKind.METHOD_BODY);
      return;
    }
    synchronized (monitor) {
      ChangeKind kind = classifier.classify(state.currentBytes, newBytes);
      if (kind != ChangeKind.METHOD_BODY) {
        String unsupported = HotswapPreflight.check(newBytes);
        if (unsupported != null) {
          throw new UnsupportedOperationException(unsupported
            + ": class versioning cannot safely reload this class; restart the application");
        }
      }
      if (kind == ChangeKind.HIERARCHY || kind == ChangeKind.UNSUPPORTED) {
        throw new UnsupportedOperationException("class change is not hot-reloadable: " + kind);
      }
      if (kind == ChangeKind.METHOD_BODY) {
        if (state.currentVersionClass == null) {
          // 还没有 V0：直接以新字节作为首次版本基线，避免创建后再 retransform。
          state.original = newBytes.clone();
          state.currentBytes = newBytes.clone();
          firePluginEvent(className, original, null, ChangeKind.METHOD_BODY);
          return;
        }
        byte[] versionBytes = versionGenerator.generate(newBytes, className,
          state.currentVersionClass.getName());
        versionBytes = staticRewriter.rewriteVersion(versionBytes, className);
        retransformVersion(state.currentVersionClass, versionBytes);
        state.currentBytes = newBytes.clone();
        firePluginEvent(className, original, state.currentVersionClass, ChangeKind.METHOD_BODY);
        return;
      }
      int number = state.counter.getAndIncrement();
      Class<?> versionClass = defineVersion(original, className, newBytes, number);
      state.currentVersionClass = versionClass;
      state.currentBytes = newBytes.clone();
      for (Object facade : liveFacades(state)) {
        Object old = ensureDelegate(facade);
        Object next = allocate(versionClass);
        stateMigrator.copy(old, next);
        delegateField(original).set(facade, next);
        recordVersionHistory(facade, next);
      }
      firePluginEvent(className, original, versionClass, kind);
    }
  }

  /**
   * 对当前版本类执行 retransform，仅更新方法体。
   *
   * @param versionClass 当前版本类
   * @param versionBytes 重映射到版本类名的新字节
   * @throws Exception retransform 失败
   */
  private void retransformVersion(Class<?> versionClass, byte[] versionBytes) throws Exception {
    ClassFileTransformer transformer = new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                              ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classBeingRedefined == versionClass
          && versionClass.getName().replace('.', '/').equals(className)) {
          return versionBytes.clone();
        }
        return null;
      }
    };
    instrumentation.addTransformer(transformer, true);
    try {
      instrumentation.retransformClasses(versionClass);
    }
    finally {
      instrumentation.removeTransformer(transformer);
    }
  }

  @Override
  public void record(String className, byte[] bytes) {
    if (className == null || bytes == null) {
      return;
    }
    synchronized (monitor) {
      VersionState state = states.get(className);
      if (state == null) {
        state = new VersionState();
        states.put(className, state);
      }
      if (state.original == null) {
        state.original = bytes.clone();
        state.currentBytes = bytes.clone();
      }
    }
  }

  /**
   * 解析 facade 的当前版本类。
   *
   * @param facadeClass facade 类
   * @return 当前版本类或 facade 自身
   */
  private Class<?> effectiveClassOf(Class<?> facadeClass) {
    if (facadeClass == null) {
      return null;
    }
    VersionState state = stateOf(facadeClass);
    if (state != null && state.currentVersionClass != null) {
      return state.currentVersionClass;
    }
    return facadeClass;
  }

  /**
   * 判断类型是否为已改写的 facade。
   *
   * @param type 类型
   * @return facade 返回 true
   */
  private boolean isFacadeClass(Class<?> type) {
    if (type == null) {
      return false;
    }
    try {
      type.getDeclaredField(HotswapNames.DELEGATE_FIELD);
      return true;
    }
    catch (NoSuchFieldException ignored) {
      return false;
    }
  }

  /**
   * 创建 facade 外壳并绑定版本对象，同步已有字段供 Bean Validation 等反射读取。
   *
   * @param facadeClass facade 类
   * @param version 版本对象
   * @return facade 对象
   * @throws Exception facade 无效时抛出
   */
  private Object wrapFacadeObject(Class<?> facadeClass, Object version) throws Exception {
    if (facadeClass == null || version == null) {
      throw new IllegalArgumentException("facadeClass and version must not be null");
    }
    if (!isFacadeClass(facadeClass)) {
      throw new IllegalArgumentException("not a facade class: " + facadeClass.getName());
    }
    Object facade = allocate(facadeClass);
    stateMigrator.copy(version, facade);
    delegateField(facadeClass).set(facade, version);
    registerFacade(facadeClass, facade);
    return facade;
  }

  /**
   * 分发一次插件事件；批量模式下由 PluginManager 暂存。
   *
   * @param className 点分类名
   * @param facadeClass facade 类
   * @param versionClass 当前版本类
   * @param kind 变更类型
   */
  private void firePluginEvent(String className, Class<?> facadeClass, Class<?> versionClass,
                               ChangeKind kind) {
    pluginManager.fire(new HotswapReloadEvent(className, facadeClass, versionClass, kind));
  }

  /**
   * 解析 Spring AOP 代理的目标对象，用于调用代理类上不存在的新方法。
   *
   * <p>已有方法仍优先走代理，保留事务/缓存等拦截器；只有代理方法表找不到目标方法时
   * 才回退到 target。
   *
   * @param proxy 可能的 Spring 代理
   * @return 目标对象；不是代理时返回 null
   */
  private Object resolveSpringTarget(Object proxy) {
    if (proxy == null) {
      return null;
    }
    try {
      ClassLoader loader = proxy.getClass().getClassLoader();
      Class<?> advisedType = Class.forName("org.springframework.aop.framework.Advised",
        false, loader);
      if (!advisedType.isInstance(proxy)) {
        return null;
      }
      Method getTargetSource = advisedType.getMethod("getTargetSource");
      Object targetSource = getTargetSource.invoke(proxy);
      if (targetSource == null) {
        return null;
      }
      Method getTarget = targetSource.getClass().getMethod("getTarget");
      return getTarget.invoke(targetSource);
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * 执行 facade 转发，先同步框架在版本创建后注入的依赖，保留版本内普通业务状态。
   *
   * @param facade facade 对象
   * @param methodName 方法名
   * @param descriptor 描述符
   * @param args 参数
   * @return 返回值
   * @throws Throwable 调用失败
   */
  private Object dispatch(Object facade, String methodName, String descriptor, Object[] args)
      throws Throwable {
    if (facade == null) {
      throw new NullPointerException("facade is null");
    }
    Object current = ensureDelegate(facade);
    stateMigrator.refreshInjectedFields(facade, current);
    try {
      return dispatcher.invoke(current, methodName, descriptor, args);
    }
    catch (NoSuchMethodException missing) {
      for (Object older : earlierVersions(facade, current)) {
        try {
          return dispatcher.invoke(older, methodName, descriptor, args);
        }
        catch (NoSuchMethodException ignored) {
          // continue with the next older version
        }
      }
      throw missing;
    }
  }

  /**
   * 确保 facade 有当前版本对象；首次调用时创建 V0 并迁移字段。
   *
   * @param facade facade 对象
   * @return 当前版本对象
   * @throws Exception 创建失败
   */
  private Object ensureDelegate(Object facade) throws Exception {
    Class<?> facadeClass = facade.getClass();
    Field field = delegateField(facadeClass);
    Object delegate = field.get(facade);
    if (delegate != null) {
      registerFacade(facadeClass, facade);
      return delegate;
    }
    synchronized (monitor) {
      delegate = field.get(facade);
      if (delegate != null) {
        registerFacade(facadeClass, facade);
        return delegate;
      }
      VersionState state = stateOf(facadeClass);
      if (state == null) {
        throw new IllegalStateException("no original bytes for " + facadeClass.getName());
      }
      if (state.currentVersionClass == null) {
        staticState.capture(facadeClass);
        state.currentVersionClass = defineVersion(facadeClass, facadeClass.getName(),
            state.original, 0);
      }
      Object version = allocate(state.currentVersionClass);
      stateMigrator.copy(facade, version);
      field.set(facade, version);
      registerFacade(facadeClass, facade);
      recordVersionHistory(facade, version);
      return version;
    }
  }

  /**
   * 定义版本类，并应用静态状态改写。
   *
   * @param original 原类
   * @param className 原点分类名
   * @param bytes 原/新字节
   * @param number 版本号
   * @return 版本类
   * @throws Exception 生成或定义失败
   */
  private Class<?> defineVersion(Class<?> original, String className, byte[] bytes, int number)
      throws Exception {
    String versionName = AsmVersionClassGenerator.versionName(className, number);
    byte[] versionBytes = versionGenerator.generate(bytes, className, versionName);
    versionBytes = staticRewriter.rewriteVersion(versionBytes, className);
    return classDefiner.define(original, versionName, versionBytes);
  }

  /**
   * 分配版本实例但不执行构造器。
   *
   * @param type 版本类
   * @return 实例
   * @throws Exception Unsafe 不可用
   */
  private Object allocate(Class<?> type) throws Exception {
    Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    Field field = unsafeType.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Object unsafe = field.get(null);
    Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
    return allocate.invoke(unsafe, type);
  }

  /**
   * 获取 facade 的 delegate 字段。
   *
   * @param facadeClass facade 类
   * @return 字段
   * @throws NoSuchFieldException 不是 facade
   */
  private Field delegateField(Class<?> facadeClass) throws NoSuchFieldException {
    Field field = facadeClass.getDeclaredField(HotswapNames.DELEGATE_FIELD);
    field.setAccessible(true);
    return field;
  }

  /**
   * 记录 facade 的版本历史，供签名变化后旧 descriptor 调用回退。
   *
   * @param facade facade 对象
   * @param version 版本对象
   */
  private void recordVersionHistory(Object facade, Object version) {
    synchronized (versionHistory) {
      List<Object> list = versionHistory.get(facade);
      if (list == null) {
        list = new ArrayList<Object>();
        versionHistory.put(facade, list);
      }
      list.add(version);
    }
  }

  /**
   * 返回指定 facade 当前版本之前的版本，按从新到旧排序。
   *
   * @param facade facade 对象
   * @param current 当前版本
   * @return 更早版本
   */
  private List<Object> earlierVersions(Object facade, Object current) {
    synchronized (versionHistory) {
      List<Object> list = versionHistory.get(facade);
      if (list == null || list.size() <= 1) {
        return Collections.emptyList();
      }
      List<Object> result = new ArrayList<Object>();
      for (int index = list.size() - 1; index >= 0; index--) {
        Object value = list.get(index);
        if (value != current) {
          result.add(value);
        }
      }
      return result;
    }
  }

  /**
   * 注册 facade 弱引用，供 reload 时更新所有存活实例。
   *
   * @param facadeClass facade 类
   * @param facade facade 对象
   */
  private void registerFacade(Class<?> facadeClass, Object facade) {
    VersionState state = stateOf(facadeClass);
    if (state == null) {
      return;
    }
    synchronized (state.facades) {
      for (WeakReference<Object> reference : state.facades) {
        if (reference.get() == facade) {
          return;
        }
      }
      state.facades.add(new WeakReference<Object>(facade));
    }
  }

  /**
   * 返回仍存活的 facade 对象。
   *
   * @param state 版本状态
   * @return facade 列表
   */
  private List<Object> liveFacades(VersionState state) {
    List<Object> live = new ArrayList<Object>();
    synchronized (state.facades) {
      Iterator<WeakReference<Object>> iterator = state.facades.iterator();
      while (iterator.hasNext()) {
        Object facade = iterator.next().get();
        if (facade == null) {
          iterator.remove();
        }
        else {
          live.add(facade);
        }
      }
    }
    return live;
  }

  /**
   * 查找类对应的版本状态。
   *
   * @param type 类
   * @return 状态；未改写返回 null
   */
  private VersionState stateOf(Class<?> type) {
    if (type == null) {
      return null;
    }
    synchronized (states) {
      return states.get(type.getName());
    }
  }

  /**
   * 在已加载类中查找目标类。
   *
   * @param className 点分类名
   * @return 类
   */
  private Class<?> findLoadedClass(String className) {
    Class<?> candidate = null;
    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
      if (!loaded.getName().equals(className)) {
        continue;
      }
      if (loaded.getClassLoader() == ClassLoader.getSystemClassLoader()) {
        return loaded;
      }
      if (candidate == null) {
        candidate = loaded;
      }
    }
    return candidate;
  }

  /**
   * 判断类是否属于应用输出目录（jar 依赖不改写）。
   *
   * @param className 内部类名
   * @param protectionDomain protection domain
   * @return 应改写返回 true
   */
  private boolean shouldInstrument(String className, ProtectionDomain protectionDomain) {
    String dotted = className.replace('/', '.');
    if (dotted.startsWith("java.") || dotted.startsWith("javax.") || dotted.startsWith("sun.")
        || dotted.startsWith("jdk.") || dotted.startsWith("io.simpleide.hotswap.")
        || dotted.startsWith("org.springframework.") || dotted.startsWith("com.baomidou.")
        || dotted.startsWith("com.cloudhelios.") || dotted.startsWith("com.alibaba.")
        || dotted.startsWith("org.apache.") || dotted.startsWith("org.mybatis.")
        || dotted.startsWith("com.fasterxml.")
        || dotted.contains("$")
        || dotted.contains(HotswapNames.VERSION_PREFIX)
        || dotted.contains("$$EnhancerBySpringCGLIB$$")
        || dotted.contains("$$FastClassBySpringCGLIB$$")
        || dotted.contains("$$SpringCGLIB$$")
        || dotted.startsWith("$Proxy")) {
      return false;
    }
    if (!includePrefix.isEmpty() && !dotted.startsWith(includePrefix)) {
      return false;
    }
    if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
      java.net.URL location = protectionDomain.getCodeSource().getLocation();
      if (location != null && "file".equals(location.getProtocol())) {
        File file = new File(location.getPath());
        return file.isDirectory();
      }
    }
    return false;
  }

  /** 加载期 facade transformer。 */
  private final class FacadeTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
      if (classBeingRedefined != null || className == null) {
        return null;
      }
      if (!shouldInstrument(className, protectionDomain)) {
        return null;
      }
      String unsupported = HotswapPreflight.check(classfileBuffer);
      if (unsupported != null) {
        unsupportedOriginalBytes.put(className.replace('/', '.'), classfileBuffer.clone());
        return null;
      }
      try {
        String dotted = className.replace('/', '.');
        byte[] facade = instrumenter.instrument(classfileBuffer, dotted, loader);
        return staticRewriter.rewriteFacade(facade, dotted);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] cannot instrument " + className + ": " + error);
        return null;
      }
    }
  }

  /** 一个类的版本状态。 */
  private static final class VersionState {
    private byte[] original;
    private byte[] currentBytes;
    private volatile Class<?> currentVersionClass;
    private final AtomicInteger counter = new AtomicInteger(1);
    private final List<WeakReference<Object>> facades = new ArrayList<WeakReference<Object>>();
  }
}
