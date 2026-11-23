# MCFPP Language Support (LSP & IntelliJ Plugin) Guidelines

## Grammar, Bytecode & Packaging Guardrails
- **Zero `.g4` copies**: Never duplicate ANTLR `.g4` files in this repository. Always consume `:mcfpp-syntax` and `:mcfpp-library-model` from `../../MCFPP` via Gradle composite build.
- **Java 21 compatibility**: `intellij-plugin` and `shared` MUST compile to Java 21 (`options.release.set(21)`, classfile major version `<= 65`). Never place JVM 25 compiler classes on the IntelliJ plugin runtime classpath.
- **Thin packaging**: Never unpack Fat JARs inside LSP startup, and never bundle duplicate `bin.mclib` inside `intellij-plugin` (`mcfpp-language-server-intellij.jar` reads stdlib via `-Dmcfpp.stdlib.jar`).
- **File size limit**: Keep every `src/main` source file `<= 700` lines. Do not grow grandfathered LSP files above their cap in `LspArchitectureGuardrailsTest`.

## Language Server (`language-server`) Performance Rules
- **Strip closed ASTs**: Analyses cached for closed workspace files or libraries in `MCFPPProjectIndex` MUST drop `compilationUnit` (`null`) so full ANTLR parse trees and token streams are not retained on the heap.
- **Scoped cache invalidation**: Body-only edits that do not change semantic surface (`isMacro`, `isNative`, signatures, imports) or symbol ranges MUST only evict the edited document cache—never wipe global `SymbolCaches` or other open documents' caches.
- **O(1) symbol lookups**: Maintain indexed sets on `SymbolCaches` for macro signatures and enum member names; avoid `O(W)` workspace scans per token or identifier.
- **Executor isolation**: Never block the keystroke `analysisExecutor` with workspace filesystem refreshes or `CompletableFuture.join()`; run `projectIndex.refresh` on its dedicated executor and shut it down cleanly on `shutdown()`.
- **No hot-loop `Regex(...)` or per-line ANTLR parsers**: Hoist static `Regex` constants and only fall back to per-line parsing when full-file parsing misses a symbol.

## IntelliJ Plugin (`intellij-plugin`) Threading & Lifecycle Rules
- **Never block the EDT**: Do not call `future.get(...)`, subprocess I/O, or Node IPC synchronously on EDT (including `FileDocumentManagerListener.beforeDocumentSaving`). Keep save formatting strictly asynchronous with stamp validation and re-entry guards.
- **Never pass `Project` as a parent `Disposable`**: Register disposables under a project-level `Disposable` service (e.g. `McfppProjectService`), and dispose short-lived process/session handlers on termination.
- **Share cached analyses**: Reuse `CachedValuesManager`-backed `McfppFileModels` helpers (`McfppFileModel`, `McfppSemanticTokens.Analysis`) across annotators and inspections.
- **Thread safety**: Guard `McfppNodeService` process/stream transitions with explicit synchronization; never mutate streams outside the lock.

## Documentation & Verification
- **Keep README concise**: Put detailed changelogs in `CHANGELOG.md` and technical guides in `intellij-plugin/DEVELOPMENT.md`. Do not dump per-PR paragraphs into `README.md`.
- **Verification**: Never weaken or delete test assertions. Run `LspArchitectureGuardrailsTest` and `McfppArchitectureGuardrailsTest` alongside affected tests.
