# Changelog

## Unreleased

- Highlight resolved Macro functions and ordinary MNI functions separately in IDEA, with independent color settings and light/dark defaults. Preserve classification for declarations, calls and import aliases, and publish `macro` / `mniFunction` semantic tokens through LSP metadata.
- Synchronize IDEA and LSP with structural object types, intersections/unions, `new` factories and `?.` / `?:` / `!!`. Provide factory signatures, navigation, return-literal field completion and migration diagnostics for `constructor` and factory `this`. Data inheritance contributes fields; explicit literals accept additional fields and require all nonoptional fields, including fields with initializers. Read the compiler's object/literal library metadata and bundle matching CLI/LSP artifacts.
- Validate contextual data-template literals in variables and function arguments, including defaults, inheritance, nested templates, lists, generic types, and compiled library schemas. Complete unused accessible fields with `name: value` insertion and field documentation in IDEA and LSP; resolve literal keys to their declarations.
- Fix duplicate IDEA documentation by yielding to an available LSP documentation target and retaining native documentation when LSP hover has no result.
- Resolve chained data-template members to the final receiver type in IDEA, including inherited field completion and navigation. Keep LSP template members scoped to their declaring namespace so custom types do not pick up members from same-named standard-library types.
- Resolve and complete `nbt.merge(d: nbt): nbt` in IDEA and the language server, including multiline compound receivers, chained results, navigation, and argument diagnostics. Reconstruct imported entity templates' intrinsic `summon` members for `super.summon(...)`.
- Synchronize the ANTLR grammar with the compiler's command blocks (`/{ ... }`), entity templates (`entity Name: Parent`, `entity var`, `entity func`), selector callbacks, member keywords, constructor delegation, and NBT keys.
- Add `EntityRef<T>` editor semantics: template members and entity members are completed separately; `summon()` and typed entity factories infer the reference type; display/entity base members remain available through inheritance.
- Recognize `forEach`/`forEachAt` entity context entries and keep `this` typed as `EntityRef<T>` inside `entity func` bodies.
- Parse and format command blocks in the language server, including `${...}` references, and classify command-block lines for IDEA lexer, command completion, highlighting, folding, and brace matching.
- Update Kotlin-first MNI support: macros are discovered from explicit `func ... = Provider.method` declarations, `@MNIMacro(normalParams, returnType)` is validated, and parameter modes are inferred from JVM `Value`/`Ref`/`Const`/`InlineFn` wrappers instead of the removed `params` array. `injectedBy` no longer contributes macros.
- Fix IDEA validation issues: keep LSP undefined-symbol diagnostics when native inspection cannot run, diagnose missing declared types, and offer native declaration/import fixes on LSP errors and at identifier ends.
- Resolve imported type aliases in assignment, argument, and return checks, and prevent unrelated members from resolving as unqualified names.
- Warn and dim unused imports; remove individual unused imports without losing attached comments or version directives.
- Dim and initially fold inactive Minecraft version branches, including nested conditions; refresh open editors after configuration saves.
- Avoid attaching project sources as external libraries and retain the packaged standard library when discovered sources are not yet available in the VFS.

- Make IDEA completion candidates declaration-specific, retaining namespace collisions and overload signatures, reusing aliases, and importing exactly the selected symbol.
- Offer multiple Alt+Enter import candidates and use qualified references for name conflicts.
- Add conservative IDEA Optimize Imports support that preserves unused imports, aliases, comments, and conditional branches.
- Add native unresolved-reference inspection with import/create fixes and stable `mcfpp.undefined-symbol` LSP diagnostic codes for selective deduplication and fallback.
- Respect nested local scopes in IDEA resolution and duplicate checks; guard declaration creation for qualified references and apply project code style.

- Synchronize the parser with the compiler's compound assignment, unary minus, and newline rules.
- Analyze active Minecraft version branches using `mcfpp.json`'s `version`, including nested conditions, original source ranges, directive diagnostics, and refresh after target version changes.
- Share version preprocessing between the language server and IntelliJ's native declaration model.
- Add version directive completion and highlighting in both editor integrations.
- Update the bundled Datapack Sandbox service to support the compiler's `26.3` target, including native float `compute` command completion and validation.
- Keep VS Code arithmetic `/3` and `/=` outside Minecraft command highlighting; commands begin at the start of a line.

## v0.4.1-preview.1 — 2026-08-17

This is the first public preview of the unified MCFPP language tooling repository.

### Included artifacts

- Standalone MCFPP language server fat JAR
- IntelliJ IDEA plugin 0.4.1 for IntelliJ IDEA Ultimate 2025.3.x
- Visual Studio Code extension preview package

### Highlights

- Project-aware MCFPP diagnostics, completion, navigation, symbols, formatting, refactoring, inlay hints, and semantic highlighting
- Standard-library and external dependency indexing
- Optional Java/MNI completion and navigation without making Java support a core dependency
- IntelliJ IDEA support for MNI bindings, standard-library source navigation, Minecraft command completion, target selectors, `.mcfunction`, and datapack JSON files
- Distinct semantic highlighting for Minecraft command roots, selectors, arguments, and namespaced resource locations
- Visual Studio Code integration with embedded Minecraft command language support

### Preview notes

- APIs, configuration, and packaging may change before a stable release.
- The IntelliJ plugin currently requires IntelliJ IDEA Ultimate 2025.3.x.
- Datapack Sandbox command completion requires Java 25 in the IntelliJ environment.
- Please report reproducible issues with IDE/build versions, a minimal project, and relevant logs.

### Installation

- IntelliJ IDEA: open **Settings | Plugins**, choose **Install Plugin from Disk**, and select `mcfpp-intellij-0.4.1.zip`.
- Visual Studio Code: run **Extensions: Install from VSIX...** and select `mcfpp-vscode-1.0.0-preview.1.vsix`.
- Standalone server: start `mcfpp-language-server-v0.4.1-preview.1.jar` with Java 21 or newer using `java -jar` and communicate over standard input/output.

Verify downloaded files with `SHA256SUMS.txt` from the GitHub release assets.
