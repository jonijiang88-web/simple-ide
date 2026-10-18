plugins {
  java
}

repositories {
  mavenCentral()
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

dependencies {
  compileOnly(project(":hotswap-agent"))
  compileOnly("com.fasterxml.jackson.core:jackson-databind:2.13.5")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
  useJUnitPlatform()
}

tasks.jar {
  archiveFileName.set("simple-ide-hotswap-jackson-adapter.jar")
}
