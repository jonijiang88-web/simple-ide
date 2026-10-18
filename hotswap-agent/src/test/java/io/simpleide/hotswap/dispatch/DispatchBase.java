package io.simpleide.hotswap.dispatch;

/**
 * 分发器测试夹具的父类，用于验证沿类层次解析方法。
 */
public class DispatchBase {

  /**
   * 返回父类实现的固定字符串。
   *
   * @return 固定字符串
   */
  public String base() {
    return "base";
  }
}
