plugins {
  application
}

repositories {
  mavenCentral()
  maven("https://www.jetbrains.com/intellij-repository/releases")
  maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

val jpsVersion = "261.27258.48"

dependencies {
  implementation("com.jetbrains.intellij.platform:jps-build:$jpsVersion")
  implementation("com.jetbrains.intellij.platform:jps-model:$jpsVersion")
  implementation("com.jetbrains.intellij.platform:jps-model-serialization:$jpsVersion")
  implementation("com.jetbrains.intellij.platform:jps-build-javac-rt:$jpsVersion")
  implementation("com.jetbrains.intellij.platform:util:$jpsVersion")
  implementation("org.junit.platform:junit-platform-launcher:1.11.4")
  implementation("org.junit.jupiter:junit-jupiter-engine:5.11.4")
  implementation("org.junit.vintage:junit-vintage-engine:5.11.4")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

application {
  mainClass.set("io.simpleide.SimpleIde")
  applicationDefaultJvmArgs = listOf(
    "--add-opens=java.base/sun.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
  )
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
