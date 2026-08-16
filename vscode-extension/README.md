# MCFPP Language for Visual Studio Code

This preview extension provides project-aware language support for MCFPP through the bundled MCFPP language server.

## Features

- Diagnostics, completion, hover documentation, and signature help
- Go to definition/declaration/type definition/implementation and find references
- Document and workspace symbols, rename, formatting, folding, and selection ranges
- Call and type hierarchies, inlay hints, and semantic highlighting
- MCFPP project, namespace, import, standard-library, and dependency indexing
- Embedded Minecraft command diagnostics, completion, and semantic tokens through the Spyglass mcfunction extension
- Optional Java/MNI navigation, completion, and hover support when the Red Hat Java extension is installed

## Requirements

- Java 21 or newer available through `JAVA_HOME` or `PATH`
- The [Minecraft command syntax extension](https://marketplace.visualstudio.com/items?itemName=MinecraftCommands.syntax-mcfunction), declared as `MinecraftCommands.syntax-mcfunction`

The Java language extension is optional. MCFPP's core language features work without it.

## Preview status

This package is an early preview. Configuration and behavior may change before the first stable release. Please report reproducible issues at the [GitHub repository](https://github.com/Alumopper/mcfpp-language-support/issues).

## License

MIT
