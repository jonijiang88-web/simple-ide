package io.simpleide.hotswap.dispatch;

/**
 * 分发器测试夹具：模拟后续版本新增的方法。
 */
public class DispatchTargetV2 extends DispatchBase {

  /**
   * 后续版本新增的方法。
   *
   * @return 固定字符串
   */
  public String extra() {
    return "extra";
  }
}
