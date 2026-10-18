package io.simpleide.hotswap.integration.spring.cache;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring Cache 插件在无 Spring 运行时的行为、异常隔离与反射清理逻辑。
 */
class CachePluginTest {

  /** 没有捕获到 context 时 reload 应直接返回。 */
  @Test
  void emptyContextsAreIgnored() {
    // 验证：空 context 列表不会抛出异常
    assertDoesNotThrow(() -> CachePlugin.refreshAll(new ArrayList<Object>(), event()));
  }

  /** context 处理失败必须被隔离，只记录到 stderr。 */
  @Test
  void brokenContextIsIsolated() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    try {
      // 验证：getBeanFactory 抛异常的 context 不会向外传播异常
      assertDoesNotThrow(() -> CachePlugin.refreshAll(
        Arrays.<Object>asList(new BrokenContext()), event()));
    }
    finally {
      System.setErr(original);
    }
    String output = captured.toString();
    // 验证：失败信息写入 stderr 便于排查
    assertTrue(output.contains("cache metadata refresh failed"), output);
    // 验证：stderr 中包含失败 context 的类名
    assertTrue(output.contains(BrokenContext.class.getName()), output);
  }

  /** 缺少 Spring 类时应静默跳过，不影响非 Cache 应用。 */
  @Test
  void contextWithoutSpringCacheIsIgnored() {
    // 验证：测试类路径没有 Spring Cache 时刷新不抛异常
    assertDoesNotThrow(() -> CachePlugin.refreshAll(
      Arrays.<Object>asList(new EmptyContext()), event()));
  }

  /** 应清空 AbstractFallbackCacheOperationSource 的 attributeCache。 */
  @Test
  void clearsAttributeCache() {
    FakeAnnotationCacheOperationSource source = new FakeAnnotationCacheOperationSource();
    source.attributeCache.put("com.demo.Service#get()", new Object());
    CachePlugin.clearOperationSource(source);
    // 验证：注解元数据缓存被清空
    assertTrue(source.attributeCache.isEmpty());
  }

  /** 应递归清理 CacheInterceptor 的 cacheOperationSource 与组合操作源。 */
  @Test
  void clearsNestedOperationSources() {
    FakeAnnotationCacheOperationSource first = new FakeAnnotationCacheOperationSource();
    first.attributeCache.put("first", new Object());
    FakeAnnotationCacheOperationSource second = new FakeAnnotationCacheOperationSource();
    second.attributeCache.put("second", new Object());
    FakeCompositeCacheOperationSource composite =
      new FakeCompositeCacheOperationSource(Arrays.<Object>asList(first, second));
    FakeCacheInterceptor interceptor = new FakeCacheInterceptor(composite);

    CachePlugin.clearOperationSource(interceptor);

    // 验证：组合操作源中第一个子源的元数据被清空
    assertTrue(first.attributeCache.isEmpty());
    // 验证：组合操作源中第二个子源的元数据被清空
    assertTrue(second.attributeCache.isEmpty());
  }

  /** 循环引用不应导致无限递归。 */
  @Test
  void cyclicOperationSourcesAreHandledOnce() {
    FakeCompositeCacheOperationSource composite = new FakeCompositeCacheOperationSource();
    FakeAnnotationCacheOperationSource source = new FakeAnnotationCacheOperationSource();
    composite.cacheOperationSources.add(source);
    composite.cacheOperationSources.add(composite);
    source.attributeCache.put("key", new Object());

    // 验证：存在自引用时清理仍能正常结束
    assertDoesNotThrow(() -> CachePlugin.clearOperationSource(composite));
    // 验证：自引用场景下元数据仍被清空
    assertTrue(source.attributeCache.isEmpty());
  }

  /** 不含缓存相关字段的对象应保持原样。 */
  @Test
  void unrelatedObjectIsUntouched() {
    Map<String, Object> businessCache = new HashMap<String, Object>();
    businessCache.put("order", new Object());

    CachePlugin.clearOperationSource(new UnrelatedBean(businessCache));

    // 验证：非缓存元数据的业务 Map 不被清理
    assertEquals(1, businessCache.size());
    // 验证：业务缓存内容保持原值
    assertTrue(businessCache.containsKey("order"));
  }

  /** 按类名判断类型时兼容父类与接口。 */
  @Test
  void detectsTypeByName() {
    // 验证：子类可匹配父类名
    assertTrue(CachePlugin.hasTypeName(SubSource.class,
      FakeAnnotationCacheOperationSource.class.getName()));
    // 验证：实现类可匹配接口名
    assertTrue(CachePlugin.hasTypeName(FakeAnnotationCacheOperationSource.class,
      FakeOperationSource.class.getName()));
    // 验证：无关类型不误匹配
    assertFalse(CachePlugin.hasTypeName(UnrelatedBean.class,
      FakeOperationSource.class.getName()));
  }

  /**
   * 构造注解变更事件。
   *
   * @return reload 事件
   */
  private static HotswapReloadEvent event() {
    return new HotswapReloadEvent("com.demo.Service", Object.class, null, ChangeKind.ANNOTATION);
  }

  /** 模拟 getBeanFactory 失败的 context。 */
  private static final class BrokenContext {

    /**
     * 模拟获取 BeanFactory 失败。
     *
     * @return 不会返回
     */
    public Object getBeanFactory() {
      throw new IllegalStateException("boom");
    }
  }

  /** 模拟返回空 BeanFactory 的 context。 */
  private static final class EmptyContext {

    /**
     * 返回空 BeanFactory。
     *
     * @return BeanFactory
     */
    public Object getBeanFactory() {
      return new EmptyBeanFactory();
    }
  }

  /** 模拟未注册任何 bean 的 BeanFactory。 */
  private static final class EmptyBeanFactory {

    /**
     * 返回空的 bean 名称数组。
     *
     * @param type 类型
     * @param includeNonSingletons 是否包含非单例
     * @param allowEagerInit 是否允许提前初始化
     * @return 空数组
     */
    public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons,
                                        boolean allowEagerInit) {
      return new String[0];
    }
  }

  /** 模拟 Spring 缓存操作源接口。 */
  private interface FakeOperationSource {
  }

  /** 模拟 AnnotationCacheOperationSource 的 attributeCache 字段。 */
  private static class FakeAnnotationCacheOperationSource implements FakeOperationSource {

    private final Map<String, Object> attributeCache = new HashMap<String, Object>();
  }

  /** 模拟子类，用于按类名匹配测试。 */
  private static final class SubSource extends FakeAnnotationCacheOperationSource {
  }

  /** 模拟 CompositeCacheOperationSource 的 cacheOperationSources 字段。 */
  private static final class FakeCompositeCacheOperationSource implements FakeOperationSource {

    private final Collection<Object> cacheOperationSources = new ArrayList<Object>();

    /**
     * 创建组合操作源。
     *
     * @param sources 子操作源
     */
    FakeCompositeCacheOperationSource(Collection<Object> sources) {
      cacheOperationSources.addAll(sources);
    }

    /** 创建空组合操作源。 */
    FakeCompositeCacheOperationSource() {
    }
  }

  /** 模拟 CacheInterceptor 的 cacheOperationSource 字段。 */
  private static final class FakeCacheInterceptor {

    private final Object cacheOperationSource;

    /**
     * 创建模拟拦截器。
     *
     * @param cacheOperationSource 缓存操作源
     */
    FakeCacheInterceptor(Object cacheOperationSource) {
      this.cacheOperationSource = cacheOperationSource;
    }
  }

  /** 模拟与缓存无关的 bean。 */
  private static final class UnrelatedBean {

    private final Map<String, Object> businessCache;

    /**
     * 创建无关 bean。
     *
     * @param businessCache 业务缓存
     */
    UnrelatedBean(Map<String, Object> businessCache) {
      this.businessCache = businessCache;
    }
  }
}
