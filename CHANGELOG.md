# Changelog

## Unreleased

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
