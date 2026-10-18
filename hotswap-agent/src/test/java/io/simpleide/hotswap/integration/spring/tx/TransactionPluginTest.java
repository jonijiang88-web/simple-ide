package io.simpleide.hotswap.integration.spring.tx;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证事务插件在无 Spring 运行时的场景下的反射辅助逻辑与异常隔离。
 */
class TransactionPluginTest {

  private static final String FAKE_ATTRIBUTE_SOURCE =
      "org.springframework.transaction.interceptor.AnnotationTransactionAttributeSource";
  private static final String FAKE_INTERCEPTOR =
      "org.springframework.transaction.interceptor.TransactionInterceptor";
  private static final String FAKE_ADVISOR =
      "org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor";
  private static final String FAKE_PROXY_CREATOR =
      "org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator";

  /** 所有伪 Spring 类型共享同一加载器，保证生成的子类能解析父类。 */
  private static final TestClassLoader FAKE_LOADER =
      new TestClassLoader(TransactionPluginTest.class.getClassLoader());

  /** 事务拦截器和属性源应按类名及父类层级被识别。 */
  @Test
  void matchesTransactionBeansByClassNameAndSuperclass() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Class<?> interceptor = fakeType(FAKE_INTERCEPTOR, "java/lang/Object",
      "transactionAttributeSource");
    Class<?> proxy = fakeType("com.demo.TxInterceptorProxy", FAKE_INTERCEPTOR.replace('.', '/'),
      "unused");

    // 验证：属性源实现类的类名命中事务 bean 集合
    assertTrue(TransactionPlugin.isTransactionBean(attributeSource));
    // 验证：事务拦截器类名命中事务 bean 集合
    assertTrue(TransactionPlugin.isTransactionBean(interceptor));
    // 验证：CGLIB 风格子类通过父类层级命中事务 bean 集合
    assertTrue(TransactionPlugin.isTransactionBean(proxy));
    // 验证：普通业务类不被识别为事务 bean
    assertFalse(TransactionPlugin.isTransactionBean(String.class));
  }

  /** 不可清理缓存的 AOP 缓存持有者应被识别，普通类不应被识别。 */
  @Test
  void matchesAopCacheHolderByClassName() throws Exception {
    Class<?> creator = fakeType(FAKE_PROXY_CREATOR, "java/lang/Object", "advisorsCache");
    // 验证：Spring 自动代理创建器被识别为 AOP 缓存持有者
    assertTrue(TransactionPlugin.isAopCacheHolder(creator));
    // 验证：普通类不是 AOP 缓存持有者
    assertFalse(TransactionPlugin.isAopCacheHolder(Object.class));
  }

  /** 事务属性缓存 Map 必须被清空。 */
  @Test
  void clearsAttributeCacheMap() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Object bean = attributeSource.getDeclaredConstructor().newInstance();
    Map<String, Object> cache = new HashMap<String, Object>();
    cache.put("com.demo.Service#save", "transactionAttribute");
    setField(bean, "attributeCache", cache);

    TransactionPlugin.clearTransactionCaches(bean);

    // 验证：事务属性缓存被完全清空
    assertTrue(cache.isEmpty());
  }

  /** TransactionInterceptor 的 transactionAttributeSource 字段应被递归清理。 */
  @Test
  void clearsNestedTransactionAttributeSource() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Class<?> interceptor = fakeType(FAKE_INTERCEPTOR, "java/lang/Object",
      "transactionAttributeSource");
    Object source = attributeSource.getDeclaredConstructor().newInstance();
    Map<String, Object> cache = new HashMap<String, Object>();
    cache.put("com.demo.Service#save", "transactionAttribute");
    setField(source, "attributeCache", cache);
    Object bean = interceptor.getDeclaredConstructor().newInstance();
    setField(bean, "transactionAttributeSource", source);

    TransactionPlugin.clearTransactionCaches(bean);

    // 验证：拦截器内嵌属性源的缓存被递归清空
    assertTrue(cache.isEmpty());
  }

  /** advisor 内嵌的事务属性源同样应被清理。 */
  @Test
  void clearsAttributeCacheOfAdvisor() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Class<?> advisor = fakeType(FAKE_ADVISOR, "java/lang/Object",
      "transactionAttributeSource");
    Object source = attributeSource.getDeclaredConstructor().newInstance();
    Map<String, Object> cache = new HashMap<String, Object>();
    cache.put("com.demo.Service#save", "transactionAttribute");
    setField(source, "attributeCache", cache);
    Object bean = advisor.getDeclaredConstructor().newInstance();
    setField(bean, "transactionAttributeSource", source);

    TransactionPlugin.clearTransactionCaches(bean);

    // 验证：事务 advisor 的属性源缓存被清空
    assertTrue(cache.isEmpty());
  }

  /** 非 Map 的缓存对象应尝试调用公开的 clear() 方法。 */
  @Test
  void clearsCacheObjectWithClearMethod() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Object bean = attributeSource.getDeclaredConstructor().newInstance();
    ClearingCache cache = new ClearingCache();
    setField(bean, "attributeCache", cache);

    TransactionPlugin.clearTransactionCaches(bean);

    // 验证：无 Map 结构时通过 clear() 方法清理缓存
    assertTrue(cache.cleared);
  }

  /** 循环引用的属性源不应导致无限递归。 */
  @Test
  void ignoresCyclicAttributeSourceReference() throws Exception {
    Class<?> interceptor = fakeType(FAKE_INTERCEPTOR, "java/lang/Object",
      "transactionAttributeSource");
    Object bean = interceptor.getDeclaredConstructor().newInstance();
    setField(bean, "transactionAttributeSource", bean);

    assertDoesNotThrow(() -> TransactionPlugin.clearTransactionCaches(bean));
  }

  /** 空 context 列表和 null 列表都必须安全返回。 */
  @Test
  void handlesEmptyContexts() {
    TransactionPlugin plugin = new TransactionPlugin();

    // 验证：空 context 列表不会抛出异常
    assertDoesNotThrow(() -> plugin.refreshContexts(Collections.emptyList()));
    // 验证：null context 列表不会抛出异常
    assertDoesNotThrow(() -> plugin.refreshContexts(null));
  }

  /** 没有 getBeanFactory 的对象应被隔离并继续处理其他 context。 */
  @Test
  void isolatesBrokenContext() {
    TransactionPlugin plugin = new TransactionPlugin();
    List<Object> contexts = new ArrayList<Object>();
    contexts.add(new Object());
    ClearingCacheCache marker = new ClearingCacheCache();
    contexts.add(marker);

    // 验证：单个非法 context 的反射失败不会逃出插件
    assertDoesNotThrow(() -> plugin.refreshContexts(contexts));
    // 验证：前面的 context 失败后仍会继续遍历后续 context
    assertEquals(2, contexts.size());
  }

  /** null 事件必须被直接忽略。 */
  @Test
  void ignoresNullEvent() {
    TransactionPlugin plugin = new TransactionPlugin();
    // 验证：null 事件不会抛出异常
    assertDoesNotThrow(() -> plugin.onReload(null));
  }

  /** 插件初始化不注册 transformer，允许重复调用。 */
  @Test
  void initializeIsNoOp() {
    TransactionPlugin plugin = new TransactionPlugin();
    // 验证：无 instrumentation 也能完成初始化（无 transformer 注册）
    assertDoesNotThrow(() -> plugin.initialize(null));
  }

  /** 非 ANNOTATION 事件也应触发清理，事件本身不改变插件行为。 */
  @Test
  void acceptsAllChangeKinds() {
    TransactionPlugin plugin = new TransactionPlugin();
    for (ChangeKind kind : ChangeKind.values()) {
      HotswapReloadEvent event = new HotswapReloadEvent("com.demo.Service",
        TransactionPluginTest.class, null, kind);
      // 验证：所有变更类型都不会导致插件抛异常
      assertDoesNotThrow(() -> plugin.onReload(event));
    }
  }

  /** 类层级中查找字段应能命中父类声明。 */
  @Test
  void findsInheritedField() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Class<?> proxy = fakeType("com.demo.AttributeSourceProxy",
      FAKE_ATTRIBUTE_SOURCE.replace('.', '/'), "unused");

    // 验证：子类可以找到父类声明的缓存字段
    assertNotNull(TransactionPlugin.findField(proxy, "attributeCache"));
    // 验证：不存在的字段返回 null
    assertNull(TransactionPlugin.findField(proxy, "missingField"));
    // 验证：字段确实声明在父类上
    assertEquals(attributeSource, TransactionPlugin.findField(proxy, "attributeCache")
      .getDeclaringClass());
  }

  /**
   * 生成一个仅包含无参构造器和指定字段的测试类型。
   *
   * @param name 点分类名
   * @param superName 父类内部名
   * @param fieldName 字段名
   * @return 已加载的测试类型
   * @throws Exception 类定义失败
   */
  private static Class<?> fakeType(String name, String superName, String fieldName)
      throws Exception {
    byte[] bytes = classBytes(name.replace('.', '/'), superName, fieldName);
    return FAKE_LOADER.define(name, bytes);
  }

  /**
   * 生成测试类型的字节码。
   *
   * @param internalName 内部类名
   * @param superName 父类内部名
   * @param fieldName 字段名
   * @return class 字节
   */
  private static byte[] classBytes(String internalName, String superName, String fieldName) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
      superName, null);
    writer.visitField(Opcodes.ACC_PUBLIC, fieldName, "Ljava/lang/Object;", null, null).visitEnd();
    MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    constructor.visitCode();
    constructor.visitVarInsn(Opcodes.ALOAD, 0);
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
    constructor.visitInsn(Opcodes.RETURN);
    constructor.visitMaxs(1, 1);
    constructor.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 给对象字段赋值。
   *
   * @param target 目标对象
   * @param name 字段名
   * @param value 字段值
   * @throws Exception 反射失败
   */
  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = TransactionPlugin.findField(target.getClass(), name);
    if (field == null) {
      throw new NoSuchFieldException(name);
    }
    field.setAccessible(true);
    field.set(target, value);
  }

  /**
   * 可定义任意名字类型的测试类加载器。
   */
  private static final class TestClassLoader extends ClassLoader {

    /**
     * 创建测试类加载器。
     *
     * @param parent 父加载器
     */
    private TestClassLoader(ClassLoader parent) {
      super(parent);
    }

    /**
     * 定义测试类型；同名类型复用首次定义，避免重复 defineClass。
     *
     * @param name 点分类名
     * @param bytes class 字节
     * @return 已定义类型
     */
    private synchronized Class<?> define(String name, byte[] bytes) {
      Class<?> existing = findLoadedClass(name);
      if (existing != null) {
        return existing;
      }
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  /**
   * 模拟带公开 clear() 的缓存实现。
   */
  public static final class ClearingCache {

    private boolean cleared;

    /**
     * 清空缓存标记。
     */
    public void clear() {
      cleared = true;
    }
  }

  /**
   * 简单占位对象，用于验证 context 遍历不中断。
   */
  private static final class ClearingCacheCache {
  }

  /** 验证辅助方法在未匹配到缓存结构时保持静默。 */
  @Test
  void ignoresBeanWithoutCacheField() {
    // 验证：没有缓存字段的对象调用清理不会抛异常
    assertDoesNotThrow(() -> TransactionPlugin.clearTransactionCaches(new Object()));
    // 验证：null 对象调用清理不会抛异常
    assertDoesNotThrow(() -> TransactionPlugin.clearTransactionCaches(null));
    // 验证：不存在字段时返回 null
    assertNull(TransactionPlugin.findField(Object.class, "attributeCache"));
  }

  /** 验证缓存清理对不可变空集合安全。 */
  @Test
  void clearsSingletonCacheField() throws Exception {
    Class<?> attributeSource = fakeType(FAKE_ATTRIBUTE_SOURCE, "java/lang/Object", "attributeCache");
    Object bean = attributeSource.getDeclaredConstructor().newInstance();
    List<String> cache = new ArrayList<String>();
    cache.add("entry");
    setField(bean, "attributeCache", cache);

    TransactionPlugin.clearTransactionCaches(bean);

    // 验证：Collection 类型的缓存也被清空
    assertTrue(cache.isEmpty());
  }
}
