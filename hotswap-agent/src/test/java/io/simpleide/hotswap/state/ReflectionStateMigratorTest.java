package io.simpleide.hotswap.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.simpleide.hotswap.state.fixtures.StateFixtures.MismatchV1;
import io.simpleide.hotswap.state.fixtures.StateFixtures.MismatchV2;
import io.simpleide.hotswap.state.fixtures.StateFixtures.ModelV1;
import io.simpleide.hotswap.state.fixtures.StateFixtures.ModelV2;
import io.simpleide.hotswap.state.fixtures.StateFixtures.SubV1;
import io.simpleide.hotswap.state.fixtures.StateFixtures.SubV2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ReflectionStateMigrator} 的行为测试。
 */
class ReflectionStateMigratorTest {

  /** 被测迁移器。 */
  private final ReflectionStateMigrator migrator = new ReflectionStateMigrator();

  /**
   * 验证旧对象字段被复制、引用保持同一对象、新字段保持 JVM 默认值。
   *
   * @throws Exception 迁移失败时测试失败
   */
  @Test
  void copiesExistingFieldsAndKeepsNewFieldsAtDefaults() throws Exception {
    ModelV1 from = new ModelV1(7);
    from.count = 42;
    from.name = "hello";
    List<String> items = new ArrayList<String>();
    items.add("a");
    from.items = items;
    from.removed = 99;
    ModelV2 to = new ModelV2(0);

    migrator.copy(from, to);

    // 验证：旧对象的 int 字段值被复制到新对象
    assertEquals(42, to.count);
    // 验证：引用字段复制的是同一个对象引用
    assertSame(from.name, to.name);
    // 验证：集合字段保持同一个 List 实例
    assertSame(items, to.items);
    // 验证：final 字段通过 Unsafe 写入成功
    assertEquals(7, to.id);
    // 验证：新版本新增的 int 字段保持 JVM 默认值 0
    assertEquals(0, to.added);
    // 验证：新版本新增的引用字段保持 null
    assertNull(to.addedReference);
  }

  /**
   * 验证父类和子类声明的字段都会按继承层次复制。
   *
   * @throws Exception 迁移失败时测试失败
   */
  @Test
  void copiesFieldsAcrossClassHierarchy() throws Exception {
    SubV1 from = new SubV1();
    from.base = 3;
    from.baseName = "base";
    from.sub = 9L;
    SubV2 to = new SubV2();

    migrator.copy(from, to);

    // 验证：父类字段被复制
    assertEquals(3, to.base);
    // 验证：父类引用字段被复制
    assertSame(from.baseName, to.baseName);
    // 验证：子类字段被复制
    assertEquals(9L, to.sub);
    // 验证：新版本父类新增字段保持默认值
    assertEquals(0, to.baseAdded);
    // 验证：新版本子类新增字段保持默认值
    assertEquals(0, to.subAdded);
  }

  /**
   * 验证同名字段类型不一致时抛出明确异常，而不是静默强转。
   */
  @Test
  void rejectsFieldTypeChange() {
    MismatchV1 from = new MismatchV1();
    from.prefix = 3;
    from.value = 5;
    MismatchV2 to = new MismatchV2();

    IllegalStateException error = assertThrows(IllegalStateException.class,
      () -> migrator.copy(from, to));

    // 验证：异常信息包含发生冲突的字段名
    assertTrue(error.getMessage().contains("value"), error.getMessage());
    // 验证：异常信息说明原类型为 int
    assertTrue(error.getMessage().contains("int"), error.getMessage());
    // 验证：异常信息说明目标类型为 java.lang.String
    assertTrue(error.getMessage().contains("java.lang.String"), error.getMessage());
    // 验证：校验失败时类型一致的字段也没有被部分复制
    assertEquals(0, to.prefix);
  }

  /**
   * 验证静态字段不会参与实例字段迁移。
   *
   * @throws Exception 迁移失败时测试失败
   */
  @Test
  void skipsStaticFields() throws Exception {
    ModelV1 from = new ModelV1(1);
    from.count = 6;
    ModelV2 to = new ModelV2(0);
    ModelV1.shared = 123;
    ModelV2.shared = 0;
    try {
      migrator.copy(from, to);

      // 验证：目标类的静态字段保持自身初值，没有被源对象覆盖
      assertEquals(0, ModelV2.shared);
      // 验证：源类的静态字段保持不变
      assertEquals(123, ModelV1.shared);
    }
    finally {
      ModelV1.shared = 0;
      ModelV2.shared = 0;
    }
  }

  /**
   * 验证空对象和同一对象等边界输入。
   */
  @Test
  void handlesNullAndSameObject() throws Exception {
    ModelV1 from = new ModelV1(1);
    from.count = 8;

    // 验证：from 为 null 时抛出参数异常
    assertThrows(IllegalArgumentException.class, () -> migrator.copy(null, new ModelV2(0)));
    // 验证：to 为 null 时抛出参数异常
    assertThrows(IllegalArgumentException.class, () -> migrator.copy(from, null));

    migrator.copy(from, from);

    // 验证：同一对象迁移后字段值不变
    assertEquals(8, from.count);
  }
}
