package org.jetbrains.jps.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证依赖 Classpath 缓存的指纹校验、残缺缓存拒绝、原子写入与旧格式迁移路径。
 */
public class MavenPomResolverCacheTest {

    private File tempDir;
    private File modulePom;
    private File parentPom;
    private File localRepo;
    private File cacheDir;
    private File cacheFile;

    /**
     * 测试前初始化临时模块、父 POM 与缓存目录
     *
     * @throws Exception 初始化异常
     */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jps-cache-test").toFile();
        parentPom = new File(tempDir, "pom.xml");
        write(parentPom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>com.example</groupId>\n"
                        + "  <artifactId>parent</artifactId>\n"
                        + "  <version>1.0.0</version>\n"
                        + "</project>");
        File moduleDir = new File(tempDir, "app");
        moduleDir.mkdirs();
        modulePom = new File(moduleDir, "pom.xml");
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0.0</version></parent>\n"
                        + "  <artifactId>app</artifactId>\n"
                        + "</project>");
        localRepo = new File(tempDir, ".m2/repository");
        localRepo.mkdirs();
        cacheDir = new File(tempDir, ".jps-deps");
        cacheDir.mkdirs();
        cacheFile = new File(cacheDir, "classpath-app.cache");
    }

    /** 测试结束后清理临时目录。 */
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
     * 验证缓存不存在时判定为 ABSENT，而不是被当成可用缓存
     */
    @Test
    public void absentCacheIsReportedAsAbsent() {
        // 验证：没有缓存文件时状态为 ABSENT
        assertEquals(MavenPomResolver.CacheStatus.ABSENT,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证空缓存文件不会被当成有效缓存
     *
     * @throws Exception 写入异常
     */
    @Test
    public void emptyCacheIsRejected() throws Exception {
        cacheFile.createNewFile();
        // 验证：空缓存判定为 EMPTY
        assertEquals(MavenPomResolver.CacheStatus.EMPTY,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证缓存列出的 Jar 缺失时不会被静默接受为部分缓存
     *
     * @throws Exception 写入异常
     */
    @Test
    public void missingJarIsNotAcceptedAsPartialCache() throws Exception {
        File existingJar = new File(tempDir, "repo/present-1.0.jar");
        write(existingJar, "jar");
        String fingerprint = MavenPomResolver.computeFingerprint(modulePom, localRepo);
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, Arrays.asList(existingJar), fingerprint);
        // 追加一个不存在的 Jar，模拟缓存被清理/仓库被移动
        append(cacheFile, new File(tempDir, "repo/gone-1.0.jar").getAbsolutePath() + "\n");

        // 验证：存在缺失 Jar 时缓存不可接受
        assertEquals(MavenPomResolver.CacheStatus.MISSING_JARS,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证按指纹写入的缓存有效
     *
     * @throws Exception 写入异常
     */
    @Test
    public void cacheWithMatchingFingerprintIsValid() throws Exception {
        File jar = new File(tempDir, "repo/present-1.0.jar");
        write(jar, "jar");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, Arrays.asList(jar),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));

        // 验证：指纹匹配的缓存有效
        assertEquals(MavenPomResolver.CacheStatus.VALID,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
        // 验证：元数据文件被原子写入并与缓存同名
        assertTrue(MavenPomResolver.classpathCacheMetaFile(cacheFile).isFile());
        // 验证：没有残留临时文件
        File[] leftovers = cacheDir.listFiles((d, name) -> name.contains(".tmp-"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    /**
     * 【核心回归】验证父 POM 变更会使缓存指纹失效
     *
     * @throws Exception 写入异常
     */
    @Test
    public void parentPomChangeInvalidatesCache() throws Exception {
        File jar = new File(tempDir, "repo/present-1.0.jar");
        write(jar, "jar");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, Arrays.asList(jar),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));
        assertEquals(MavenPomResolver.CacheStatus.VALID,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));

        // 修改父 POM（例如升级依赖管理版本），必须导致缓存失效
        write(parentPom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>com.example</groupId>\n"
                        + "  <artifactId>parent</artifactId>\n"
                        + "  <version>1.0.1</version>\n"
                        + "</project>");

        // 验证：父 POM 变更后缓存判定为指纹不匹配
        assertEquals(MavenPomResolver.CacheStatus.FINGERPRINT_MISMATCH,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证模块自身 POM 变更会使缓存失效
     *
     * @throws Exception 写入异常
     */
    @Test
    public void modulePomChangeInvalidatesCache() throws Exception {
        File jar = new File(tempDir, "repo/present-1.0.jar");
        write(jar, "jar");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, Arrays.asList(jar),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));

        append(modulePom, "\n<!-- changed -->\n");

        // 验证：模块 POM 变更后缓存失效
        assertEquals(MavenPomResolver.CacheStatus.FINGERPRINT_MISMATCH,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证旧格式（无元数据）缓存结构完整时被标记为可迁移采纳
     *
     * @throws Exception 写入异常
     */
    @Test
    public void legacyCacheWithoutMetaIsAdoptable() throws Exception {
        File jar = new File(tempDir, "repo/present-1.0.jar");
        write(jar, "jar");
        write(cacheFile, jar.getAbsolutePath() + "\n");

        // 验证：旧格式缓存被标记为可迁移，而不是直接丢弃或静默接受
        assertEquals(MavenPomResolver.CacheStatus.LEGACY_ADOPTABLE,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证条目数与元数据不一致时缓存失效
     *
     * @throws Exception 写入异常
     */
    @Test
    public void entryCountMismatchInvalidatesCache() throws Exception {
        File jar = new File(tempDir, "repo/present-1.0.jar");
        write(jar, "jar");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, Arrays.asList(jar),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));
        append(cacheFile, jar.getAbsolutePath() + "\n");

        // 验证：条目数与元数据不一致时缓存不可接受
        assertEquals(MavenPomResolver.CacheStatus.ENTRY_COUNT_MISMATCH,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证父 POM 链被正确收集（用于指纹计算）
     */
    @Test
    public void collectsParentPomChain() {
        List<File> chain = MavenPomResolver.parentPomChain(modulePom);
        // 验证：链包含模块 POM 与父 POM
        assertEquals(2, chain.size());
        // 验证：模块 POM 在链首
        assertEquals(modulePom.getAbsolutePath(), chain.get(0).getAbsolutePath());
        // 验证：父 POM 在链尾
        assertEquals(parentPom.getAbsolutePath(), chain.get(1).getAbsolutePath());
    }

    /**
     * 验证指纹对模块 POM 与父 POM 内容敏感，且不含凭据
     *
     * @throws Exception 写入异常
     */
    @Test
    public void fingerprintCoversModuleAndParentAndLeaksNoCredentials() throws Exception {
        String first = MavenPomResolver.computeFingerprint(modulePom, localRepo);
        // 验证：指纹稳定（同输入同输出）
        assertEquals(first, MavenPomResolver.computeFingerprint(modulePom, localRepo));

        // 写入包含凭据的 settings.xml，只应参与摘要而不应出现在任何可见输出中
        File settings = new File(tempDir, "settings.xml");
        write(settings, "<settings><servers><server><password>SUPER_SECRET_TOKEN</password></server></servers></settings>");
        System.setProperty("simple.ide.maven.user.settings", settings.getAbsolutePath());
        try {
            String second = MavenPomResolver.computeFingerprint(modulePom, localRepo);
            // 验证：settings 变化会改变指纹
            assertNotEquals(first, second);
            // 验证：指纹中不包含任何凭据明文
            assertFalse(second.contains("SUPER_SECRET_TOKEN"));
            // 验证：元数据文件中也不包含凭据明文
            MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
            File jar = new File(tempDir, "repo/present-1.0.jar");
            write(jar, "jar");
            resolver.writeClasspathCache(cacheFile, Arrays.asList(jar), second);
            String meta = new String(Files.readAllBytes(MavenPomResolver.classpathCacheMetaFile(cacheFile).toPath()), StandardCharsets.UTF_8);
            assertFalse(meta.contains("SUPER_SECRET_TOKEN"));
        } finally {
            System.clearProperty("simple.ide.maven.user.settings");
        }
    }

    /**
     * 验证缓存条目读取不做存在性过滤（完整性校验需要原始条目）
     *
     * @throws Exception 写入异常
     */
    @Test
    public void readsRawCacheEntries() throws Exception {
        write(cacheFile, "a.jar\n\nb.jar\n");
        List<String> entries = MavenPomResolver.readCacheEntries(cacheFile);
        // 验证：忽略空行并保留全部条目
        assertEquals(2, entries.size());
        assertEquals("a.jar", entries.get(0));
        assertEquals("b.jar", entries.get(1));
    }

    /**
     * 验证 Maven 依赖解析有默认超时边界
     */
    @Test
    public void mavenTimeoutIsBounded() {
        // 验证：默认超时为有限正数，不存在无限等待
        assertTrue(MavenPomResolver.DEFAULT_MAVEN_TIMEOUT_SEC > 0);
        // 验证：可通过系统属性覆盖
        System.setProperty("simple.ide.maven.timeout.sec", "7");
        try {
            assertEquals(7, MavenPomResolver.mavenTimeoutSec());
        } finally {
            System.clearProperty("simple.ide.maven.timeout.sec");
        }
    }

    /**
     * 验证依赖解析失败时不会写入残缺缓存，并保留失败证据
     */
    @Test
    public void failedMavenResolutionWritesNoCacheButKeepsEvidence() {
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        // 使用不存在的模块目录，使子进程无法启动，从而稳定触发失败路径
        MavenPomResolver.Resolution resolution = resolver.resolveViaMavenBuildClasspath(
                new File(tempDir, "not-a-module"), cacheFile, modulePom, java.util.Collections.<String>emptySet());

        // 验证：失败被显式报告而不是静默返回空依赖
        assertFalse(resolution.resolved);
        assertTrue(resolution.error != null && !resolution.error.isEmpty());
        // 验证：失败时不写入任何缓存文件（避免残缺缓存被后续当成有效）
        assertFalse(cacheFile.exists());
        // 验证：失败证据被保留，便于定位原因
        File evidence = new File(cacheDir, "maven-resolve-not-a-module.log");
        assertTrue(evidence.isFile());
        assertTrue(evidence.length() > 0);
    }

    /** 工具方法：写入文本文件（自动创建父目录）。 */
    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    /** 工具方法：追加文本。 */
    private static void append(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(content);
        }
    }
}
