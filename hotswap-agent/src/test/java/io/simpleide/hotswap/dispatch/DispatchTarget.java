package io.simpleide.hotswap.dispatch;

import java.io.IOException;

/**
 * 分发器测试夹具：覆盖基本类型、引用类型、数组、重载、私有方法与异常。
 */
public class DispatchTarget extends DispatchBase {

  private int counter;

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
   * 计数器加一。
   */
  public void bump() {
    counter++;
  }

  /**
   * 读取计数器。
   *
   * @return 当前计数
   */
  public int counter() {
    return counter;
  }

  /**
   * long 值加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public long widen(long value) {
    return value + 1L;
  }

  /**
   * double 值减半。
   *
   * @param value 入参
   * @return 入参一半
   */
  public double half(double value) {
    return value / 2.0D;
  }

  /**
   * float 值翻倍。
   *
   * @param value 入参
   * @return 入参两倍
   */
  public float twice(float value) {
    return value * 2.0F;
  }

  /**
   * 布尔取反。
   *
   * @param value 入参
   * @return 取反结果
   */
  public boolean negate(boolean value) {
    return !value;
  }

  /**
   * char 值加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public char nextChar(char value) {
    return (char) (value + 1);
  }

  /**
   * byte 值加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public byte nextByte(byte value) {
    return (byte) (value + 1);
  }

  /**
   * short 值加一。
   *
   * @param value 入参
   * @return 入参加一
   */
  public short nextShort(short value) {
    return (short) (value + 1);
  }

  /**
   * 多参数拼接。
   *
   * @param prefix 前缀
   * @param suffix 后缀
   * @param number 数字
   * @return 拼接结果
   */
  public String concat(String prefix, String suffix, int number) {
    return prefix + suffix + number;
  }

  /**
   * 原样返回引用参数，null 转为固定字符串。
   *
   * @param value 入参
   * @return 入参或固定字符串
   */
  public String nullEcho(String value) {
    return value == null ? "null" : value;
  }

  /**
   * 数组求和。
   *
   * @param values 数组
   * @return 元素之和
   */
  public int sum(int[] values) {
    int total = 0;
    for (int value : values) {
      total += value;
    }
    return total;
  }

  /**
   * 数组每个元素加一。
   *
   * @param values 数组
   * @return 新数组
   */
  public int[] increment(int[] values) {
    int[] result = new int[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = values[index] + 1;
    }
    return result;
  }

  /**
   * int 重载。
   *
   * @param value 入参
   * @return 标记结果
   */
  public String pick(int value) {
    return "int:" + value;
  }

  /**
   * String 重载。
   *
   * @param value 入参
   * @return 标记结果
   */
  public String pick(String value) {
    return "string:" + value;
  }

  /**
   * 抛出非受检异常。
   */
  public void failRuntime() {
    throw new IllegalStateException("boom");
  }

  /**
   * 抛出受检异常。
   *
   * @throws IOException 固定异常
   */
  public void failChecked() throws IOException {
    throw new IOException("io-boom");
  }

  /**
   * 私有方法，用于验证访问权限处理。
   *
   * @return 固定字符串
   */
  private String hidden() {
    return "hidden";
  }
}
