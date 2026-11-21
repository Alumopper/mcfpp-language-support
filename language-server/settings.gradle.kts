rootProject.name = "mcfpp-language-server"

includeBuild("../../MCFPP") {
    dependencySubstitution {
        substitute(module("top.mcfpp:mcfpp")).using(project(":"))
        substitute(module("top.mcfpp:mcfpp-syntax")).using(project(":mcfpp-syntax"))
        substitute(module("top.mcfpp:mcfpp-library-model")).using(project(":mcfpp-library-model"))
    }
}
