package io.simpleide.hotswap.classify;

import io.simpleide.hotswap.api.ChangeKind;
import io.simpleide.hotswap.api.SchemaClassifier;

import java.util.List;
import java.util.Map;

/**
 * 基于 ASM 的 class 变更分类器。
 *
 * <p>分类优先级按“最不可热替换”排序，保证结果保守：
 * UNSUPPORTED（native/枚举/类修饰符）→ HIERARCHY（父类/接口/类结构）
 * → SCHEMA（字段）→ SIGNATURE（方法签名）→ ANNOTATION（注解）
 * → METHOD_BODY（仅方法体或常量池变化）。
 */
public final class AsmSchemaClassifier implements SchemaClassifier {

  /**
   * 比较新旧 class 字节并返回保守的变更类型。
   *
   * @param oldBytes 已加载的 class 字节
   * @param newBytes 新编译的 class 字节
   * @return 保守的变更类型
   * @throws Exception 任一字节无法解析时抛出
   */
  @Override
  public ChangeKind classify(byte[] oldBytes, byte[] newBytes) throws Exception {
    if (oldBytes == null || newBytes == null) {
      throw new IllegalArgumentException("需要同时提供新旧 class 字节");
    }
    ClassSnapshot oldClass = ClassSnapshot.read(oldBytes);
    ClassSnapshot newClass = ClassSnapshot.read(newBytes);
    if (oldClass.name == null || newClass.name == null
      || !oldClass.name.equals(newClass.name)) {
      return ChangeKind.HIERARCHY;
    }
    // 枚举的常量初始化、values()/valueOf() 与 switch 映射无法安全重定义
    if (oldClass.isEnum() || newClass.isEnum()) {
      return ChangeKind.UNSUPPORTED;
    }
    // native 方法没有可替换的字节码
    if (oldClass.hasNativeMethod() || newClass.hasNativeMethod()) {
      return ChangeKind.UNSUPPORTED;
    }
    // 类修饰符（final/abstract/interface/annotation）变化无法通过重定义完成
    if (oldClass.access != newClass.access) {
      return ChangeKind.UNSUPPORTED;
    }
    if (hierarchyChanged(oldClass, newClass)) {
      return ChangeKind.HIERARCHY;
    }
    if (schemaChanged(oldClass, newClass)) {
      return ChangeKind.SCHEMA;
    }
    if (signatureChanged(oldClass, newClass)) {
      return ChangeKind.SIGNATURE;
    }
    if (annotationsChanged(oldClass, newClass)) {
      return ChangeKind.ANNOTATION;
    }
    // 剩余差异只可能来自方法体、常量池或调试信息
    return ChangeKind.METHOD_BODY;
  }

  /**
   * 判断父类、接口及类结构属性是否变化。
   *
   * @param oldClass 旧类快照
   * @param newClass 新类快照
   * @return 变化返回 true
   */
  private static boolean hierarchyChanged(ClassSnapshot oldClass, ClassSnapshot newClass) {
    if (!equal(oldClass.superName, newClass.superName)) {
      return true;
    }
    if (!oldClass.interfaces.equals(newClass.interfaces)) {
      return true;
    }
    if (!equal(oldClass.signature, newClass.signature)) {
      return true;
    }
    if (!oldClass.innerClasses.equals(newClass.innerClasses)) {
      return true;
    }
    if (!equal(oldClass.outerClass, newClass.outerClass)
      || !equal(oldClass.outerMethod, newClass.outerMethod)
      || !equal(oldClass.outerMethodDescriptor, newClass.outerMethodDescriptor)) {
      return true;
    }
    if (!equal(oldClass.nestHost, newClass.nestHost)) {
      return true;
    }
    return !oldClass.nestMembers.equals(newClass.nestMembers);
  }

  /**
   * 判断字段是否增删、改类型或改修饰符。
   *
   * @param oldClass 旧类快照
   * @param newClass 新类快照
   * @return 字段结构变化返回 true
   */
  private static boolean schemaChanged(ClassSnapshot oldClass, ClassSnapshot newClass) {
    if (!oldClass.fields.keySet().equals(newClass.fields.keySet())) {
      return true;
    }
    for (Map.Entry<String, ClassSnapshot.FieldInfo> entry : oldClass.fields.entrySet()) {
      ClassSnapshot.FieldInfo oldField = entry.getValue();
      ClassSnapshot.FieldInfo newField = newClass.fields.get(entry.getKey());
      if (oldField.access != newField.access
        || !equal(oldField.descriptor, newField.descriptor)
        || !equal(oldField.signature, newField.signature)
        || !equal(ClassSnapshot.valueText(oldField.value),
          ClassSnapshot.valueText(newField.value))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断方法是否增删、改描述符、修饰符、泛型签名或抛出列表。
   *
   * @param oldClass 旧类快照
   * @param newClass 新类快照
   * @return 方法签名变化返回 true
   */
  private static boolean signatureChanged(ClassSnapshot oldClass, ClassSnapshot newClass) {
    if (!oldClass.methods.keySet().equals(newClass.methods.keySet())) {
      return true;
    }
    for (Map.Entry<String, ClassSnapshot.MethodInfo> entry : oldClass.methods.entrySet()) {
      ClassSnapshot.MethodInfo oldMethod = entry.getValue();
      ClassSnapshot.MethodInfo newMethod = newClass.methods.get(entry.getKey());
      if (oldMethod.access != newMethod.access
        || !equal(oldMethod.signature, newMethod.signature)
        || !oldMethod.exceptions.equals(newMethod.exceptions)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断类、字段或方法上的注解是否变化。
   *
   * @param oldClass 旧类快照
   * @param newClass 新类快照
   * @return 注解变化返回 true
   */
  private static boolean annotationsChanged(ClassSnapshot oldClass, ClassSnapshot newClass) {
    if (!oldClass.classAnnotations.equals(newClass.classAnnotations)) {
      return true;
    }
    for (Map.Entry<String, ClassSnapshot.FieldInfo> entry : oldClass.fields.entrySet()) {
      ClassSnapshot.FieldInfo newField = newClass.fields.get(entry.getKey());
      if (!entry.getValue().annotations.equals(newField.annotations)) {
        return true;
      }
    }
    for (Map.Entry<String, ClassSnapshot.MethodInfo> entry : oldClass.methods.entrySet()) {
      ClassSnapshot.MethodInfo newMethod = newClass.methods.get(entry.getKey());
      if (!entry.getValue().annotations.equals(newMethod.annotations)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 比较两个可空字符串。
   *
   * @param left 左值
   * @param right 右值
   * @return 内容相同或同为 null 返回 true
   */
  private static boolean equal(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }
}
