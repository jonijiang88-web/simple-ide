package io.simpleide.hotswap.plugin;

import io.simpleide.hotswap.api.ChangeKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证插件批量分发和异常隔离。
 */
class PluginManagerTest {

  /** 批量模式下事件应等 endBatch 后才分发。 */
  @Test
  void batchDispatchesOnlyAtEnd() {
    PluginManager manager = new PluginManager();
    List<String> received = new ArrayList<String>();
    manager.register(event -> received.add(event.className()));

    manager.beginBatch();
    manager.fire(event("com.demo.A"));
    manager.fire(event("com.demo.B"));
    // 验证：批次结束前插件未收到事件
    assertEquals(0, received.size());

    manager.endBatch();
    // 验证：批次结束后按顺序收到两个事件
    assertEquals(2, received.size());
    // 验证：事件顺序保持提交顺序
    assertEquals("com.demo.A", received.get(0));
    assertEquals("com.demo.B", received.get(1));
  }

  /** 单个插件抛异常不能阻断后续插件。 */
  @Test
  void pluginFailureIsIsolated() {
    PluginManager manager = new PluginManager();
    List<String> received = new ArrayList<String>();
    manager.register(event -> {
      throw new IllegalStateException("boom");
    });
    manager.register(event -> received.add(event.className()));

    manager.fire(event("com.demo.C"));
    // 验证：失败插件之后注册的插件仍然收到事件
    assertEquals(1, received.size());
    // 验证：事件内容未被异常插件破坏
    assertEquals("com.demo.C", received.get(0));
  }

  /**
   * 构造测试事件。
   *
   * @param className 类名
   * @return 事件
   */
  private static HotswapReloadEvent event(String className) {
    return new HotswapReloadEvent(className, Object.class, null, ChangeKind.METHOD_BODY);
  }
}
