package io.simpleide.hotswap.version;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.simpleide.hotswap.version.fixtures.Bar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 验证版本类改名与自引用重写。
 */
class AsmVersionClassGeneratorTest {

  private static final String ORIGINAL_INTERNAL = "io/simpleide/hotswap/version/fixtures/Bar";

  private static final String VERSION_INTERNAL =
    "io/simpleide/hotswap/version/fixtures/Bar$$SIDEV1";

  private static byte[] originalBytes;

  /**
   * 读取 fixture 类字节码。
   *
   * @throws IOException 资源读取失败
   */
  @BeforeAll
  static void loadFixture() throws IOException {
    originalBytes = readClassBytes(Bar.class);
  }

  /**
   * 验证版本类名、父类、接口与访问标志。
   */
  @Test
  @DisplayName("生成版本类时把类名改为版本类名并保留父类与接口")
  void renamesClassAndKeepsHierarchy() {
    byte[] generated = generate();
    Recording recording = record(generated);

    // 验证：生成的类名是版本类内部名
    assertEquals(VERSION_INTERNAL, recording.className);
    // 验证：父类仍为 Object
    assertEquals("java/lang/Object", recording.superName);
    // 验证：原类没有接口，生成的版本类也没有接口
    assertTrue(recording.interfaces.isEmpty());
    // 验证：访问标志与 public 保持一致
    assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, recording.access);
  }

  /**
   * 验证字段描述符与泛型签名中的自引用被改写，其他类型不变。
   */
  @Test
  @DisplayName("重写字段描述符与泛型签名中的自引用")
  void rewritesFieldReferences() {
    Recording recording = record(generate());

    // 验证：基本类型字段描述符不变
    assertEquals("I", recording.fields.get("x").descriptor);
    // 验证：其他类型字段描述符不变
    assertEquals("Ljava/lang/String;", recording.fields.get("name").descriptor);
    // 验证：自引用字段描述符指向版本类
    assertEquals("L" + VERSION_INTERNAL + ";", recording.fields.get("next").descriptor);
    // 验证：自引用数组字段描述符指向版本类
    assertEquals("[L" + VERSION_INTERNAL + ";", recording.fields.get("children").descriptor);
    // 验证：泛型集合字段的原始描述符不变
    assertEquals("Ljava/util/List;", recording.fields.get("related").descriptor);
    // 验证：泛型集合字段的签名中自引用指向版本类
    assertEquals("Ljava/util/List<L" + VERSION_INTERNAL + ";>;",
      recording.fields.get("related").signature);
    // 验证：静态字段访问标志保留
    assertTrue((recording.fields.get("counter").access & Opcodes.ACC_STATIC) != 0);
    // 验证：私有字段访问标志保留
    assertEquals(Opcodes.ACC_PRIVATE, recording.fields.get("secret").access);
  }

  /**
   * 验证方法描述符、构造器与方法签名中的自引用被改写。
   */
  @Test
  @DisplayName("重写方法描述符、构造器与方法签名中的自引用")
  void rewritesMethodReferences() {
    Recording recording = record(generate());

    // 验证：普通方法描述符不变
    assertEquals("()Ljava/lang/String;", recording.methods.get("hello").descriptor);
    // 验证：返回自引用类型的方法描述符指向版本类
    assertEquals("()L" + VERSION_INTERNAL + ";", recording.methods.get("copy").descriptor);
    // 验证：自引用数组返回值描述符指向版本类
    assertEquals("()[L" + VERSION_INTERNAL + ";", recording.methods.get("array").descriptor);
    // 验证：自引用参数描述符指向版本类
    assertEquals("(L" + VERSION_INTERNAL + ";)V", recording.methods.get("take").descriptor);
    // 验证：构造器名仍是 <init>，描述符保持 (I)V
    assertEquals("(I)V", recording.methods.get("<init>").descriptor);
    // 验证：泛型方法的签名边界指向版本类
    assertEquals("<T:L" + VERSION_INTERNAL + ";>(TT;)TT;", recording.methods.get("pick").signature);
    // 验证：私有方法访问标志保留
    assertEquals(Opcodes.ACC_PUBLIC, recording.methods.get("secret").access);
  }

  /**
   * 验证注解中的 class 常量、instanceof 常量与栈帧类型都被改写。
   */
  @Test
  @DisplayName("重写注解 class 常量、类型常量与栈帧中的自引用")
  void rewritesAnnotationAndFrameReferences() {
    Recording recording = record(generate());

    // 验证：类注解描述符不变
    assertEquals(Type.getDescriptor(io.simpleide.hotswap.version.fixtures.Ref.class),
      recording.annotationTypes.keySet().iterator().next());
    // 验证：注解 value 中的 class 常量指向版本类
    assertEquals(Type.getObjectType(VERSION_INTERNAL), recording.annotationTypes.values().iterator().next());
    // 验证：instanceof 使用的类型常量指向版本类
    assertEquals(VERSION_INTERNAL, recording.instanceOfTypes.iterator().next());
    // 验证：栈帧中不再出现原类内部名
    assertFalse(recording.frameTypes.contains(ORIGINAL_INTERNAL));
    // 验证：栈帧中出现版本类内部名
    assertTrue(recording.frameTypes.contains(VERSION_INTERNAL));
  }

  /**
   * 验证 InnerClasses 属性保留且版本类自身的条目使用新名字。
   */
  @Test
  @DisplayName("保留内部类属性且不改名内部类")
  void keepsInnerClassEntries() {
    Recording recording = record(generate());

    // 验证：内部类 Bar$Inner 的条目仍然存在
    assertTrue(recording.innerClasses.contains(ORIGINAL_INTERNAL + "$Inner"));
  }

  /**
   * 验证生成过程不修改输入字节，且非法参数会明确报错。
   */
  @Test
  @DisplayName("生成过程不修改输入字节并拒绝非法参数")
  void keepsInputUntouchedAndRejectsInvalidArguments() {
    byte[] snapshot = originalBytes.clone();
    generate();

    // 验证：生成后原类字节保持不变
    assertArrayEquals(snapshot, originalBytes);

    AsmVersionClassGenerator generator = new AsmVersionClassGenerator();
    // 验证：字节码类名与参数不一致时抛出 IllegalArgumentException
    assertThrows(IllegalArgumentException.class,
      () -> generator.generate(originalBytes, "com.demo.Other", VERSION_INTERNAL.replace('/', '.')));
    // 验证：版本类名与原类名相同时抛出 IllegalArgumentException
    assertThrows(IllegalArgumentException.class,
      () -> generator.generate(originalBytes, Bar.class.getName(), Bar.class.getName()));
    // 验证：空字节码被拒绝
    assertThrows(IllegalArgumentException.class,
      () -> generator.generate(new byte[0], Bar.class.getName(), Bar.class.getName() + "$$SIDEV1"));
  }

  /**
   * 验证版本类名按 HotswapNames.VERSION_PREFIX 规则生成。
   */
  @Test
  @DisplayName("按版本前缀生成版本类名")
  void buildsVersionName() {
    // 验证：版本类名等于原类名加 $$SIDEV 前缀和版本号
    assertEquals("com.demo.Bar$$SIDEV1", AsmVersionClassGenerator.versionName("com.demo.Bar", 1));
    // 验证：版本号 0 也按规则拼接
    assertEquals("com.demo.Bar$$SIDEV0", AsmVersionClassGenerator.versionName("com.demo.Bar", 0));
    // 验证：负数版本号被拒绝
    assertThrows(IllegalArgumentException.class,
      () -> AsmVersionClassGenerator.versionName("com.demo.Bar", -1));
  }

  /**
   * 用被测生成器生成版本类字节码。
   *
   * @return 版本类字节码
   */
  private static byte[] generate() {
    try {
      return new AsmVersionClassGenerator().generate(originalBytes, Bar.class.getName(),
        VERSION_INTERNAL.replace('/', '.'));
    }
    catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  /**
   * 读取指定类的字节码资源。
   *
   * @param type 目标类
   * @return 类字节码
   * @throws IOException 资源读取失败
   */
  static byte[] readClassBytes(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("找不到类字节码资源: " + resource);
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  /**
   * 用记录型 visitor 解析类字节码。
   *
   * @param bytes 类字节码
   * @return 记录结果
   */
  private static Recording record(byte[] bytes) {
    Recording recording = new Recording();
    new ClassReader(bytes).accept(recording, 0);
    return recording;
  }

  /**
   * 记录字段、方法、注解、类型常量、栈帧和内部类的 visitor。
   */
  private static final class Recording extends ClassVisitor {

    private String className;

    private String superName;

    private int access;

    private final List<String> interfaces = new ArrayList<String>();

    private final Map<String, Member> fields = new LinkedHashMap<String, Member>();

    private final Map<String, Member> methods = new LinkedHashMap<String, Member>();

    private final Map<String, Type> annotationTypes = new LinkedHashMap<String, Type>();

    private final Set<String> instanceOfTypes = new LinkedHashSet<String>();

    private final Set<String> frameTypes = new LinkedHashSet<String>();

    private final Set<String> innerClasses = new LinkedHashSet<String>();

    /**
     * 构造记录型 visitor。
     */
    private Recording() {
      super(Opcodes.ASM9);
    }

    /**
     * 记录类头信息。
     *
     * @param version 类文件版本
     * @param access 访问标志
     * @param name 类内部名
     * @param signature 泛型签名
     * @param superName 父类内部名
     * @param interfaces 接口列表
     */
    @Override
    public void visit(int version, int access, String name, String signature, String superName,
                      String[] interfaces) {
      this.className = name;
      this.access = access;
      this.superName = superName;
      if (interfaces != null) {
        for (String item : interfaces) {
          this.interfaces.add(item);
        }
      }
    }

    /**
     * 记录类注解以及注解中的 class 常量。
     *
     * @param descriptor 注解描述符
     * @param visible 是否运行时可见
     * @return 注解 visitor
     */
    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      return new AnnotationVisitor(Opcodes.ASM9) {
        /**
         * 记录注解中名为 value 的 class 常量。
         *
         * @param name 属性名
         * @param value 属性值
         */
        @Override
        public void visit(String name, Object value) {
          if (value instanceof Type) {
            annotationTypes.put(descriptor, (Type) value);
          }
        }
      };
    }

    /**
     * 记录字段。
     *
     * @param access 访问标志
     * @param name 字段名
     * @param descriptor 字段描述符
     * @param signature 泛型签名
     * @param value 常量值
     * @return 字段 visitor
     */
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                                   Object value) {
      fields.put(name, new Member(access, descriptor, signature));
      return null;
    }

    /**
     * 记录方法，并解析方法字节码中的类型常量与栈帧类型。
     *
     * @param access 访问标志
     * @param name 方法名
     * @param descriptor 方法描述符
     * @param signature 泛型签名
     * @param exceptions 异常列表
     * @return 方法 visitor
     */
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                     String[] exceptions) {
      methods.put(name, new Member(access, descriptor, signature));
      return new MethodVisitor(Opcodes.ASM9) {
        /**
         * 记录 instanceof 等指令使用的类型常量。
         *
         * @param opcode 指令
         * @param type 类型内部名
         */
        @Override
        public void visitTypeInsn(int opcode, String type) {
          if (opcode == Opcodes.INSTANCEOF) {
            instanceOfTypes.add(type);
          }
        }

        /**
         * 记录栈帧中的局部变量与操作数栈类型。
         *
         * @param type 栈帧类型
         * @param numLocal 局部变量数量
         * @param local 局部变量类型
         * @param numStack 操作数栈数量
         * @param stack 操作数栈类型
         */
        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
          collectFrameTypes(local);
          collectFrameTypes(stack);
        }
      };
    }

    /**
     * 记录内部类条目。
     *
     * @param name 内部类内部名
     * @param outerName 外部类内部名
     * @param innerName 内部类简单名
     * @param access 访问标志
     */
    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      innerClasses.add(name);
    }

    /**
     * 收集栈帧数组中的字符串类型。
     *
     * @param types 栈帧类型数组
     */
    private void collectFrameTypes(Object[] types) {
      if (types == null) {
        return;
      }
      for (Object type : types) {
        if (type instanceof String) {
          frameTypes.add((String) type);
        }
      }
    }
  }

  /**
   * 字段或方法的记录项。
   */
  private static final class Member {

    private final int access;

    private final String descriptor;

    private final String signature;

    /**
     * 构造记录项。
     *
     * @param access 访问标志
     * @param descriptor 描述符
     * @param signature 泛型签名
     */
    private Member(int access, String descriptor, String signature) {
      this.access = access;
      this.descriptor = descriptor;
      this.signature = signature;
    }
  }
}
