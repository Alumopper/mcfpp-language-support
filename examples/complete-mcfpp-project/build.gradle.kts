plugins {
    java
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://libraries.minecraft.net")
    maven("https://nexus.mcfpp.top/repository/maven-public/")
}

dependencies {
    compileOnly("top.mcfpp:mcfpp:1.0.2-SNAPSHOT")
    runtimeOnly("top.mcfpp:mcfpp:1.0.2-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.jar {
    archiveFileName.set("mcfpp-complete-project.jar")
}

val compileMcfpp by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compile the complete MCFPP fixture with its Java MNI implementation."
    dependsOn(tasks.jar)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    mainClass.set("top.mcfpp.MCFPPKt")
    classpath = configurations.runtimeClasspath.get() + sourceSets.main.get().output
    workingDir = projectDir
    args(layout.projectDirectory.file("mcfpp.json").asFile.absolutePath)
}

val verifyMcfppOutput by tasks.registering {
    group = "verification"
    dependsOn(compileMcfpp)
    doLast {
        val output = layout.buildDirectory.dir("datapack").get().asFile
        check(output.isDirectory) { "MCFPP compiler did not create the datapack output directory" }
        check(output.walkTopDown().any { it.isFile && it.extension == "mcfunction" }) {
            "MCFPP compiler did not emit any mcfunction files"
        }
    }
}

tasks.check {
    dependsOn(verifyMcfppOutput)
}
