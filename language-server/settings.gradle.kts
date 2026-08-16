rootProject.name = "mcfpp-language-server"

// Develop the language server and compiler together. The compiler publication
// uses a custom artifact id, so the substitution must be declared explicitly.
// A standalone LSP checkout can still sync by resolving the published compiler.
val localCompilerBuild = file("../../MCFPP")
if (localCompilerBuild.resolve("settings.gradle.kts").isFile) {
    includeBuild(localCompilerBuild) {
        dependencySubstitution {
            substitute(module("top.mcfpp:mcfpp")).using(project(":"))
        }
    }
}
