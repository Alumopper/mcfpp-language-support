plugins {
    java
}

val mcfppApiJar = providers.gradleProperty("mcfppApiJar")
    .map { file(it) }
    .orElse(file("../../language-server/build/libs/mcfpp-language-server.jar"))

dependencies {
    // The locally built server also contains the real compiler's MNI API.
    compileOnly(files(mcfppApiJar))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val verifyMniDependency by tasks.registering {
    group = "verification"
    description = "Check that the local MCFPP MNI API is available."
    doLast {
        check(mcfppApiJar.get().isFile) {
            "Build ../../language-server first, or set -PmcfppApiJar=/absolute/path/to/mcfpp.jar"
        }
    }
}

tasks.compileJava {
    dependsOn(verifyMniDependency)
}
