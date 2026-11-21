import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    application
    id("com.gradleup.shadow") version "9.2.2"
}

group = "top.mcfpp"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal { content { includeGroup("moe.afox.mcfpm") } }
    mavenCentral()
    maven("https://nexus.mcfpp.top/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://maven.aliyun.com/nexus/content/groups/public/")
    maven("https://libraries.minecraft.net")
    maven("https://nexus.mcfpp.top/repository/maven-releases/")
}

val lsp4jVersion = "0.22.0"
val compilerVersion = "0.1.0"
val compiler = "top.mcfpp:mcfpp:$compilerVersion"
val compilerArtifacts = configurations.dependencyScope("compilerArtifacts")

fun compilerArtifact(name: String, purpose: String) = configurations.resolvable(name) {
    extendsFrom(compilerArtifacts.get())
    isTransitive = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(purpose))
}

val compilerDistribution = compilerArtifact("compilerDistribution", "mcfpp-cli")
val editorMetadata = compilerArtifact("editorMetadata", "mcfpp-editor-metadata")
val stdlibIndex = compilerArtifact("stdlibIndex", "mcfpp-stdlib-index")

dependencies {
    implementation(kotlin("stdlib"))
    implementation("top.mcfpp:mcfpp-syntax:$compilerVersion")
    implementation("top.mcfpp:mcfpp-library-model:$compilerVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    add(compilerArtifacts.name, compiler)

    // Only compiler cross-check tests inspect compiler registries directly; production jars stay lightweight.
    testImplementation(compiler)
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
    dependsOn(generateEntityEditorApi, editorMetadata)
    inputs.files(editorMetadata)
    from(layout.buildDirectory.dir("generated-entity-api"))
    from({ zipTree(editorMetadata.get().singleFile) }) { exclude("META-INF/MANIFEST.MF") }
}

fun ShadowJar.configureServerShadowJar(fileName: String, includeStdlibIndex: Boolean) {
    val distribution = project.files(compilerDistribution)
    dependsOn(distribution)
    inputs.files(distribution)
    archiveFileName.set(fileName)
    archiveClassifier.set("")
    from(sourceSets.main.get().output)
    configurations.set(setOf(project.configurations.runtimeClasspath.get()))
    if (includeStdlibIndex) {
        dependsOn(stdlibIndex)
        inputs.files(stdlibIndex)
        from({ zipTree(stdlibIndex.get().singleFile) }) { exclude("META-INF/MANIFEST.MF") }
    }
    doFirst {
        val digest = MessageDigest.getInstance("SHA-256")
        distribution.singleFile.inputStream().use { input ->
            DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream())
        }
        manifest.attributes["Mcfpp-Compiler-Sha256"] = digest.digest().joinToString("") { "%02x".format(it) }
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

configurations.consumable("intellijServerElements") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("mcfpp-intellij-server"))
    outgoing.artifact(intellijServerJar)
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
