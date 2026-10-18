package io.simpleide.hotswap.integration.spring;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring Boot 启动返回值被注入 context 捕获调用。
 */
class SpringContextTransformerTest {

  /** SpringApplication.run 的返回路径应插入 bridge 调用。 */
  @Test
  void injectsBridgeCallIntoSpringApplicationRun() {
    SpringContextTransformer transformer = new SpringContextTransformer();
    byte[] transformed = transformer.transform(null,
      "org/springframework/boot/SpringApplication", null, null, springApplicationBytes());
    assertNotNull(transformed);

    boolean found = false;
    ClassNode node = new ClassNode();
    new ClassReader(transformed).accept(node, 0);
    for (MethodNode method : node.methods) {
      for (int index = 0; index < method.instructions.size(); index++) {
        if (method.instructions.get(index) instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) method.instructions.get(index);
          if (SpringContextBridge.class.getName().replace('.', '/').equals(call.owner)
            && "capture".equals(call.name)) {
            found = true;
          }
        }
      }
    }
    // 验证：启动返回路径注入了 context 捕获调用
    assertTrue(found);
  }

  /** 非 SpringApplication 类不应被改写。 */
  @Test
  void ignoresOtherClasses() {
    SpringContextTransformer transformer = new SpringContextTransformer();
    // 验证：普通类不返回改写后的字节
    assertNull(transformer.transform(null, "com/demo/Other", null, null, springApplicationBytes()));
  }

  /**
   * 生成一个返回 ConfigurableApplicationContext 的 SpringApplication 测试类。
   *
   * @return class 字节
   */
  private static byte[] springApplicationBytes() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/springframework/boot/SpringApplication",
      null, "java/lang/Object", null);
    MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
      "()Lorg/springframework/context/ConfigurableApplicationContext;", null, null);
    method.visitCode();
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(1, 0);
    method.visitEnd();
    method.accept(writer);
    writer.visitEnd();
    return writer.toByteArray();
  }
}
