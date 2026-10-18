package io.simpleide;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code reload --class} 的类名规范化与变更过滤。
 */
public final class HotswapReloadTest {

  /** 全限定名应转换为相对 class 路径。 */
  @Test
  public void convertsDottedClassNameToRelativePath() {
    // 验证：com.demo.Bar 转换为 com/demo/Bar.class
    assertEquals("com/demo/Bar.class", SimpleIde.normalizeHotswapClassPath("com.demo.Bar"));
  }

  /** 斜杠写法和 .class 后缀应被兼容。 */
  @Test
  public void acceptsSlashFormAndClassSuffix() {
    // 验证：com/demo/Bar 保持相对路径
    assertEquals("com/demo/Bar.class", SimpleIde.normalizeHotswapClassPath("com/demo/Bar"));
    // 验证：com/demo/Bar.class 去掉后缀后再补回
    assertEquals("com/demo/Bar.class", SimpleIde.normalizeHotswapClassPath("com/demo/Bar.class"));
    // 验证：带空白和反斜杠的输入也被规范化
    assertEquals("com/demo/Bar.class", SimpleIde.normalizeHotswapClassPath("  com\\demo\\Bar  "));
  }

  /** 只保留匹配的变更 class。 */
  @Test
  public void filtersOnlyMatchedChangedClasses() {
    List<String> changed = Arrays.asList(
      "com/demo/Bar.class", "com/demo/Bar$Inner.class", "com/demo/Other.class");

    List<String> matched = SimpleIde.filterHotswapClasses(changed,
      Arrays.asList("com.demo.Bar", "com/demo/Other"));

    // 验证：只提交匹配的 class，未指定类不进入请求
    assertEquals(Arrays.asList("com/demo/Bar.class", "com/demo/Other.class"), matched);
  }

  /** 没有匹配时返回空列表，供调用方输出 CLASS_NOT_CHANGED。 */
  @Test
  public void returnsEmptyWhenNothingMatched() {
    List<String> matched = SimpleIde.filterHotswapClasses(
      Arrays.asList("com/demo/Bar.class"), Arrays.asList("com.demo.Missing"));

    // 验证：没有匹配的变更时结果为空
    assertTrue(matched.isEmpty());
  }

  /** 未指定 --class 时保持原有行为，返回全部变更。 */
  @Test
  public void keepsAllChangesWithoutClassFilter() {
    List<String> changed = Arrays.asList("com/demo/Bar.class", "com/demo/Other.class");

    List<String> matched = SimpleIde.filterHotswapClasses(changed, new ArrayList<String>());

    // 验证：未指定 --class 时返回全部变更 class
    assertEquals(changed, matched);
  }

  /** 空类名不应匹配任何 class。 */
  @Test
  public void emptyClassNameMatchesNothing() {
    List<String> matched = SimpleIde.filterHotswapClasses(
      Arrays.asList("com/demo/Bar.class"), Arrays.asList("   "));

    // 验证：空白类名被忽略，不产生错误匹配
    assertTrue(matched.isEmpty());
  }
}
