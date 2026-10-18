package io.simpleide.hotswap.version;

import io.simpleide.hotswap.api.HotswapNames;
import io.simpleide.hotswap.api.VersionClassGenerator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * 基于 ASM 的版本类生成器。
 *
 * <p>把已编译的原类字节码改名成版本类，并重写全部自引用：
 * 类名、this 类型（栈帧/局部变量类型）、字段与方法描述符、泛型签名、
 * 注解中的 class 常量、构造器 owner 与描述符、数组与内部类属性。
 * 其他类的引用保持不变。
 */
public final class AsmVersionClassGenerator implements VersionClassGenerator {

  /**
   * 计算版本类的点分名。
   *
   * @param originalName 原点分类名，例如 {@code com.demo.Bar}
   * @param versionNumber 版本号，从 1 开始
   * @return 版本类名，例如 {@code com.demo.Bar$$SIDEV1}
   */
  public static String versionName(String originalName, int versionNumber) {
    if (originalName == null || originalName.trim().isEmpty()) {
      throw new IllegalArgumentException("原类名不能为空");
    }
    if (versionNumber < 0) {
      throw new IllegalArgumentException("版本号不能为负数: " + versionNumber);
    }
    return originalName + HotswapNames.VERSION_PREFIX + versionNumber;
  }

  /**
   * 生成版本类字节码。
   *
   * @param originalBytes 新编译出的原类字节码
   * @param originalName 原点分类名
   * @param versionName 目标版本类点分名
   * @return 版本类字节码
   * @throws Exception 字节码无法解析或类名不匹配时抛出
   */
  @Override
  public byte[] generate(byte[] originalBytes, String originalName, String versionName) throws Exception {
    if (originalBytes == null || originalBytes.length == 0) {
      throw new IllegalArgumentException("原类字节码不能为空");
    }
    if (originalName == null || originalName.trim().isEmpty()) {
      throw new IllegalArgumentException("原类名不能为空");
    }
    if (versionName == null || versionName.trim().isEmpty()) {
      throw new IllegalArgumentException("版本类名不能为空");
    }
    String originalInternalName = toInternalName(originalName);
    String versionInternalName = toInternalName(versionName);
    if (originalInternalName.equals(versionInternalName)) {
      throw new IllegalArgumentException("版本类名不能与原类名相同: " + originalName);
    }
    ClassReader reader = new ClassReader(originalBytes);
    if (!reader.getClassName().equals(originalInternalName)) {
      throw new IllegalArgumentException("字节码中的类名 " + toDottedName(reader.getClassName())
        + " 与参数中的原类名 " + originalName + " 不一致");
    }
    ClassWriter writer = new ClassWriter(0);
    Remapper remapper = new SelfReferenceRemapper(originalInternalName, versionInternalName);
    reader.accept(new ClassRemapper(writer, remapper), 0);
    return writer.toByteArray();
  }

  /**
   * 把点分类名转换成 JVM 内部名。
   *
   * @param className 点分类名
   * @return 内部名
   */
  static String toInternalName(String className) {
    return className.replace('.', '/');
  }

  /**
   * 把 JVM 内部名转换回点分类名。
   *
   * @param internalName 内部名
   * @return 点分类名
   */
  static String toDottedName(String internalName) {
    return internalName.replace('/', '.');
  }

  /**
   * 只把原类自身映射到版本类的重映射器。
   *
   * <p>内部类（例如 {@code Bar$Inner}）不会被改名，因为本阶段只生成外层版本类，
   * 这些内部类仍然存在于原类名下。
   */
  private static final class SelfReferenceRemapper extends Remapper {

    private final String originalInternalName;

    private final String versionInternalName;

    /**
     * 构造重映射器。
     *
     * @param originalInternalName 原类内部名
     * @param versionInternalName 版本类内部名
     */
    private SelfReferenceRemapper(String originalInternalName, String versionInternalName) {
      this.originalInternalName = originalInternalName;
      this.versionInternalName = versionInternalName;
    }

    /**
     * 映射类型名：仅原类自身改名，其余类型原样返回。
     *
     * @param internalName 类型内部名
     * @return 映射后的内部名
     */
    @Override
    public String map(String internalName) {
      if (originalInternalName.equals(internalName)) {
        return versionInternalName;
      }
      return internalName;
    }

    /**
     * 映射 InnerClasses 属性中的内部类简单名，保证版本类自身的条目与新类名一致。
     *
     * @param name 内部类内部名
     * @param ownerName 外部类内部名
     * @param innerName 原内部类简单名
     * @return 映射后的简单名
     */
    @Override
    public String mapInnerClassName(String name, String ownerName, String innerName) {
      if (!originalInternalName.equals(name)) {
        return innerName;
      }
      String outerPrefix = ownerName + "$";
      if (versionInternalName.startsWith(outerPrefix)) {
        return versionInternalName.substring(outerPrefix.length());
      }
      return innerName;
    }
  }
}
