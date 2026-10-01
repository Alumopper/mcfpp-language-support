# MCFPP Language Support

> Preview software: the language server and editor integrations are under active development. Please report reproducible issues through GitHub Issues.

This repository provides an MCFPP language server built with Kotlin, ANTLR 4, and LSP4J, together with extensions for Visual Studio Code and IntelliJ IDEA. Language behavior follows the [MCFPP documentation](https://www.mcfpp.top/) and the compiler grammar.

## Editor features

- Incremental document synchronization, debounced background analysis, and syntax/semantic diagnostics
- Workspace and multi-root indexing, project namespaces, imports, standard-library sources, and `.mclib`/JAR/ZIP dependencies
- Context-aware completion, hover documentation, overloads, generic functions/types, constructors, and signature help
- Definition, declaration, type-definition, implementation, reference, and document-highlight navigation
- Safe rename with prepare-rename validation, including import aliases
- Document/workspace symbols, folding, selection ranges, linked editing, call hierarchy, and type hierarchy
- Full-document and range formatting driven by syntax tokens
- Inlay hints for inferred types and argument names
- Full-document and range semantic tokens
- Compiler-compatible arithmetic: unary minus, compound assignments (`+=`, `-=`, `*=`, `/=`, `%=`), and continuation after a trailing operator
- Minecraft version directives (`#if`, `#elif`, `#else`, `#endif`), directive completion/highlighting, and diagnostics for invalid conditions or nesting
- Minecraft command diagnostics, completion, selectors, resource locations, and semantic highlighting
- Java/MNI navigation and completion when Java support is available; core MCFPP support does not require a Java editor plugin
- Semantic diagnostics and quick fixes for calls, types, returns, visibility, unresolved members, and duplicate declarations

## Components

```text
language-server/                  Kotlin LSP server, ANTLR grammar, and tests
shared/                           Version preprocessing shared by the LSP and IntelliJ
vscode-extension/                 VS Code client, syntax highlighting, and bundled server
intellij-plugin/                  IntelliJ IDEA plugin, LSP client, MNI support, and datapack editing
examples/complete-mcfpp-project/  End-to-end MCFPP and Java/MNI sample project
```

### Visual Studio Code

The VS Code extension recognizes `.mcfpp` files and uses `MinecraftCommands.syntax-mcfunction` for embedded Minecraft command language support. `redhat.java` is an optional enhancement: MCFPP editing works without it, while Java/MNI navigation and completion are enabled automatically when it is installed.

### IntelliJ IDEA

The IntelliJ plugin provides MCFPP language services, Java PSI-based MNI navigation, standard-library source navigation, Datapack Sandbox-powered command completion, vanilla `.mcfunction` support, and path-sensitive datapack JSON schemas. Java and JSON integrations are optional plugin fragments; the core MCFPP plugin remains loadable without them.

The current preview targets IntelliJ IDEA Ultimate 2025.3.x because JetBrains' LSP API is not available in IntelliJ IDEA Community Edition or Android Studio.

## Build and test

### Language server

Java 21 is required.

```powershell
cd language-server
./gradlew.bat clean test build
```

The executable fat JAR is written to `language-server/build/libs/mcfpp-language-server.jar`. The build also synchronizes it to the VS Code extension's bundled `server` directory.

### VS Code extension

Node.js and npm are required.

```powershell
cd vscode-extension
npm ci
npm run compile
npx @vscode/vsce package
```

Open the repository root or `vscode-extension` directory in VS Code, select `Run MCFPP Extension (Example Project)`, and press `F5`. The extension development host opens the complete example project directly.

### IntelliJ IDEA plugin

Gradle 9, Java 21, and IntelliJ IDEA Ultimate 2025.3.x are required.

```powershell
cd intellij-plugin
./gradlew.bat test buildPlugin verifyPlugin -PideaLocalPath="C:/path/to/IntelliJ IDEA"
```

The plugin archive is generated under `intellij-plugin/build/distributions`. Running `runIde` with the same `ideaLocalPath` tests against the exact locally installed IDEA API.

## Project detection

The language server reads compiler-compatible project JSON settings, including `namespace`, `sourcePath`, `targetPath`, `version`, `include`/`includes`, `jar`/`jars`, and `-ignoreStdLib` in `compileArgs`. Without a project configuration, it treats the workspace or `src/main/mcfpp` as the source root.

Version conditions use the project's `version` (default `1.21.8`). Only the selected branches contribute declarations, imports, references, and semantic diagnostics. Saving a changed target version refreshes open documents and cached workspace files. IntelliJ's native declaration model uses the same version filtering. Conditions may be nested and compare integer version segments using `==`, `!=`, `<`, `<=`, `>`, or `>=`; logical combinations and abbreviated old versions such as `21.6` are rejected.

```mcfpp
#if MC >= 26.3
func calculate(value as float) -> float {
    value += 12 /
        3;
    value %= 2.5f;
    return -value;
}
#else
func calculate(value as float) -> float {
    value += 4;
    return -value;
}
#endif
```

The compiler selects its native NBT float backend for `26.3`. These editor features retain the same `float` type and support the new arithmetic syntax. Newlines terminate statements unless the preceding line ends with an operator. A leading `/` starts a Minecraft command; `++` and `--` are unsupported.

## License

Licensed under the [MIT License](LICENSE).
