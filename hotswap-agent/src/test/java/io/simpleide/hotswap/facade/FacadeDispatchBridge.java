package io.simpleide.hotswap.facade;

/**
 * 测试用转发桥接：替身 {@code HotswapRuntime} 把调用转到这里，再由测试决定如何分发。
 */
public final class FacadeDispatchBridge {

  /**
   * 分发处理器。
   */
  public interface Handler {

    /**
     * 处理一次转发调用。
     *
     * @param facade facade 实例
     * @param methodName 方法名
     * @param descriptor JVM 描述符
     * @param args 装箱参数
     * @return 装箱返回值
     * @throws Throwable 分发失败时抛出
     */
    Object invoke(Object facade, String methodName, String descriptor, Object[] args) throws Throwable;
  }

  private static volatile Handler handler;

  private FacadeDispatchBridge() {
  }

  /**
   * 设置当前处理器。
   *
   * @param newHandler 处理器
   */
  public static void setHandler(Handler newHandler) {
    handler = newHandler;
  }

  /**
   * 清空当前处理器。
   */
  public static void clear() {
    handler = null;
  }

  /**
   * 转发一次调用到当前处理器。
   *
   * @param facade facade 实例
   * @param methodName 方法名
   * @param descriptor JVM 描述符
   * @param args 装箱参数
   * @return 装箱返回值
   * @throws Throwable 没有处理器或处理器失败时抛出
   */
  public static Object invoke(Object facade, String methodName, String descriptor, Object[] args)
      throws Throwable {
    Handler current = handler;
    if (current == null) {
      throw new IllegalStateException("no dispatch handler installed");
    }
    return current.invoke(facade, methodName, descriptor, args);
  }
}
