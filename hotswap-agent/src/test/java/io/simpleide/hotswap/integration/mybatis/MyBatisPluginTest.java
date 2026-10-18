package io.simpleide.hotswap.integration.mybatis;

import io.simpleide.hotswap.integration.spring.SpringContextBridge;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MyBatis 插件在不加载 MyBatis 运行时的行为与反射辅助逻辑。
 */
class MyBatisPluginTest {

  /** 每个测试结束后清空 Spring context 桥，避免测试相互影响。 */
  @AfterEach
  void clearContexts() throws Exception {
    Field field = SpringContextBridge.class.getDeclaredField("CONTEXTS");
    field.setAccessible(true);
    ((List<?>) field.get(null)).clear();
  }

  /** 空事件不应抛出异常。 */
  @Test
  void ignoresNullEvent() {
    // 验证：null 事件被忽略且不抛异常
    assertDoesNotThrow(() -> new MyBatisPlugin().onReload(null));
  }

  /** facade 为空的普通事件不应抛出异常。 */
  @Test
  void ignoresEventWithoutFacade() {
    MyBatisPlugin plugin = new MyBatisPlugin();
    // 验证：facade 为 null 的事件被忽略
    assertDoesNotThrow(() -> plugin.onReload(
      new HotswapReloadEvent("com.demo.Mapper", null, null, null)));
  }

  /** 没有 MyBatis 运行时和 context 时应静默跳过。 */
  @Test
  void skipsWhenMyBatisIsAbsent() {
    SpringContextBridge.capture(new FakeContext());
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream original = System.err;
    try {
      System.setErr(new PrintStream(err));
      MyBatisPlugin plugin = new MyBatisPlugin();
      // 验证：无 MyBatis 时不抛异常
      assertDoesNotThrow(() -> plugin.onReload(
        new HotswapReloadEvent("com.demo.Mapper", FakeMapper.class, null, null)));
    }
    finally {
      System.setErr(original);
    }
    // 验证：无 MyBatis 时不输出错误
    assertEquals("", err.toString());
  }

  /** 单个刷新动作的异常必须被隔离并写入 System.err。 */
  @Test
  void isolatesAndReportsActionFailure() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream original = System.err;
    try {
      System.setErr(new PrintStream(err));
      // 验证：guarded 不向调用方抛出异常
      assertDoesNotThrow(() -> MyBatisPlugin.guarded("unit action", () -> {
        throw new IllegalStateException("boom");
      }));
    }
    finally {
      System.setErr(original);
    }
    // 验证：错误信息包含动作描述和原始异常
    assertTrue(err.toString().contains("unit action"));
    // 验证：错误信息包含原始异常内容
    assertTrue(err.toString().contains("boom"));
  }

  /** 命名空间匹配应同时支持内部类和点分命名空间。 */
  @Test
  void matchesNamespaceWithNestedClass() {
    // 验证：完全同名的命名空间匹配
    assertTrue(MyBatisPlugin.namespaceMatches("com.demo.Mapper", "com.demo.Mapper"));
    // 验证：内部类名与点分命名空间等价
    assertTrue(MyBatisPlugin.namespaceMatches("com.demo.Outer.Inner", "com.demo.Outer$Inner"));
    // 验证：前缀相同的其他命名空间不匹配
    assertFalse(MyBatisPlugin.namespaceMatches("com.demo.MapperOther", "com.demo.Mapper"));
    // 验证：null 参数不匹配
    assertFalse(MyBatisPlugin.namespaceMatches(null, "com.demo.Mapper"));
  }

  /** XML 资源路径应由命名空间转换而来。 */
  @Test
  void buildsXmlResourcePath() {
    // 验证：点号转换为目录分隔符并追加 .xml
    assertEquals("com/demo/Mapper.xml", MyBatisPlugin.xmlResourceFor("com.demo.Mapper"));
  }

  /** 命名空间 key 清理只应命中该命名空间及其语句。 */
  @Test
  void filtersIdsByNamespace() {
    Set<String> keys = new LinkedHashSet<String>(Arrays.asList(
      "com.demo.Mapper", "com.demo.Mapper.selectById", "com.demo.MapperOther.selectById"));
    List<String> matched = MyBatisPlugin.idsInNamespace(keys, "com.demo.Mapper");
    // 验证：只保留命名空间自身与其语句
    assertEquals(2, matched.size());
    // 验证：包含命名空间自身
    assertEquals("com.demo.Mapper", matched.get(0));
    // 验证：包含命名空间下的语句
    assertEquals("com.demo.Mapper.selectById", matched.get(1));
  }

  /** 重新解析前应移除 XML 与 namespace 的已加载标记。 */
  @Test
  void listsLoadedResourcesToReset() {
    List<String> resources = MyBatisPlugin.loadedResourcesFor("com.demo.Mapper");
    // 验证：包含 XML 资源标记
    assertTrue(resources.contains("com/demo/Mapper.xml"));
    // 验证：包含 namespace 标记
    assertTrue(resources.contains("namespace:com.demo.Mapper"));
    // 验证：包含 mapper 标记
    assertTrue(resources.contains("mapper:com.demo.Mapper"));
  }

  /** 按字段名扫描应当清理父类中的 methodCache。 */
  @Test
  void clearsMethodCacheFieldFromSuperclass() throws Exception {
    CacheFixture fixture = new CacheFixture();
    fixture.cache().put("a", "b");
    MyBatisPlugin.clearFieldsNamed(fixture, "methodCache");
    // 验证：父类 methodCache 被清空
    assertTrue(fixture.cache().isEmpty());
  }

  /** 字段查找应沿父类层级向上。 */
  @Test
  void findsFieldInSuperclass() {
    // 验证：可以找到父类声明的 methodCache 字段
    assertNotNull(MyBatisPlugin.findField(CacheFixture.class, "methodCache"));
    // 验证：不存在的字段返回 null
    assertNull(MyBatisPlugin.findField(CacheFixture.class, "missing"));
  }

  /** 缓存字段名判定只接受已知的 MyBatis 缓存字段。 */
  @Test
  void recognizesCacheFieldNames() {
    // 验证：methodCache 是缓存字段
    assertTrue(MyBatisPlugin.isCacheFieldName("methodCache"));
    // 验证：reflectorMap 是缓存字段
    assertTrue(MyBatisPlugin.isCacheFieldName("reflectorMap"));
    // 验证：普通字段不是缓存字段
    assertFalse(MyBatisPlugin.isCacheFieldName("mappedStatements"));
  }

  /** 未注册 mapper 接口不应被误判为 MyBatis mapper。 */
  @Test
  void skipsNonMapperInterfaceWithoutMyBatis() {
    SpringContextBridge.capture(new FakeContext());
    MyBatisPlugin plugin = new MyBatisPlugin();
    // 验证：普通接口在无 MyBatis 时被静默跳过
    assertDoesNotThrow(() -> plugin.onReload(
      new HotswapReloadEvent("java.lang.Runnable", Runnable.class, null, null)));
  }

  /** 用于测试反射缓存的父类 fixture。 */
  private static class CacheBase {

    /** 模拟 MapperProxy 的 methodCache。 */
    private final Map<String, String> methodCache = new HashMap<String, String>();

    /**
     * 返回 methodCache，便于测试断言。
     *
     * @return 方法缓存
     */
    Map<String, String> cache() {
      return methodCache;
    }
  }

  /** 用于测试反射缓存的子类 fixture。 */
  private static class CacheFixture extends CacheBase {
  }

  /** 模拟 Spring context，getBeanFactory 返回 null。 */
  private static final class FakeContext {

    /**
     * 返回空 BeanFactory。
     *
     * @return null
     */
    public Object getBeanFactory() {
      return null;
    }
  }

  /** 用于构造 reload 事件的接口 fixture。 */
  private interface FakeMapper {
  }
}
