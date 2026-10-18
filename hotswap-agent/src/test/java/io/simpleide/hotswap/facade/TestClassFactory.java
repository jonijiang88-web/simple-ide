package io.simpleide.hotswap.facade;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 测试夹具字节生成器：用 ASM 直接生成待改写的类，避免夹具放在
 * {@code io.simpleide.hotswap.*} 前缀下被 facade 改写规则跳过。
 */
final class TestClassFactory implements Opcodes {

  /** 夹具类内部名。 */
  static final String SAMPLE_INTERNAL_NAME = "sample/FacadeSample";

  /** 夹具类点分名。 */
  static final String SAMPLE_CLASS_NAME = "sample.FacadeSample";

  /** 运行期分发类内部名。 */
  static final String RUNTIME_INTERNAL_NAME = "io/simpleide/hotswap/runtime/HotswapRuntime";

  /** 转发桥接类内部名。 */
  static final String BRIDGE_INTERNAL_NAME = "io/simpleide/hotswap/facade/FacadeDispatchBridge";

  private TestClassFactory() {
  }

  /**
   * 方法体生成回调。
   */
  interface Body {

    /**
     * 向方法写入指令。
     *
     * @param method 方法访问器
     */
    void emit(MethodVisitor method);
  }

  /**
   * 生成 v1 版本的夹具类，包含各类参数与返回类型。
   *
   * @return 类字节
   */
  static byte[] sample() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, SAMPLE_INTERNAL_NAME, null, "java/lang/Object", null);
    writer.visitField(ACC_PRIVATE, "count", "I", null, null).visitEnd();
    writer.visitField(ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd();
    addMethod(writer, ACC_PUBLIC, "<init>", "()V", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitIntInsn(BIPUSH, 7);
        method.visitFieldInsn(PUTFIELD, SAMPLE_INTERNAL_NAME, "count", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn("sample");
        method.visitFieldInsn(PUTFIELD, SAMPLE_INTERNAL_NAME, "name", "Ljava/lang/String;");
        method.visitInsn(RETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "hello", "()Ljava/lang/String;", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitLdcInsn("v1");
        method.visitInsn(ARETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "add", "(II)I", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IADD);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "widen", "(J)J", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(LLOAD, 1);
        method.visitInsn(LCONST_1);
        method.visitInsn(LADD);
        method.visitInsn(LRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "third", "(D)D", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(DLOAD, 1);
        method.visitLdcInsn(Double.valueOf(3.0D));
        method.visitInsn(DDIV);
        method.visitInsn(DRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "half", "(F)F", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(FLOAD, 1);
        method.visitInsn(FCONST_2);
        method.visitInsn(FDIV);
        method.visitInsn(FRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "flag", "(Z)Z", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(ICONST_1);
        method.visitInsn(IXOR);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "byteValue", "(B)B", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitInsn(I2B);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "charValue", "(C)C", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitInsn(I2C);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "shortValue", "(S)S", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitInsn(I2S);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "noop", "()V", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitInsn(RETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "pick", "(I)Ljava/lang/String;", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitLdcInsn("v1-int");
        method.visitInsn(ARETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "pick", "(Ljava/lang/String;)Ljava/lang/String;", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitLdcInsn("v1-string");
        method.visitInsn(ARETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC, "arraySum", "([I)I", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ARRAYLENGTH);
        method.visitInsn(IRETURN);
      }
    });
    addMethod(writer, ACC_PUBLIC | ACC_STATIC, "staticHello", "()Ljava/lang/String;", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitLdcInsn("v1-static");
        method.visitInsn(ARETURN);
      }
    });
    // 静态方法带分支与异常处理器：用于验证未改写方法体的栈映射帧被原样保留
    MethodVisitor staticSafeDivide = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "staticSafeDivide",
      "(II)I", null, null);
    Label tryStart = new Label();
    Label tryEnd = new Label();
    Label handler = new Label();
    staticSafeDivide.visitCode();
    staticSafeDivide.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/ArithmeticException");
    staticSafeDivide.visitLabel(tryStart);
    staticSafeDivide.visitVarInsn(ILOAD, 0);
    staticSafeDivide.visitVarInsn(ILOAD, 1);
    staticSafeDivide.visitInsn(IDIV);
    staticSafeDivide.visitInsn(IRETURN);
    staticSafeDivide.visitLabel(tryEnd);
    staticSafeDivide.visitLabel(handler);
    staticSafeDivide.visitInsn(ICONST_M1);
    staticSafeDivide.visitInsn(IRETURN);
    staticSafeDivide.visitMaxs(0, 0);
    staticSafeDivide.visitEnd();
    addMethod(writer, ACC_PRIVATE, "secret", "()Ljava/lang/String;", new Body() {
      @Override
      public void emit(MethodVisitor method) {
        method.visitLdcInsn("v1-secret");
        method.visitInsn(ARETURN);
      }
    });
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 生成接口字节，用于验证接口被跳过。
   *
   * @return 类字节
   */
  static byte[] sampleInterface() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_8, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "sample/FacadeSampleInterface",
      null, "java/lang/Object", null);
    MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "hello",
      "()Ljava/lang/String;", null, null);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 生成枚举字节，用于验证枚举被跳过。
   *
   * @return 类字节
   */
  static byte[] sampleEnum() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, "sample/FacadeSampleEnum",
      null, "java/lang/Enum", null);
    writer.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "ONLY",
      "Lsample/FacadeSampleEnum;", null, null).visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 生成注解字节，用于验证注解被跳过。
   *
   * @return 类字节
   */
  static byte[] sampleAnnotation() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_8, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION,
      "sample/FacadeSampleAnnotation", null, "java/lang/Object",
      new String[]{"java/lang/annotation/Annotation"});
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 生成运行期分发的替身类：静态 invoke 直接转发到测试桥接类。
   *
   * @return 类字节
   */
  static byte[] stubRuntime() {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, RUNTIME_INTERNAL_NAME, null,
      "java/lang/Object", null);
    MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "invoke",
      "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
      null, null);
    method.visitCode();
    method.visitVarInsn(ALOAD, 0);
    method.visitVarInsn(ALOAD, 1);
    method.visitVarInsn(ALOAD, 2);
    method.visitVarInsn(ALOAD, 3);
    method.visitMethodInsn(INVOKESTATIC, BRIDGE_INTERNAL_NAME, "invoke",
      "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
      false);
    method.visitInsn(ARETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 追加一个方法并结束方法访问。
   *
   * @param writer 类写出器
   * @param access 方法修饰符
   * @param name 方法名
   * @param descriptor 方法描述符
   * @param body 方法体
   */
  private static void addMethod(ClassWriter writer, int access, String name, String descriptor,
      Body body) {
    MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
    method.visitCode();
    body.emit(method);
    method.visitMaxs(0, 0);
    method.visitEnd();
  }
}
