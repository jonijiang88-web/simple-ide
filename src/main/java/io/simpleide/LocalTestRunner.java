package io.simpleide;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立的本地测试执行器，兼容 JUnit 4 与 JUnit 5，输出单行结构化 JSON 并完整记录所有失败堆栈。
 */
public class LocalTestRunner {

    /**
     * 测试执行器入口主函数。
     *
     * @param args 命令行参数：args[0] 为全限定测试类名，可选 args[1] 为测试方法名
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("[ERROR] 用法: LocalTestRunner <testClass> [<method>]");
            System.exit(1);
        }

        String testClass = args[0];
        String method = (args.length > 1 && !args[1].isEmpty() && !"null".equalsIgnoreCase(args[1])) ? args[1] : null;

        try {
            Map<String, Object> result = executeTests(testClass, method);
            boolean isSuccess = Boolean.TRUE.equals(result.get("success"));
            System.out.println(SimpleIde.json(result));
            System.exit(isSuccess ? 0 : 1);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            System.err.println(sw.toString());
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("success", false);
            err.put("testClass", testClass);
            if (method != null) err.put("method", method);
            err.put("exitCode", 2);
            err.put("error", t.getMessage() != null ? t.getMessage() : t.toString());
            err.put("engine", "jps-local");
            System.out.println(SimpleIde.json(err));
            System.exit(2);
        }
    }

    /**
     * 单个测试用例执行记录。
     */
    static class TestCaseRecord {
        final String name;
        final String displayName;
        String status = "PASSED";
        long duration = 0;
        long startTime = 0;
        String message;

        TestCaseRecord(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    /**
     * 自定义测试生命周期监听器，采集每个用例的耗时与状态。
     */
    static class CaseCollectorListener implements TestExecutionListener {
        final Map<TestIdentifier, TestCaseRecord> activeCases = new LinkedHashMap<TestIdentifier, TestCaseRecord>();
        final List<TestCaseRecord> finishedCases = new ArrayList<TestCaseRecord>();

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            if (testIdentifier.isTest()) {
                TestCaseRecord record = new TestCaseRecord(resolveTestName(testIdentifier), testIdentifier.getDisplayName());
                record.startTime = System.currentTimeMillis();
                activeCases.put(testIdentifier, record);
            }
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (testIdentifier.isTest()) {
                TestCaseRecord record = activeCases.remove(testIdentifier);
                if (record == null) {
                    record = new TestCaseRecord(resolveTestName(testIdentifier), testIdentifier.getDisplayName());
                }
                if (record.startTime > 0) {
                    record.duration = System.currentTimeMillis() - record.startTime;
                }
                TestExecutionResult.Status status = testExecutionResult.getStatus();
                if (status == TestExecutionResult.Status.SUCCESSFUL) {
                    record.status = "PASSED";
                } else if (status == TestExecutionResult.Status.FAILED) {
                    record.status = "FAILED";
                    Throwable t = testExecutionResult.getThrowable().orElse(null);
                    if (t != null) {
                        record.message = t.getMessage() != null ? t.getMessage() : t.toString();
                    }
                } else if (status == TestExecutionResult.Status.ABORTED) {
                    record.status = "IGNORED";
                    record.message = "Aborted";
                }
                finishedCases.add(record);
            }
        }

        @Override
        public void executionSkipped(TestIdentifier testIdentifier, String reason) {
            if (testIdentifier.isTest()) {
                TestCaseRecord record = new TestCaseRecord(resolveTestName(testIdentifier), testIdentifier.getDisplayName());
                record.status = "IGNORED";
                record.message = reason;
                finishedCases.add(record);
            }
        }
    }

    /**
     * 执行测试并构建完整的结构化结果 Map，包含所有失败项的完整定位与堆栈。
     *
     * @param testClass 全限定测试类名
     * @param method 可选的测试方法名
     * @return 结构化的测试执行结果 Map
     */
    public static Map<String, Object> executeTests(String testClass, String method) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        if (method != null) {
            builder.selectors(DiscoverySelectors.selectMethod(testClass, method));
        } else {
            builder.selectors(DiscoverySelectors.selectClass(testClass));
        }

        LauncherDiscoveryRequest request = builder.build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        CaseCollectorListener caseListener = new CaseCollectorListener();
        launcher.registerTestExecutionListeners(summaryListener);
        launcher.registerTestExecutionListeners(caseListener);
        launcher.execute(request);

        TestExecutionSummary summary = summaryListener.getSummary();
        long duration = summary.getTimeFinished() - summary.getTimeStarted();
        long found = summary.getTestsFoundCount();
        long succeeded = summary.getTestsSucceededCount();
        long failed = summary.getTestsFailedCount();
        long skipped = summary.getTestsSkippedCount();

        // 收集全部失败用例详细信息
        List<TestExecutionSummary.Failure> failures = summary.getFailures();
        List<Map<String, Object>> failureList = new ArrayList<Map<String, Object>>();
        for (TestExecutionSummary.Failure failure : failures) {
            Map<String, Object> failObj = new LinkedHashMap<String, Object>();
            TestIdentifier id = failure.getTestIdentifier();
            String name = resolveTestName(id);
            Throwable t = failure.getException();
            String stackTrace = getStackTrace(t);
            String message = t != null ? (t.getMessage() != null ? t.getMessage() : t.toString()) : "";
            String excType = t != null ? t.getClass().getName() : "UnknownError";
            String location = extractFailureLocation(stackTrace, testClass);

            failObj.put("test", name);
            failObj.put("exception", excType);
            if (message != null && !message.isEmpty()) {
                failObj.put("message", message);
            }
            if (location != null) {
                failObj.put("location", location);
            }
            if (stackTrace != null && !stackTrace.isEmpty()) {
                failObj.put("details", stackTrace);
            }
            failureList.add(failObj);
        }

        // 收集所有用例状态
        List<Map<String, Object>> testList = new ArrayList<Map<String, Object>>();
        for (TestCaseRecord r : caseListener.finishedCases) {
            Map<String, Object> tcMap = new LinkedHashMap<String, Object>();
            tcMap.put("name", r.name);
            tcMap.put("status", r.status);
            tcMap.put("duration", r.duration);
            if (r.message != null && !r.message.isEmpty()) {
                tcMap.put("message", r.message);
            }
            testList.add(tcMap);
        }

        boolean isSuccess = (failed == 0 && found > 0);

        // 构造控制台精炼输出
        StringBuilder outBuilder = new StringBuilder();
        if (isSuccess) {
            outBuilder.append("✓ All ").append(succeeded).append(" tests passed");
            if (duration > 0) outBuilder.append(" (").append(duration).append("ms)");
        } else {
            outBuilder.append("✗ Test Failed: ").append(failed).append("/").append(found).append(" tests failed");
            if (duration > 0) outBuilder.append(" (").append(duration).append("ms)");
            for (Map<String, Object> failObj : failureList) {
                outBuilder.append("\n\n--- Failure: ").append(failObj.get("test")).append(" ---");
                if (failObj.containsKey("location")) outBuilder.append("\nLocation: ").append(failObj.get("location"));
                if (failObj.containsKey("exception")) outBuilder.append("\nException: ").append(failObj.get("exception"));
                if (failObj.containsKey("message")) outBuilder.append("\nMessage: ").append(failObj.get("message"));
                if (failObj.containsKey("details")) {
                    outBuilder.append("\nDetails:\n").append(failObj.get("details"));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", isSuccess);
        result.put("testClass", testClass);
        if (method != null) result.put("method", method);
        result.put("exitCode", isSuccess ? 0 : 1);
        result.put("total", found);
        result.put("passed", succeeded);
        result.put("failed", failed);
        result.put("ignored", skipped);
        result.put("duration", duration);

        // 兼容旧字段
        result.put("testsFound", found);
        result.put("testsPassed", succeeded);
        result.put("testsFailed", failed);
        result.put("testsSkipped", skipped);
        result.put("durationMs", duration);

        if (!failureList.isEmpty()) {
            result.put("failures", failureList);
            // 兼容单个 failureMessage 字段
            result.put("failureMessage", failureList.get(0).get("message"));
        }
        if (!testList.isEmpty()) {
            result.put("tests", testList);
        }
        result.put("output", outBuilder.toString());
        result.put("engine", "jps-local");
        return result;
    }

    /**
     * 解析测试标识符的方法名或显示名。
     *
     * @param id 测试标识符
     * @return 友好的方法或测试名
     */
    static String resolveTestName(TestIdentifier id) {
        if (id == null) return "unknown";
        try {
            if (id.getSource() != null && id.getSource().isPresent()) {
                TestSource source = id.getSource().get();
                if (source instanceof MethodSource) {
                    MethodSource ms = (MethodSource) source;
                    return ms.getMethodName();
                }
            }
        } catch (Throwable ignored) {}
        String legacy = id.getLegacyReportingName();
        if (legacy != null && !legacy.isEmpty()) return legacy;
        return id.getDisplayName();
    }

    /**
     * 将异常堆栈转为字符串。
     *
     * @param t 异常对象
     * @return 堆栈追踪文本
     */
    static String getStackTrace(Throwable t) {
        if (t == null) return "";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * 从异常堆栈中提取测试失败的用户源码定位（如 DemoTest.java:42）。
     *
     * @param stackTrace 异常完整堆栈
     * @param testClass 目标测试类名
     * @return 源码定位字符串，无法提取时返回 null
     */
    static String extractFailureLocation(String stackTrace, String testClass) {
        if (stackTrace == null || stackTrace.isEmpty()) return null;
        String simpleClass = testClass != null && testClass.contains(".")
            ? testClass.substring(testClass.lastIndexOf('.') + 1)
            : testClass;

        String fallbackLocation = null;
        for (String line : stackTrace.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("at ")) continue;
            int openParen = trimmed.indexOf('(');
            int closeParen = trimmed.indexOf(')', openParen);
            if (openParen > 0 && closeParen > openParen) {
                String loc = trimmed.substring(openParen + 1, closeParen);
                if (simpleClass != null && (trimmed.contains(simpleClass) || loc.startsWith(simpleClass))) {
                    return loc;
                }
                if (fallbackLocation == null
                    && !trimmed.contains("java.base")
                    && !trimmed.contains("sun.reflect")
                    && !trimmed.contains("jdk.internal")
                    && !trimmed.contains("org.junit")
                    && !trimmed.contains("org.mockito")
                    && !trimmed.contains("org.springframework")) {
                    fallbackLocation = loc;
                }
            }
        }
        return fallbackLocation;
    }
}

