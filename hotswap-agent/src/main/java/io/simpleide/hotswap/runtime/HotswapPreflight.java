package io.simpleide.hotswap.runtime;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * reload 前的保守预检：识别当前版本化模型无法安全处理的类结构。
 *
 * <p>预检只用于 schema/signature 变更；纯方法体变化仍走 JVM retransform/redefine。
 */
public final class HotswapPreflight {

  /** 类包含内部类、匿名类、nest 成员或 this$0 引用。 */
  public static final String INNER_CLASS_REQUIRES_RESTART = "INNER_CLASS_REQUIRES_RESTART";

  /** 类声明了 native 方法，版本类 JNI 符号无法自动映射。 */
  public static final String NATIVE_METHOD_REQUIRES_RESTART = "NATIVE_METHOD_REQUIRES_RESTART";

  private HotswapPreflight() {
  }

  /**
   * 检查新 class 是否命中不支持的预检项。
   *
   * @param classBytes 新编译字节
   * @return 错误码；支持时返回 null
   */
  public static String check(byte[] classBytes) {
    if (classBytes == null || classBytes.length == 0) {
      return null;
    }
    ClassNode node;
    try {
      node = new ClassNode();
      new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
    }
    catch (Throwable ignored) {
      return null;
    }
    for (FieldNode field : node.fields) {
      if ("this$0".equals(field.name)) {
        return INNER_CLASS_REQUIRES_RESTART;
      }
    }
    if (node.outerClass != null || node.nestHostClass != null) {
      return INNER_CLASS_REQUIRES_RESTART;
    }
    if (node.nestMembers != null && !node.nestMembers.isEmpty()) {
      return INNER_CLASS_REQUIRES_RESTART;
    }
    for (InnerClassNode inner : node.innerClasses) {
      if (inner.name != null && inner.name.startsWith(node.name + "$")) {
        return INNER_CLASS_REQUIRES_RESTART;
      }
    }
    for (MethodNode method : node.methods) {
      if ((method.access & Opcodes.ACC_NATIVE) != 0) {
        return NATIVE_METHOD_REQUIRES_RESTART;
      }
    }
    return null;
  }

  /**
   * 从异常消息中提取预检错误码。
   *
   * @param message 异常消息
   * @return 错误码；未命中返回 null
   */
  public static String codeOf(String message) {
    if (message == null) {
      return null;
    }
    if (message.startsWith(INNER_CLASS_REQUIRES_RESTART)) {
      return INNER_CLASS_REQUIRES_RESTART;
    }
    if (message.startsWith(NATIVE_METHOD_REQUIRES_RESTART)) {
      return NATIVE_METHOD_REQUIRES_RESTART;
    }
    return null;
  }
}
