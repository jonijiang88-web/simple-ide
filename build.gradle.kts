plugins {
  application
}

repositories {
  mavenCentral()
}

// 独立解析旧版项目依赖，回归测试必须在子进程中复现新旧 JUnit 混用。
val legacyJUnitTestClasspath by configurations.creating

dependencies {
  implementation(fileTree("lib") { include("*.jar") })
  implementation("org.ow2.asm:asm:9.3")
  implementation("org.ow2.asm:asm-tree:9.3")
  implementation("org.junit.platform:junit-platform-launcher:1.11.4")
  implementation("org.junit.jupiter:junit-jupiter-engine:5.11.4")
  implementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
  implementation("org.junit.vintage:junit-vintage-engine:5.11.4")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  // LocalTestRunner 需要同时验证 JUnit 4（经 Vintage 引擎）链路
  testImplementation("junit:junit:4.13.2")
  legacyJUnitTestClasspath("org.junit.jupiter:junit-jupiter:5.8.2")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

application {
  mainClass.set("io.simpleide.SimpleIde")
}

distributions {
  main {
    contents {
      from(".") {
        include("INSTALL.md")
        include("README.md")
        include("install.sh")
        include("install.ps1")
        include("skills/**")
      }
      from(project(":hotswap-agent").layout.buildDirectory.file("libs/simple-ide-hotswap-agent.jar")) {
        into("lib")
      }
      from(project(":hotswap-jackson-adapter").layout.buildDirectory.file("libs/simple-ide-hotswap-jackson-adapter.jar")) {
        into("lib")
      }
      from(project(":hotswap-mybatis-adapter").layout.buildDirectory.file("libs/simple-ide-hotswap-mybatis-adapter.jar")) {
        into("lib")
      }
      filesMatching("bin/*") {
        permissions {
          unix("rwxr-xr-x")
        }
      }
      filesMatching("install.sh") {
        permissions {
          unix("rwxr-xr-x")
        }
      }
    }
  }
}

tasks.named("installDist") { dependsOn(":hotswap-agent:jar", ":hotswap-jackson-adapter:jar", ":hotswap-mybatis-adapter:jar") }
tasks.named("distZip") { dependsOn(":hotswap-agent:jar", ":hotswap-jackson-adapter:jar", ":hotswap-mybatis-adapter:jar") }
tasks.named("distTar") { dependsOn(":hotswap-agent:jar", ":hotswap-jackson-adapter:jar", ":hotswap-mybatis-adapter:jar") }

tasks.test {
  systemProperty("simple.ide.test.runtime-classpath", sourceSets.test.get().runtimeClasspath.asPath)
  systemProperty("simple.ide.test.legacy-junit-classpath", legacyJUnitTestClasspath.asPath)
  useJUnitPlatform {
    excludeTags("fixture")
  }
  // io.simpleide.fixtures.* 是 LocalTestRunner 的受控夹具，由 LocalTestRunnerTest 主动执行，
  // 不参与 Gradle 常规测试扫描（否则 @BeforeAll 失败的夹具会让整个构建失败）
  filter {
    excludeTestsMatching("io.simpleide.fixtures.*")
  }
  systemProperty("ij.mcp.server.port", "65431")
}
