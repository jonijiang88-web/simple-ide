package io.simpleide.hotswap.facade;

/**
 * 测试用「版本类」对象：与夹具 facade 方法签名一致，返回 v2 结果。
 */
public final class FacadeSampleVersion {

  private int noopCount;

  /**
   * 返回 v2 字符串。
   *
   * @return 固定字符串
   */
  public String hello() {
    return "v2";
  }

  /**
   * 两数相加。
   *
   * @param left 左操作数
   * @param right 右操作数
   * @return 和
   */
  public int add(int left, int right) {
    return left + right;
  }

  /**
   * long 参数加十。
   *
   * @param value 入参
   * @return 入参加十
   */
  public long widen(long value) {
    return value + 10L;
  }

  /**
   * double 参数减半。
   *
   * @param value 入参
   * @return 入参一半
   */
  public double third(double value) {
    return value / 2.0D;
  }

  /**
   * float 参数翻倍。
   *
   * @param value 入参
   * @return 入参两倍
   */
  public float half(float value) {
    return value * 2.0F;
  }

  /**
   * 布尔取反。
   *
   * @param value 入参
   * @return 取反结果
   */
  public boolean flag(boolean value) {
    return !value;
  }

  /**
   * byte 参数加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public byte byteValue(byte value) {
    return (byte) (value + 1);
  }

  /**
   * char 参数加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public char charValue(char value) {
    return (char) (value + 1);
  }

  /**
   * short 参数加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public short shortValue(short value) {
    return (short) (value + 1);
  }

  /**
   * 记录一次调用。
   */
  public void noop() {
    noopCount++;
  }

  /**
   * 返回当前 noop 调用次数。
   *
   * @return 调用次数
   */
  public int noopCount() {
    return noopCount;
  }

  /**
   * int 重载。
   *
   * @param value 入参
   * @return v2 结果
   */
  public String pick(int value) {
    return "v2-int:" + value;
  }

  /**
   * String 重载。
   *
   * @param value 入参
   * @return v2 结果
   */
  public String pick(String value) {
    return "v2-string:" + value;
  }

  /**
   * 数组长度求和。
   *
   * @param values 数组
   * @return 长度两倍
   */
  public int arraySum(int[] values) {
    return values.length * 2;
  }

  /**
   * 私有方法对应实现。
   *
   * @return v2 结果
   */
  public String secret() {
    return "v2-secret";
  }
}
