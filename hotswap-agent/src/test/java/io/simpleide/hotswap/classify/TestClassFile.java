package io.simpleide.hotswap.classify;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 class 字节生成器，尽量只暴露分类测试需要的结构。
 */
final class TestClassFile {

  /** 方法体类型。 */
  enum Body {
    /** 返回字符串常量。 */
    STRING,
    /** 返回整数常量。 */
    INT,
    /** void 返回。 */
    VOID,
    /** 没有方法体（native/abstract）。 */
    NONE
  }

  /** 内部类名。 */
  private final String name;
  /** 父类名。 */
  private String superName = "java/lang/Object";
  /** 类访问标志。 */
  private int access = Opcodes.ACC_PUBLIC;
  /** 接口列表。 */
  private final List<String> interfaces = new ArrayList<String>();
  /** 类级注解。 */
  private final List<String> classAnnotations = new ArrayList<String>();
  /** 字段列表。 */
  private final List<FieldSpec> fields = new ArrayList<FieldSpec>();
  /** 方法列表。 */
  private final List<MethodSpec> methods = new ArrayList<MethodSpec>();

  /**
   * 创建测试类。
   *
   * @param name 内部类名
   */
  TestClassFile(String name) {
    this.name = name;
  }

  /**
   * 设置父类。
   *
   * @param value 父类内部名
   * @return 当前构建器
   */
  TestClassFile superName(String value) {
    this.superName = value;
    return this;
  }

  /**
   * 设置类访问标志。
   *
   * @param value 访问标志
   * @return 当前构建器
   */
  TestClassFile access(int value) {
    this.access = value;
    return this;
  }

  /**
   * 添加接口。
   *
   * @param value 接口内部名
   * @return 当前构建器
   */
  TestClassFile addInterface(String value) {
    interfaces.add(value);
    return this;
  }

  /**
   * 添加类注解。
   *
   * @param descriptor 注解描述符
   * @return 当前构建器
   */
  TestClassFile addClassAnnotation(String descriptor) {
    classAnnotations.add(descriptor);
    return this;
  }

  /**
   * 添加私有字段。
   *
   * @param fieldName 字段名
   * @param descriptor 字段描述符
   * @return 当前构建器
   */
  TestClassFile addField(String fieldName, String descriptor) {
    fields.add(new FieldSpec(fieldName, descriptor));
    return this;
  }

  /**
   * 添加返回字符串的方法。
   *
   * @param methodName 方法名
   * @param value 返回的字符串常量
   * @return 当前构建器
   */
  TestClassFile addStringMethod(String methodName, String value) {
    methods.add(new MethodSpec(Opcodes.ACC_PUBLIC, methodName, "()Ljava/lang/String;", Body.STRING, value));
    return this;
  }

  /**
   * 添加返回整数的方法。
   *
   * @param methodName 方法名
   * @param value 返回的整数常量
   * @return 当前构建器
   */
  TestClassFile addIntMethod(String methodName, int value) {
    methods.add(new MethodSpec(Opcodes.ACC_PUBLIC, methodName, "()I", Body.INT, String.valueOf(value)));
    return this;
  }

  /**
   * 添加 void 方法。
   *
   * @param methodName 方法名
   * @param descriptor 方法描述符
   * @return 当前构建器
   */
  TestClassFile addVoidMethod(String methodName, String descriptor) {
    methods.add(new MethodSpec(Opcodes.ACC_PUBLIC, methodName, descriptor, Body.VOID, null));
    return this;
  }

  /**
   * 添加 native 方法。
   *
   * @param methodName 方法名
   * @return 当前构建器
   */
  TestClassFile addNativeMethod(String methodName) {
    methods.add(new MethodSpec(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, methodName, "()V", Body.NONE, null));
    return this;
  }

  /**
   * 给指定方法添加注解。
   *
   * @param methodName 方法名
   * @param descriptor 注解描述符
   * @return 当前构建器
   */
  TestClassFile addMethodAnnotation(String methodName, String descriptor) {
    for (MethodSpec method : methods) {
      if (method.name.equals(methodName)) {
        method.annotations.add(descriptor);
        return this;
      }
    }
    throw new IllegalArgumentException("方法不存在：" + methodName);
  }

  /**
   * 修改指定方法的返回字符串常量。
   *
   * @param methodName 方法名
   * @param value 新的返回字符串
   * @return 当前构建器
   */
  TestClassFile setStringResult(String methodName, String value) {
    for (MethodSpec method : methods) {
      if (method.name.equals(methodName)) {
        method.value = value;
        return this;
      }
    }
    throw new IllegalArgumentException("方法不存在：" + methodName);
  }

  /**
   * 生成 class 字节。
   *
   * @return class 字节
   */
  byte[] bytes() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, access, name, null, superName,
      interfaces.toArray(new String[0]));
    for (String annotation : classAnnotations) {
      AnnotationVisitor visitor = writer.visitAnnotation(annotation, true);
      visitor.visitEnd();
    }
    for (FieldSpec field : fields) {
      FieldVisitor visitor = writer.visitField(Opcodes.ACC_PRIVATE, field.name, field.descriptor,
        null, null);
      for (String annotation : field.annotations) {
        visitor.visitAnnotation(annotation, true).visitEnd();
      }
      visitor.visitEnd();
    }
    for (MethodSpec method : methods) {
      MethodVisitor visitor = writer.visitMethod(method.access, method.name, method.descriptor,
        null, null);
      for (String annotation : method.annotations) {
        visitor.visitAnnotation(annotation, true).visitEnd();
      }
      if (method.body != Body.NONE) {
        visitor.visitCode();
        if (method.body == Body.STRING) {
          visitor.visitLdcInsn(method.value);
          visitor.visitInsn(Opcodes.ARETURN);
        }
        else if (method.body == Body.INT) {
          visitor.visitLdcInsn(Integer.valueOf(method.value));
          visitor.visitInsn(Opcodes.IRETURN);
        }
        else {
          visitor.visitInsn(Opcodes.RETURN);
        }
        visitor.visitMaxs(2, 2);
      }
      visitor.visitEnd();
    }
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** 字段定义。 */
  private static final class FieldSpec {
    /** 字段名。 */
    final String name;
    /** 字段描述符。 */
    final String descriptor;
    /** 字段注解。 */
    final List<String> annotations = new ArrayList<String>();

    /**
     * 创建字段定义。
     *
     * @param name 字段名
     * @param descriptor 字段描述符
     */
    FieldSpec(String name, String descriptor) {
      this.name = name;
      this.descriptor = descriptor;
    }
  }

  /** 方法定义。 */
  private static final class MethodSpec {
    /** 访问标志。 */
    final int access;
    /** 方法名。 */
    final String name;
    /** 方法描述符。 */
    final String descriptor;
    /** 方法体类型。 */
    final Body body;
    /** 字符串或整数常量。 */
    String value;
    /** 方法注解。 */
    final List<String> annotations = new ArrayList<String>();

    /**
     * 创建方法定义。
     *
     * @param access 访问标志
     * @param name 方法名
     * @param descriptor 方法描述符
     * @param body 方法体类型
     * @param value 常量值
     */
    MethodSpec(int access, String name, String descriptor, Body body, String value) {
      this.access = access;
      this.name = name;
      this.descriptor = descriptor;
      this.body = body;
      this.value = value;
    }
  }
}
