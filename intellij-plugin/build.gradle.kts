import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

group = "top.mcfpp"
version = "0.6.3"

val localIdeaPath = providers.gradleProperty("ideaLocalPath")
val compilerVersion = "0.1.0"
val compilerArtifacts = configurations.dependencyScope("compilerArtifacts")
val serverArtifacts = configurations.dependencyScope("serverArtifacts")

fun bundledArtifact(name: String, purpose: String, dependencies: NamedDomainObjectProvider<out Configuration>) =
    configurations.resolvable(name) {
        extendsFrom(dependencies.get())
        isTransitive = false
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(purpose))
    }

val compilerCli = bundledArtifact("compilerCli", "mcfpp-cli", compilerArtifacts)
val compilerSources = bundledArtifact("compilerSources", "mcfpp-sources", compilerArtifacts)
val stdlibSources = bundledArtifact("stdlibSources", "mcfpp-stdlib-sources", compilerArtifacts)
val languageServerJar = bundledArtifact("languageServerJar", "mcfpp-intellij-server", serverArtifacts)

dependencies {
    implementation("top.mcfpp:mcfpp-syntax:$compilerVersion")
    add(compilerArtifacts.name, "top.mcfpp:mcfpp:$compilerVersion")
    add(serverArtifacts.name, "top.mcfpp:mcfpp-language-server:1.0-SNAPSHOT")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.commonmark:commonmark:0.24.0")
    intellijPlatform {
        if (localIdeaPath.isPresent) {
            local(localIdeaPath.get())
        } else {
            intellijIdea("2025.3.3")
        }
        bundledPlugin("com.intellij.java")
        if (localIdeaPath.orNull?.let { file(it).resolve("plugins/platform-testRunner-plugin").isDirectory } == true) {
            bundledPlugin("intellij.testRunner.plugin")
        }
        testBundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("mcfppBuildJava").orElse("25").get().toInt()))
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

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

val unpackPackResourceSources by tasks.registering(Sync::class) {
    dependsOn(compilerSources)
    inputs.files(compilerSources)
    from({ zipTree(compilerSources.get().singleFile) }) { include("top/mcfpp/api/resources/**") }
    into(layout.buildDirectory.dir("pack-resource-api"))
}

sourceSets.main {
    java.srcDir("../shared/src/main/java")
    java.srcDir(unpackPackResourceSources)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
    pluginVerification {
        ides {
            current()
        }
    }
}

val linterRoot = layout.projectDirectory.dir("../../MCFPP-Linter")
val formatterBundle by tasks.registering(Zip::class) {
    val prettierPackage = linterRoot.file("node_modules/prettier/package.json").asFile
    archiveFileName.set("formatter.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundled-tools"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("formatter/service.mjs")
    from(linterRoot.dir("src")) { into("mcfpp-linter/src") }
    from(linterRoot.file("package.json")) { into("mcfpp-linter") }
    from(linterRoot.dir("node_modules/prettier")) { into("node_modules/prettier") }
    doFirst {
        check(prettierPackage.isFile) { "Run npm ci in MCFPP-Linter before packaging the plugin." }
    }
}
val toolchainManifest by tasks.registering {
    val target = layout.buildDirectory.file("bundled-tools/toolchain.json")
    val targetFile = target.get().asFile
    val cliFiles = files(compilerCli)
    val sourceFiles = files(compilerSources)
    val serverFiles = files(languageServerJar)
    val formatterFile = formatterBundle.get().archiveFile.get().asFile
    val versionJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().executablePath.asFile
    val linterPackage = linterRoot.file("package.json").asFile
    val prettierPackage = linterRoot.file("node_modules/prettier/package.json").asFile
    val pluginVersion = project.version.toString()
    dependsOn(formatterBundle, cliFiles, sourceFiles, serverFiles)
    inputs.files(cliFiles, sourceFiles, serverFiles)
    inputs.file(formatterBundle.flatMap { it.archiveFile })
    inputs.files(linterPackage, prettierPackage)
    inputs.property("pluginVersion", pluginVersion)
    outputs.file(target)
    doLast {
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val cliFile = cliFiles.singleFile
        val sourcesFile = sourceFiles.singleFile
        val serverFile = serverFiles.singleFile
        JarFile(cliFile).use { compiler ->
            check(compiler.getJarEntry("moe/afox/mcfpm/repository/maven/ProjectPackageApi.class") != null) {
                "The bundled CLI lacks the MCFPM project package API. Rebuild the compiler with its runtime dependencies using shadowJar."
            }
        }
        val compilerHash = sha(cliFile)
        val versionProcess = ProcessBuilder(versionJava.path, "-jar", cliFile.path, "--version").start()
        val versionOutput = versionProcess.inputStream.bufferedReader().readText()
        check(versionProcess.waitFor() == 0) { "Unable to read the bundled CLI version: ${versionProcess.errorStream.bufferedReader().readText()}" }
        val compilerVersion = Regex("MCFPP ([^\\s]+)").find(versionOutput)?.groupValues?.get(1)
            ?: error("Unexpected CLI version response: $versionOutput")
        fun packageVersion(file: File) = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(file.readText())!!.groupValues[1]
        JarFile(serverFile).use { server ->
            check(server.manifest.mainAttributes.getValue("Mcfpp-Compiler-Sha256") == compilerHash) {
                "The language server and CLI must use the same compiler."
            }
        }
        targetFile.apply {
            parentFile.mkdirs()
            writeText("""{"schema":1,"pluginVersion":"$pluginVersion","compilerVersion":"$compilerVersion","linterVersion":"${packageVersion(linterPackage)}","prettierVersion":"${packageVersion(prettierPackage)}","compilerSha256":"$compilerHash","compilerSourcesSha256":"${sha(sourcesFile)}","serverSha256":"${sha(serverFile)}","formatterSha256":"${sha(formatterFile)}"}""")
        }
    }
}
val bundledDatapackSandboxJar = layout.projectDirectory.file("src/main/resources/dps/datapack-sandbox-cli.jar")
val datapackSandboxSourceJar = providers.gradleProperty("datapackSandboxCliJar")
    .orElse(providers.environmentVariable("DPS_CLI_JAR"))

tasks.register<Copy>("updateDatapackSandboxService") {
    group = "build"
    description = "Updates the checked-in Datapack Sandbox command service from -PdatapackSandboxCliJar."
    from(datapackSandboxSourceJar)
    into(bundledDatapackSandboxJar.asFile.parentFile)
    rename { "datapack-sandbox-cli.jar" }
    doFirst {
        if (!datapackSandboxSourceJar.isPresent) {
            throw GradleException("Set -PdatapackSandboxCliJar=<path> or DPS_CLI_JAR before updating the bundled service")
        }
    }
}

tasks {
    processResources {
        dependsOn(formatterBundle, toolchainManifest, stdlibSources)
        from(compilerCli) { into("toolchain"); rename { "mcfpp-cli.jar" } }
        inputs.files(compilerSources)
        from({ zipTree(compilerSources.get().singleFile) }) { into("compiler-sources") }
        from(formatterBundle.flatMap { it.archiveFile }) { into("toolchain") }
        from(layout.buildDirectory.file("bundled-tools/toolchain.json")) { into("toolchain") }
        inputs.files(languageServerJar)
        inputs.file(bundledDatapackSandboxJar)
        from(languageServerJar) {
            into("server")
            rename { "mcfpp-language-server.jar" }
        }
        inputs.files(stdlibSources)
        from({ zipTree(stdlibSources.get().singleFile) }) { include("stdlib/**") }
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.load.plugins.id", "com.intellij.java,com.intellij.modules.json,org.jetbrains.kotlin,top.mcfpp.language")
    }
}
