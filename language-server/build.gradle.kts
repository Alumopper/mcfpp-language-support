import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    application
    id("com.gradleup.shadow") version "9.2.2"
}

group = "top.mcfpp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://nexus.mcfpp.top/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://maven.aliyun.com/nexus/content/groups/public/")
    maven("https://libraries.minecraft.net")
}

val lsp4jVersion = "0.22.0"
val antlrVersion = "4.13.1"
val compilerJar = providers.gradleProperty("mcfppCompilerJar").orElse("../../MCFPP/build/libs/MCFPP-0.1.0.jar")
val compilerPlainJar = providers.gradleProperty("mcfppCompilerPlainJar").orElse("../../MCFPP/build/libs/MCFPP-0.1.0-plain.jar")
val compilerEditorJar = providers.gradleProperty("mcfppCompilerEditorJar").orElse("../../MCFPP/build/libs/MCFPP-0.1.0-editor.jar")
val compilerStdlibIndexJar = providers.gradleProperty("mcfppCompilerStdlibIndexJar").orElse("../../MCFPP/build/libs/MCFPP-0.1.0-stdlib-index.jar")

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.antlr:antlr4-runtime:$antlrVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
    implementation(files(compilerEditorJar))
    // Transitive runtime dependencies required by top.mcfpp:nbt:1.0 (minimized by ShadowJar)
    implementation("com.mojang:brigadier:1.3.10")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("com.google.guava:guava:33.4.5-jre")
    implementation("it.unimi.dsi:fastutil:8.5.12")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Only compiler cross-check tests inspect compiler registries directly; production jars stay lightweight.
    testImplementation(files(compilerPlainJar))
    testRuntimeOnly(files(compilerStdlibIndexJar))
    testRuntimeOnly(kotlin("reflect"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

sourceSets {
    main {
        java {
            srcDirs("src/main/kotlin", "../shared/src/main/java")
        }
        kotlin {
            srcDirs("src/main/kotlin")
        }
    }
}

application {
    mainClass.set("top.mcfpp.lsp.MainKt")
}

val generateEntityEditorApi by tasks.registering(JavaExec::class) {
    dependsOn(tasks.compileJava)
    classpath = files(tasks.compileJava.flatMap { it.destinationDirectory })
    mainClass.set("top.mcfpp.language.EntityBuiltins")
    val output = layout.buildDirectory.file("generated-entity-api/editor/entity-builtins.mcfpp")
    args(output.get().asFile.absolutePath)
    outputs.file(output)
}

tasks.processResources {
    dependsOn(generateEntityEditorApi)
    from(layout.buildDirectory.dir("generated-entity-api"))
}

fun ShadowJar.configureServerShadowJar(fileName: String, includeStdlibIndex: Boolean) {
    val distribution = file(compilerJar.get())
    val stdlibArchive = file(compilerStdlibIndexJar.get())
    inputs.file(distribution)
    if (includeStdlibIndex) inputs.file(stdlibArchive)
    archiveFileName.set(fileName)
    archiveClassifier.set("")
    from(sourceSets.main.get().output)
    configurations.set(setOf(project.configurations.runtimeClasspath.get()))
    if (includeStdlibIndex) {
        from({ zipTree(stdlibArchive) })
    }
    doFirst {
        check(file(compilerEditorJar.get()).isFile) {
            "Missing compiler editor artifact: ${compilerEditorJar.get()}. Run `gradlew editorRuntimeJar stdlibIndexJar shadowJar` in MCFPP."
        }
        manifest.attributes["Mcfpp-Compiler-Sha256"] =
            MessageDigest.getInstance("SHA-256").digest(distribution.readBytes()).joinToString("") { "%02x".format(it) }
    }
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Multi-Release"] = "true"
    }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class", "META-INF/versions/**/module-info.class")
    minimize {
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("org.eclipse.lsp4j:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
    }
}

tasks {
    shadowJar {
        configureServerShadowJar("mcfpp-language-server.jar", includeStdlibIndex = true)
    }

    jar {
        archiveClassifier.set("plain")
        manifest {
            attributes["Main-Class"] = application.mainClass.get()
        }
    }

    test {
        useJUnitPlatform()
    }
}

val intellijServerJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "Thin language server JAR for IntelliJ IDEA (reuses bin.mclib from bundled mcfpp-cli.jar)."
    configureServerShadowJar("mcfpp-language-server-intellij.jar", includeStdlibIndex = false)
}

val syncExtensionServer by tasks.registering(Copy::class) {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("../vscode-extension/server"))
}

tasks.build {
    dependsOn(tasks.shadowJar, intellijServerJar)
    finalizedBy(syncExtensionServer)
}
