package io.simpleide.hotswap.state.fixtures;

/**
 * 静态字段访问改写的测试目标。
 */
public class StaticAccessFixture {

  /** 可共享的 int 静态字段。 */
  public static int count = 5;

  /** 可共享的 long 静态字段。 */
  public static long total = 9L;

  /** 可共享的引用静态字段。 */
  public static String label = "init";

  /** final 静态字段，不应被改写。 */
  public static final int CONSTANT = 42;

  /**
   * 读取 int 静态字段。
   *
   * @return count
   */
  public static int readCount() {
    return count;
  }

  /**
   * 写入 int 静态字段。
   *
   * @param value 新值
   */
  public static void writeCount(int value) {
    count = value;
  }

  /**
   * 读取 long 静态字段。
   *
   * @return total
   */
  public static long readTotal() {
    return total;
  }

  /**
   * 写入 long 静态字段。
   *
   * @param value 新值
   */
  public static void writeTotal(long value) {
    total = value;
  }

  /**
   * 读取引用静态字段。
   *
   * @return label
   */
  public static String readLabel() {
    return label;
  }

  /**
   * 写入引用静态字段。
   *
   * @param value 新值，允许为 null
   */
  public static void writeLabel(String value) {
    label = value;
  }

  /**
   * 读取 final 静态字段。
   *
   * @return CONSTANT
   */
  public static int readConstant() {
    return CONSTANT;
  }
}
