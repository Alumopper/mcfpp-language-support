import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.plugins.antlr.AntlrTask
import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    java
    antlr
    id("org.jetbrains.intellij.platform")
}

group = "top.mcfpp"
version = "0.6.0"

val localIdeaPath = providers.gradleProperty("ideaLocalPath")

dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
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

// Use the language server's compiler grammar for annotation type expressions, too.
val typeGrammarOutput = layout.buildDirectory.dir("generated-src/mni-types")
tasks.generateGrammarSource { enabled = false }
val generateMniUnicode by tasks.registering(AntlrTask::class) {
    source = fileTree("../language-server/src/main/antlr4") { include("unicodeClass.g4") }
    outputDirectory = typeGrammarOutput.get().asFile
    arguments = listOf("-package", "top.mcfpp.intellij.typesyntax")
}
val generateMniLexer by tasks.registering(AntlrTask::class) {
    dependsOn(generateMniUnicode)
    source = fileTree("../language-server/src/main/antlr4") { include("mcfppLexer.g4") }
    outputDirectory = typeGrammarOutput.get().asFile
    arguments = listOf("-package", "top.mcfpp.intellij.typesyntax", "-lib", typeGrammarOutput.get().asFile.path)
}
val generateMniParser by tasks.registering(AntlrTask::class) {
    dependsOn(generateMniLexer)
    source = fileTree("../language-server/src/main/antlr4") { include("mcfppParser.g4") }
    outputDirectory = typeGrammarOutput.get().asFile
    arguments = listOf("-package", "top.mcfpp.intellij.typesyntax", "-lib", typeGrammarOutput.get().asFile.path)
}
sourceSets.main { java.srcDir(typeGrammarOutput) }
tasks.compileJava { dependsOn(generateMniParser) }

sourceSets.main {
    java.srcDir("../shared/src/main/java")
    java.source(objects.sourceDirectorySet("packResourceApi", "Shared vanilla resource identities and references").apply {
        srcDir("../../MCFPP/src/main/java")
        include("top/mcfpp/api/resources/**")
    })
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

val languageServerJar = layout.projectDirectory.file("../vscode-extension/server/mcfpp-language-server.jar")
val compilerStandardLibrary = layout.projectDirectory.dir("../../MCFPP/src/main/mcfpp")
val compilerConfiguration = layout.projectDirectory.file("../../MCFPP/mcfpp.json")
val compilerCli = providers.gradleProperty("mcfppCompilerJar").map(::file)
    .orElse(layout.projectDirectory.file("../../MCFPP/build/libs/MCFPP-1.0.2-SNAPSHOT.jar").asFile)
val compilerSources = providers.gradleProperty("mcfppCompilerSourcesJar").map(::file)
    .orElse(compilerCli.map { File(it.parentFile, it.nameWithoutExtension + "-sources.jar") })
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
    val cliFile = compilerCli.get()
    val sourcesFile = compilerSources.get()
    val serverFile = languageServerJar.asFile
    val formatterFile = formatterBundle.get().archiveFile.get().asFile
    val versionJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().executablePath.asFile
    val linterPackage = linterRoot.file("package.json").asFile
    val prettierPackage = linterRoot.file("node_modules/prettier/package.json").asFile
    val pluginVersion = project.version.toString()
    dependsOn(formatterBundle)
    inputs.file(compilerCli)
    inputs.file(compilerSources)
    inputs.file(languageServerJar)
    inputs.file(formatterBundle.flatMap { it.archiveFile })
    inputs.files(linterPackage, prettierPackage)
    inputs.property("pluginVersion", pluginVersion)
    outputs.file(target)
    doLast {
        fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val compilerHash = sha(cliFile)
        val versionProcess = ProcessBuilder(versionJava.path, "-jar", cliFile.path, "--version").start()
        val versionOutput = versionProcess.inputStream.bufferedReader().readText()
        check(versionProcess.waitFor() == 0) { "Unable to read the bundled CLI version: ${versionProcess.errorStream.bufferedReader().readText()}" }
        val compilerVersion = Regex("MCFPP ([^\\s]+)").find(versionOutput)?.groupValues?.get(1)
            ?: error("Unexpected CLI version response: $versionOutput")
        fun packageVersion(file: File) = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(file.readText())!!.groupValues[1]
        JarFile(serverFile).use { server ->
            check(server.manifest.mainAttributes.getValue("Mcfpp-Compiler-Sha256") == compilerHash) {
                "The language server and CLI must use the same compiler. Rebuild language-server: jar syncExtensionServer."
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
        dependsOn(formatterBundle, toolchainManifest)
        from(compilerCli) { into("toolchain"); rename { "mcfpp-cli.jar" } }
        from(compilerSources.map { zipTree(it) }) { into("compiler-sources") }
        from(formatterBundle.flatMap { it.archiveFile }) { into("toolchain") }
        from(layout.buildDirectory.file("bundled-tools/toolchain.json")) { into("toolchain") }
        inputs.file(languageServerJar)
        inputs.file(bundledDatapackSandboxJar)
        from(languageServerJar) {
            into("server")
        }
        from("../../MCFPP/src/main/resources/testing/assertions.mcfpp") {
            into("stdlib/src/main/mcfpp/test")
        }
        if (compilerStandardLibrary.asFile.isDirectory && compilerConfiguration.asFile.isFile) {
            inputs.dir(compilerStandardLibrary)
            inputs.file(compilerConfiguration)
            from(compilerStandardLibrary) {
                into("stdlib/src/main/mcfpp")
            }
            from(compilerConfiguration) {
                into("stdlib")
            }
        }
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.load.plugins.id", "com.intellij.java,com.intellij.modules.json,org.jetbrains.kotlin,top.mcfpp.language")
    }
}
