package io.simpleide.hotswap.api;

/**
 * Names shared by instrumentation, runtime and version classes.
 */
public final class HotswapNames {

  /** Field added to every facade class. */
  public static final String DELEGATE_FIELD = "__sid_delegate";

  /** Prefix appended to the original class name for version classes. */
  public static final String VERSION_PREFIX = "$$SIDEV";

  /** Runtime class called from generated facade methods. */
  public static final String RUNTIME_CLASS = "io.simpleide.hotswap.runtime.HotswapRuntime";

  /** Runtime dispatch method. */
  public static final String INVOKE_METHOD = "invoke";

  /** Runtime dispatch descriptor: (Object, String, String, Object[]) -> Object. */
  public static final String INVOKE_DESCRIPTOR =
      "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";

  private HotswapNames() {
  }
}
