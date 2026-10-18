package io.simpleide.hotswap.classify;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.api.SchemaClassifier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 ASM 变更分类器对各类 class 变化的判定。
 */
public final class AsmSchemaClassifierTest {

  /** 被测分类器。 */
  private final SchemaClassifier classifier = new AsmSchemaClassifier();

  /** 完全相同或仅方法体变化时应归为 METHOD_BODY。 */
  @Test
  public void methodBodyChangeIsMethodBody() {
    ChangeKind identical = classify(bar("v1"), bar("v1"));
    // 验证：完全相同的 class 不需要结构迁移
    assertEquals(ChangeKind.METHOD_BODY, identical);

    ChangeKind changed = classify(bar("v1"), bar("v2"));
    // 验证：只改返回值常量属于方法体变化
    assertEquals(ChangeKind.METHOD_BODY, changed);
  }

  /** 新增字段应归为 SCHEMA。 */
  @Test
  public void fieldAddedIsSchema() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addField("y", "I");
    // 验证：新增字段需要版本化迁移
    assertEquals(ChangeKind.SCHEMA, classify(oldClass, newClass));
  }

  /** 删除字段应归为 SCHEMA。 */
  @Test
  public void fieldRemovedIsSchema() {
    TestClassFile oldClass = base().addField("y", "I");
    TestClassFile newClass = base();
    // 验证：删除字段需要版本化迁移
    assertEquals(ChangeKind.SCHEMA, classify(oldClass, newClass));
  }

  /** 字段类型变化应归为 SCHEMA。 */
  @Test
  public void fieldTypeChangedIsSchema() {
    TestClassFile oldClass = base().addField("y", "I");
    TestClassFile newClass = base().addField("y", "J");
    // 验证：字段描述符变化不能原地重定义
    assertEquals(ChangeKind.SCHEMA, classify(oldClass, newClass));
  }

  /** 新增方法应归为 SIGNATURE。 */
  @Test
  public void methodAddedIsSignature() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addVoidMethod("extra", "()V");
    // 验证：新增方法属于方法签名变化
    assertEquals(ChangeKind.SIGNATURE, classify(oldClass, newClass));
  }

  /** 删除方法应归为 SIGNATURE。 */
  @Test
  public void methodRemovedIsSignature() {
    TestClassFile oldClass = base().addVoidMethod("extra", "()V");
    TestClassFile newClass = base();
    // 验证：删除方法属于方法签名变化
    assertEquals(ChangeKind.SIGNATURE, classify(oldClass, newClass));
  }

  /** 方法描述符变化应归为 SIGNATURE。 */
  @Test
  public void methodDescriptorChangedIsSignature() {
    TestClassFile oldClass = base().addVoidMethod("run", "()V");
    TestClassFile newClass = base().addVoidMethod("run", "(I)V");
    // 验证：方法描述符变化属于方法签名变化
    assertEquals(ChangeKind.SIGNATURE, classify(oldClass, newClass));
  }

  /** 父类变化应归为 HIERARCHY。 */
  @Test
  public void superclassChangedIsHierarchy() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().superName("com/demo/OtherBase");
    // 验证：父类变化无法安全热替换
    assertEquals(ChangeKind.HIERARCHY, classify(oldClass, newClass));
  }

  /** 接口变化应归为 HIERARCHY。 */
  @Test
  public void interfaceAddedIsHierarchy() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addInterface("java/lang/Runnable");
    // 验证：接口列表变化无法安全热替换
    assertEquals(ChangeKind.HIERARCHY, classify(oldClass, newClass));
  }

  /** 类注解变化应归为 ANNOTATION。 */
  @Test
  public void classAnnotationAddedIsAnnotation() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addClassAnnotation("Ljava/lang/Deprecated;");
    // 验证：类级注解变化需要刷新元数据
    assertEquals(ChangeKind.ANNOTATION, classify(oldClass, newClass));
  }

  /** 方法注解变化应归为 ANNOTATION。 */
  @Test
  public void methodAnnotationAddedIsAnnotation() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addMethodAnnotation("hello", "Ljava/lang/Deprecated;");
    // 验证：方法注解变化需要刷新元数据
    assertEquals(ChangeKind.ANNOTATION, classify(oldClass, newClass));
  }

  /** native 方法应归为 UNSUPPORTED。 */
  @Test
  public void nativeMethodIsUnsupported() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addNativeMethod("nativeWork");
    // 验证：native 方法不能被重新定义
    assertEquals(ChangeKind.UNSUPPORTED, classify(oldClass, newClass));
  }

  /** 枚举应归为 UNSUPPORTED。 */
  @Test
  public void enumIsUnsupported() {
    TestClassFile oldClass = new TestClassFile("com/demo/Color")
      .access(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM)
      .superName("java/lang/Enum");
    // 验证：枚举的常量与映射机制不支持热替换
    assertEquals(ChangeKind.UNSUPPORTED, classify(oldClass, oldClass));
  }

  /** 类修饰符变化应归为 UNSUPPORTED。 */
  @Test
  public void classModifierChangeIsUnsupported() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().access(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
    // 验证：类修饰符变化无法通过重定义完成
    assertEquals(ChangeKind.UNSUPPORTED, classify(oldClass, newClass));
  }

  /** 同时存在字段和方法体变化时应优先返回 SCHEMA。 */
  @Test
  public void schemaWinsOverMethodBody() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addField("y", "I").setStringResult("hello", "v2");
    // 验证：字段变化优先于方法体变化
    assertEquals(ChangeKind.SCHEMA, classify(oldClass, newClass));
  }

  /** 同时存在方法签名和注解变化时应优先返回 SIGNATURE。 */
  @Test
  public void signatureWinsOverAnnotation() {
    TestClassFile oldClass = base();
    TestClassFile newClass = base().addVoidMethod("extra", "()V")
      .addClassAnnotation("Ljava/lang/Deprecated;");
    // 验证：方法签名变化优先于注解变化
    assertEquals(ChangeKind.SIGNATURE, classify(oldClass, newClass));
  }

  /** 无法解析的字节应显式失败。 */
  @Test
  public void rejectsNullBytes() {
    // 验证：缺少任一字节时抛出参数异常
    assertThrows(IllegalArgumentException.class, () -> classifier.classify(null, bar("v1").bytes()));
  }

  /**
   * 创建带 hello 方法的测试类。
   *
   * @param helloValue hello 返回的字符串
   * @return 测试类构建器
   */
  private static TestClassFile bar(String helloValue) {
    return new TestClassFile("com/demo/Bar")
      .addField("x", "I")
      .addStringMethod("hello", helloValue);
  }

  /**
   * 创建分类测试的基础类。
   *
   * @return 测试类构建器
   */
  private static TestClassFile base() {
    return bar("v1");
  }

  /**
   * 执行分类。
   *
   * @param oldClass 旧类
   * @param newClass 新类
   * @return 分类结果
   */
  private static ChangeKind classify(TestClassFile oldClass, TestClassFile newClass) {
    try {
      return new AsmSchemaClassifier().classify(oldClass.bytes(), newClass.bytes());
    }
    catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
