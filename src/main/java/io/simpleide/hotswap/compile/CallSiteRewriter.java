package io.simpleide.hotswap.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 编译后调用点改写器。
 *
 * <p>把应用类之间的 invokevirtual/invokeinterface 改成 invokedynamic；bootstrap
 * 在运行时把调用转发给当前版本类。相比生成 CallBridge，这种方式没有桥类方法表，
 * 不会因增量构建产生旧调用点引用新桥类方法的问题。
 *
 * <p>只处理项目输出目录中的 class；JDK、框架依赖 jar 和 agent 自身不改写。
 */
public final class CallSiteRewriter {

  /** 生成桥类的旧前缀，仅用于跳过历史构建产物。 */
  private static final String LEGACY_BRIDGE_PREFIX = "io/simpleide/hotswap/generated/CallBridge";

  /** invokedynamic bootstrap 类内部名。 */
  private static final String BOOTSTRAP_INTERNAL_NAME =
      "io/simpleide/hotswap/runtime/HotswapBootstrap";

  /** invokedynamic bootstrap 方法句柄。 */
  private static final Handle BOOTSTRAP = new Handle(
      Opcodes.H_INVOKESTATIC,
      BOOTSTRAP_INTERNAL_NAME,
      "bootstrap",
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
      false);

  private CallSiteRewriter() {
  }

  /**
   * 改写一组模块输出目录中的调用点。
   *
   * @param outputDirectories 模块 classes 输出目录
   * @throws IOException 文件读写失败
   */
  public static void rewrite(List<Path> outputDirectories) throws IOException {
    if (outputDirectories == null || outputDirectories.isEmpty()) {
      return;
    }
    List<Path> outputs = new ArrayList<Path>();
    for (Path output : outputDirectories) {
      if (output != null && Files.isDirectory(output)) {
        outputs.add(output);
      }
    }
    if (outputs.isEmpty()) {
      return;
    }
    Set<String> applicationClasses = new HashSet<String>();
    List<Path> classFiles = new ArrayList<Path>();
    for (Path output : outputs) {
      try (java.util.stream.Stream<Path> stream = Files.walk(output)) {
        java.util.Iterator<Path> iterator = stream.iterator();
        while (iterator.hasNext()) {
          Path file = iterator.next();
          if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".class")) {
            continue;
          }
          String relative = output.relativize(file).toString().replace('\\', '/');
          if (relative.startsWith(LEGACY_BRIDGE_PREFIX)) {
            continue;
          }
          applicationClasses.add(className(Files.readAllBytes(file)));
          classFiles.add(file);
        }
      }
    }
    if (applicationClasses.isEmpty()) {
      return;
    }
    for (Path classFile : classFiles) {
      byte[] original = Files.readAllBytes(classFile);
      byte[] rewritten = rewriteClass(original, applicationClasses);
      if (rewritten != original) {
        writeAtomically(classFile, rewritten);
      }
    }
  }

  /**
   * 改写一个 class 中的实例调用。
   *
   * @param bytes class 字节
   * @param applicationClasses 项目类内部名集合
   * @return 改写后的字节；没有变化时返回原数组
   */
  private static byte[] rewriteClass(byte[] bytes, Set<String> applicationClasses) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    boolean changed = false;
    for (MethodNode method : node.methods) {
      AbstractInsnNode instruction = method.instructions.getFirst();
      while (instruction != null) {
        AbstractInsnNode next = instruction.getNext();
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (isRewritable(call, applicationClasses)) {
            String indyDescriptor = "(L" + call.owner + ";" + call.desc.substring(1);
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(call.name, indyDescriptor,
              BOOTSTRAP, call.owner, call.name, call.desc);
            method.instructions.set(instruction, indy);
            changed = true;
          }
        }
        instruction = next;
      }
    }
    if (!changed) {
      return bytes;
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  /**
   * 判断调用点是否可以改写。
   *
   * @param call 方法调用指令
   * @param applicationClasses 项目类内部名集合
   * @return 可以改写返回 true
   */
  private static boolean isRewritable(MethodInsnNode call, Set<String> applicationClasses) {
    if (call.owner == null || call.owner.startsWith("[")) {
      return false;
    }
    if (call.owner.startsWith(LEGACY_BRIDGE_PREFIX)) {
      return false;
    }
    if (call.name == null || call.name.startsWith("<")) {
      return false;
    }
    if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
      && call.getOpcode() != Opcodes.INVOKEINTERFACE) {
      return false;
    }
    return applicationClasses.contains(call.owner);
  }

  /**
   * 返回 class 内部名。
   *
   * @param bytes class 字节
   * @return 内部名
   */
  private static String className(byte[] bytes) {
    return new ClassReader(bytes).getClassName();
  }

  /**
   * 原子写入文件。
   *
   * @param file 目标文件
   * @param bytes 内容
   * @throws IOException 写入失败
   */
  private static void writeAtomically(Path file, byte[] bytes) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName().toString() + ".sid.tmp");
    Files.write(temporary, bytes);
    try {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    }
    catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
