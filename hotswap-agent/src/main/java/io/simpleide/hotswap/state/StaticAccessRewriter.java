package io.simpleide.hotswap.state;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 把类自身声明的非 final 静态字段访问改写为 {@link SharedStaticState} 读写。
 *
 * <p>只处理 {@code GETSTATIC}/{@code PUTSTATIC} 且 owner 为当前类、字段在本类中声明
 * 的情况。基础类型会按字段描述符自动装箱/拆箱。final 静态字段保持原样，因为常量
 * 语义不应被动态共享破坏。
 *
 * <p>版本类的字段 owner 会变成版本类名，因此调用 {@link #rewrite(byte[])} 时可用
 * {@link #rewrite(byte[], String)} 传入原始类名作为共享 key，保证所有版本命中同一
 * 份静态状态。
 */
public final class StaticAccessRewriter {

  /** 共享静态状态实现类的内部名。 */
  private static final String STORE = "io/simpleide/hotswap/state/SharedStaticState";

  /** 获取全局共享实例的方法描述符。 */
  private static final String GET_INSTANCE_DESCRIPTOR = "()L" + STORE + ";";

  /** get(String, String) 的方法描述符。 */
  private static final String GET_DESCRIPTOR =
    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;";

  /** set(String, String, Object) 的方法描述符。 */
  private static final String SET_DESCRIPTOR =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V";

  /**
   * 改写字节码，共享 key 使用类自身名字。
   *
   * @param classBytes 原始类字节
   * @return 改写后的类字节，类中没有可共享静态字段时返回原字节
   * @throws Exception 字节码解析或生成失败时抛出
   */
  public byte[] rewrite(byte[] classBytes) throws Exception {
    return rewrite(classBytes, null);
  }

  /**
   * 改写字节码，并指定共享静态字段使用的 key 前缀。
   *
   * @param classBytes 原始类或版本类字节
   * @param stateClassName 共享 key 使用的点号类名；为 null 时使用字节中的类名
   * @return 改写后的类字节，类中没有可共享静态字段时返回原字节
   * @throws Exception 字节码解析或生成失败时抛出
   */
  public byte[] rewrite(byte[] classBytes, String stateClassName) throws Exception {
    if (classBytes == null) {
      throw new IllegalArgumentException("Cannot rewrite static access: class bytes are null");
    }
    ClassNode node = new ClassNode();
    new ClassReader(classBytes).accept(node, 0);
    String className = node.name.replace('/', '.');
    String keyOwner = stateClassName == null ? className : stateClassName;
    Map<String, String> sharedFields = sharedFields(node);
    if (sharedFields.isEmpty()) {
      return classBytes;
    }
    for (MethodNode method : node.methods) {
      rewriteMethod(method, node.name, keyOwner, sharedFields);
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  /**
   * 收集类中声明的非 final 静态字段。
   *
   * @param node 类节点
   * @return 字段名到字段描述符的映射
   */
  private Map<String, String> sharedFields(ClassNode node) {
    Map<String, String> fields = new HashMap<String, String>();
    for (FieldNode field : node.fields) {
      boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
      boolean isFinal = (field.access & Opcodes.ACC_FINAL) != 0;
      if (isStatic && !isFinal) {
        fields.put(field.name, field.desc);
      }
    }
    return fields;
  }

  /**
   * 改写一个方法中的静态字段访问指令。
   *
   * @param method 方法节点
   * @param owner 当前类的内部名
   * @param keyOwner 共享 key 使用的点号类名
   * @param sharedFields 可共享字段映射
   */
  private void rewriteMethod(MethodNode method, String owner, String keyOwner,
                             Map<String, String> sharedFields) {
    for (AbstractInsnNode instruction : method.instructions.toArray()) {
      int opcode = instruction.getOpcode();
      if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) {
        continue;
      }
      FieldInsnNode field = (FieldInsnNode) instruction;
      if (!field.owner.equals(owner)) {
        continue;
      }
      String descriptor = sharedFields.get(field.name);
      if (descriptor == null || !descriptor.equals(field.desc)) {
        continue;
      }
      if (opcode == Opcodes.GETSTATIC) {
        method.instructions.insertBefore(instruction,
          readValue(keyOwner, field.name, descriptor));
      }
      else {
        method.instructions.insertBefore(instruction,
          writeValue(keyOwner, field.name, descriptor, method.maxLocals));
      }
      method.instructions.remove(instruction);
    }
  }

  /**
   * 生成读取共享静态字段的指令序列。
   *
   * @param className 共享 key 类名
   * @param fieldName 字段名
   * @param descriptor 字段描述符
   * @return 指令序列，执行后栈顶为字段值
   */
  private InsnList readValue(String className, String fieldName, String descriptor) {
    InsnList instructions = new InsnList();
    instructions.add(storeInstance());
    instructions.add(new LdcInsnNode(className));
    instructions.add(new LdcInsnNode(fieldName));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STORE, "get",
      GET_DESCRIPTOR, false));
    if (isPrimitive(descriptor)) {
      String wrapper = wrapperName(descriptor);
      instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
      instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper,
        unboxMethod(descriptor), "()" + descriptor, false));
    }
    else {
      instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, referenceTypeName(descriptor)));
    }
    return instructions;
  }

  /**
   * 把引用类型描述符转换为 CHECKCAST 需要的内部名或数组描述符。
   *
   * @param descriptor 引用类型描述符
   * @return 对象内部名或数组描述符
   */
  private String referenceTypeName(String descriptor) {
    if (descriptor.charAt(0) == '[') {
      return descriptor;
    }
    return descriptor.substring(1, descriptor.length() - 1);
  }

  /**
   * 生成写入共享静态字段的指令序列。
   *
   * @param className 共享 key 类名
   * @param fieldName 字段名
   * @param descriptor 字段描述符
   * @param local 用于暂存装箱值的局部变量槽
   * @return 指令序列，执行后清空原字段值
   */
  private InsnList writeValue(String className, String fieldName, String descriptor, int local) {
    InsnList instructions = new InsnList();
    if (isPrimitive(descriptor)) {
      String wrapper = wrapperName(descriptor);
      instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
        "(" + descriptor + ")L" + wrapper + ";", false));
    }
    instructions.add(new VarInsnNode(Opcodes.ASTORE, local));
    instructions.add(storeInstance());
    instructions.add(new LdcInsnNode(className));
    instructions.add(new LdcInsnNode(fieldName));
    instructions.add(new VarInsnNode(Opcodes.ALOAD, local));
    instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STORE, "set",
      SET_DESCRIPTOR, false));
    return instructions;
  }

  /**
   * 生成获取全局共享实例的指令。
   *
   * @return INVOKESTATIC getInstance 指令
   */
  private AbstractInsnNode storeInstance() {
    return new MethodInsnNode(Opcodes.INVOKESTATIC, STORE, "getInstance",
      GET_INSTANCE_DESCRIPTOR, false);
  }

  /**
   * 判断字段描述符是否为基础类型。
   *
   * @param descriptor 字段描述符
   * @return 基础类型返回 true
   */
  private boolean isPrimitive(String descriptor) {
    return descriptor.length() == 1;
  }

  /**
   * 返回基础类型对应的包装类内部名。
   *
   * @param descriptor 基础类型描述符
   * @return 包装类内部名
   */
  private String wrapperName(String descriptor) {
    switch (descriptor.charAt(0)) {
      case 'Z':
        return "java/lang/Boolean";
      case 'B':
        return "java/lang/Byte";
      case 'C':
        return "java/lang/Character";
      case 'S':
        return "java/lang/Short";
      case 'I':
        return "java/lang/Integer";
      case 'J':
        return "java/lang/Long";
      case 'F':
        return "java/lang/Float";
      case 'D':
        return "java/lang/Double";
      default:
        throw new IllegalArgumentException("Unsupported primitive descriptor: " + descriptor);
    }
  }

  /**
   * 返回基础类型对应的拆箱方法名。
   *
   * @param descriptor 基础类型描述符
   * @return 拆箱方法名
   */
  private String unboxMethod(String descriptor) {
    switch (descriptor.charAt(0)) {
      case 'Z':
        return "booleanValue";
      case 'B':
        return "byteValue";
      case 'C':
        return "charValue";
      case 'S':
        return "shortValue";
      case 'I':
        return "intValue";
      case 'J':
        return "longValue";
      case 'F':
        return "floatValue";
      case 'D':
        return "doubleValue";
      default:
        throw new IllegalArgumentException("Unsupported primitive descriptor: " + descriptor);
    }
  }
}
