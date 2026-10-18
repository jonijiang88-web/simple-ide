package io.simpleide.hotswap.facade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.simpleide.hotswap.api.HotswapNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * {@link AsmClassInstrumenter} 的字节码结构测试。
 */
class AsmClassInstrumenterTest {

  private static final String RUNTIME_INTERNAL_NAME = HotswapNames.RUNTIME_CLASS.replace('.', '/');

  private RecordingOriginalClassStore store;

  private AsmClassInstrumenter instrumenter;

  /**
   * 每个测试使用独立的存储与改写器。
   */
  @BeforeEach
  void setUp() {
    store = new RecordingOriginalClassStore();
    instrumenter = new AsmClassInstrumenter(store);
  }

  /**
   * 验证 facade 新增了 private volatile delegate 字段，且父类、接口、方法数量保持不变。
   *
   * @throws Exception 改写失败
   */
  @Test
  void addsDelegateFieldAndKeepsHierarchy() throws Exception {
    byte[] original = TestClassFactory.sample();
    byte[] facade = instrument(original);

    ClassNode originalNode = parse(original);
    ClassNode facadeNode = parse(facade);

    // 验证：delegate 字段存在且为 private volatile Object
    FieldNode delegate = field(facadeNode, HotswapNames.DELEGATE_FIELD);
    assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, delegate.access);
    assertEquals("Ljava/lang/Object;", delegate.desc);

    // 验证：父类保持 java/lang/Object
    assertEquals(originalNode.superName, facadeNode.superName);
    // 验证：接口列表保持为空
    assertEquals(originalNode.interfaces.size(), facadeNode.interfaces.size());
    // 验证：字段只在原字段基础上增加 delegate
    assertEquals(originalNode.fields.size() + 1, facadeNode.fields.size());
    // 验证：方法数量不变
    assertEquals(originalNode.methods.size(), facadeNode.methods.size());
    // 验证：原有 count/name 字段仍然存在
    assertNotNull(field(facadeNode, "count"));
    assertNotNull(field(facadeNode, "name"));
  }

  /**
   * 验证实例方法（含私有方法与重载）被改写为 name + descriptor 的运行期转发调用。
   *
   * @throws Exception 改写失败
   */
  @Test
  void forwardsInstanceMethodsToRuntime() throws Exception {
    ClassNode facadeNode = parse(instrument(TestClassFactory.sample()));

    MethodNode hello = method(facadeNode, "hello", "()Ljava/lang/String;");
    LdcInsnNode nameConstant = (LdcInsnNode) hello.instructions.get(1);
    LdcInsnNode descriptorConstant = (LdcInsnNode) hello.instructions.get(2);
    // 验证：转发调用携带方法名
    assertEquals("hello", nameConstant.cst);
    // 验证：转发调用携带 JVM 描述符
    assertEquals("()Ljava/lang/String;", descriptorConstant.cst);
    // 验证：调用 HotswapRuntime.invoke，描述符与约定一致
    assertTrue(hasRuntimeInvoke(hello));
    // 验证：返回前转换为声明返回类型
    assertTrue(hasCheckCast(hello, "java/lang/String"));
    // 验证：方法以 ARETURN 结束
    assertEquals(Opcodes.ARETURN, lastOpcode(hello));

    // 验证：私有方法同样被转发
    MethodNode secret = method(facadeNode, "secret", "()Ljava/lang/String;");
    assertTrue(hasRuntimeInvoke(secret));
    // 验证：私有方法描述符被正确转发
    assertEquals("()Ljava/lang/String;", descriptorOf(secret));

    // 验证：int 重载转发自身描述符
    MethodNode pickInt = method(facadeNode, "pick", "(I)Ljava/lang/String;");
    assertEquals("(I)Ljava/lang/String;", descriptorOf(pickInt));
    // 验证：String 重载转发自身描述符
    MethodNode pickString = method(facadeNode, "pick", "(Ljava/lang/String;)Ljava/lang/String;");
    assertEquals("(Ljava/lang/String;)Ljava/lang/String;", descriptorOf(pickString));

    // 验证：参数数组使用 Object[] 承载装箱参数
    MethodNode add = method(facadeNode, "add", "(II)I");
    assertTrue(hasNewObjectArray(add));
  }

  /**
   * 验证所有基本类型的装箱、拆箱与数组返回转换。
   *
   * @throws Exception 改写失败
   */
  @Test
  void boxesAndUnboxesAllPrimitiveTypes() throws Exception {
    ClassNode facadeNode = parse(instrument(TestClassFactory.sample()));

    MethodNode add = method(facadeNode, "add", "(II)I");
    // 验证：int 参数装箱为 Integer
    assertTrue(hasBoxing(add, "java/lang/Integer", "(I)Ljava/lang/Integer;"));
    // 验证：int 返回值拆箱
    assertTrue(hasUnboxing(add, "java/lang/Integer", "intValue", "()I"));
    // 验证：int 方法以 IRETURN 结束
    assertEquals(Opcodes.IRETURN, lastOpcode(add));

    MethodNode widen = method(facadeNode, "widen", "(J)J");
    // 验证：long 参数装箱为 Long
    assertTrue(hasBoxing(widen, "java/lang/Long", "(J)Ljava/lang/Long;"));
    // 验证：long 返回值拆箱
    assertTrue(hasUnboxing(widen, "java/lang/Long", "longValue", "()J"));
    // 验证：long 方法以 LRETURN 结束
    assertEquals(Opcodes.LRETURN, lastOpcode(widen));

    MethodNode third = method(facadeNode, "third", "(D)D");
    // 验证：double 参数装箱为 Double
    assertTrue(hasBoxing(third, "java/lang/Double", "(D)Ljava/lang/Double;"));
    // 验证：double 返回值拆箱
    assertTrue(hasUnboxing(third, "java/lang/Double", "doubleValue", "()D"));
    // 验证：double 方法以 DRETURN 结束
    assertEquals(Opcodes.DRETURN, lastOpcode(third));

    MethodNode half = method(facadeNode, "half", "(F)F");
    // 验证：float 参数装箱为 Float
    assertTrue(hasBoxing(half, "java/lang/Float", "(F)Ljava/lang/Float;"));
    // 验证：float 返回值拆箱
    assertTrue(hasUnboxing(half, "java/lang/Float", "floatValue", "()F"));
    // 验证：float 方法以 FRETURN 结束
    assertEquals(Opcodes.FRETURN, lastOpcode(half));

    MethodNode flag = method(facadeNode, "flag", "(Z)Z");
    // 验证：boolean 参数装箱为 Boolean
    assertTrue(hasBoxing(flag, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;"));
    // 验证：boolean 返回值拆箱
    assertTrue(hasUnboxing(flag, "java/lang/Boolean", "booleanValue", "()Z"));
    // 验证：boolean 方法以 IRETURN 结束
    assertEquals(Opcodes.IRETURN, lastOpcode(flag));

    MethodNode byteValue = method(facadeNode, "byteValue", "(B)B");
    // 验证：byte 参数装箱为 Byte
    assertTrue(hasBoxing(byteValue, "java/lang/Byte", "(B)Ljava/lang/Byte;"));
    // 验证：byte 返回值拆箱
    assertTrue(hasUnboxing(byteValue, "java/lang/Byte", "byteValue", "()B"));

    MethodNode charValue = method(facadeNode, "charValue", "(C)C");
    // 验证：char 参数装箱为 Character
    assertTrue(hasBoxing(charValue, "java/lang/Character", "(C)Ljava/lang/Character;"));
    // 验证：char 返回值拆箱
    assertTrue(hasUnboxing(charValue, "java/lang/Character", "charValue", "()C"));

    MethodNode shortValue = method(facadeNode, "shortValue", "(S)S");
    // 验证：short 参数装箱为 Short
    assertTrue(hasBoxing(shortValue, "java/lang/Short", "(S)Ljava/lang/Short;"));
    // 验证：short 返回值拆箱
    assertTrue(hasUnboxing(shortValue, "java/lang/Short", "shortValue", "()S"));

    MethodNode noop = method(facadeNode, "noop", "()V");
    // 验证：void 方法丢弃返回值后 RETURN
    assertEquals(Opcodes.RETURN, lastOpcode(noop));
    assertTrue(hasOpcode(noop, Opcodes.POP));

    MethodNode arraySum = method(facadeNode, "arraySum", "([I)I");
    // 验证：数组参数不装箱，直接作为 Object 元素
    assertTrue(hasOpcode(arraySum, Opcodes.AASTORE));
    // 验证：数组参数未做装箱，只有返回值拆箱
    assertEquals(false, hasBoxing(arraySum, "java/lang/Integer", "(I)Ljava/lang/Integer;"));
  }

  /**
   * 验证构造器与静态方法体保持原样。
   *
   * @throws Exception 改写失败
   */
  @Test
  void keepsConstructorsAndStaticMethods() throws Exception {
    ClassNode facadeNode = parse(instrument(TestClassFactory.sample()));

    MethodNode constructor = method(facadeNode, "<init>", "()V");
    // 验证：构造器没有转发到运行期
    assertEquals(false, hasRuntimeInvoke(constructor));
    // 验证：构造器仍然调用父类构造器
    assertTrue(hasMethodInsn(constructor, Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"));
    // 验证：构造器仍然写入 count 字段
    assertTrue(hasFieldInsn(constructor, "count"));
    // 验证：构造器以 RETURN 结束
    assertEquals(Opcodes.RETURN, lastOpcode(constructor));

    MethodNode staticHello = method(facadeNode, "staticHello", "()Ljava/lang/String;");
    // 验证：静态方法没有转发到运行期
    assertEquals(false, hasRuntimeInvoke(staticHello));
    // 验证：静态方法仍然返回原常量
    assertTrue(hasLdc(staticHello, "v1-static"));
    // 验证：静态方法修饰符保持 static
    assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, staticHello.access);

    MethodNode staticSafeDivide = method(facadeNode, "staticSafeDivide", "(II)I");
    // 验证：含分支与异常处理器的静态方法没有转发到运行期
    assertEquals(false, hasRuntimeInvoke(staticSafeDivide));
    // 验证：原有异常处理器保留
    assertEquals(1, staticSafeDivide.tryCatchBlocks.size());
  }

  /**
   * 验证接口、枚举、注解、JDK 类与 agent 自身类被原样返回。
   *
   * @throws Exception 改写失败
   */
  @Test
  void skipsInterfaceEnumAnnotationAndExcludedClasses() throws Exception {
    byte[] interfaceBytes = TestClassFactory.sampleInterface();
    // 验证：接口字节不变
    assertArrayEquals(interfaceBytes, instrument(interfaceBytes));

    byte[] enumBytes = TestClassFactory.sampleEnum();
    // 验证：枚举字节不变
    assertArrayEquals(enumBytes, instrument(enumBytes));

    byte[] annotationBytes = TestClassFactory.sampleAnnotation();
    // 验证：注解字节不变
    assertArrayEquals(annotationBytes, instrument(annotationBytes));

    byte[] stringBytes = classBytes(String.class);
    // 验证：java.* 类字节不变
    assertArrayEquals(stringBytes, instrumenter.instrument(stringBytes, String.class.getName(), null));

    byte[] agentBytes = classBytes(AsmClassInstrumenter.class);
    // 验证：io.simpleide.hotswap.* 类字节不变
    assertArrayEquals(agentBytes,
      instrumenter.instrument(agentBytes, AsmClassInstrumenter.class.getName(), null));

    // 验证：被跳过的类不写入原始字节存储
    assertTrue(store.recordedNames().isEmpty());
    // 验证：被跳过的类不会解析出 delegate 字段
    assertNull(field(parse(instrument(interfaceBytes)), HotswapNames.DELEGATE_FIELD));
  }

  /**
   * 验证原始字节按点分类名记录。
   *
   * @throws Exception 改写失败
   */
  @Test
  void recordsOriginalBytes() throws Exception {
    byte[] original = TestClassFactory.sample();
    instrument(original);

    // 验证：记录的类型名为夹具的点分名
    assertEquals(Collections.singleton(TestClassFactory.SAMPLE_CLASS_NAME),
      store.recordedNames());
    // 验证：记录的内容与编译输入完全一致
    assertArrayEquals(original, store.bytesOf(TestClassFactory.SAMPLE_CLASS_NAME));
  }

  /**
   * 验证对已是 facade 的字节重复改写会原样返回。
   *
   * @throws Exception 改写失败
   */
  @Test
  void isIdempotent() throws Exception {
    byte[] facade = instrument(TestClassFactory.sample());
    byte[] again = instrumenter.instrument(facade, TestClassFactory.SAMPLE_CLASS_NAME, null);

    // 验证：第二次改写结果与第一次完全一致
    assertArrayEquals(facade, again);
    // 验证：重复改写不重复记录原始字节
    assertEquals(1, store.recordedNames().size());
    // 验证：facade 中只有一个 delegate 字段
    assertEquals(1, countField(parse(facade), HotswapNames.DELEGATE_FIELD));
  }

  /**
   * 调用改写器并断言产出可解析。
   *
   * @param original 原始类字节
   * @return facade 字节
   * @throws Exception 改写失败
   */
  private byte[] instrument(byte[] original) throws Exception {
    byte[] facade = instrumenter.instrument(original, TestClassFactory.SAMPLE_CLASS_NAME, null);
    assertNotNull(facade);
    // 验证：产出可以重新解析为合法类结构
    parse(facade);
    return facade;
  }

  /**
   * 解析类字节为 ASM 树模型。
   *
   * @param bytes 类字节
   * @return 类节点
   */
  private static ClassNode parse(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    return node;
  }

  /**
   * 查找字段，不存在时返回 null。
   *
   * @param node 类节点
   * @param name 字段名
   * @return 字段节点或 null
   */
  private static FieldNode field(ClassNode node, String name) {
    for (FieldNode field : node.fields) {
      if (field.name.equals(name)) {
        return field;
      }
    }
    return null;
  }

  /**
   * 统计同名字段数量。
   *
   * @param node 类节点
   * @param name 字段名
   * @return 数量
   */
  private static int countField(ClassNode node, String name) {
    int count = 0;
    for (FieldNode field : node.fields) {
      if (field.name.equals(name)) {
        count++;
      }
    }
    return count;
  }

  /**
   * 查找方法，不存在时让测试失败。
   *
   * @param node 类节点
   * @param name 方法名
   * @param descriptor 方法描述符
   * @return 方法节点
   */
  private static MethodNode method(ClassNode node, String name, String descriptor) {
    for (MethodNode method : node.methods) {
      if (method.name.equals(name) && method.desc.equals(descriptor)) {
        return method;
      }
    }
    fail("method not found: " + name + descriptor);
    return null;
  }

  /**
   * 判断方法是否包含运行期转发调用。
   *
   * @param method 方法节点
   * @return 包含时返回 true
   */
  private static boolean hasRuntimeInvoke(MethodNode method) {
    return hasMethodInsn(method, Opcodes.INVOKESTATIC, RUNTIME_INTERNAL_NAME,
      HotswapNames.INVOKE_METHOD, HotswapNames.INVOKE_DESCRIPTOR);
  }

  /**
   * 读取承载方法名/描述符的 LDC 常量。
   *
   * @param method 方法节点
   * @return 方法描述符常量
   */
  private static Object descriptorOf(MethodNode method) {
    LdcInsnNode descriptor = null;
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof LdcInsnNode) {
        descriptor = (LdcInsnNode) instruction;
      }
    }
    if (descriptor == null) {
      fail("no descriptor constant in " + method.name);
    }
    return descriptor.cst;
  }

  /**
   * 判断方法是否包含指定方法调用。
   *
   * @param method 方法节点
   * @param opcode 调用指令
   * @param owner 调用者内部名
   * @param name 方法名
   * @param descriptor 描述符
   * @return 包含时返回 true
   */
  private static boolean hasMethodInsn(MethodNode method, int opcode, String owner, String name,
      String descriptor) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (!(instruction instanceof MethodInsnNode)) {
        continue;
      }
      MethodInsnNode call = (MethodInsnNode) instruction;
      if (call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name)
        && call.desc.equals(descriptor)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断方法是否包含指定装箱调用。
   *
   * @param method 方法节点
   * @param owner 包装类内部名
   * @param descriptor valueOf 描述符
   * @return 包含时返回 true
   */
  private static boolean hasBoxing(MethodNode method, String owner, String descriptor) {
    return hasMethodInsn(method, Opcodes.INVOKESTATIC, owner, "valueOf", descriptor);
  }

  /**
   * 判断方法是否包含指定拆箱调用。
   *
   * @param method 方法节点
   * @param owner 包装类内部名
   * @param name 拆箱方法名
   * @param descriptor 拆箱方法描述符
   * @return 包含时返回 true
   */
  private static boolean hasUnboxing(MethodNode method, String owner, String name, String descriptor) {
    return hasMethodInsn(method, Opcodes.INVOKEVIRTUAL, owner, name, descriptor);
  }

  /**
   * 判断方法是否包含指定类型的 CHECKCAST。
   *
   * @param method 方法节点
   * @param internalName 目标类型内部名
   * @return 包含时返回 true
   */
  private static boolean hasCheckCast(MethodNode method, String internalName) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof TypeInsnNode) {
        TypeInsnNode type = (TypeInsnNode) instruction;
        if (type.getOpcode() == Opcodes.CHECKCAST && type.desc.equals(internalName)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 判断方法是否创建 Object[] 参数数组。
   *
   * @param method 方法节点
   * @return 包含时返回 true
   */
  private static boolean hasNewObjectArray(MethodNode method) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof TypeInsnNode) {
        TypeInsnNode type = (TypeInsnNode) instruction;
        if (type.getOpcode() == Opcodes.ANEWARRAY && type.desc.equals("java/lang/Object")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 判断方法是否包含指定常量的 LDC。
   *
   * @param method 方法节点
   * @param constant 常量
   * @return 包含时返回 true
   */
  private static boolean hasLdc(MethodNode method, Object constant) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof LdcInsnNode && constant.equals(((LdcInsnNode) instruction).cst)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断方法是否包含指定字段访问。
   *
   * @param method 方法节点
   * @param fieldName 字段名
   * @return 包含时返回 true
   */
  private static boolean hasFieldInsn(MethodNode method, String fieldName) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction instanceof FieldInsnNode
        && ((FieldInsnNode) instruction).name.equals(fieldName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断方法是否包含指定操作码。
   *
   * @param method 方法节点
   * @param opcode 操作码
   * @return 包含时返回 true
   */
  private static boolean hasOpcode(MethodNode method, int opcode) {
    for (AbstractInsnNode instruction : method.instructions) {
      if (instruction.getOpcode() == opcode) {
        return true;
      }
    }
    return false;
  }

  /**
   * 返回方法最后一条真实指令的操作码。
   *
   * @param method 方法节点
   * @return 操作码
   */
  private static int lastOpcode(MethodNode method) {
    AbstractInsnNode last = method.instructions.getLast();
    while (last != null && last.getOpcode() < 0) {
      last = last.getPrevious();
    }
    if (last == null) {
      fail("empty method: " + method.name);
    }
    return last.getOpcode();
  }

  /**
   * 读取类路径上的 class 字节。
   *
   * @param type 目标类
   * @return 类字节
   * @throws IOException 读取失败
   */
  private static byte[] classBytes(Class<?> type) throws IOException {
    InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class");
    assertNotNull(stream, "class resource missing: " + type.getName());
    try (InputStream input = stream) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read = input.read(buffer);
      while (read >= 0) {
        output.write(buffer, 0, read);
        read = input.read(buffer);
      }
      return output.toByteArray();
    }
  }
}
