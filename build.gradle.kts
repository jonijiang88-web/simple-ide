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
  systemProperty("ij.mcp.server.port", "65431")
}
