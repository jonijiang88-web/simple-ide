plugins {
  java
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

tasks.jar {
  archiveFileName.set("simple-ide-hotswap-agent.jar")
  manifest {
    attributes(
      "Premain-Class" to "io.simpleide.hotswap.HotswapAgent",
      "Agent-Class" to "io.simpleide.hotswap.HotswapAgent",
      "Can-Redefine-Classes" to "true",
      "Can-Retransform-Classes" to "true"
    )
  }
}
