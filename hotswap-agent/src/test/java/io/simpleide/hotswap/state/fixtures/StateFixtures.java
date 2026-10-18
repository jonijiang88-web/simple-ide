package io.simpleide.hotswap.state.fixtures;

import java.util.List;

/**
 * 状态迁移测试用的类版本 fixture。
 *
 * <p>每个嵌套类模拟同一个业务类在两次编译中的不同版本。
 */
public final class StateFixtures {

  /**
   * 工具类构造器。
   */
  private StateFixtures() {
  }

  /** 旧版本模型，包含后一版本删除的字段。 */
  public static class ModelV1 {

    /** 普通 int 字段。 */
    public int count;

    /** 引用字段。 */
    public String name;

    /** 需要保持同一引用的集合字段。 */
    public List<String> items;

    /** final 字段。 */
    public final int id;

    /** 新版本中已删除的字段。 */
    public int removed;

    /** 静态字段，不参与实例迁移。 */
    public static int shared;

    /**
     * 创建旧版本模型。
     *
     * @param id final 字段初始值
     */
    public ModelV1(int id) {
      this.id = id;
    }
  }

  /** 新版本模型，新增字段保持默认值。 */
  public static class ModelV2 {

    /** 普通 int 字段。 */
    public int count;

    /** 引用字段。 */
    public String name;

    /** 需要保持同一引用的集合字段。 */
    public List<String> items;

    /** final 字段。 */
    public final int id;

    /** 新增 int 字段。 */
    public int added;

    /** 新增引用字段。 */
    public String addedReference;

    /** 静态字段，不参与实例迁移。 */
    public static int shared;

    /**
     * 创建新版本模型。
     *
     * @param id final 字段初始值
     */
    public ModelV2(int id) {
      this.id = id;
    }
  }

  /** 旧版本父类。 */
  public static class BaseV1 {

    /** 父类普通字段。 */
    public int base;

    /** 父类引用字段。 */
    public String baseName;
  }

  /** 旧版本子类。 */
  public static class SubV1 extends BaseV1 {

    /** 子类普通字段。 */
    public long sub;
  }

  /** 新版本父类。 */
  public static class BaseV2 {

    /** 父类普通字段。 */
    public int base;

    /** 父类引用字段。 */
    public String baseName;

    /** 父类新增字段。 */
    public int baseAdded;
  }

  /** 新版本子类。 */
  public static class SubV2 extends BaseV2 {

    /** 子类普通字段。 */
    public long sub;

    /** 子类新增字段。 */
    public int subAdded;
  }

  /** 字段同名但类型不同的旧版本。 */
  public static class MismatchV1 {

    /** 类型一致的字段，用于验证失败时不会被部分复制。 */
    public int prefix;

    /** 将被改成 String 的字段。 */
    public int value;
  }

  /** 字段同名但类型不同的新版本。 */
  public static class MismatchV2 {

    /** 类型一致的字段，用于验证失败时不会被部分复制。 */
    public int prefix;

    /** 类型与旧版本不一致的字段。 */
    public String value;
  }

  /** 静态字段父类。 */
  public static class StaticParent {

    /** 父类可变静态字段。 */
    public static int parentCount = 11;

    /** 父类 final 静态字段。 */
    public static final String PARENT_CONSTANT = "parent-constant";
  }

  /** 静态字段子类。 */
  public static class StaticChild extends StaticParent {

    /** int 静态字段。 */
    public static int count = 7;

    /** long 静态字段。 */
    public static long total = 9L;

    /** boolean 静态字段。 */
    public static boolean enabled = true;

    /** 引用静态字段。 */
    public static String label = "v1";

    /** 初始值为 null 的静态字段。 */
    public static Object nullable;

    /** final 静态字段，不参与共享。 */
    public static final int CONSTANT = 5;
  }
}
