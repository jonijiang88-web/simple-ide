package io.simpleide.hotswap.integration.jackson;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.plugin.HotswapReloadEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Jackson 插件在无 Jackson 运行时的容错、异常隔离与反射缓存清理。
 */
class JacksonPluginTest {

  /** 空 context 列表不应影响 reload。 */
  @Test
  void ignoresEmptyContexts() {
    JacksonPlugin plugin = new TestPlugin(new ArrayList<Object>());

    // 验证：没有 Spring context 时 onReload 正常返回
    assertDoesNotThrow(() -> plugin.onReload(event()));
  }

  /** null 事件应直接忽略。 */
  @Test
  void ignoresNullEvent() {
    CountingContext context = new CountingContext();
    JacksonPlugin plugin = new TestPlugin(listOf(context));

    plugin.onReload(null);

    // 验证：null 事件不触发任何 context 访问
    assertEquals(0, context.beanFactoryCalls);
  }

  /** 单个 context 失败只记录错误，不向调用方抛出。 */
  @Test
  void isolatesContextFailure() {
    CountingContext context = new CountingContext();
    JacksonPlugin plugin = new TestPlugin(listOf(new BrokenContext(), context));
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(errors, true));
    try {
      // 验证：context 抛异常时 reload 主流程不中断
      assertDoesNotThrow(() -> plugin.onReload(event()));
    }
    finally {
      System.setErr(original);
    }
    // 验证：后续 context 仍被处理
    assertEquals(1, context.beanFactoryCalls);
    // 验证：失败被记录到 System.err
    assertTrue(errors.toString().contains("jackson cache flush failed"));
  }

  /** 单个 bean 创建失败不影响同类型其他 bean。 */
  @Test
  void skipsFailingBeanAndKeepsOthers() {
    FakeBeanFactory beanFactory = new FakeBeanFactory();
    FakeObjectMapper mapper = new FakeObjectMapper();
    beanFactory.add("broken", new FailingBean());
    beanFactory.add("mapper", mapper);
    List<Object> collected = new ArrayList<Object>();
    JacksonPlugin plugin = new JacksonPlugin();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(errors, true));
    try {
      plugin.collectBeans(beanFactory, FakeObjectMapper.class,
        FakeObjectMapper.class.getName(), collected);
    }
    finally {
      System.setErr(original);
    }

    // 验证：失败的 bean 被跳过，正常 bean 被收集
    assertEquals(1, collected.size());
    // 验证：收集到的是正常 mapper
    assertSame(mapper, collected.get(0));
    // 验证：bean 创建失败被记录到 System.err
    assertTrue(errors.toString().contains("jackson bean lookup failed"));
  }

  /** 按类型名查找时应兼容 XmlMapper 等子类 bean。 */
  @Test
  void collectsSubclassBeans() {
    FakeBeanFactory beanFactory = new FakeBeanFactory();
    FakeXmlMapper xmlMapper = new FakeXmlMapper();
    beanFactory.add("xmlMapper", xmlMapper);
    List<Object> collected = new ArrayList<Object>();
    JacksonPlugin plugin = new JacksonPlugin();

    plugin.collectBeans(beanFactory, FakeObjectMapper.class,
      FakeObjectMapper.class.getName(), collected);

    // 验证：子类 bean 被父类型查找到
    assertEquals(1, collected.size());
    // 验证：收集对象就是 XmlMapper 子类实例
    assertSame(xmlMapper, collected.get(0));
  }

  /** 同一 bean 被多个类型查询命中时只收集一次。 */
  @Test
  void avoidsDuplicateBeans() {
    FakeBeanFactory beanFactory = new FakeBeanFactory();
    FakeObjectMapper mapper = new FakeObjectMapper();
    beanFactory.add("mapper", mapper);
    List<Object> collected = new ArrayList<Object>();
    JacksonPlugin plugin = new JacksonPlugin();

    plugin.collectBeans(beanFactory, FakeObjectMapper.class,
      FakeObjectMapper.class.getName(), collected);
    plugin.collectBeans(beanFactory, FakeObjectMapper.class,
      FakeObjectMapper.class.getName(), collected);

    // 验证：重复查询不会重复收集
    assertEquals(1, collected.size());
  }

  /** 清理 ObjectMapper 时应覆盖所有 Jackson 2.13.5 缓存位置。 */
  @Test
  void clearsAllMapperCaches() {
    FakeObjectMapper mapper = new FakeObjectMapper();
    mapper._rootDeserializers.put("root", "value");
    mapper._serializerProvider._knownSerializers.put("known", "value");
    mapper._serializerProvider._rootDeserializers.put("root", "value");
    mapper._serializerProvider._serializerCache._mainTypeLookup.put("main", "value");
    mapper._deserializationContext._cache._cachedDeserializers.put("cached", "value");
    mapper._deserializationContext._cache._incompleteDeserializers.put("incomplete", "value");
    mapper._typeFactory._typeCache.put("type", "value");
    mapper._typeFactory._recursiveTypeCache.put("recursive", "value");
    JacksonPlugin plugin = new JacksonPlugin();

    plugin.flushMapper(mapper);

    // 验证：ObjectMapper 的 root 反序列化器缓存被清空
    assertTrue(mapper._rootDeserializers.isEmpty());
    // 验证：SerializerProvider 的 knownSerializers 缓存被清空
    assertTrue(mapper._serializerProvider._knownSerializers.isEmpty());
    // 验证：SerializerProvider 的 root 反序列化器缓存被清空
    assertTrue(mapper._serializerProvider._rootDeserializers.isEmpty());
    // 验证：SerializerCache 内部缓存被清空
    assertTrue(mapper._serializerProvider._serializerCache._mainTypeLookup.isEmpty());
    // 验证：DeserializerCache 的已缓存反序列化器被清空
    assertTrue(mapper._deserializationContext._cache._cachedDeserializers.isEmpty());
    // 验证：DeserializerCache 的未完成反序列化器被清空
    assertTrue(mapper._deserializationContext._cache._incompleteDeserializers.isEmpty());
    // 验证：TypeFactory 的类型缓存被清空
    assertTrue(mapper._typeFactory._typeCache.isEmpty());
    // 验证：TypeFactory 的递归类型缓存被清空
    assertTrue(mapper._typeFactory._recursiveTypeCache.isEmpty());
    // 验证：优先调用了 ObjectMapper.flushCaches()
    assertEquals(1, mapper.flushCachesCalls);
    // 验证：优先调用了 SerializerProvider.flushCaches()
    assertEquals(1, mapper._serializerProvider.flushCachesCalls);
    // 验证：优先调用了 SerializerCache.flush()
    assertEquals(1, mapper._serializerProvider._serializerCache.flushCalls);
    // 验证：优先调用了 DeserializerCache.flushCaches()
    assertEquals(1, mapper._deserializationContext._cache.flushCachesCalls);
    // 验证：优先调用了 TypeFactory.clearCache()
    assertEquals(1, mapper._typeFactory.clearCacheCalls);
  }

  /** 字段名变化时应回退到 DeserializationContext 上的其他缓存字段名。 */
  @Test
  void supportsAlternativeDeserializerCacheField() {
    AlternativeCacheMapper mapper = new AlternativeCacheMapper();
    mapper._deserializationContext._deserializerCache._cachedDeserializers.put("cached", "value");
    JacksonPlugin plugin = new JacksonPlugin();

    plugin.flushMapper(mapper);

    // 验证：`_deserializerCache` 字段名下的缓存也被清空
    assertTrue(mapper._deserializationContext._deserializerCache._cachedDeserializers.isEmpty());
  }

  /** 缺少 Jackson 字段或清理方法时不得抛出异常。 */
  @Test
  void toleratesMissingFieldsAndMethods() {
    JacksonPlugin plugin = new JacksonPlugin();

    // 验证：普通对象没有 Jackson 字段时清理是安全的
    assertDoesNotThrow(() -> plugin.flushMapper(new PlainObject()));
    // 验证：null mapper 被安全忽略
    assertDoesNotThrow(() -> plugin.flushMapper(null));
  }

  /** 反射查字段应支持父类继承，缺失字段返回 null。 */
  @Test
  void readsInheritedAndMissingFields() {
    InheritedFixture fixture = new InheritedFixture();
    fixture.value = "present";

    // 验证：可以读取父类声明的字段
    assertEquals("present", JacksonPlugin.readField(fixture, "value"));
    // 验证：缺失字段返回 null 而不是抛异常
    assertNull(JacksonPlugin.readField(fixture, "missing"));
    // 验证：可以在类层级中找到父类字段
    assertEquals("value", JacksonPlugin.findField(InheritedFixture.class, "value").getName());
  }

  /** clearMapField 只处理集合字段并容忍缺失字段。 */
  @Test
  void clearsOnlyCollectionFields() {
    PlainObject fixture = new PlainObject();
    fixture.map.put("key", "value");
    fixture.collection.add("value");

    // 验证：Map 字段被清空
    assertTrue(JacksonPlugin.clearMapField(fixture, "map"));
    // 验证：Map 字段内容确实为空
    assertTrue(fixture.map.isEmpty());
    // 验证：Collection 字段被清空
    assertTrue(JacksonPlugin.clearMapField(fixture, "collection"));
    // 验证：缺失字段返回 false
    assertFalse(JacksonPlugin.clearMapField(fixture, "missing"));
    // 验证：非集合字段不处理
    assertFalse(JacksonPlugin.clearMapField(fixture, "text"));
  }

  /** clearMapFields 应清理所有集合字段且保留普通字段。 */
  @Test
  void clearsAllCollectionFields() {
    PlainObject fixture = new PlainObject();
    fixture.map.put("key", "value");
    fixture.collection.add("value");

    int cleared = JacksonPlugin.clearMapFields(fixture);

    // 验证：Map 和 Collection 两个字段被清理
    assertEquals(2, cleared);
    // 验证：Map 字段为空
    assertTrue(fixture.map.isEmpty());
    // 验证：Collection 字段为空
    assertTrue(fixture.collection.isEmpty());
    // 验证：普通字符串字段保持不变
    assertEquals("text", fixture.text);
  }

  /** 类型名匹配应覆盖父类和接口。 */
  @Test
  void matchesTypeNamesInHierarchy() {
    // 验证：ArrayList 的类名层级命中 List 接口
    assertTrue(JacksonPlugin.hasTypeName(ArrayList.class, List.class.getName()));
    // 验证：ArrayList 的父类 AbstractList 被命中
    assertTrue(JacksonPlugin.hasTypeName(ArrayList.class, "java.util.AbstractList"));
    // 验证：不相关类型名不命中
    assertFalse(JacksonPlugin.hasTypeName(ArrayList.class, "com.fasterxml.jackson.databind.ObjectMapper"));
  }

  /**
   * 创建测试用 reload 事件。
   *
   * @return reload 事件
   */
  private static HotswapReloadEvent event() {
    return new HotswapReloadEvent("com.demo.Dto", PlainObject.class, null, ChangeKind.METHOD_BODY);
  }

  /**
   * 创建可变列表。
   *
   * @param values 初始值
   * @param <T> 元素类型
   * @return 列表
   */
  @SafeVarargs
  private static <T> List<T> listOf(T... values) {
    List<T> result = new ArrayList<T>();
    for (T value : values) {
      result.add(value);
    }
    return result;
  }

  /**
   * 可注入 context 列表的测试插件。
   */
  private static class TestPlugin extends JacksonPlugin {

    private final List<Object> contexts;

    /**
     * 创建测试插件。
     *
     * @param contexts 注入的 context 列表
     */
    TestPlugin(List<Object> contexts) {
      this.contexts = contexts;
    }

    /**
     * 返回注入的 context 列表。
     *
     * @return context 列表
     */
    @Override
    List<Object> springContexts() {
      return contexts;
    }
  }

  /**
   * 记录 beanFactory 访问次数的 context。
   */
  private static class CountingContext {

    private int beanFactoryCalls;

    /**
     * 返回 null beanFactory 并记录访问次数。
     *
     * @return null
     */
    public Object getBeanFactory() {
      beanFactoryCalls++;
      return null;
    }
  }

  /**
   * getBeanFactory 直接抛异常的 context。
   */
  private static class BrokenContext {

    /**
     * 模拟 Spring context 刷新失败。
     *
     * @return 不会返回
     */
    public Object getBeanFactory() {
      throw new IllegalStateException("broken context");
    }
  }

  /**
   * 测试用 BeanFactory，支持按类型查找并模拟 bean 创建失败。
   */
  private static class FakeBeanFactory {

    private final Map<String, Object> beans = new LinkedHashMap<String, Object>();

    /**
     * 注册 bean。
     *
     * @param name bean 名称
     * @param bean bean 实例
     */
    void add(String name, Object bean) {
      beans.put(name, bean);
    }

    /**
     * 按类型返回 bean 名称。
     *
     * @param type 目标类型
     * @param includeNonSingletons 是否包含非单例
     * @param allowEagerInit 是否允许提前初始化
     * @return bean 名称
     */
    public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons,
                                       boolean allowEagerInit) {
      List<String> names = new ArrayList<String>();
      for (Map.Entry<String, Object> entry : beans.entrySet()) {
        if (type.isInstance(entry.getValue())) {
          names.add(entry.getKey());
        }
      }
      return names.toArray(new String[0]);
    }

    /**
     * 按名称返回 bean，FailingBean 模拟创建失败。
     *
     * @param name bean 名称
     * @return bean 实例
     */
    public Object getBean(String name) {
      Object bean = beans.get(name);
      if (bean instanceof FailingBean) {
        throw new IllegalStateException("cannot create bean " + name);
      }
      return bean;
    }
  }

  /**
   * 模拟创建失败的 bean；继承 mapper 以便被类型查询命中。
   */
  private static class FailingBean extends FakeObjectMapper {
  }

  /**
   * 模拟 ObjectMapper 的缓存结构。
   */
  private static class FakeObjectMapper {

    final Map<Object, Object> _rootDeserializers = new HashMap<Object, Object>();
    final FakeSerializerProvider _serializerProvider = new FakeSerializerProvider();
    final FakeDeserializationContext _deserializationContext = new FakeDeserializationContext();
    final FakeTypeFactory _typeFactory = new FakeTypeFactory();
    int flushCachesCalls;

    /**
     * 模拟 ObjectMapper.flushCaches()。
     */
    public void flushCaches() {
      flushCachesCalls++;
    }
  }

  /**
   * 模拟 XmlMapper，验证子类 bean 兼容。
   */
  private static class FakeXmlMapper extends FakeObjectMapper {
  }

  /**
   * 模拟 DefaultSerializerProvider 的缓存结构。
   */
  private static class FakeSerializerProvider {

    final FakeSerializerCache _serializerCache = new FakeSerializerCache();
    final Map<Object, Object> _knownSerializers = new HashMap<Object, Object>();
    final Map<Object, Object> _rootDeserializers = new HashMap<Object, Object>();
    int flushCachesCalls;

    /**
     * 模拟 DefaultSerializerProvider.flushCaches()。
     */
    public void flushCaches() {
      flushCachesCalls++;
    }
  }

  /**
   * 模拟 SerializerCache 的缓存结构。
   */
  private static class FakeSerializerCache {

    final Map<Object, Object> _mainTypeLookup = new HashMap<Object, Object>();
    int flushCalls;

    /**
     * 模拟 SerializerCache.flush()。
     */
    public void flush() {
      flushCalls++;
    }
  }

  /**
   * 模拟 DeserializationContext 的缓存结构。
   */
  private static class FakeDeserializationContext {

    final FakeDeserializerCache _cache = new FakeDeserializerCache();
  }

  /**
   * 使用备选字段名的 DeserializationContext。
   */
  private static class AlternativeDeserializationContext {

    final FakeDeserializerCache _deserializerCache = new FakeDeserializerCache();
  }

  /**
   * 使用备选缓存字段名的 ObjectMapper。
   */
  private static class AlternativeCacheMapper {

    final AlternativeDeserializationContext _deserializationContext =
      new AlternativeDeserializationContext();
  }

  /**
   * 模拟 DeserializerCache 的缓存结构。
   */
  private static class FakeDeserializerCache {

    final Map<Object, Object> _cachedDeserializers = new HashMap<Object, Object>();
    final Map<Object, Object> _incompleteDeserializers = new HashMap<Object, Object>();
    int flushCachesCalls;

    /**
     * 模拟 DeserializerCache.flushCaches()。
     */
    public void flushCaches() {
      flushCachesCalls++;
    }
  }

  /**
   * 模拟 TypeFactory 的缓存结构。
   */
  private static class FakeTypeFactory {

    final Map<Object, Object> _typeCache = new HashMap<Object, Object>();
    final Map<Object, Object> _recursiveTypeCache = new HashMap<Object, Object>();
    int clearCacheCalls;

    /**
     * 模拟 TypeFactory.clearCache()。
     */
    public void clearCache() {
      clearCacheCalls++;
    }
  }

  /**
   * 普通对象，用于验证缺失字段的容错。
   */
  private static class PlainObject {

    final Map<Object, Object> map = new HashMap<Object, Object>();
    final Collection<Object> collection = new ArrayList<Object>();
    final String text = "text";
  }

  /**
   * 父类字段夹具。
   */
  private static class ParentFixture {

    String value;
  }

  /**
   * 继承父类字段的夹具。
   */
  private static class InheritedFixture extends ParentFixture {
  }
}
