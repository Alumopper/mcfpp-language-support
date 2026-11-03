# MCFPP IntelliJ IDEA Plugin

The IntelliJ plugin provides first-class MCFPP support in IntelliJ IDEA by combining the existing language server with IDEA's Java PSI and project indexes.

Version 0.5 adds CLI-compatible **MCFPP / MCFPP MNI project wizards**, dependency synchronization, native run configurations, a task/artifact tool window, and bundled Prettier/Linter formatting on save. See the [complete development guide](DEVELOPMENT.md) for installation, settings, building and the two CLI sample projects.

Version 0.5.1 corrects whitespace diagnostics, closing-brace Enter indentation and console colors, simplifies the MNI template to explicit `@MNIFunction` bindings, and adds demand-linked datapacks and a **Dump IR** task. See the [development experience fixes](FEEDBACK-0.5.1.md).

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
- Dimmed, initially collapsed inactive Minecraft version branches, refreshed when `mcfpp.toml` or `mcfpp.json` is saved
- Indexed cross-file navigation and completion for functions, data types, aliases, members, parameters, and variables, including attached standard-library sources
- Structure view, Navigate | Symbol/Class integration, code folding, Find Usages, in-place rename, and duplicate declaration inspection
- Automatic brace/quote pairing, continuation indentation, generic/read-only angle formatting, configurable code style, file templates, and MCFPP live templates (`fn`, `nfn`, `data`, `object`, `enum`, `ctor`, `field`, `if`, `for`)
- Parameter information, completion auto-imports, and intentions for creating missing functions/types or importing declarations
- Java PSI completion and signature-aware navigation for native MNI function targets when the bundled Java plugin is enabled
- Ctrl+B and gutter navigation from MCFPP native declaration names and `@From` data declarations directly to Java
- Native binding inspection for missing classes/methods, `@MNIFunction` signature mismatches, non-static methods, and invalid Java argument counts
- Type completion, diagnostics, highlighting, documentation, navigation, Find Usages, and rename inside Java/Kotlin MNI annotation strings
- Java PSI completion and navigation for quoted or unquoted `@From(...)` targets
- Direct resolution against project sources, dependencies, libraries, and the configured JDK
- Automatic MNI and MCFPP standard-library source attachment from a nearby compiler checkout, `MCFPP_HOME`, or `-Dmcfpp.sources.path=...`
- Implicit `mcfpp.lang`, `mcfpp.sys`, and `mcfpp` resolution, including navigation from built-ins such as `print` to standard-library source
- The language server JAR is bundled with the plugin; users do not configure an external server

The native unresolved-reference inspection handles complete, active code after indexing. Generic and inherited declaration contexts, qualified members, incomplete syntax, imports whose sources are unavailable, and projects with compiler archive/include dependencies continue to use LSP diagnostics. You can disable **MCFPP | Unresolved MCFPP reference** in **Settings | Editor | Inspections** to use LSP diagnostics throughout. Creating missing declarations follows the project's code style and only applies to unqualified references in the current file.

`::jvm` is an MCFPP value conversion operator, not a Java fully-qualified name. It remains handled by the shared MCFPP language server rather than the Java PSI bridge.

### Types in MNI annotations

The following literal string fields use MCFPP type syntax in both Java and Kotlin:

| Annotation | Type fields |
|---|---|
| `MNIFunction` | `normalParams`, `readOnlyParams`, `caller`, `returnType`; `genericType` declares method-scoped type parameters |
| `MNIMacro` | `returnType` |
| `MacroParam` | `type`, including callback signatures with `ref`, `const`, and `inline` parameter modes |
| `MNIOperator` | `paramType`, `returnType` |

Use Ctrl+Space inside a type string for builtins, generic parameters, project types, and attached library types. Project/library completion inserts namespace-qualified MCFPP names without adding Java/Kotlin imports. Ctrl+B and Quick Documentation resolve source types; Find Usages and Rename include annotation references. A generic parameter declared by `genericType` can also be renamed within its method.

The parser is generated from the same ANTLR grammar as the language server. Diagnostics identify malformed syntax, unresolved/ambiguous names, and invalid readonly/type argument counts. Empty unary-operator `paramType` and native parameter defaults such as `"int = 1"` are supported. Rename preserves default expressions and unrelated strings such as `identifier`, `tag`, and parameter names. Java/Kotlin string escapes are decoded with source ranges mapped back to the original literal.

## Requirements

- JetBrains IntelliJ IDEA 2025.3 or newer (SDK baseline: 2025.3.3, build 253.31033.145; UI acceptance: 2026.2.2)
- JDK 21 for the bundled CLI and Java MNI development
- Node.js 20 or newer for bundled Prettier and Linter services
- Java 25 available on `PATH` for Datapack Sandbox-powered Minecraft command completion; alternatively set `MCFPP_DPS_JAVA` or `-Dmcfpp.dps.java.path=/path/to/java`

The JetBrains LSP API requires an IDEA distribution providing `com.intellij.modules.lsp`; it is unavailable in open-source builds and Android Studio. The Java plugin is an optional dependency: Java PSI-based MNI features and project wizards activate when it is available. The bundled IDEA JSON module is optional in the same way; only datapack JSON schema features are omitted when it is unavailable.

Minecraft command support runs in a separate, lazily started Java process and does not depend on IDEA's Java plugin. The profile comes from the top-level `version` in `mcfpp.toml` (preferred) or `mcfpp.json`; projects without one use `1.21.8`.

The bundled service includes the `26.3` profile and native float `compute` commands. MCFPP version conditions, unary minus, and compound assignments are supported by the shared language server and native declaration model.

Datapack Sandbox currently exposes command completion and command checking through its JSONL service. `.mcfunction` uses that semantic command tree directly; datapack JSON uses bundled path-sensitive JSON Schemas because DPS does not currently expose a JSON completion protocol.

## Build and run

First build the sibling MCFPP CLI, install the sibling MCFPP-Linter dependencies, and build/synchronize the language server as described in [DEVELOPMENT.md](DEVELOPMENT.md). Packaging verifies that the bundled CLI and LSP compiler fingerprints match.

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
