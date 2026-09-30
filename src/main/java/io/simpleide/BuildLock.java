package io.simpleide;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 本地构建排队锁，防止并发执行 build 耗尽系统资源。支持跨进程文件锁与同进程多线程排队。 */
public final class BuildLock implements Closeable {

  /** 进程内路径锁映射表，避免同进程重复锁定同一文件导致 OverlappingFileLockException。 */
  private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<String, ReentrantLock>();

  /** 锁文件路径。 */
  private final Path lockFile;
  /** 对应的进程内 JVM 锁。 */
  private final ReentrantLock jvmLock;
  /** 文件底层句柄。 */
  private final RandomAccessFile raf;
  /** 文件 NIO 通道。 */
  private final FileChannel channel;
  /** 操作系统级别文件锁。 */
  private final FileLock fileLock;
  /** 标识当前锁是否处于持有状态。 */
  private boolean locked;

  /** 私有构造器，由工厂方法完成锁定后实例化。 */
  private BuildLock(Path lockFile, ReentrantLock jvmLock, RandomAccessFile raf, FileChannel channel, FileLock fileLock) {
    this.lockFile = lockFile;
    this.jvmLock = jvmLock;
    this.raf = raf;
    this.channel = channel;
    this.fileLock = fileLock;
    this.locked = true;
  }

  /** 获取默认全局构建锁文件路径。 */
  public static Path defaultLockFile() {
    String fileEnv = System.getenv("SIMPLE_IDE_LOCK_FILE");
    if (fileEnv != null && !fileEnv.trim().isEmpty()) {
      return Paths.get(fileEnv.trim()).toAbsolutePath().normalize();
    }
    String fileProp = System.getProperty("simple.ide.lock.file");
    if (fileProp != null && !fileProp.trim().isEmpty()) {
      return Paths.get(fileProp.trim()).toAbsolutePath().normalize();
    }

    Path baseDir;
    String dirEnv = System.getenv("SIMPLE_IDE_LOCK_DIR");
    String dirProp = System.getProperty("simple.ide.lock.dir");
    if (dirEnv != null && !dirEnv.trim().isEmpty()) {
      baseDir = Paths.get(dirEnv.trim());
    } else if (dirProp != null && !dirProp.trim().isEmpty()) {
      baseDir = Paths.get(dirProp.trim());
    } else {
      String userHome = System.getProperty("user.home");
      if (userHome != null && !userHome.trim().isEmpty()) {
        baseDir = Paths.get(userHome, ".simple-ide", "locks");
      } else {
        baseDir = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "simple-ide", "locks");
      }
    }
    return baseDir.resolve("build.lock").toAbsolutePath().normalize();
  }

  /** 阻塞排队获取默认全局构建锁。 */
  public static BuildLock acquire(Path project) throws IOException {
    return acquire(project, defaultLockFile());
  }

  /** 阻塞排队获取指定路径的构建锁，若被占用则输出排队信息并在控制台等待。 */
  public static BuildLock acquire(Path project, Path lockFile) throws IOException {
    Path normalizedPath = lockFile.toAbsolutePath().normalize();
    ensureParentDir(normalizedPath);

    ReentrantLock jvmLock = getJvmLock(normalizedPath);
    if (jvmLock.isHeldByCurrentThread()) {
      throw new IllegalStateException("当前线程已持有该构建锁，不支持重入锁定");
    }

    RandomAccessFile raf = null;
    FileChannel channel = null;
    FileLock fileLock = null;

    boolean queuedMessagePrinted = false;

    // 先尝试非阻塞获取锁；如果失败则说明存在并发竞争，输出排队提示后阻塞等待
    if (jvmLock.tryLock()) {
      boolean acquiredImmediately = false;
      try {
        raf = new RandomAccessFile(normalizedPath.toFile(), "rw");
        channel = raf.getChannel();
        fileLock = channel.tryLock();
        if (fileLock != null) {
          acquiredImmediately = true;
        }
      } catch (java.nio.channels.OverlappingFileLockException ignored) {
      } catch (Exception ignored) {
      }

      if (acquiredImmediately) {
        writeHolderInfo(raf, project);
        return new BuildLock(normalizedPath, jvmLock, raf, channel, fileLock);
      }

      // 未能立即拿到文件锁，释放资源准备进入阻塞排队流程
      closeSilently(fileLock, channel, raf);
      jvmLock.unlock();
    }

    // 存在并发竞争，读取当前占用者信息并提示排队
    String holderInfo = readHolderInfo(normalizedPath);
    System.err.println("[simple-ide] 检测到已有 build 正在执行 (" + holderInfo + ")，正在排队等待获取本地构建锁...");
    queuedMessagePrinted = true;

    // 阻塞获取进程内 JVM 锁
    jvmLock.lock();
    try {
      raf = new RandomAccessFile(normalizedPath.toFile(), "rw");
      channel = raf.getChannel();
      fileLock = channel.lock();

      if (queuedMessagePrinted) {
        System.err.println("[simple-ide] 已获取本地构建锁，开始执行编译...");
      }

      writeHolderInfo(raf, project);
      return new BuildLock(normalizedPath, jvmLock, raf, channel, fileLock);
    } catch (Throwable t) {
      closeSilently(fileLock, channel, raf);
      if (jvmLock.isHeldByCurrentThread()) {
        jvmLock.unlock();
      }
      if (t instanceof IOException) {
        throw (IOException) t;
      }
      throw new IOException("获取构建本地锁失败", t);
    }
  }

  /** 非阻塞尝试获取指定路径构建锁，若已被占用则立即返回 null。 */
  public static BuildLock tryAcquire(Path project, Path lockFile) throws IOException {
    Path normalizedPath = lockFile.toAbsolutePath().normalize();
    ensureParentDir(normalizedPath);

    ReentrantLock jvmLock = getJvmLock(normalizedPath);
    if (jvmLock.isHeldByCurrentThread()) {
      return null;
    }
    if (!jvmLock.tryLock()) {
      return null;
    }

    RandomAccessFile raf = null;
    FileChannel channel = null;
    FileLock fileLock = null;
    try {
      raf = new RandomAccessFile(normalizedPath.toFile(), "rw");
      channel = raf.getChannel();
      fileLock = channel.tryLock();
      if (fileLock == null) {
        closeSilently(fileLock, channel, raf);
        jvmLock.unlock();
        return null;
      }

      writeHolderInfo(raf, project);
      return new BuildLock(normalizedPath, jvmLock, raf, channel, fileLock);
    } catch (java.nio.channels.OverlappingFileLockException e) {
      closeSilently(fileLock, channel, raf);
      if (jvmLock.isHeldByCurrentThread()) {
        jvmLock.unlock();
      }
      return null;
    } catch (Throwable t) {
      closeSilently(fileLock, channel, raf);
      if (jvmLock.isHeldByCurrentThread()) {
        jvmLock.unlock();
      }
      if (t instanceof IOException) {
        throw (IOException) t;
      }
      throw new IOException("尝试获取构建本地锁异常", t);
    }
  }

  /** 检查当前锁是否正处于持有状态。 */
  public boolean isLocked() {
    return locked && fileLock != null && fileLock.isValid();
  }

  /** 释放构建锁并清理句柄。 */
  @Override
  public void close() {
    if (!locked) {
      return;
    }
    locked = false;

    try {
      if (raf != null) {
        try {
          raf.setLength(0);
        } catch (Throwable ignored) {
        }
      }
      closeSilently(fileLock, channel, raf);
    } finally {
      if (jvmLock != null && jvmLock.isHeldByCurrentThread()) {
        jvmLock.unlock();
      }
    }
  }

  /** 获取指定规范化路径对应的 JVM 级别可重入锁。 */
  private static ReentrantLock getJvmLock(Path path) {
    String key = path.toString();
    ReentrantLock lock = JVM_LOCKS.get(key);
    if (lock == null) {
      ReentrantLock newLock = new ReentrantLock();
      ReentrantLock existing = JVM_LOCKS.putIfAbsent(key, newLock);
      lock = existing != null ? existing : newLock;
    }
    return lock;
  }

  /** 确保锁文件的父目录存在。 */
  private static void ensureParentDir(Path lockFile) throws IOException {
    Path parent = lockFile.getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
    }
  }

  /** 读取现有锁文件中的持有者摘要信息。 */
  private static String readHolderInfo(Path lockFile) {
    try {
      if (Files.isRegularFile(lockFile)) {
        byte[] bytes = Files.readAllBytes(lockFile);
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!content.isEmpty()) {
          return content;
        }
      }
    } catch (Throwable ignored) {
    }
    return "未知进程";
  }

  /** 将当前进程与项目信息写入锁文件以便排队诊断。 */
  private static void writeHolderInfo(RandomAccessFile raf, Path project) {
    try {
      String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
      long pid = currentPid();
      String projectPath = project != null ? project.toAbsolutePath().normalize().toString() : "-";
      String info = "PID: " + pid + ", project: " + projectPath + ", acquiredAt: " + now;
      raf.setLength(0);
      raf.seek(0);
      raf.write(info.getBytes(StandardCharsets.UTF_8));
      raf.getChannel().force(true);
    } catch (Throwable ignored) {
    }
  }

  /** 获取当前运行的 JVM 进程 PID。 */
  private static long currentPid() {
    try {
      String jvmName = ManagementFactory.getRuntimeMXBean().getName();
      int atIndex = jvmName.indexOf('@');
      if (atIndex > 0) {
        return Long.parseLong(jvmName.substring(0, atIndex));
      }
    } catch (Throwable ignored) {
    }
    return -1;
  }

  /** 静默关闭文件锁与通道资源。 */
  private static void closeSilently(FileLock fileLock, FileChannel channel, RandomAccessFile raf) {
    if (fileLock != null && fileLock.isValid()) {
      try {
        fileLock.release();
      } catch (Throwable ignored) {
      }
    }
    if (channel != null && channel.isOpen()) {
      try {
        channel.close();
      } catch (Throwable ignored) {
      }
    }
    if (raf != null) {
      try {
        raf.close();
      } catch (Throwable ignored) {
      }
    }
  }
}
