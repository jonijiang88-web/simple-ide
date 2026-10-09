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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 JPS 增量构建核心链路与时间戳增量识别能力
 */
public class JpsIncrementalCompilerTest {

    private File tempProjectDir;
    private File cacheDir;
    private File pomFile;
    private File javaSourceFile;
    private File outputClassFile;
    private File jdkHome;

    /**
     * 测试前初始化临时 Maven 项目与源码文件
     *
     * @throws Exception 初始化异常
     */
    @Before
    public void setUp() throws Exception {
        tempProjectDir = Files.createTempDirectory("jps-test-project").toFile();
        cacheDir = new File(tempProjectDir, ".jps-cache");

        File srcMainJava = new File(tempProjectDir, "src/main/java/sample");
        srcMainJava.mkdirs();

        pomFile = new File(tempProjectDir, "pom.xml");
        writeStringToFile(pomFile,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>demo-app</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "</project>");

        javaSourceFile = new File(srcMainJava, "Calculator.java");
        writeStringToFile(javaSourceFile,
                "package sample;\n" +
                "public class Calculator {\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}");

        outputClassFile = new File(tempProjectDir, "target/classes/sample/Calculator.class");

        // 探测 JDK home
        File detectedJdk = new File(System.getProperty("java.home"));
        if ("jre".equals(detectedJdk.getName()) && new File(detectedJdk.getParentFile(), "lib/tools.jar").exists()) {
            detectedJdk = detectedJdk.getParentFile();
        }
        jdkHome = detectedJdk;
    }

    /**
     * 测试结束后清理临时文件目录
     *
     * @throws IOException 清理异常
     */
    @After
    public void tearDown() throws IOException {
        if (tempProjectDir != null && tempProjectDir.exists()) {
            Files.walk(tempProjectDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * 核心测试：验证全量初次编译、无变动增量跳过、以及源码变动后的精确增量更新
     *
     * @throws Exception 测试异常
     */
    @Test
    public void testInitialAndIncrementalBuild() throws Exception {
        // 1. 初次全量构建
        MavenPomResolver resolver = new MavenPomResolver(pomFile, cacheDir);
        List<MavenModuleInfo> modules = resolver.resolve();
        // 验证：能够正确解析出一个模块
        assertEquals(1, modules.size());

        MavenJpsModelLoader loader = new MavenJpsModelLoader(modules, jdkHome);
        JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();

        boolean firstBuildSuccess = compiler.compile(loader, cacheDir, false, false, false);
        // 验证：首次编译返回成功状态
        assertTrue("首次编译应该成功", firstBuildSuccess);
        // 验证：目标 class 文件已生成
        assertTrue("编译后应该生成 Calculator.class", outputClassFile.exists());

        long firstModified = outputClassFile.lastModified();

        // 2. 睡眠等待文件系统时间戳分辨率（部分文件系统时间戳为秒级）
        Thread.sleep(1200);

        // 3. 无改动时执行增量构建
        boolean noChangeSuccess = compiler.compile(loader, cacheDir, false, false, false);
        // 验证：无改动增量编译返回成功状态
        assertTrue("无改动增量编译应该成功", noChangeSuccess);
        // 验证：未改动代码时，class 文件未被重复写入，时间戳保持一致
        assertEquals("无改动时 class 文件不应被重新写入", firstModified, outputClassFile.lastModified());

        // 4. 修改源码，测试增量重新编译
        writeStringToFile(javaSourceFile,
                "package sample;\n" +
                "public class Calculator {\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b + 1;\n" +
                "    }\n" +
                "}");

        boolean modifiedBuildSuccess = compiler.compile(loader, cacheDir, false, false, false);
        // 验证：修改源码后增量编译返回成功状态
        assertTrue("修改源码后增量编译应该成功", modifiedBuildSuccess);
        // 验证：修改代码后，class 文件被重新编译更新，时间戳发生变化
        assertTrue("源码修改后 class 文件时间戳应被更新", outputClassFile.lastModified() > firstModified);
    }

    /**
     * 工具方法：将文本写入指定文件
     *
     * @param file 目标文件
     * @param content 文本内容
     * @throws IOException 写入异常
     */
    private void writeStringToFile(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
