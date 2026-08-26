plugins {
  application
}

repositories {
  mavenCentral()
}

dependencies {
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
      filesMatching("bin/simple-ide-launcher") {
        filePermissions {
          unix("rwxr-xr-x")
        }
      }
    }
  }
}

tasks.test {
  useJUnitPlatform()
}
