package io.simpleide.hotswap.plugin;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * 插件可使用的运行时上下文。
 */
public final class HotswapPluginContext {

  private final Instrumentation instrumentation;

  /**
   * 创建插件上下文。
   *
   * @param instrumentation JVM instrumentation
   */
  public HotswapPluginContext(Instrumentation instrumentation) {
    if (instrumentation == null) {
      throw new IllegalArgumentException("instrumentation must not be null");
    }
    this.instrumentation = instrumentation;
  }

  /**
   * 返回 JVM instrumentation。
   *
   * @return instrumentation
   */
  public Instrumentation instrumentation() {
    return instrumentation;
  }

  /**
   * 注册加载期 transformer。
   *
   * @param transformer 类文件转换器
   */
  public void addTransformer(ClassFileTransformer transformer) {
    if (transformer != null) {
      instrumentation.addTransformer(transformer, false);
    }
  }

  /**
   * 返回应用线程上下文类加载器，缺失时回退到系统类加载器。
   *
   * @return 类加载器
   */
  public ClassLoader applicationClassLoader() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    return loader == null ? ClassLoader.getSystemClassLoader() : loader;
  }
}
