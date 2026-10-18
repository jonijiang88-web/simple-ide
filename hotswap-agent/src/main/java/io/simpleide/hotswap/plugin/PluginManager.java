package io.simpleide.hotswap.plugin;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 插件注册、批量缓冲与异常隔离。
 */
public final class PluginManager {

  private final List<HotswapPlugin> plugins = new ArrayList<HotswapPlugin>();
  private final List<HotswapReloadEvent> pending = new ArrayList<HotswapReloadEvent>();
  private final Set<ClassLoader> loadedLoaders =
      Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
  private Instrumentation instrumentation;
  private HotswapPluginContext pluginContext;
  private boolean batching;

  /**
   * 加载 ServiceLoader 插件并初始化。
   *
   * @param instrumentation JVM instrumentation
   */
  public synchronized void initialize(Instrumentation instrumentation) {
    if (instrumentation == null) {
      return;
    }
    this.instrumentation = instrumentation;
    HotswapPluginContext context = new HotswapPluginContext(instrumentation);
    this.pluginContext = context;
    for (HotswapPlugin plugin : ServiceLoader.load(HotswapPlugin.class,
        HotswapPlugin.class.getClassLoader())) {
      register(plugin, context);
    }
    ClassLoader applicationLoader = context.applicationClassLoader();
    if (applicationLoader != HotswapPlugin.class.getClassLoader()) {
      try {
        for (HotswapPlugin plugin : ServiceLoader.load(HotswapPlugin.class, applicationLoader)) {
          if (!contains(plugin.getClass().getName())) {
            register(plugin, context);
          }
        }
      }
      catch (Throwable ignored) {
        // 应用类加载器不可见插件时保持静默，agent 插件仍可工作
      }
    }
  }

  /**
   * 从指定类加载器加载插件。
   *
   * <p>Jackson 等可选适配器 jar 只放在应用 classpath 上，premain 阶段不可见；
   * 第一次 reload 事件使用 facade 的类加载器加载即可。
   *
   * @param loader 插件类加载器
   */
  private synchronized void loadFrom(ClassLoader loader) {
    if (loader == null || instrumentation == null || pluginContext == null
      || loader == HotswapPlugin.class.getClassLoader() || !loadedLoaders.add(loader)) {
      return;
    }
    try {
      for (HotswapPlugin plugin : ServiceLoader.load(HotswapPlugin.class, loader)) {
        register(plugin, pluginContext);
      }
    }
    catch (Throwable error) {
      System.err.println("[simple-ide-hotswap] cannot load plugins from " + loader + ": " + error);
    }
  }

  /**
   * 注册一个插件。
   *
   * @param plugin 插件
   */
  public synchronized void register(HotswapPlugin plugin) {
    if (plugin != null) {
      plugins.add(plugin);
    }
  }

  /**
   * 返回已注册插件数量。
   *
   * @return 插件数量
   */
  public synchronized int size() {
    return plugins.size();
  }

  /**
   * 开始批量替换，事件暂存到批次结束。
   */
  public synchronized void beginBatch() {
    if (!batching) {
      pending.clear();
      batching = true;
    }
  }

  /**
   * 结束批量替换并逐个分发事件。
   */
  public void endBatch() {
    List<HotswapReloadEvent> events;
    synchronized (this) {
      if (!batching) {
        return;
      }
      batching = false;
      events = new ArrayList<HotswapReloadEvent>(pending);
      pending.clear();
    }
    for (HotswapReloadEvent event : events) {
      fire(event);
    }
  }

  /**
   * 分发一次替换事件；批量模式下暂存。
   *
   * @param event 替换事件
   */
  public void fire(HotswapReloadEvent event) {
    if (event == null) {
      return;
    }
    loadFrom(event.facadeClass() == null ? null : event.facadeClass().getClassLoader());
    synchronized (this) {
      if (batching) {
        pending.add(event);
        return;
      }
    }
    dispatch(event);
  }

  /**
   * 向所有插件分发事件，单个插件失败不影响其他插件。
   *
   * @param event 替换事件
   */
  private void dispatch(HotswapReloadEvent event) {
    List<HotswapPlugin> snapshot;
    synchronized (this) {
      snapshot = new ArrayList<HotswapPlugin>(plugins);
    }
    for (HotswapPlugin plugin : snapshot) {
      try {
        plugin.onReload(event);
      }
      catch (Throwable error) {
        System.err.println("[simple-ide-hotswap] plugin " + plugin.getClass().getName()
          + " failed for " + event.className() + ": " + error);
      }
    }
  }

  /**
   * 初始化插件并隔离初始化异常。
   *
   * @param plugin 插件
   * @param context 插件上下文
   */
  private void register(HotswapPlugin plugin, HotswapPluginContext context) {
    if (plugin == null || contains(plugin.getClass().getName())) {
      return;
    }
    try {
      plugin.initialize(context);
      plugins.add(plugin);
    }
    catch (Throwable error) {
      System.err.println("[simple-ide-hotswap] cannot initialize plugin "
        + plugin.getClass().getName() + ": " + error);
    }
  }

  /**
   * 判断插件实现类是否已注册。
   *
   * @param className 插件类名
   * @return 已注册返回 true
   */
  private boolean contains(String className) {
    for (HotswapPlugin plugin : plugins) {
      if (plugin.getClass().getName().equals(className)) {
        return true;
      }
    }
    return false;
  }
}
