package io.simpleide.hotswap.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.simpleide.hotswap.state.fixtures.StateFixtures.StaticChild;
import io.simpleide.hotswap.state.fixtures.StateFixtures.StaticParent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SharedStaticState} 的行为测试。
 */
class SharedStaticStateTest {

  /** 被测静态字段存储，每个测试使用独立实例。 */
  private SharedStaticState state;

  /**
   * 每个测试前重置 fixture 的静态字段并创建独立存储。
   */
  @BeforeEach
  void setUp() {
    StaticChild.count = 7;
    StaticChild.total = 9L;
    StaticChild.enabled = true;
    StaticChild.label = "v1";
    StaticChild.nullable = null;
    StaticParent.parentCount = 11;
    state = new SharedStaticState();
  }

  /**
   * 验证 capture 能读取当前类及父类的非 final 静态字段。
   *
   * @throws Exception 捕获失败时测试失败
   */
  @Test
  void capturesNonFinalStaticFields() throws Exception {
    state.capture(StaticChild.class);

    // 验证：int 静态字段被捕获为 Integer
    assertEquals(Integer.valueOf(7), state.get(StaticChild.class.getName(), "count"));
    // 验证：long 静态字段被捕获为 Long
    assertEquals(Long.valueOf(9L), state.get(StaticChild.class.getName(), "total"));
    // 验证：boolean 静态字段被捕获为 Boolean
    assertEquals(Boolean.TRUE, state.get(StaticChild.class.getName(), "enabled"));
    // 验证：引用静态字段被捕获
    assertSame(StaticChild.label, state.get(StaticChild.class.getName(), "label"));
    // 验证：值为 null 的静态字段也会被捕获
    assertNull(state.get(StaticChild.class.getName(), "nullable"));
    // 验证：父类静态字段使用声明类名作为 key
    assertEquals(Integer.valueOf(11), state.get(StaticParent.class.getName(), "parentCount"));
  }

  /**
   * 验证 capture 不会捕获 final 静态字段。
   *
   * @throws Exception 捕获失败时测试失败
   */
  @Test
  void doesNotCaptureFinalStaticFields() throws Exception {
    state.capture(StaticChild.class);

    // 验证：子类 final 静态字段没有进入共享存储
    assertThrows(IllegalStateException.class,
      () -> state.get(StaticChild.class.getName(), "CONSTANT"));
    // 验证：父类 final 静态字段没有进入共享存储
    assertThrows(IllegalStateException.class,
      () -> state.get(StaticParent.class.getName(), "PARENT_CONSTANT"));
  }

  /**
   * 验证 set/get 对基础类型使用装箱值读写，并支持 null 引用。
   */
  @Test
  void readsAndWritesBoxedPrimitives() {
    String className = StaticChild.class.getName();

    state.set(className, "count", Integer.valueOf(100));
    state.set(className, "total", Long.valueOf(200L));
    state.set(className, "enabled", Boolean.FALSE);
    state.set(className, "label", null);

    Object count = state.get(className, "count");
    Object total = state.get(className, "total");
    Object enabled = state.get(className, "enabled");

    // 验证：int 字段读取结果为 Integer
    assertTrue(count instanceof Integer);
    // 验证：int 字段拆箱后为写入值
    assertEquals(100, ((Integer) count).intValue());
    // 验证：long 字段读取结果为 Long
    assertTrue(total instanceof Long);
    // 验证：long 字段拆箱后为写入值
    assertEquals(200L, ((Long) total).longValue());
    // 验证：boolean 字段读取结果为 Boolean
    assertTrue(enabled instanceof Boolean);
    // 验证：boolean 字段拆箱后为写入值
    assertEquals(false, ((Boolean) enabled).booleanValue());
    // 验证：引用字段允许写入 null 并读回 null
    assertNull(state.get(className, "label"));
  }

  /**
   * 验证重复 capture 不会覆盖运行期已经修改的共享值。
   *
   * @throws Exception 捕获失败时测试失败
   */
  @Test
  void recaptureKeepsRuntimeValue() throws Exception {
    state.capture(StaticChild.class);
    state.set(StaticChild.class.getName(), "count", Integer.valueOf(99));

    state.capture(StaticChild.class);

    // 验证：第二次 capture 后仍是运行期修改的值
    assertEquals(Integer.valueOf(99), state.get(StaticChild.class.getName(), "count"));
  }

  /**
   * 验证未知静态字段读取时给出明确异常。
   */
  @Test
  void rejectsUnknownStaticField() {
    IllegalStateException error = assertThrows(IllegalStateException.class,
      () -> state.get("com.demo.Missing", "value"));

    // 验证：异常信息包含缺失的类名和字段名
    assertTrue(error.getMessage().contains("com.demo.Missing.value"), error.getMessage());
    // 验证：异常信息提示先调用 capture
    assertTrue(error.getMessage().contains("capture"), error.getMessage());
  }

  /**
   * 验证类名或字段名为空时参数校验生效。
   */
  @Test
  void rejectsEmptyKeys() {
    // 验证：空类名被拒绝
    assertThrows(IllegalArgumentException.class, () -> state.get("", "value"));
    // 验证：空字段名被拒绝
    assertThrows(IllegalArgumentException.class, () -> state.set("com.demo.Bar", "", "x"));
  }

  /**
   * 验证多线程并发读写不会丢失 key。
   *
   * @throws Exception 线程执行失败时测试失败
   */
  @Test
  void supportsConcurrentAccess() throws Exception {
    final int threadCount = 8;
    final int valuesPerThread = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<Future<?>>();
    try {
      for (int thread = 0; thread < threadCount; thread++) {
        final int threadId = thread;
        futures.add(pool.submit(new Runnable() {
          /** 每个线程只写自己的 key，验证 Map 并发读写不互相丢失。 */
          @Override
          public void run() {
            for (int index = 0; index < valuesPerThread; index++) {
              String className = "Concurrent" + threadId;
              String fieldName = "value" + index;
              state.set(className, fieldName, Integer.valueOf(threadId));
              // 验证：当前线程写入后能立即读回自己的值
              assertEquals(Integer.valueOf(threadId), state.get(className, fieldName));
            }
          }
        }));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    }
    finally {
      pool.shutdownNow();
    }

    for (int thread = 0; thread < threadCount; thread++) {
      for (int index = 0; index < valuesPerThread; index++) {
        // 验证：并发写入结束后每个线程的每个 key 都仍存在
        assertEquals(Integer.valueOf(thread), state.get("Concurrent" + thread, "value" + index));
      }
    }
  }
}
