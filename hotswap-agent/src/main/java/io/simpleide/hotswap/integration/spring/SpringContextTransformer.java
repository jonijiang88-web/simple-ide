package io.simpleide.hotswap.integration.spring;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 在 Spring Boot 启动完成后捕获 ApplicationContext。
 *
 * <p>只改写 {@code SpringApplication.run(...)} 的返回值，不依赖 Spring 编译期 API。
 */
final class SpringContextTransformer implements ClassFileTransformer {

  private static final String SPRING_APPLICATION = "org/springframework/boot/SpringApplication";
  private static final String CONTEXT_DESCRIPTOR =
      "Lorg/springframework/context/ConfigurableApplicationContext;";
  private static final String BRIDGE = "io/simpleide/hotswap/integration/spring/SpringContextBridge";

  @Override
  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (classBeingRedefined != null || !SPRING_APPLICATION.equals(className)) {
      return null;
    }
    try {
      ClassReader reader = new ClassReader(classfileBuffer);
      ClassNode node = new ClassNode();
      reader.accept(node, 0);
      boolean changed = false;
      for (MethodNode method : node.methods) {
        if (!CONTEXT_DESCRIPTOR.equals(method.desc.substring(method.desc.indexOf(')') + 1))) {
          continue;
        }
        InsnList instructions = method.instructions;
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; ) {
          AbstractInsnNode next = instruction.getNext();
          if (instruction.getOpcode() == Opcodes.ARETURN) {
            InsnList capture = new InsnList();
            capture.add(new InsnNode(Opcodes.DUP));
            capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "capture",
              "(Ljava/lang/Object;)V", false));
            instructions.insertBefore(instruction, capture);
            changed = true;
          }
          instruction = next;
        }
      }
      if (!changed) {
        return null;
      }
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
      node.accept(writer);
      return writer.toByteArray();
    }
    catch (Throwable error) {
      System.err.println("[simple-ide-hotswap] Spring context transformer failed: " + error);
      return null;
    }
  }
}
