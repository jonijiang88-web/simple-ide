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
  implementation("org.ow2.asm:asm:9.9.1")
  implementation("org.ow2.asm:asm-commons:9.9.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  // Gradle 9 不再自动注入 JUnit Platform Launcher，测试进程需要显式依赖。
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
  useJUnitPlatform()
}

tasks.jar {
  archiveFileName.set("simple-ide-hotswap-agent.jar")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  // Agent 运行在目标 JVM 中，ASM 必须一起打进 agent jar。
  from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
  manifest {
    attributes(
      "Premain-Class" to "io.simpleide.hotswap.HotswapAgent",
      "Agent-Class" to "io.simpleide.hotswap.HotswapAgent",
      "Can-Redefine-Classes" to "true",
      "Can-Retransform-Classes" to "true"
    )
  }
}
