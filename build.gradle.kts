plugins {
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(fileTree("lib") { include("*.jar") })
  implementation("org.junit.platform:junit-platform-launcher:1.11.4")
  implementation("org.junit.jupiter:junit-jupiter-engine:5.11.4")
  implementation("org.junit.vintage:junit-vintage-engine:5.11.4")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  // LocalTestRunner 需要同时验证 JUnit 4（经 Vintage 引擎）链路
  testImplementation("junit:junit:4.13.2")
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

tasks.test {
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
