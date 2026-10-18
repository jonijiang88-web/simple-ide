package io.simpleide.hotswap.plugin;

/**
 * 热替换插件：在类版本切换后刷新框架元数据或实例状态。
 *
 * <p>插件是可选的；插件异常会被隔离，不会回滚已完成的类替换。
 */
public interface HotswapPlugin {

  /**
   * 初始化插件，允许注册加载期 transformer。
   *
   * @param context 插件上下文
   */
  default void initialize(HotswapPluginContext context) {
  }

  /**
   * 接收一次类替换完成事件。
   *
   * @param event 替换事件
   */
  void onReload(HotswapReloadEvent event);
}
