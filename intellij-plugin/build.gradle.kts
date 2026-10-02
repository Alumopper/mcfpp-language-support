import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

group = "top.mcfpp"
version = "0.4.2"

val localIdeaPath = providers.gradleProperty("ideaLocalPath")

dependencies {
    intellijPlatform {
        if (localIdeaPath.isPresent) {
            local(localIdeaPath.get())
        } else {
            intellijIdea("2025.3.3")
        }
        bundledPlugin("com.intellij.java")
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
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets.main {
    java.srcDir("../shared/src/main/java")
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
        inputs.file(languageServerJar)
        inputs.file(bundledDatapackSandboxJar)
        from(languageServerJar) {
            into("server")
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
        systemProperty("idea.load.plugins.id", "com.intellij.java,com.intellij.modules.json,top.mcfpp.language")
    }
}
