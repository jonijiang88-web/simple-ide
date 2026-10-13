package org.jetbrains.jps.cli;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * jps-cli 子进程侧的项目级构建锁。
 *
 * <p>存在意义：simple-ide 父进程持有的跨进程构建锁在父进程被 {@code SIGKILL} 后会由操作系统释放，
 * 但仍在运行的 JPS 子进程会继续写同一项目的构建缓存。子进程自己持有该锁后，
 * 任何新构建在启动前都能发现「本项目已有 JPS 在构建」，从而避免并发写同一缓存。
 */
final class ChildBuildLock implements Closeable {

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;

    private ChildBuildLock(RandomAccessFile raf, FileChannel channel, FileLock lock) {
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 以非阻塞方式获取项目构建锁
     *
     * @param lockFile 锁文件路径
     * @return 已持有的锁
     * @throws IOException 锁文件不可用，或已被其它构建持有
     */
    static ChildBuildLock acquire(File lockFile) throws IOException {
        File parent = lockFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
        FileChannel channel = raf.getChannel();
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // 同一 JVM 内重复加锁：语义上等同于「已被其它构建持有」
            closeQuietly(channel, raf);
            throw new IOException("JPS 构建锁已被其它构建持有（同 JVM 重复加锁）", e);
        } catch (Exception e) {
            closeQuietly(channel, raf);
            throw new IOException("获取 JPS 构建锁异常: " + e.getMessage(), e);
        }
        if (lock == null) {
            String holder = readHolder(lockFile);
            closeQuietly(channel, raf);
            throw new IOException("JPS 构建锁已被其它构建持有" + (holder.isEmpty() ? "" : "（" + holder + "）"));
        }
        try {
            raf.setLength(0);
            raf.seek(0);
            raf.write(("PID: " + ProcessTree.currentPid() + ", startedAt: " + System.currentTimeMillis()).getBytes("UTF-8"));
            raf.getChannel().force(true);
        } catch (Exception ignored) {
        }
        return new ChildBuildLock(raf, channel, lock);
    }

    /** 读取锁文件中的持有者摘要。 */
    private static String readHolder(File lockFile) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(lockFile.toPath());
            String text = new String(bytes, "UTF-8").trim();
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    /** 释放锁并清理句柄。 */
    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (Exception ignored) {
        }
        closeQuietly(channel, raf);
    }

    /** 静默关闭资源。 */
    private static void closeQuietly(FileChannel channel, RandomAccessFile raf) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        }
    }
}
