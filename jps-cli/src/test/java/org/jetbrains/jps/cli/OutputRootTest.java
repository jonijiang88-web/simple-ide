package org.jetbrains.jps.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 【R4】验证 jps-cli 的编译输出重定向：受保护构建的 class 必须写到隔离根目录，
 * 共享 target/classes 必须完全不被改动。
 */
public class OutputRootTest {

    private File tempDir;
    private File sourceDir;
    private File jdkHome;

    /**
     * 初始化零依赖小项目
     *
     * @throws Exception 初始化异常
     */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jps-output-root").toFile();
        sourceDir = new File(tempDir, "src/main/java/sample");
        sourceDir.mkdirs();
        write(new File(tempDir, "pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                        "  <modelVersion>4.0.0</modelVersion>\n" +
                        "  <groupId>com.example</groupId>\n" +
                        "  <artifactId>outroot-app</artifactId>\n" +
                        "  <version>1.0.0</version>\n" +
                        "</project>\n");
        write(new File(sourceDir, "Hello.java"),
                "package sample;\npublic class Hello { public static String v() { return \"v1\"; } }\n");
        File detected = new File(System.getProperty("java.home"));
        if ("jre".equals(detected.getName()) && new File(detected.getParentFile(), "lib/tools.jar").exists()) {
            detected = detected.getParentFile();
        }
        jdkHome = detected;
    }

    /** 清理临时目录。 */
    @After
    public void tearDown() throws IOException {
        if (tempDir != null && tempDir.exists()) {
            Files.walk(tempDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * 验证输出重定向：class 写入隔离根目录，共享 target/classes 不被创建
     *
     * @throws Exception 测试异常
     */
    @Test
    public void redirects_compilation_outputs() throws Exception {
        File cacheDir = new File(tempDir, ".jps-cache");
        File outputRoot = new File(tempDir, ".simple-ide/isolated-build/out");

        MavenPomResolver resolver = new MavenPomResolver(new File(tempDir, "pom.xml"), cacheDir);
        List<MavenModuleInfo> modules = resolver.resolve();
        assertTrue(modules.size() == 1);

        // 模拟 --output-root 的重定向
        File classes = new File(outputRoot, modules.get(0).getModuleName() + "/classes");
        File testClasses = new File(outputRoot, modules.get(0).getModuleName() + "/test-classes");
        modules.get(0).setOutputDir(classes);
        modules.get(0).setTestOutputDir(testClasses);

        MavenJpsModelLoader loader = new MavenJpsModelLoader(modules, jdkHome);
        JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
        // 验证：重定向后编译成功
        assertTrue(compiler.compile(loader, cacheDir, false, false, false));

        File isolatedClass = new File(classes, "sample/Hello.class");
        File sharedClass = new File(tempDir, "target/classes/sample/Hello.class");
        // 验证：class 写入隔离输出目录
        assertTrue("隔离输出目录应包含 class: " + isolatedClass, isolatedClass.isFile());
        // 验证：共享 target/classes 完全未被创建
        assertFalse("共享 target 不应被本进程写入: " + sharedClass, sharedClass.exists());
        assertFalse("共享 target 目录不应被创建", new File(tempDir, "target/classes").exists());
    }

    /**
     * 验证重复构建可复用隔离输出（增量）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void isolated_outputs_are_reusable() throws Exception {
        File cacheDir = new File(tempDir, ".jps-cache");
        File outputRoot = new File(tempDir, ".simple-ide/isolated-build/out");
        MavenPomResolver resolver = new MavenPomResolver(new File(tempDir, "pom.xml"), cacheDir);
        List<MavenModuleInfo> modules = resolver.resolve();
        File classes = new File(outputRoot, modules.get(0).getModuleName() + "/classes");
        modules.get(0).setOutputDir(classes);
        modules.get(0).setTestOutputDir(new File(outputRoot, modules.get(0).getModuleName() + "/test-classes"));

        MavenJpsModelLoader loader = new MavenJpsModelLoader(modules, jdkHome);
        JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
        assertTrue(compiler.compile(loader, cacheDir, false, false, false));
        File isolatedClass = new File(classes, "sample/Hello.class");
        long firstModified = isolatedClass.lastModified();

        Thread.sleep(1200);
        // 验证：无改动时增量构建不重写 class
        assertTrue(compiler.compile(loader, cacheDir, false, false, false));
        assertTrue(isolatedClass.lastModified() == firstModified);

        // 改源码后应重新编译（隔离输出仍有效）
        write(new File(sourceDir, "Hello.java"),
                "package sample;\npublic class Hello { public static String v() { return \"v2\"; } }\n");
        assertTrue(compiler.compile(loader, cacheDir, false, false, false));
        // 验证：class 被更新
        assertTrue(isolatedClass.lastModified() > firstModified);
    }

    /** 工具方法：写入文本文件。 */
    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
