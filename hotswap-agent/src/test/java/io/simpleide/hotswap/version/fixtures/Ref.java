package io.simpleide.hotswap.version.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 注解中的 class 常量也会引用原类自身。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Ref {

  /**
   * 被标注的类型。
   *
   * @return 类型
   */
  Class<?> value();
}
