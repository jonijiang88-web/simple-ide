package io.simpleide.hotswap.compile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证编译期调用点改写会生成桥类并改写应用类调用。
 */
class CallSiteRewriterTest {

  /** 应用类之间新增方法的调用应改成 invokedynamic 转发。 */
  @Test
  void rewritesApplicationCallToInvokeDynamic(@TempDir Path output) throws Exception {
    writeClass(output, targetClass());
    writeClass(output, callerClass());

    CallSiteRewriter.rewrite(Collections.singletonList(output));

    ClassNode caller = read(output.resolve("com/demo/Caller.class"));
    boolean indyCall = false;
    for (MethodNode method : caller.methods) {
      for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
           instruction = instruction.getNext()) {
        if (instruction instanceof InvokeDynamicInsnNode) {
          InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) instruction;
          if (call.bsm != null && call.bsm.getOwner().contains("HotswapBootstrap")) {
            indyCall = true;
          }
        }
      }
    }
    // 验证：调用点被改为 Runtime bootstrap 的 invokedynamic
    assertTrue(indyCall);
    // 验证：不再生成桥类目录
    assertFalse(Files.isDirectory(output.resolve("io/simpleide/hotswap/generated")));
  }

  /** 普通应用类不应被改写为 JDK 调用。 */
  @Test
  void leavesJdkCallsUntouched(@TempDir Path output) throws Exception {
    writeClass(output, callerClass());
    CallSiteRewriter.rewrite(Collections.singletonList(output));
    ClassNode caller = read(output.resolve("com/demo/Caller.class"));
    boolean stringCall = false;
    for (MethodNode method : caller.methods) {
      for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
           instruction = instruction.getNext()) {
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if ("java/lang/String".equals(call.owner)) {
            stringCall = true;
          }
        }
      }
    }
    // 验证：JDK String 调用没有被改写
    assertTrue(stringCall);
  }

  /** 生成测试目标类。 */
  private static byte[] targetClass() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/demo/Target", null,
      "java/lang/Object", null);
    MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "newMethod",
      "()Ljava/lang/String;", null, null);
    method.visitCode();
    method.visitLdcInsn("ok");
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** 生成测试调用方类。 */
  private static byte[] callerClass() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/demo/Caller", null,
      "java/lang/Object", null);
    MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
      "(Lcom/demo/Target;)Ljava/lang/String;", null, null);
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/demo/Target", "newMethod",
      "()Ljava/lang/String;", false);
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim",
      "()Ljava/lang/String;", false);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 将 class 字节写入输出目录。
   *
   * @param output 输出目录
   * @param bytes class 字节
   * @throws Exception 写入失败
   */
  private static void writeClass(Path output, byte[] bytes) throws Exception {
    ClassReader reader = new ClassReader(bytes);
    Path file = output.resolve(reader.getClassName() + ".class");
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  /**
   * 读取 class 树。
   *
   * @param file class 文件
   * @return class 树
   * @throws Exception 读取失败
   */
  private static ClassNode read(Path file) throws Exception {
    ClassNode node = new ClassNode();
    new ClassReader(Files.readAllBytes(file)).accept(node, 0);
    return node;
  }
}
