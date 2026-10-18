package io.simpleide.hotswap.facade;

import io.simpleide.hotswap.api.ClassInstrumenter;
import io.simpleide.hotswap.api.HotswapNames;
import io.simpleide.hotswap.api.OriginalClassStore;
import java.util.ArrayList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 基于 ASM 的 {@link ClassInstrumenter} 实现：把应用类改写成 facade。
 *
 * <p>改写规则：
 * <ul>
 *   <li>新增 {@code private volatile Object __sid_delegate;} 字段，运行期指向当前版本类实例；</li>
 *   <li>所有非 static、非构造器、非抽象/本地方法的实例方法体改为
 *   {@code HotswapRuntime.invoke(this, name, descriptor, new Object[]{boxed args})}；</li>
 *   <li>构造器、静态方法、字段、父类、接口、方法修饰符保持原样。</li>
 * </ul>
 *
 * <p>定义期改写不需要重新计算 StackMapTable：新方法体是直线代码（无分支、无异常处理器），
 * 使用 {@link ClassWriter#COMPUTE_MAXS} 只重算 max stack/locals 即可，未改写的方法保留原有栈映射帧。
 */
public final class AsmClassInstrumenter implements ClassInstrumenter {

  /** java/lang/Object 的内部名，用于构造参数数组。 */
  private static final String OBJECT_INTERNAL_NAME = "java/lang/Object";

  /** delegate 字段的 JVM 描述符。 */
  private static final String DELEGATE_DESCRIPTOR = "Ljava/lang/Object;";

  /** 运行期分发类的内部名。 */
  private static final String RUNTIME_INTERNAL_NAME = HotswapNames.RUNTIME_CLASS.replace('.', '/');

  /** 不参与改写的类名前缀：JDK 自身与 agent 实现。 */
  private static final String[] EXCLUDED_PREFIXES = {"java.", "io.simpleide.hotswap."};

  private final OriginalClassStore originalClassStore;

  /**
   * 创建 facade 改写器。
   *
   * @param originalClassStore 原始字节存储，用于后续生成 v0 版本类
   */
  public AsmClassInstrumenter(OriginalClassStore originalClassStore) {
    if (originalClassStore == null) {
      throw new IllegalArgumentException("originalClassStore must not be null");
    }
    this.originalClassStore = originalClassStore;
  }

  /**
   * 把编译后的类字节改写为 facade 字节。
   *
   * <p>接口、枚举、注解、模块描述符、{@code java.*} 与 {@code io.simpleide.hotswap.*} 原样返回；
   * 已经带有 delegate 字段的类视为已改写，同样原样返回。
   *
   * @param originalBytes JPS 编译出的原始字节
   * @param className 点分类型名，为空时取类文件内部名
   * @param loader 应用类加载器（当前实现无需解析类型，仅保留接口约定）
   * @return facade 字节
   * @throws Exception 类字节无法解析或无法改写时抛出
   */
  @Override
  public byte[] instrument(byte[] originalBytes, String className, ClassLoader loader) throws Exception {
    if (originalBytes == null || originalBytes.length == 0) {
      throw new IllegalArgumentException("originalBytes is empty: " + className);
    }
    ClassReader reader = createReader(originalBytes, className);
    ClassNode node = readClass(reader, className);
    String dottedName = (className == null || className.isEmpty())
      ? node.name.replace('/', '.') : className;
    if (isExcluded(node.name) || isSkippedType(node.access) || hasDelegateField(node)) {
      return originalBytes;
    }
    node.fields.add(createDelegateField());
    for (MethodNode method : node.methods) {
      if (isDispatchable(method)) {
        rewriteBody(method);
      }
    }
    byte[] facadeBytes = writeClass(reader, node, dottedName);
    originalClassStore.record(dottedName, originalBytes.clone());
    return facadeBytes;
  }

  /**
   * 解析原始类字节。
   *
   * @param originalBytes 原始类字节
   * @param className 点分类型名，仅用于错误信息
   * @return 类读取器
   * @throws IllegalArgumentException 字节不是合法 class 文件时抛出
   */
  private static ClassReader createReader(byte[] originalBytes, String className) {
    try {
      return new ClassReader(originalBytes);
    }
    catch (RuntimeException error) {
      throw new IllegalArgumentException("Cannot parse class bytes: " + className, error);
    }
  }

  /**
   * 把类字节读入 ASM 树模型。
   *
   * @param reader 类读取器
   * @param className 点分类型名，仅用于错误信息
   * @return 类节点
   * @throws IllegalArgumentException 类结构无法解析时抛出
   */
  private static ClassNode readClass(ClassReader reader, String className) {
    ClassNode node = new ClassNode();
    try {
      reader.accept(node, 0);
    }
    catch (RuntimeException error) {
      throw new IllegalArgumentException("Cannot read class structure: " + className, error);
    }
    return node;
  }

  /**
   * 写回改写后的类字节。
   *
   * @param reader 原始类读取器，用于复用常量池
   * @param node 改写后的类节点
   * @param className 点分类型名，仅用于错误信息
   * @return 新类字节
   * @throws IllegalArgumentException 类写出失败时抛出
   */
  private static byte[] writeClass(ClassReader reader, ClassNode node, String className) {
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    try {
      node.accept(writer);
    }
    catch (RuntimeException error) {
      throw new IllegalArgumentException("Cannot write facade class: " + className, error);
    }
    return writer.toByteArray();
  }

  /**
   * 判断类是否属于不改写范围（JDK 与 agent 自身）。
   *
   * @param internalName 类内部名
   * @return 需要跳过时返回 true
   */
  private static boolean isExcluded(String internalName) {
    String dottedName = internalName.replace('/', '.');
    for (String prefix : EXCLUDED_PREFIXES) {
      if (dottedName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断类型本身是否不支持 facade 改写（接口、枚举、注解、模块描述符）。
   *
   * @param access 类访问标志
   * @return 不支持时返回 true
   */
  private static boolean isSkippedType(int access) {
    return (access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION
      | Opcodes.ACC_MODULE)) != 0;
  }

  /**
   * 判断类是否已经带有 delegate 字段，避免重复改写。
   *
   * @param node 类节点
   * @return 已改写时返回 true
   */
  private static boolean hasDelegateField(ClassNode node) {
    for (FieldNode field : node.fields) {
      if (HotswapNames.DELEGATE_FIELD.equals(field.name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 创建 facade 的 delegate 字段：{@code private volatile Object __sid_delegate;}。
   *
   * @return 字段节点
   */
  private static FieldNode createDelegateField() {
    return new FieldNode(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
      HotswapNames.DELEGATE_FIELD,
      DELEGATE_DESCRIPTOR,
      null,
      null);
  }

  /**
   * 判断方法是否需要改写成转发调用。
   *
   * @param method 方法节点
   * @return 需要改写时返回 true
   */
  private static boolean isDispatchable(MethodNode method) {
    if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      return false;
    }
    return !"<init>".equals(method.name) && !"<clinit>".equals(method.name);
  }

  /**
   * 把实例方法体替换为运行期转发调用。
   *
   * @param method 目标方法节点，方法体与局部变量表会被替换
   */
  private static void rewriteBody(MethodNode method) {
    Type[] argumentTypes = Type.getArgumentTypes(method.desc);
    Type returnType = Type.getReturnType(method.desc);
    InsnList code = new InsnList();
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new LdcInsnNode(method.name));
    code.add(new LdcInsnNode(method.desc));
    code.add(intConstant(argumentTypes.length));
    code.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT_INTERNAL_NAME));
    int localIndex = 1;
    for (int index = 0; index < argumentTypes.length; index++) {
      Type argumentType = argumentTypes[index];
      code.add(new InsnNode(Opcodes.DUP));
      code.add(intConstant(index));
      code.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), localIndex));
      appendBoxing(code, argumentType);
      code.add(new InsnNode(Opcodes.AASTORE));
      localIndex += argumentType.getSize();
    }
    code.add(new MethodInsnNode(
      Opcodes.INVOKESTATIC,
      RUNTIME_INTERNAL_NAME,
      HotswapNames.INVOKE_METHOD,
      HotswapNames.INVOKE_DESCRIPTOR,
      false));
    appendReturn(code, returnType);
    method.instructions = code;
    method.tryCatchBlocks = new ArrayList<TryCatchBlockNode>();
    method.localVariables = null;
    method.visibleLocalVariableAnnotations = null;
    method.invisibleLocalVariableAnnotations = null;
  }

  /**
   * 追加返回值转换与返回指令。
   *
   * @param code 指令列表
   * @param returnType 方法返回类型
   */
  private static void appendReturn(InsnList code, Type returnType) {
    if (returnType.getSort() == Type.VOID) {
      code.add(new InsnNode(Opcodes.POP));
      code.add(new InsnNode(Opcodes.RETURN));
      return;
    }
    if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
      if (!OBJECT_INTERNAL_NAME.equals(returnType.getInternalName())) {
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
      }
      code.add(new InsnNode(Opcodes.ARETURN));
      return;
    }
    code.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapperInternalName(returnType)));
    code.add(new MethodInsnNode(
      Opcodes.INVOKEVIRTUAL,
      wrapperInternalName(returnType),
      unboxMethodName(returnType),
      "()" + returnType.getDescriptor(),
      false));
    code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
  }

  /**
   * 追加基本类型装箱指令；引用类型与数组不处理。
   *
   * @param code 指令列表
   * @param type 参数类型
   */
  private static void appendBoxing(InsnList code, Type type) {
    if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
      return;
    }
    String wrapperName = wrapperInternalName(type);
    code.add(new MethodInsnNode(
      Opcodes.INVOKESTATIC,
      wrapperName,
      "valueOf",
      "(" + type.getDescriptor() + ")L" + wrapperName + ";",
      false));
  }

  /**
   * 返回基本类型对应的包装类内部名。
   *
   * @param type 基本类型
   * @return 包装类内部名
   */
  private static String wrapperInternalName(Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
        return "java/lang/Boolean";
      case Type.BYTE:
        return "java/lang/Byte";
      case Type.CHAR:
        return "java/lang/Character";
      case Type.SHORT:
        return "java/lang/Short";
      case Type.INT:
        return "java/lang/Integer";
      case Type.LONG:
        return "java/lang/Long";
      case Type.FLOAT:
        return "java/lang/Float";
      case Type.DOUBLE:
        return "java/lang/Double";
      default:
        throw new IllegalArgumentException("Not a primitive type: " + type);
    }
  }

  /**
   * 返回基本类型的拆箱方法名。
   *
   * @param type 基本类型
   * @return 拆箱方法名
   */
  private static String unboxMethodName(Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
        return "booleanValue";
      case Type.BYTE:
        return "byteValue";
      case Type.CHAR:
        return "charValue";
      case Type.SHORT:
        return "shortValue";
      case Type.INT:
        return "intValue";
      case Type.LONG:
        return "longValue";
      case Type.FLOAT:
        return "floatValue";
      case Type.DOUBLE:
        return "doubleValue";
      default:
        throw new IllegalArgumentException("Not a primitive type: " + type);
    }
  }

  /**
   * 生成整型常量压栈指令。
   *
   * @param value 常量值
   * @return 常量指令
   */
  private static AbstractInsnNode intConstant(int value) {
    if (value >= -1 && value <= 5) {
      return new InsnNode(Opcodes.ICONST_0 + value);
    }
    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      return new IntInsnNode(Opcodes.BIPUSH, value);
    }
    if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      return new IntInsnNode(Opcodes.SIPUSH, value);
    }
    return new LdcInsnNode(Integer.valueOf(value));
  }
}
