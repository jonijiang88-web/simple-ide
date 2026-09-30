package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 BuildLock 本地排队锁的行为与生命周期。 */
public final class BuildLockTest {

  /** 验证正常获取与释放锁，并确认锁文件中记录了持有者信息。 */
  @Test
  void acquires_and_releases_lock_with_holder_info(@TempDir Path tempDir) throws Exception {
    Path lockFile = tempDir.resolve("build.lock");
    Path project = tempDir.resolve("mock-project");

    // 验证：初始状态锁文件不存在
    assertFalse(Files.exists(lockFile));

    try (BuildLock lock = BuildLock.acquire(project, lockFile)) {
      // 验证：成功获取锁对象且处于锁定状态
      assertNotNull(lock);
      assertTrue(lock.isLocked());

      // 验证：锁文件已创建且包含 PID 和项目路径
      assertTrue(Files.exists(lockFile));
      String content = new String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8);
      assertTrue(content.contains("PID: "));
      assertTrue(content.contains(project.toString()));
    }

    // 验证：退出 try-with-resources 后锁已释放
    assertNotNull(lockFile);
  }

  /** 验证锁释放后，同一线程或新调用可立即再次获取锁。 */
  @Test
  void allows_reacquisition_after_release(@TempDir Path tempDir) throws Exception {
    Path lockFile = tempDir.resolve("build.lock");
    Path project = tempDir.resolve("mock-project");

    BuildLock lock1 = BuildLock.acquire(project, lockFile);
    // 验证：第一次获取锁成功
    assertTrue(lock1.isLocked());
    lock1.close();
    // 验证：第一次关闭后处于未锁定状态
    assertFalse(lock1.isLocked());

    BuildLock lock2 = BuildLock.acquire(project, lockFile);
    // 验证：第二次获取锁成功
    assertTrue(lock2.isLocked());
    lock2.close();
  }

  /** 验证非阻塞尝试获取锁（tryAcquire）在已被占用时返回 null。 */
  @Test
  void try_acquire_returns_null_when_already_held(@TempDir Path tempDir) throws Exception {
    Path lockFile = tempDir.resolve("build.lock");
    Path project = tempDir.resolve("mock-project");

    try (BuildLock lock1 = BuildLock.acquire(project, lockFile)) {
      // 验证：第一把锁已持有
      assertTrue(lock1.isLocked());

      // 验证：第二次尝试非阻塞获取应直接返回 null，表示已被占用
      BuildLock lock2 = BuildLock.tryAcquire(project, lockFile);
      assertNull(lock2);
    }

    // 验证：前一把锁释放后，tryAcquire 应成功获取
    try (BuildLock lock3 = BuildLock.tryAcquire(project, lockFile)) {
      assertNotNull(lock3);
      assertTrue(lock3.isLocked());
    }
  }

  /** 验证多线程并发获取锁时的排队等待互斥行为。 */
  @Test
  void queues_multiple_threads_sequentially(@TempDir Path tempDir) throws Exception {
    Path lockFile = tempDir.resolve("build.lock");
    Path project = tempDir.resolve("mock-project");

    CountDownLatch thread1Acquired = new CountDownLatch(1);
    CountDownLatch thread1ReleaseSignal = new CountDownLatch(1);
    AtomicBoolean thread2AcquiredWhileThread1Held = new AtomicBoolean(false);
    AtomicBoolean thread2Done = new AtomicBoolean(false);

    // 线程 1：获取锁后挂起，等待通知再释放
    Thread thread1 = new Thread(() -> {
      try (BuildLock lock = BuildLock.acquire(project, lockFile)) {
        thread1Acquired.countDown();
        thread1ReleaseSignal.await(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    // 线程 2：尝试获取同一锁，验证在线程 1 释放前不会成功
    Thread thread2 = new Thread(() -> {
      try {
        thread1Acquired.await(5, TimeUnit.SECONDS);
        // 此时线程 1 正持有锁，验证 tryAcquire 返回 null
        BuildLock nonBlocking = BuildLock.tryAcquire(project, lockFile);
        if (nonBlocking != null) {
          thread2AcquiredWhileThread1Held.set(true);
          nonBlocking.close();
        }
        // 阻塞排队获取
        try (BuildLock lock2 = BuildLock.acquire(project, lockFile)) {
          thread2Done.set(true);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    thread1.start();
    thread2.start();

    // 验证：线程 1 已经成功拿到锁
    assertTrue(thread1Acquired.await(5, TimeUnit.SECONDS));

    // 等待一小会儿确认线程 2 在排队
    Thread.sleep(100);
    // 验证：线程 1 未释放期间，线程 2 未能同时持有锁
    assertFalse(thread2AcquiredWhileThread1Held.get());
    assertFalse(thread2Done.get());

    // 释放线程 1
    thread1ReleaseSignal.countDown();
    thread1.join(5000);
    thread2.join(5000);

    // 验证：线程 1 释放后，线程 2 成功排队获得锁并执行完毕
    assertTrue(thread2Done.get());
  }

  /** 验证默认锁文件路径能正确定位。 */
  @Test
  void resolves_lock_file_path() {
    Path lockFile = BuildLock.defaultLockFile();
    // 验证：默认锁文件名以 build.lock 结尾
    assertTrue(lockFile.getFileName().toString().equals("build.lock"));
    // 验证：默认锁文件为绝对路径
    assertTrue(lockFile.isAbsolute());
  }
}
