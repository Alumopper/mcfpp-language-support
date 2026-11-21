import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "mcfpp-intellij"

includeBuild("../../MCFPP") {
    dependencySubstitution {
        substitute(module("top.mcfpp:mcfpp")).using(project(":"))
        substitute(module("top.mcfpp:mcfpp-syntax")).using(project(":mcfpp-syntax"))
        substitute(module("top.mcfpp:mcfpp-library-model")).using(project(":mcfpp-library-model"))
    }
}
includeBuild("../language-server")
