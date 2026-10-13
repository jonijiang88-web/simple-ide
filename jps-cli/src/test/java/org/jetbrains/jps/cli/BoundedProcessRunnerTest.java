package org.jetbrains.jps.cli;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证 jps-cli 内部依赖解析所用的有界子进程执行器行为。 */
public class BoundedProcessRunnerTest {

    /**
     * 验证挂起且无输出的子进程会被超时终止，且不阻塞父进程
     *
     * @throws Exception 测试异常
     */
    @Test
    public void timeoutKillsHangingProcess() throws Exception {
        Path dir = Files.createTempDirectory("jps-runner");
        long start = System.currentTimeMillis();
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("sh", "-c", "sleep 60"), dir, 1, 1024 * 1024);
        long elapsed = System.currentTimeMillis() - start;

        // 验证：子进程启动成功
        assertTrue(result.started);
        // 验证：被判定为超时且未正常完成
        assertTrue(result.timedOut);
        assertFalse(result.finished);
        // 验证：超时及时生效（远小于 60 秒）
        assertTrue("超时未及时生效，实际 " + elapsed + "ms", elapsed < 30000);
        // 验证：子进程树被确认退出
        assertTrue(result.killComplete);
    }

    /**
     * 验证输出量超过管道容量时不会死锁，并有界保留头尾
     *
     * @throws Exception 测试异常
     */
    @Test
    public void consumesLargeOutputWithoutDeadlock() throws Exception {
        Path dir = Files.createTempDirectory("jps-runner-output");
        String script = "i=0; while [ $i -lt 8000 ]; do echo line-$i-0123456789012345678901234567890123456789; i=$((i+1)); done";
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("sh", "-c", script), dir, 30, 256 * 1024);

        // 验证：大量输出下进程正常完成
        assertTrue(result.finished);
        assertEquals(0, result.exitCode);
        // 验证：输出超过上限时被标记为截断
        assertTrue(result.outputTruncated);
        // 验证：内存有界
        assertTrue(result.output.length() < 400 * 1024);
        // 验证：保留了末尾输出
        assertTrue(result.output.contains("line-7999"));
    }

    /**
     * 验证超时后不遗留子进程
     *
     * @throws Exception 测试异常
     */
    @Test
    public void leavesNoChildProcessAfterTimeout() throws Exception {
        Path dir = Files.createTempDirectory("jps-runner-tree");
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("sh", "-c", "sleep 60 & echo $!; wait"), dir, 1, 1024 * 1024);
        assertTrue(result.timedOut);

        String firstLine = result.output.trim().split("\n")[0].trim();
        long childPid = Long.parseLong(firstLine);
        Path procEntry = Paths.get("/proc", Long.toString(childPid));
        for (int i = 0; i < 50 && Files.exists(procEntry); i++) {
            Thread.sleep(100);
        }
        if (Files.exists(Paths.get("/proc"))) {
            // 验证：孙进程已被回收，不会与新构建并发写同一缓存
            assertFalse("超时后不应遗留子进程 PID=" + childPid, Files.exists(procEntry));
        }
    }

    /**
     * 验证正常退出码与合并输出被采集
     *
     * @throws Exception 测试异常
     */
    @Test
    public void capturesExitCodeAndMergedOutput() throws Exception {
        Path dir = Files.createTempDirectory("jps-runner-ok");
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("sh", "-c", "echo out; echo err 1>&2; exit 4"), dir, 10, 1024 * 1024);

        // 验证：正常完成
        assertTrue(result.finished);
        // 验证：退出码被正确采集
        assertEquals(4, result.exitCode);
        // 验证：stderr 被合并进输出
        assertTrue(result.output.contains("out"));
        assertTrue(result.output.contains("err"));
    }

    /**
     * 验证进程无法启动时返回错误而不是挂起
     *
     * @throws Exception 测试异常
     */
    @Test
    public void reportsStartFailure() throws Exception {
        Path dir = Files.createTempDirectory("jps-runner-fail");
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("definitely-not-a-real-binary-xyz"), dir, 5, 1024 * 1024);

        // 验证：未启动且给出错误说明
        assertFalse(result.started);
        assertTrue(result.error != null && result.error.contains("子进程启动异常"));
    }

    /**
     * 验证 pidOf 能获取进程 PID（用于进程树回收）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void resolvesProcessPid() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                Arrays.asList("sh", "-c", "echo hi"), Files.createTempDirectory("jps-runner-pid"), 10, 1024);
        // 验证：成功采集到 PID
        assertTrue(result.pid > 0);
    }
}
