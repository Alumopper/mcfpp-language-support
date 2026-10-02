# MCFPP IntelliJ IDEA Plugin

The IntelliJ plugin provides first-class MCFPP support in IntelliJ IDEA by combining the existing language server with IDEA's Java PSI and project indexes.

## Features

- All LSP-backed MCFPP diagnostics, completion, navigation, symbols, formatting, refactoring, hints, and semantic highlighting
- Immediate lexical and semantic highlighting for declarations, types, functions, variables, commands, selectors, annotations, documentation, and literals
- Datapack Sandbox 1.1.0-powered completion for raw Minecraft command lines, including exact nested replacement ranges for selectors, NBT, text components, resources, and version-scoped command trees
- Target-selector-aware autopopup on `@`, `[`, `,`, `=` and `:`, with prioritized icons and distinct labels for selectors, selector fields, command branches, blocks, items, entities, resources, and values
- First-class vanilla `.mcfunction` editing with DPS completion and diagnostics, line comments, function macros, and the same exact replacement behavior as embedded MCFPP commands
- Path-sensitive vanilla datapack JSON completion and validation for `pack.mcmeta`, tags, advancements, recipes, predicates, loot tables, item modifiers, and data-driven registries through IDEA's optional JSON module
- Background syntax diagnostics for static commands, plus command-specific highlighting for roots, subcommands, ordinary arguments, resource locations, selector fields/values, coordinates, macros, structured data, and `${...}` MCFPP interpolation
- Declaration-specific completion with distinct namespace/type labels, function overload signatures, existing import aliases, and exact imports for the selected candidate
- Alt+Enter import selection for ambiguous names; conflicting names use a namespace-qualified reference
- Conservative **Code | Optimize Imports** sorting and deduplication inside comment/directive-separated blocks, retaining unused imports and aliases
- Native unresolved-reference inspection with import and declaration-creation fixes; LSP diagnostics remain available for compiler-specific resolution and when the inspection is disabled
- Unused import warnings with dimmed text and a quick fix that preserves surrounding comments and version directives
- Dimmed, initially collapsed inactive Minecraft version branches, refreshed when `mcfpp.json` is saved
- Indexed cross-file navigation and completion for functions, data types, aliases, members, parameters, and variables, including attached standard-library sources
- Structure view, Navigate | Symbol/Class integration, code folding, Find Usages, in-place rename, and duplicate declaration inspection
- Automatic brace/quote pairing, continuation indentation, generic/read-only angle formatting, configurable code style, file templates, and MCFPP live templates (`fn`, `nfn`, `data`, `object`, `enum`, `ctor`, `field`, `if`, `for`)
- Parameter information, completion auto-imports, and intentions for creating missing functions/types or importing declarations
- Java PSI completion and signature-aware navigation for native MNI function targets when the bundled Java plugin is enabled
- Ctrl+B and gutter navigation from MCFPP native declaration names and `@From` data declarations directly to Java
- Native binding inspection for missing classes/methods, `@MNIFunction` signature mismatches, non-static methods, and invalid Java argument counts
- Java PSI completion and navigation for quoted or unquoted `@From<...>` targets
- Direct resolution against project sources, dependencies, libraries, and the configured JDK
- Automatic MNI and MCFPP standard-library source attachment from a nearby compiler checkout, `MCFPP_HOME`, or `-Dmcfpp.sources.path=...`
- Implicit `mcfpp.lang`, `mcfpp.sys`, and `mcfpp` resolution, including navigation from built-ins such as `print` to standard-library source
- The language server JAR is bundled with the plugin; users do not configure an external server

The native unresolved-reference inspection handles complete, active code after indexing. Generic and inherited declaration contexts, qualified members, incomplete syntax, imports whose sources are unavailable, and projects with compiler archive/include dependencies continue to use LSP diagnostics. You can disable **MCFPP | Unresolved MCFPP reference** in **Settings | Editor | Inspections** to use LSP diagnostics throughout. Creating missing declarations follows the project's code style and only applies to unqualified references in the current file.

`::jvm` is an MCFPP value conversion operator, not a Java fully-qualified name. It remains handled by the shared MCFPP language server rather than the Java PSI bridge.

## Requirements

- IntelliJ IDEA Ultimate 2025.3.x (tested against 2025.3.3, build 253.31033.145)
- A project SDK and Gradle JVM running Java 21 or newer for MNI development (Gradle 9.1+ is required when the Gradle JVM is Java 25)
- Java 25 available on `PATH` for Datapack Sandbox-powered Minecraft command completion; alternatively set `MCFPP_DPS_JAVA` or `-Dmcfpp.dps.java.path=/path/to/java`

The JetBrains LSP API is not available in IntelliJ IDEA open-source builds or Android Studio, so the plugin targets IntelliJ IDEA Ultimate. The Java plugin is an optional dependency: core MCFPP editing and LSP features still load when Java support is disabled, while Java PSI-based MNI features activate automatically when it is available. The bundled IDEA JSON module is optional in the same way; only datapack JSON schema features are omitted when it is unavailable.

Minecraft command support runs in a separate, lazily started Java process and does not depend on IDEA's Java plugin. The profile comes from the top-level `version` in `mcfpp.json`; projects without one use `1.21.8`.

The bundled service includes the `26.3` profile and native float `compute` commands. MCFPP version conditions, unary minus, and compound assignments are supported by the shared language server and native declaration model.

Datapack Sandbox currently exposes command completion and command checking through its JSONL service. `.mcfunction` uses that semantic command tree directly; datapack JSON uses bundled path-sensitive JSON Schemas because DPS does not currently expose a JSON completion protocol.

## Build and run

```powershell
cd intellij-plugin
./gradlew.bat updateDatapackSandboxService -PdatapackSandboxCliJar="C:/path/to/datapack-sandbox-cli.jar"
./gradlew.bat test buildPlugin verifyPlugin -PideaLocalPath="C:/path/to/IntelliJ IDEA"
```

When `ideaLocalPath` is omitted, Gradle downloads IntelliJ IDEA 2025.3.3. Using the property is recommended during development because compilation, tests, `runIde`, and plugin verification then use the exact local installation.

To start a development IDE:

```powershell
./gradlew.bat runIde `
    --args="C:/absolute/path/to/lsp/examples/complete-mcfpp-project" `
    -PideaLocalPath="C:/path/to/IntelliJ IDEA"
```

Passing the example project at startup keeps it inside the development sandbox. Opening it later with **File | Open** can create a separate regular IDE window that is not the plugin host. The plugin archive is generated under `build/distributions`.
