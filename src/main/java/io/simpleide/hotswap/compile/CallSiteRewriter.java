package io.simpleide.hotswap.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 编译后调用点改写器。
 *
 * <p>把应用类之间新增方法的 invokevirtual/invokeinterface 改成调用生成的静态桥；
 * 桥再通过 HotswapRuntime 找到接收者对应的当前版本类。这样可以支持
 * “已有类新增方法 + Spring 自动注入”的调用，而不需要修改已加载的 facade schema。
 *
 * <p>只处理项目输出目录中的 class；JDK、框架依赖 jar 和 agent 自身不改写。
 */
public final class CallSiteRewriter {

  /** 生成桥类的内部名前缀。 */
  private static final String BRIDGE_PREFIX = "io/simpleide/hotswap/generated/CallBridge";

  /** 单个桥类最多包含的方法数，避免大型项目触达 Class too large。 */
  private static final int MAX_METHODS_PER_BRIDGE = 200;

  /** HotswapRuntime 内部名。 */
  private static final String RUNTIME_INTERNAL_NAME = "io/simpleide/hotswap/runtime/HotswapRuntime";

  /** HotswapRuntime.invoke 描述符。 */
  private static final String INVOKE_DESCRIPTOR =
      "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";

  private CallSiteRewriter() {
  }

  /**
   * 改写一组模块输出目录中的调用点，并把桥类写入每个输出目录。
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
    Map<Path, byte[]> classBytes = new LinkedHashMap<Path, byte[]>();
    Set<String> applicationClasses = new HashSet<String>();
    for (Path output : outputs) {
      try (java.util.stream.Stream<Path> stream = Files.walk(output)) {
        java.util.Iterator<Path> iterator = stream.iterator();
        while (iterator.hasNext()) {
          Path file = iterator.next();
          if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".class")) {
            continue;
          }
          String relative = output.relativize(file).toString().replace('\\', '/');
          if (relative.startsWith(BRIDGE_PREFIX)) {
            continue;
          }
          byte[] bytes = Files.readAllBytes(file);
          classBytes.put(file, bytes);
          applicationClasses.add(className(bytes));
        }
      }
    }
    if (applicationClasses.isEmpty()) {
      return;
    }
    Map<CallSpec, CallSpec> specs = new LinkedHashMap<CallSpec, CallSpec>();
    for (byte[] bytes : classBytes.values()) {
      collectSpecs(bytes, applicationClasses, specs);
    }
    List<CallSpec> ordered = new ArrayList<CallSpec>(specs.keySet());
    Collections.sort(ordered, new Comparator<CallSpec>() {
      @Override
      public int compare(CallSpec left, CallSpec right) {
        return left.key().compareTo(right.key());
      }
    });
    if (ordered.isEmpty()) {
      return;
    }
    Map<String, BridgeCall> byKey = new HashMap<String, BridgeCall>();
    List<BridgeClass> bridgeClasses = new ArrayList<BridgeClass>();
    for (int start = 0; start < ordered.size(); start += MAX_METHODS_PER_BRIDGE) {
      int end = Math.min(start + MAX_METHODS_PER_BRIDGE, ordered.size());
      List<CallSpec> chunk = ordered.subList(start, end);
      String bridgeInternalName = bridgeInternalName(chunk);
      bridgeClasses.add(new BridgeClass(bridgeInternalName, chunk));
      for (CallSpec spec : chunk) {
        byKey.put(spec.key(), new BridgeCall(bridgeInternalName, spec.bridgeName,
          spec.bridgeDescriptor));
      }
    }
    for (Map.Entry<Path, byte[]> entry : classBytes.entrySet()) {
      byte[] rewritten = rewriteClass(entry.getValue(), byKey);
      if (rewritten != entry.getValue()) {
        writeAtomically(entry.getKey(), rewritten);
      }
    }
    for (BridgeClass bridgeClass : bridgeClasses) {
      byte[] bridge = generateBridge(bridgeClass.specs, bridgeClass.internalName);
      for (Path output : outputs) {
        Path bridgeFile = output.resolve(bridgeClass.internalName.concat(".class"));
        if (!Files.isDirectory(bridgeFile.getParent())) {
          Files.createDirectories(bridgeFile.getParent());
        }
        writeAtomically(bridgeFile, bridge);
      }
    }
  }

  /**
   * 扫描一个 class 中需要改写的调用点。
   *
   * @param bytes class 字节
   * @param applicationClasses 项目类内部名集合
   * @param specs 调用点规格
   */
  private static void collectSpecs(byte[] bytes, Set<String> applicationClasses,
                                   Map<CallSpec, CallSpec> specs) {
    ClassNode node = readClass(bytes);
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
           instruction = instruction.getNext()) {
        if (!(instruction instanceof MethodInsnNode)) {
          continue;
        }
        MethodInsnNode call = (MethodInsnNode) instruction;
        if (!isRewritable(call, applicationClasses)) {
          continue;
        }
        CallSpec spec = new CallSpec(call.owner, call.name, call.desc);
        if (!specs.containsKey(spec)) {
          specs.put(spec, spec);
        }
      }
    }
  }

  /**
   * 改写一个 class 中的调用点。
   *
   * @param bytes class 字节
   * @param specs 调用点索引
   * @return 改写后的字节；没有变化时返回原数组
   */
  private static byte[] rewriteClass(byte[] bytes, Map<String, BridgeCall> specs) {
    ClassNode node = readClass(bytes);
    boolean changed = false;
    for (MethodNode method : node.methods) {
      AbstractInsnNode instruction = method.instructions.getFirst();
      while (instruction != null) {
        AbstractInsnNode next = instruction.getNext();
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          BridgeCall spec = specs.get(CallSpec.key(call.owner, call.name, call.desc));
          if (spec != null) {
            method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
              spec.bridgeInternalName, spec.bridgeName, spec.bridgeDescriptor, false));
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
    if (call.owner.startsWith(BRIDGE_PREFIX)) {
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
   * 根据调用点集合生成稳定且唯一的桥类内部名。
   *
   * <p>新增调用签名时生成新的桥类，避免已经加载的旧桥类无法增加方法。
   *
   * @param specs 调用点列表
   * @return 桥类内部名
   */
  private static String bridgeInternalName(List<CallSpec> specs) {
    StringBuilder text = new StringBuilder();
    for (CallSpec spec : specs) {
      text.append(spec.key()).append('\n');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hash = digest.digest(text.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder name = new StringBuilder(BRIDGE_PREFIX).append('$');
      for (int index = 0; index < 8; index++) {
        name.append(String.format("%02x", hash[index] & 0xFF));
      }
      return name.toString();
    }
    catch (Exception ignored) {
      return BRIDGE_PREFIX + "$" + Integer.toHexString(text.toString().hashCode());
    }
  }

  /**
   * 生成桥类。
   *
   * @param specs 调用点列表
   * @return bridge class 字节
   */
  private static byte[] generateBridge(List<CallSpec> specs, String bridgeInternalName) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      bridgeInternalName, null, "java/lang/Object", null);
    for (CallSpec spec : specs) {
      MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
        spec.bridgeName, spec.bridgeDescriptor, null, null);
      method.visitCode();
      method.visitVarInsn(Opcodes.ALOAD, 0);
      method.visitLdcInsn(spec.name);
      method.visitLdcInsn(spec.desc);
      Type[] arguments = Type.getArgumentTypes(spec.desc);
      pushInt(method, arguments.length);
      method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
      int slot = 1;
      for (int index = 0; index < arguments.length; index++) {
        Type argument = arguments[index];
        method.visitInsn(Opcodes.DUP);
        pushInt(method, index);
        method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
        box(method, argument);
        method.visitInsn(Opcodes.AASTORE);
        slot += argument.getSize();
      }
      method.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL_NAME, "invoke",
        INVOKE_DESCRIPTOR, false);
      writeReturn(method, Type.getReturnType(spec.desc));
      method.visitMaxs(0, 0);
      method.visitEnd();
    }
    writer.visitEnd();
    return writer.toByteArray();
  }

  /**
   * 写入返回值转换和返回指令。
   *
   * @param method 方法访问器
   * @param returnType 返回类型
   */
  private static void writeReturn(MethodVisitor method, Type returnType) {
    if (returnType.getSort() == Type.VOID) {
      method.visitInsn(Opcodes.POP);
      method.visitInsn(Opcodes.RETURN);
      return;
    }
    if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
      method.visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
      method.visitInsn(Opcodes.ARETURN);
      return;
    }
    unbox(method, returnType);
    method.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
  }

  /**
   * 将栈顶基本类型装箱。
   *
   * @param method 方法访问器
   * @param type 类型
   */
  private static void box(MethodVisitor method, Type type) {
    String wrapper;
    String descriptor;
    switch (type.getSort()) {
      case Type.BOOLEAN:
        wrapper = "java/lang/Boolean";
        descriptor = "(Z)Ljava/lang/Boolean;";
        break;
      case Type.CHAR:
        wrapper = "java/lang/Character";
        descriptor = "(C)Ljava/lang/Character;";
        break;
      case Type.BYTE:
        wrapper = "java/lang/Byte";
        descriptor = "(B)Ljava/lang/Byte;";
        break;
      case Type.SHORT:
        wrapper = "java/lang/Short";
        descriptor = "(S)Ljava/lang/Short;";
        break;
      case Type.INT:
        wrapper = "java/lang/Integer";
        descriptor = "(I)Ljava/lang/Integer;";
        break;
      case Type.FLOAT:
        wrapper = "java/lang/Float";
        descriptor = "(F)Ljava/lang/Float;";
        break;
      case Type.LONG:
        wrapper = "java/lang/Long";
        descriptor = "(J)Ljava/lang/Long;";
        break;
      case Type.DOUBLE:
        wrapper = "java/lang/Double";
        descriptor = "(D)Ljava/lang/Double;";
        break;
      default:
        return;
    }
    method.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", descriptor, false);
  }

  /**
   * 将栈顶包装类型拆箱。
   *
   * @param method 方法访问器
   * @param type 基本类型
   */
  private static void unbox(MethodVisitor method, Type type) {
    String wrapper;
    String methodName;
    String descriptor;
    switch (type.getSort()) {
      case Type.BOOLEAN:
        wrapper = "java/lang/Boolean";
        methodName = "booleanValue";
        descriptor = "()Z";
        break;
      case Type.CHAR:
        wrapper = "java/lang/Character";
        methodName = "charValue";
        descriptor = "()C";
        break;
      case Type.BYTE:
        wrapper = "java/lang/Byte";
        methodName = "byteValue";
        descriptor = "()B";
        break;
      case Type.SHORT:
        wrapper = "java/lang/Short";
        methodName = "shortValue";
        descriptor = "()S";
        break;
      case Type.INT:
        wrapper = "java/lang/Integer";
        methodName = "intValue";
        descriptor = "()I";
        break;
      case Type.FLOAT:
        wrapper = "java/lang/Float";
        methodName = "floatValue";
        descriptor = "()F";
        break;
      case Type.LONG:
        wrapper = "java/lang/Long";
        methodName = "longValue";
        descriptor = "()J";
        break;
      case Type.DOUBLE:
        wrapper = "java/lang/Double";
        methodName = "doubleValue";
        descriptor = "()D";
        break;
      default:
        return;
    }
    method.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, methodName, descriptor, false);
  }

  /**
   * 压入 int 常量。
   *
   * @param method 方法访问器
   * @param value 常量
   */
  private static void pushInt(MethodVisitor method, int value) {
    if (value >= -1 && value <= 5) {
      method.visitInsn(Opcodes.ICONST_0 + value);
    }
    else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      method.visitIntInsn(Opcodes.BIPUSH, value);
    }
    else {
      method.visitLdcInsn(Integer.valueOf(value));
    }
  }

  /**
   * 读取 class 树。
   *
   * @param bytes class 字节
   * @return class 树
   */
  private static ClassNode readClass(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    return node;
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
   * 原子写入文件，降低运行中 classloader 读到半写文件的风险。
   *
   * @param file 目标文件
   * @param bytes 内容
   * @throws IOException 写入失败
   */
  private static void writeAtomically(Path file, byte[] bytes) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName().toString() + ".sid.tmp");
    Files.write(temporary, bytes);
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE);
  }

  /** 一个桥目标和原始调用签名。 */
  private static final class BridgeCall {

    private final String bridgeInternalName;
    private final String bridgeName;
    private final String bridgeDescriptor;

    /**
     * 创建桥调用信息。
     *
     * @param bridgeInternalName 桥类内部名
     * @param bridgeName 桥方法名
     * @param bridgeDescriptor 桥方法描述符
     */
    private BridgeCall(String bridgeInternalName, String bridgeName, String bridgeDescriptor) {
      this.bridgeInternalName = bridgeInternalName;
      this.bridgeName = bridgeName;
      this.bridgeDescriptor = bridgeDescriptor;
    }
  }

  /** 一组待生成桥方法。 */
  private static final class BridgeClass {

    private final String internalName;
    private final List<CallSpec> specs;

    /**
     * 创建桥类描述。
     *
     * @param internalName 内部名
     * @param specs 桥方法
     */
    private BridgeClass(String internalName, List<CallSpec> specs) {
      this.internalName = internalName;
      this.specs = specs;
    }
  }

  /** 调用点规格。 */
  private static final class CallSpec {

    private final String owner;
    private final String name;
    private final String desc;
    private final String bridgeName;
    private final String bridgeDescriptor;

    /**
     * 创建调用点规格。
     *
     * @param owner 方法 owner 内部名
     * @param name 方法名
     * @param desc 方法描述符
     */
    private CallSpec(String owner, String name, String desc) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.bridgeName = bridgeName(owner, name, desc);
      this.bridgeDescriptor = "(L" + owner + ";" + desc.substring(1);
    }

    /** 返回规格键。 */
    private String key() {
      return key(owner, name, desc);
    }

    /**
     * 构造规格键。
     *
     * @param owner owner
     * @param name 方法名
     * @param desc 描述符
     * @return 键
     */
    private static String key(String owner, String name, String desc) {
      return owner + "#" + name + desc;
    }

    /**
     * 生成稳定的桥方法名。
     *
     * @param owner owner
     * @param name 方法名
     * @param desc 描述符
     * @return 桥方法名
     */
    private static String bridgeName(String owner, String name, String desc) {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(key(owner, name, desc).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder("m");
        for (int index = 0; index < 10; index++) {
          result.append(String.format("%02x", hash[index] & 0xFF));
        }
        return result.toString();
      }
      catch (Exception ignored) {
        return "m" + Integer.toHexString(key(owner, name, desc).hashCode());
      }
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CallSpec && key().equals(((CallSpec) other).key());
    }

    @Override
    public int hashCode() {
      return key().hashCode();
    }
  }
}
