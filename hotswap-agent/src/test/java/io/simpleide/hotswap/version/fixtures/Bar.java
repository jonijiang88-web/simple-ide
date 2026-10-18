package io.simpleide.hotswap.version.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本类生成测试用的原类，覆盖各种自引用形态。
 */
@Ref(Bar.class)
public class Bar {

  /** 静态字段，用于确认版本类保留静态初始化。 */
  public static int counter = 1;

  /** 基本类型字段，描述符不应变化。 */
  public int x;

  /** 其他类型字段，描述符不应变化。 */
  public String name;

  /** 自引用字段。 */
  public Bar next;

  /** 自引用数组字段。 */
  public Bar[] children;

  /** 泛型签名中的自引用。 */
  public List<Bar> related = new ArrayList<Bar>();

  /** 私有字段，用于确认访问标志与字段完整保留。 */
  private int secret;

  /**
   * 构造器。
   *
   * @param x 初始值
   */
  public Bar(int x) {
    this.x = x;
    this.secret = x * 2;
  }

  /**
   * 普通方法。
   *
   * @return 固定前缀加字段值
   */
  public String hello() {
    return "hello-" + x + "-" + counter;
  }

  /**
   * 返回自引用类型的方法。
   *
   * @return 新实例
   */
  public Bar copy() {
    return new Bar(x);
  }

  /**
   * 返回自引用数组的方法。
   *
   * @return 包含自身的数组
   */
  public Bar[] array() {
    return new Bar[] {this};
  }

  /**
   * 参数为自引用类型的方法。
   *
   * @param other 另一个实例
   */
  public void take(Bar other) {
    this.next = other;
  }

  /**
   * 泛型签名含自引用边界的方法。
   *
   * @param value 输入值
   * @param <T> 自引用上界类型
   * @return 原值
   */
  public <T extends Bar> T pick(T value) {
    return value;
  }

  /**
   * instanceof 自引用类型的方法。
   *
   * @param value 待判断对象
   * @return 是否为 Bar
   */
  public boolean isBar(Object value) {
    return value instanceof Bar;
  }

  /**
   * 三元表达式会在栈帧中留下自引用类型。
   *
   * @param flag 是否创建实例
   * @return 新实例或 null
   */
  public Bar choose(boolean flag) {
    return flag ? new Bar(x) : null;
  }

  /**
   * 读取私有字段。
   *
   * @return 私有字段值
   */
  public int secret() {
    return secret;
  }

  /**
   * 内部类，用于确认 InnerClasses 属性保留。
   */
  public static class Inner {
  }
}
