package org.jetbrains.jps.cli;

import org.jetbrains.jps.model.JpsModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证依赖解析的硬失败语义与「未授权不得破坏性恢复」的策略。
 *
 * <p>覆盖评审提出的两点：不允许 Maven 失败后继续用残缺依赖；不允许未授权时删除缓存重建。
 */
public class MavenResolutionPolicyTest {

    private File tempDir;
    private File cacheDir;
    private File moduleDir;
    private File modulePom;
    private File localRepo;
    private File jdkHome;

    /**
     * 初始化临时模块与空本地仓库
     *
     * @throws Exception 初始化异常
     */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jps-policy-test").toFile();
        cacheDir = new File(tempDir, ".jps-cache");
        cacheDir.mkdirs();
        moduleDir = new File(tempDir, "app");
        moduleDir.mkdirs();
        modulePom = new File(moduleDir, "pom.xml");
        localRepo = new File(tempDir, "empty-repo");
        localRepo.mkdirs();
        new File(moduleDir, "src/main/java").mkdirs();
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
     * 【R3-2 回归】外部依赖即使 Jar 已在本地仓库，也不得被静态「证明完整」（无法构造传递闭包）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void static_proof_rejects_external_dependency_even_when_jar_present() throws Exception {
        File jar = new File(localRepo, "com/example/dep/1.0.0/dep-1.0.0.jar");
        write(jar, "jar");
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>dep</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies>\n</project>\n");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        MavenPomResolver.StaticProof proof = resolver.proveStaticCompleteness(
                newModuleInfo(), modulePom, new java.util.LinkedHashSet<>(Arrays.asList("app")));
        // 验证：直接 Jar 存在也不能证明完整（dep 自身的传递依赖不可见）
        assertFalse(proof.provable);
        // 验证：原因明确指向传递闭包不可证明
        assertTrue(proof.reason, proof.reason.contains("传递闭包"));
    }

    /**
     * 验证零依赖模块可被证明完整（唯一允许的静态例外之一）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void static_proof_accepts_zero_dependencies() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        MavenPomResolver.StaticProof proof = resolver.proveStaticCompleteness(
                newModuleInfo(), modulePom, new java.util.LinkedHashSet<>(Arrays.asList("app")));
        // 验证：零依赖可直接证明
        assertTrue(proof.reason, proof.provable);
    }

    /**
     * 验证全部依赖都由 reactor 模块图提供时可被证明完整
     *
     * @throws Exception 测试异常
     */
    @Test
    public void static_proof_accepts_reactor_only_dependencies() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>lib</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies>\n</project>\n");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        MavenPomResolver.StaticProof proof = resolver.proveStaticCompleteness(
                newModuleInfo(), modulePom, new java.util.LinkedHashSet<>(Arrays.asList("app", "lib")));
        // 验证：reactor 内部依赖由模块图提供，可证明完整
        assertTrue(proof.reason, proof.provable);
    }

    /**
     * 验证存在 dependencyManagement 时静态解析不可证明完整
     *
     * @throws Exception 测试异常
     */
    @Test
    public void static_proof_fails_with_dependency_management() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencyManagement><dependencies><dependency><groupId>a</groupId>"
                        + "<artifactId>b</artifactId><version>1.0</version></dependency></dependencies></dependencyManagement>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>lib</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies>\n"
                        + "</project>\n");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        MavenPomResolver.StaticProof proof = resolver.proveStaticCompleteness(
                newModuleInfo(), modulePom, new java.util.LinkedHashSet<>(Arrays.asList("app", "lib")));
        // 验证：dependencyManagement 会引入不可见版本，必须判定为不可证明
        assertFalse(proof.provable);
        assertTrue(proof.reason.contains("dependencyManagement"));
    }

    /**
     * 【R3-2 回归】直接 Jar 在库中、但 Maven 无法解析时对外部依赖必须硬失败，且不写有效缓存
     *
     * @throws Exception 测试异常
     */
    @Test
    public void maven_unavailable_with_external_dependency_hard_fails_and_writes_no_cache() throws Exception {
        // 直接依赖 Jar 存在，但其传递依赖在本用例中并不由我们解析
        File jar = new File(localRepo, "com/example/direct/1.0.0/direct-1.0.0.jar");
        write(jar, "jar");
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>direct</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies>\n</project>\n");
        System.setProperty("simple.ide.maven.binary", "/nonexistent/mvn-for-test");
        try {
            MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
            resolver.resolve();
            fail("Maven 不可用且存在外部依赖时必须硬失败");
        } catch (MavenPomResolver.DependencyResolutionException expected) {
            // 验证：失败信息包含模块名与原因
            assertTrue(expected.getMessage().contains("app"));
        } finally {
            System.clearProperty("simple.ide.maven.binary");
        }
        // 验证：没有写出可被判为有效的依赖缓存
        File cacheFile = new File(cacheDir, "classpath-app.cache");
        assertFalse(cacheFile.exists());
        assertFalse(MavenPomResolver.classpathCacheMetaFile(cacheFile).exists());
    }

    /**
     * 验证有效缓存命中时无需调用 Maven（离线可用且不被误判）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void valid_cache_is_used_without_invoking_maven() throws Exception {
        File jar = new File(localRepo, "com/example/direct/1.0.0/direct-1.0.0.jar");
        write(jar, "jar");
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>direct</artifactId>"
                        + "<version>1.0.0</version></dependency></dependencies>\n</project>\n");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        File cacheFile = new File(cacheDir, "classpath-app.cache");
        resolver.writeClasspathCache(cacheFile, Arrays.asList(jar),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));

        System.setProperty("simple.ide.maven.binary", "/nonexistent/mvn-for-test");
        try {
            List<MavenModuleInfo> modules = resolver.resolve();
            // 验证：缓存有效时不调用 Maven，依赖仍可用
            assertTrue(modules.get(0).getDependencyJars().contains(jar));
        } finally {
            System.clearProperty("simple.ide.maven.binary");
        }
    }

    /**
     * 验证依赖解析失败且静态不可证明时必须硬失败，而不是继续用残缺依赖
     *
     * @throws Exception 测试异常
     */
    @Test
    public void resolve_hard_fails_when_dependency_cannot_be_proven() throws Exception {
        // 依赖不存在于空本地仓库，版本为字面值；静态证明与 Maven 解析都会失败
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
                        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>missing-dep</artifactId>"
                        + "<version>9.9.9</version></dependency></dependencies>\n</project>\n");
        System.setProperty("simple.ide.maven.timeout.sec", "30");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        try {
            resolver.resolve();
            fail("依赖不可确证完整时必须抛出 DependencyResolutionException");
        } catch (MavenPomResolver.DependencyResolutionException expected) {
            // 验证：失败信息包含模块与原因，便于定位
            assertTrue(expected.getMessage().contains("app"));
            assertTrue(expected.getMessage().contains("依赖解析失败"));
        } finally {
            System.clearProperty("simple.ide.maven.timeout.sec");
        }
        // 验证：失败时不得写入任何依赖缓存（避免残缺缓存被后续当成有效）
        File[] caches = cacheDir.listFiles((d, name) -> name.startsWith("classpath-"));
        assertTrue(caches == null || caches.length == 0);
    }

    /**
     * 验证旧格式缓存中的缺失 Jar 不会被当作可迁移采纳的部分缓存
     *
     * @throws Exception 测试异常
     */
    @Test
    public void legacy_cache_with_missing_jar_is_rejected() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n");
        File cacheFile = new File(cacheDir, "classpath-app.cache");
        write(cacheFile, new File(tempDir, "gone-1.0.jar").getAbsolutePath() + "\n");
        // 验证：旧格式缓存存在缺失 Jar 时判定为 MISSING_JARS（不再走可迁移采纳）
        assertEquals(MavenPomResolver.CacheStatus.MISSING_JARS,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证「已确证无外部依赖」的空缓存（注释行 + entryCount=0）被视为有效
     *
     * @throws Exception 测试异常
     */
    @Test
    public void proven_empty_cache_is_valid() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n");
        File cacheFile = new File(cacheDir, "classpath-app.cache");
        MavenPomResolver resolver = new MavenPomResolver(modulePom, localRepo, cacheDir, cacheDir);
        resolver.writeClasspathCache(cacheFile, new java.util.ArrayList<File>(),
                MavenPomResolver.computeFingerprint(modulePom, localRepo));
        // 验证：0 外部依赖的已确证缓存有效（避免每次构建都重复调用 Maven）
        assertEquals(MavenPomResolver.CacheStatus.VALID,
                MavenPomResolver.validateClasspathCache(cacheFile, modulePom, localRepo));
    }

    /**
     * 验证未授权时检测到存储损坏不删除缓存，而是抛出明确异常
     *
     * @throws Exception 测试异常
     */
    @Test
    public void unauthorized_corruption_does_not_delete_cache() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n");
        File sentinel = new File(cacheDir, "sentinel.txt");
        write(sentinel, "keep-me");

        MavenPomResolver resolver = new MavenPomResolver(modulePom, cacheDir);
        List<MavenModuleInfo> modules = resolver.resolve();
        MavenJpsModelLoader loader = new ExplodingLoader(modules, jdkHome);

        JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
        try {
            compiler.compile(loader, cacheDir, false, false, false, new java.util.ArrayList<String>(), false);
            fail("未授权破坏性恢复时必须抛出 CacheRecoveryNotAllowedException");
        } catch (JpsIncrementalCompiler.CacheRecoveryNotAllowedException expected) {
            // 验证：异常信息说明存储损坏且未授权清理
            assertTrue(expected.getMessage().contains("未授权"));
        }
        // 验证：缓存目录未被删除（未授权的破坏性恢复被阻止）
        assertTrue("未授权时不得删除缓存目录", sentinel.isFile());
    }

    /**
     * 验证已授权时仍执行清理重建（策略开关不影响授权路径）
     *
     * @throws Exception 测试异常
     */
    @Test
    public void authorized_corruption_rebuilds() throws Exception {
        write(modulePom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n");
        File sentinel = new File(cacheDir, "sentinel.txt");
        write(sentinel, "delete-me");

        MavenPomResolver resolver = new MavenPomResolver(modulePom, cacheDir);
        List<MavenModuleInfo> modules = resolver.resolve();
        MavenJpsModelLoader loader = new ExplodingLoader(modules, jdkHome);

        JpsIncrementalCompiler compiler = new JpsIncrementalCompiler();
        // 验证：授权路径下清理缓存并重建，构建成功
        assertTrue(compiler.compile(loader, cacheDir, false, false, false, new java.util.ArrayList<String>(), true));
        // 验证：缓存目录被清理（已授权的破坏性恢复）
        assertFalse(sentinel.isFile());
    }

    /** 解析时抛出 EOFException 的模型加载器，模拟存储损坏异常逃逸到外层。 */
    static class ExplodingLoader extends MavenJpsModelLoader {
        private int calls = 0;

        ExplodingLoader(List<MavenModuleInfo> modules, File jdkHome) {
            super(modules, jdkHome);
        }

        @Override
        public JpsModel loadModel() throws IOException {
            calls++;
            if (calls == 1) {
                throw new EOFException("simulated corrupted storage read");
            }
            return super.loadModel();
        }
    }

    /** 直接构造模块信息（避免经由 resolve()，便于单独验证证明逻辑）。 */
    private MavenModuleInfo newModuleInfo() {
        return new MavenModuleInfo("app", moduleDir,
                new File(moduleDir, "src/main/java"), new File(moduleDir, "src/main/resources"),
                new File(moduleDir, "src/test/java"), new File(moduleDir, "src/test/resources"),
                new File(moduleDir, "target/classes"), new File(moduleDir, "target/test-classes"));
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
