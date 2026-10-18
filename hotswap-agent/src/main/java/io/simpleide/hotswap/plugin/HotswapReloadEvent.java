package io.simpleide.hotswap.plugin;

import io.simpleide.hotswap.api.ChangeKind;

/**
 * 一次类热替换完成事件。
 */
public final class HotswapReloadEvent {

  private final String className;
  private final Class<?> facadeClass;
  private final Class<?> versionClass;
  private final ChangeKind changeKind;

  /**
   * 创建替换事件。
   *
   * @param className 点分类名
   * @param facadeClass facade 类
   * @param versionClass 当前版本类；未版本化时为 null
   * @param changeKind 变更类型
   */
  public HotswapReloadEvent(String className, Class<?> facadeClass, Class<?> versionClass,
                            ChangeKind changeKind) {
    this.className = className;
    this.facadeClass = facadeClass;
    this.versionClass = versionClass;
    this.changeKind = changeKind;
  }

  /** 返回点分类名。 */
  public String className() {
    return className;
  }

  /** 返回 facade 类。 */
  public Class<?> facadeClass() {
    return facadeClass;
  }

  /** 返回当前版本类；未版本化时为 null。 */
  public Class<?> versionClass() {
    return versionClass;
  }

  /** 返回变更类型。 */
  public ChangeKind changeKind() {
    return changeKind;
  }
}
