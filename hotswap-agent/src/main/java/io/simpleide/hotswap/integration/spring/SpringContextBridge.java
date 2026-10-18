package io.simpleide.hotswap.integration.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spring ApplicationContext 捕获桥。
 *
 * <p>桥类由 agent 类加载器加载，Spring Boot 启动类通过 transformer 注入的调用只传
 * {@link Object}，避免 agent 编译期依赖 Spring。
 */
public final class SpringContextBridge {

  private static final List<Object> CONTEXTS =
      Collections.synchronizedList(new ArrayList<Object>());

  private SpringContextBridge() {
  }

  /**
   * 记录一个已启动的 Spring ApplicationContext。
   *
   * @param context Spring context
   */
  public static void capture(Object context) {
    if (context == null) {
      return;
    }
    synchronized (CONTEXTS) {
      for (Object current : CONTEXTS) {
        if (current == context) {
          return;
        }
      }
      CONTEXTS.add(context);
    }
  }

  /**
   * 返回已捕获的 Spring context 快照。
   *
   * @return context 列表
   */
  public static List<Object> contexts() {
    synchronized (CONTEXTS) {
      return new ArrayList<Object>(CONTEXTS);
    }
  }
}
