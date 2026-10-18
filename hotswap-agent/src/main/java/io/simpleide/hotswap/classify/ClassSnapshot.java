package io.simpleide.hotswap.classify;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 class 文件在分类时需要比较的结构快照。
 *
 * <p>快照只保留影响 JVM 链接、反射元数据和方法执行的字节码信息：
 * 结构、注解与字节码指令。调试信息（行号、局部变量表、SourceFile）被忽略，
 * 因此仅调试信息变化会被归为方法体变化，不会误判成 schema 变化。
 */
final class ClassSnapshot {

  /** 类名（内部形式）。 */
  String name;
  /** 父类名（内部形式）。 */
  String superName;
  /** 泛型签名。 */
  String signature;
  /** 类访问标志。 */
  int access;
  /** 接口列表，保持 class 文件中的顺序（顺序影响 JVM 链接）。 */
  final List<String> interfaces = new ArrayList<String>();
  /** 类级注解文本。 */
  final List<String> classAnnotations = new ArrayList<String>();
  /** 内部类表，属于类结构信息。 */
  final List<String> innerClasses = new ArrayList<String>();
  /** 外部类名，属于类结构信息。 */
  String outerClass;
  /** 外部方法名，属于类结构信息。 */
  String outerMethod;
  /** 外部方法描述符，属于类结构信息。 */
  String outerMethodDescriptor;
  /** NestHost，属于类结构信息。 */
  String nestHost;
  /** NestMembers，属于类结构信息。 */
  final List<String> nestMembers = new ArrayList<String>();
  /** 字段快照，key 为字段名。 */
  final Map<String, FieldInfo> fields = new LinkedHashMap<String, FieldInfo>();
  /** 方法快照，key 为方法名加描述符。 */
  final Map<String, MethodInfo> methods = new LinkedHashMap<String, MethodInfo>();

  /**
   * 读取 class 字节并生成结构快照。
   *
   * @param bytes class 字节
   * @return 结构快照
   */
  static ClassSnapshot read(byte[] bytes) {
    ClassSnapshot snapshot = new ClassSnapshot();
    new ClassReader(bytes).accept(new SnapshotVisitor(snapshot),
      ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return snapshot;
  }

  /**
   * 判断是否为枚举类型。
   *
   * @return 枚举返回 true
   */
  boolean isEnum() {
    return (access & Opcodes.ACC_ENUM) != 0 || "java/lang/Enum".equals(superName);
  }

  /**
   * 判断是否存在 native 方法。
   *
   * @return 存在 native 方法返回 true
   */
  boolean hasNativeMethod() {
    for (MethodInfo method : methods.values()) {
      if ((method.access & Opcodes.ACC_NATIVE) != 0) {
        return true;
      }
    }
    return false;
  }

  /** 字段结构信息。 */
  static final class FieldInfo {
    /** 字段访问标志。 */
    int access;
    /** 字段描述符。 */
    String descriptor;
    /** 字段泛型签名。 */
    String signature;
    /** 静态常量值。 */
    Object value;
    /** 字段注解文本。 */
    final List<String> annotations = new ArrayList<String>();
  }

  /** 方法结构信息。 */
  static final class MethodInfo {
    /** 方法访问标志。 */
    final int access;
    /** 方法泛型签名。 */
    final String signature;
    /** 声明抛出的异常。 */
    final List<String> exceptions;
    /** 方法、参数和类型注解文本。 */
    final List<String> annotations = new ArrayList<String>();
    /** 规范化后的字节码文本。 */
    String body = "";

    /**
     * 创建方法结构信息。
     *
     * @param access 访问标志
     * @param signature 泛型签名
     * @param exceptions 声明抛出的异常
     */
    MethodInfo(int access, String signature, String[] exceptions) {
      this.access = access;
      this.signature = signature;
      this.exceptions = exceptions == null
        ? new ArrayList<String>()
        : new ArrayList<String>(Arrays.asList(exceptions));
    }
  }

  /** 遍历 class 结构并填充快照。 */
  private static final class SnapshotVisitor extends ClassVisitor {

    /** 待填充的快照。 */
    private final ClassSnapshot snapshot;

    /**
     * 创建快照访问器。
     *
     * @param snapshot 待填充的快照
     */
    SnapshotVisitor(ClassSnapshot snapshot) {
      super(Opcodes.ASM9);
      this.snapshot = snapshot;
    }

    /** 记录类基本信息。 */
    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
      snapshot.access = access;
      snapshot.name = name;
      snapshot.signature = signature;
      snapshot.superName = superName;
      if (interfaces != null) {
        snapshot.interfaces.addAll(Arrays.asList(interfaces));
      }
    }

    /** 记录外部类信息。 */
    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
      snapshot.outerClass = owner;
      snapshot.outerMethod = name;
      snapshot.outerMethodDescriptor = descriptor;
    }

    /** 记录内部类表。 */
    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      snapshot.innerClasses.add(name + "|" + outerName + "|" + innerName + "|" + access);
    }

    /** 记录 NestHost。 */
    @Override
    public void visitNestHost(String nestHost) {
      snapshot.nestHost = nestHost;
    }

    /** 记录 NestMembers。 */
    @Override
    public void visitNestMember(String nestMember) {
      snapshot.nestMembers.add(nestMember);
    }

    /** 记录类注解。 */
    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      return capture(snapshot.classAnnotations, "class|" + descriptor + "|" + visible);
    }

    /** 记录类上的类型注解。 */
    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                String descriptor, boolean visible) {
      return capture(snapshot.classAnnotations,
        "classType|" + typeRef + "|" + typePath + "|" + descriptor + "|" + visible);
    }

    /** 记录字段结构。 */
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
      final FieldInfo field = new FieldInfo();
      field.access = access;
      field.descriptor = descriptor;
      field.signature = signature;
      field.value = value;
      snapshot.fields.put(name, field);
      final String prefix = "field|" + name + "|" + descriptor + "|";
      return new FieldVisitor(Opcodes.ASM9) {
        /** 记录字段注解。 */
        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
          return capture(field.annotations, prefix + annotationDescriptor + "|" + visible);
        }

        /** 记录字段类型注解。 */
        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                    String annotationDescriptor, boolean visible) {
          return capture(field.annotations,
            prefix + "type|" + typeRef + "|" + typePath + "|" + annotationDescriptor + "|" + visible);
        }
      };
    }

    /** 记录方法结构和方法体。 */
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
      MethodInfo method = new MethodInfo(access, signature, exceptions);
      snapshot.methods.put(name + descriptor, method);
      return new SnapshotMethodVisitor(method, "method|" + name + descriptor + "|");
    }
  }

  /** 记录方法注解和规范化字节码文本。 */
  private static final class SnapshotMethodVisitor extends MethodVisitor {

    /** 对应的方法快照。 */
    private final MethodInfo method;
    /** 注解 key 前缀。 */
    private final String annotationPrefix;
    /** 规范化字节码。 */
    private final StringBuilder body = new StringBuilder();
    /** 标签到稳定编号的映射。 */
    private final Map<Label, Integer> labels = new IdentityHashMap<Label, Integer>();

    /**
     * 创建方法访问器。
     *
     * @param method 方法快照
     * @param annotationPrefix 注解 key 前缀
     */
    SnapshotMethodVisitor(MethodInfo method, String annotationPrefix) {
      super(Opcodes.ASM9);
      this.method = method;
      this.annotationPrefix = annotationPrefix;
    }

    /** 记录方法注解。 */
    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      return capture(method.annotations, annotationPrefix + descriptor + "|" + visible);
    }

    /** 记录参数注解。 */
    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                      boolean visible) {
      return capture(method.annotations,
        annotationPrefix + "param" + parameter + "|" + descriptor + "|" + visible);
    }

    /** 记录方法上的类型注解。 */
    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                 String descriptor, boolean visible) {
      return capture(method.annotations,
        annotationPrefix + "type|" + typeRef + "|" + typePath + "|" + descriptor + "|" + visible);
    }

    /** 记录注解默认值。 */
    @Override
    public AnnotationVisitor visitAnnotationDefault() {
      return capture(method.annotations, annotationPrefix + "default|");
    }

    /** 记录方法体结束时的栈和局部变量大小。 */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      body.append("maxs:").append(maxStack).append('/').append(maxLocals).append(';');
      method.body = body.toString();
    }

    /** 记录无操作数指令。 */
    @Override
    public void visitInsn(int opcode) {
      body.append("i").append(opcode).append(';');
    }

    /** 记录整数操作数指令。 */
    @Override
    public void visitIntInsn(int opcode, int operand) {
      body.append("int").append(opcode).append(':').append(operand).append(';');
    }

    /** 记录局部变量指令。 */
    @Override
    public void visitVarInsn(int opcode, int var) {
      body.append("var").append(opcode).append(':').append(var).append(';');
    }

    /** 记录类型指令。 */
    @Override
    public void visitTypeInsn(int opcode, String type) {
      body.append("type").append(opcode).append(':').append(type).append(';');
    }

    /** 记录字段访问指令。 */
    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      body.append("field").append(opcode).append(':').append(owner).append('.')
        .append(name).append(':').append(descriptor).append(';');
    }

    /** 记录方法调用指令。 */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
      body.append("call").append(opcode).append(':').append(owner).append('.')
        .append(name).append(descriptor).append(':').append(isInterface).append(';');
    }

    /** 记录 invokedynamic 指令。 */
    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments) {
      body.append("indy:").append(name).append(descriptor).append(':')
        .append(bootstrapMethodHandle).append(':');
      for (Object argument : bootstrapMethodArguments) {
        body.append(valueText(argument)).append(',');
      }
      body.append(';');
    }

    /** 记录跳转指令。 */
    @Override
    public void visitJumpInsn(int opcode, Label label) {
      body.append("jump").append(opcode).append(':').append(labelId(label)).append(';');
    }

    /** 记录标签。 */
    @Override
    public void visitLabel(Label label) {
      body.append('L').append(labelId(label)).append(';');
    }

    /** 记录 ldc 指令。 */
    @Override
    public void visitLdcInsn(Object value) {
      body.append("ldc:").append(valueText(value)).append(';');
    }

    /** 记录局部变量自增指令。 */
    @Override
    public void visitIincInsn(int var, int increment) {
      body.append("iinc:").append(var).append(':').append(increment).append(';');
    }

    /** 记录 tableswitch 指令。 */
    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
      body.append("tableswitch:").append(min).append(':').append(max).append(':')
        .append(labelId(dflt)).append(':');
      for (Label label : labels) {
        body.append(labelId(label)).append(',');
      }
      body.append(';');
    }

    /** 记录 lookupswitch 指令。 */
    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
      body.append("lookupswitch:").append(labelId(dflt)).append(':');
      for (int index = 0; index < keys.length; index++) {
        body.append(keys[index]).append('=').append(labelId(labels[index])).append(',');
      }
      body.append(';');
    }

    /** 记录多维数组指令。 */
    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
      body.append("multianewarray:").append(descriptor).append(':').append(numDimensions).append(';');
    }

    /** 记录异常处理表。 */
    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      body.append("try:").append(labelId(start)).append(':').append(labelId(end)).append(':')
        .append(labelId(handler)).append(':').append(type).append(';');
    }

    /**
     * 取标签的稳定编号。
     *
     * @param label 字节码标签
     * @return 首次出现顺序编号
     */
    private int labelId(Label label) {
      Integer id = labels.get(label);
      if (id == null) {
        id = labels.size();
        labels.put(label, id);
      }
      return id;
    }
  }

  /**
   * 把注解内容采集为稳定文本，避免依赖 class 文件中的常量池下标。
   *
   * @param sink 目标注解列表
   * @param key 注解所属元素与描述符
   * @return 注解访问器
   */
  private static AnnotationVisitor capture(final List<String> sink, final String key) {
    final StringBuilder text = new StringBuilder();
    return new AnnotationVisitor(Opcodes.ASM9) {
      /** 记录普通注解值。 */
      @Override
      public void visit(String name, Object value) {
        text.append(name).append('=').append(valueText(value)).append(';');
      }

      /** 记录枚举注解值。 */
      @Override
      public void visitEnum(String name, String descriptor, String value) {
        text.append(name).append("=E").append(descriptor).append(':').append(value).append(';');
      }

      /** 记录嵌套注解。 */
      @Override
      public AnnotationVisitor visitAnnotation(String name, String descriptor) {
        text.append(name).append('=');
        return annotationText(text, descriptor);
      }

      /** 记录数组注解值。 */
      @Override
      public AnnotationVisitor visitArray(String name) {
        text.append(name).append("=[");
        return new AnnotationVisitor(Opcodes.ASM9) {
          /** 记录数组中的普通值。 */
          @Override
          public void visit(String elementName, Object value) {
            text.append(valueText(value)).append(',');
          }

          /** 记录数组中的枚举值。 */
          @Override
          public void visitEnum(String elementName, String descriptor, String value) {
            text.append('E').append(descriptor).append(':').append(value).append(',');
          }

          /** 记录数组中的嵌套注解。 */
          @Override
          public AnnotationVisitor visitAnnotation(String elementName, String descriptor) {
            return annotationText(text, descriptor);
          }

          /** 结束数组。 */
          @Override
          public void visitEnd() {
            text.append(']');
          }
        };
      }

      /** 结束当前注解并写入结果。 */
      @Override
      public void visitEnd() {
        sink.add(key + "=" + text);
      }
    };
  }

  /**
   * 把嵌套注解内容追加到同一文本。
   *
   * @param text 目标文本
   * @param descriptor 注解描述符
   * @return 注解访问器
   */
  private static AnnotationVisitor annotationText(final StringBuilder text, String descriptor) {
    text.append('@').append(descriptor).append('(');
    return new AnnotationVisitor(Opcodes.ASM9) {
      /** 记录嵌套注解的普通值。 */
      @Override
      public void visit(String name, Object value) {
        text.append(name).append('=').append(valueText(value)).append(';');
      }

      /** 记录嵌套注解的枚举值。 */
      @Override
      public void visitEnum(String name, String enumDescriptor, String value) {
        text.append(name).append("=E").append(enumDescriptor).append(':').append(value).append(';');
      }

      /** 记录更深层的嵌套注解。 */
      @Override
      public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
        text.append(name).append('=');
        return annotationText(text, nestedDescriptor);
      }

      /** 记录嵌套注解的数组值。 */
      @Override
      public AnnotationVisitor visitArray(String name) {
        text.append(name).append("=[");
        return new AnnotationVisitor(Opcodes.ASM9) {
          /** 记录数组中的普通值。 */
          @Override
          public void visit(String elementName, Object value) {
            text.append(valueText(value)).append(',');
          }

          /** 记录数组中的枚举值。 */
          @Override
          public void visitEnum(String elementName, String enumDescriptor, String value) {
            text.append('E').append(enumDescriptor).append(':').append(value).append(',');
          }

          /** 记录数组中的嵌套注解。 */
          @Override
          public AnnotationVisitor visitAnnotation(String elementName, String nestedDescriptor) {
            return annotationText(text, nestedDescriptor);
          }

          /** 结束数组。 */
          @Override
          public void visitEnd() {
            text.append(']');
          }
        };
      }

      /** 结束嵌套注解。 */
      @Override
      public void visitEnd() {
        text.append(')');
      }
    };
  }

  /**
   * 把注解值或常量值转成稳定文本。
   *
   * @param value 注解值或常量值
   * @return 稳定文本
   */
  static String valueText(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Type) {
      return "T" + ((Type) value).getDescriptor();
    }
    if (value instanceof Handle) {
      return "H" + value;
    }
    if (value.getClass().isArray()) {
      return "A" + Arrays.deepToString(new Object[]{value});
    }
    return value.getClass().getSimpleName() + ":" + value;
  }
}
