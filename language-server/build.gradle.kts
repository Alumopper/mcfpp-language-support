import org.gradle.api.plugins.antlr.AntlrTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("antlr")
    application
}

group = "com.simplelanguage"
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
val antlrPackage = "com.simplelanguage.lsp"
val generatedAntlrDir = layout.buildDirectory.dir("generated-src/antlr/antlr4")

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    antlr("org.antlr:antlr4:$antlrVersion")
    implementation("org.antlr:antlr4-runtime:$antlrVersion")

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
    implementation("top.mcfpp:mcfpp:1.0.2-SNAPSHOT")

    implementation("org.slf4j:slf4j-simple:2.0.9")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.generateGrammarSource {
    enabled = false
}

val generateUnicodeClassGrammar by tasks.registering(AntlrTask::class) {
    outputDirectory = generatedAntlrDir.get().asFile
    arguments = listOf(
        "-visitor",
        "-listener",
        "-package", antlrPackage
    )
    source = fileTree("src/main/antlr4") {
        include("unicodeClass.g4")
    }
}

val generateMcfppLexerGrammar by tasks.registering(AntlrTask::class) {
    dependsOn(generateUnicodeClassGrammar)
    outputDirectory = generatedAntlrDir.get().asFile
    arguments = listOf(
        "-visitor",
        "-listener",
        "-package", antlrPackage,
        "-lib", generatedAntlrDir.get().asFile.absolutePath
    )
    source = fileTree("src/main/antlr4") {
        include("mcfppLexer.g4")
    }
}

val generateMcfppParserGrammar by tasks.registering(AntlrTask::class) {
    dependsOn(generateMcfppLexerGrammar)
    outputDirectory = generatedAntlrDir.get().asFile
    arguments = listOf(
        "-visitor",
        "-listener",
        "-package", antlrPackage,
        "-lib", generatedAntlrDir.get().asFile.absolutePath
    )
    source = fileTree("src/main/antlr4") {
        include("mcfppParser.g4")
    }
}

val generateSimpleLanguageGrammar by tasks.registering(AntlrTask::class) {
    outputDirectory = generatedAntlrDir.get().asFile
    arguments = listOf(
        "-visitor",
        "-listener",
        "-package", antlrPackage,
        "-lib", file("src/main/antlr4").absolutePath
    )
    source = fileTree("src/main/antlr4") {
        include("SimpleLanguage.g4")
    }
}

val generateAllGrammarSources by tasks.registering {
    dependsOn(generateMcfppParserGrammar, generateSimpleLanguageGrammar)
}

sourceSets {
    main {
        java {
            srcDirs("src/main/kotlin", "build/generated-src/antlr/antlr4")
        }
        kotlin {
            srcDirs("src/main/kotlin", "build/generated-src/antlr/antlr4")
        }
    }
}

application {
    mainClass.set("com.simplelanguage.lsp.MainKt")
}

tasks {
    compileJava {
        dependsOn(generateAllGrammarSources)
    }

    compileKotlin {
        dependsOn(generateAllGrammarSources)
    }

    compileTestKotlin {
        dependsOn(generateTestGrammarSource)
    }

    jar {
        archiveFileName.set("mcfpp-language-server.jar")
        manifest {
            attributes["Main-Class"] = application.mainClass.get()
            // Runtime dependencies such as Log4j ship Java 9+ implementations in
            // META-INF/versions. Preserve their multi-release behaviour in the fat JAR.
            attributes["Multi-Release"] = "true"
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        from({
            configurations.runtimeClasspath.get().map { dependency ->
                if (dependency.isDirectory) dependency else zipTree(dependency)
            }
        })
    }

    test {
        useJUnitPlatform()
    }
}

val syncExtensionServer by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("../vscode-extension/server"))
}

tasks.build {
    finalizedBy(syncExtensionServer)
}
