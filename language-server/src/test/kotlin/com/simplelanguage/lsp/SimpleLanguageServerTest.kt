package com.simplelanguage.lsp

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleLanguageServerTest {
    @Test
    fun `target version changes refresh open document diagnostics and symbols`(@TempDir root: Path) {
        val config = root.resolve("mcfpp.json")
        config.writeText("""{"version":"26.3","compileArgs":["-ignoreStdLib"]}""")
        val uri = root.resolve("versions.mcfpp").toUri().toString()
        val analyzed = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val switching = AtomicBoolean(false)
        val latest = AtomicReference<List<Diagnostic>>()
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                latest.set(params.diagnostics)
                if (switching.get()) {
                    val current = server.getTextDocumentService()
                        .documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()
                        .firstOrNull { it.right.name == "selected" }
                    if (current?.right?.selectionRange?.start?.line == 12) switched.countDown()
                }
                true
            } else false
        })
        server.initialize(InitializeParams().apply { rootUri = root.toUri().toString() }).get()
        val service = server.getTextDocumentService() as MCFPPTextDocumentService
        val source = """
            #if MC >= 26.3
            func selected(value as float) -> float {
                var negative = -value
                var result as float = -negative
                result += 2
                result -= 1.0f
                result *= 3
                result /= 2
                result %= 1.5f
                return -result
            }
            #else
            func selected() -> int { return 1; }
            #endif
        """.trimIndent()
        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, source)))
            assertTrue(analyzed.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList(), latest.get().map { it.message })
            val symbols = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()
                .filter { it.right.name == "selected" }
            assertEquals(1, symbols.size)
            assertEquals(1, symbols.single().right.selectionRange.start.line)
            val tokens = decodeSemanticTokens(service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get().data)
            assertEquals(setOf(0, 11, 13), tokens.filter { it.type == "macro" }.map { it.line }.toSet())

            config.writeText("""{"version":"26.2","compileArgs":["-ignoreStdLib"]}""")
            switching.set(true)
            service.refreshProjectIndex(listOf(config.toUri().toString()))
            assertTrue(switched.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList(), latest.get().map { it.message })
            val oldSymbols = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()
                .filter { it.right.name == "selected" }
            assertEquals(12, oldSymbols.single().right.selectionRange.start.line)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `completes version directives with a replacement edit`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///directives.mcfpp"
        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, "  #i")))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS))
            val completions = service.completion(CompletionParams(TextDocumentIdentifier(uri), Position(0, 4))).get().left
            assertEquals(listOf("#if", "#elif", "#else", "#endif"), completions.map { it.label })
            val edit = completions.first().textEdit.left
            assertEquals(Range(Position(0, 2), Position(0, 4)), edit.range)
            assertEquals("#if MC >= \${1:26.3}", edit.newText)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `advertises incremental synchronization with save text`() {
        val server = SimpleLanguageServer()
        try {
            val capabilities = server.initialize(InitializeParams()).get().capabilities
            val sync = capabilities.textDocumentSync

            assertTrue(sync.isRight)
            assertEquals(TextDocumentSyncKind.Incremental, sync.right.change)
            assertTrue(sync.right.openClose)
            assertTrue(sync.right.save.isRight)
            assertTrue(sync.right.save.right.includeText)
            assertTrue(capabilities.renameProvider.isRight)
            assertTrue(capabilities.renameProvider.right.prepareProvider)
            assertTrue(capabilities.foldingRangeProvider.left)
            assertTrue(capabilities.documentFormattingProvider.left)
            assertTrue(capabilities.documentRangeFormattingProvider.left)
            assertEquals(listOf(CodeActionKind.QuickFix), capabilities.codeActionProvider.right.codeActionKinds)
            assertEquals(listOf("(", "<", ","), capabilities.signatureHelpProvider.triggerCharacters)
            assertEquals(listOf(","), capabilities.signatureHelpProvider.retriggerCharacters)
            assertTrue(capabilities.callHierarchyProvider.left)
            assertTrue(capabilities.typeHierarchyProvider.left)
            assertTrue(capabilities.inlayHintProvider.isRight)
            assertFalse(capabilities.inlayHintProvider.right.resolveProvider)
            assertTrue(capabilities.semanticTokensProvider.full.left)
            assertTrue(capabilities.semanticTokensProvider.range.left)
            assertEquals(MCFPPSemanticTokenLegend.tokenTypes, capabilities.semanticTokensProvider.legend.tokenTypes)
            assertEquals(MCFPPSemanticTokenLegend.tokenModifiers, capabilities.semanticTokensProvider.legend.tokenModifiers)
            assertTrue(capabilities.workspace.workspaceFolders.supported)
            assertTrue(capabilities.workspace.workspaceFolders.changeNotifications.right)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `debounced analysis publishes only the latest document version`() {
        val latestDiagnostics = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(latestDiagnostics) { params ->
            params.diagnostics.any { it.message.contains("Duplicate declaration") }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///debounced.mcfpp"

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, "func initial(){}")))
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent("func stale(){}"))
                )
            )
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 3),
                    listOf(TextDocumentContentChangeEvent("func latest(){\nvar duplicate = 1;\nvar duplicate = 2;\n}"))
                )
            )
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent("func outOfOrder(){}"))
                )
            )

            assertTrue(latestDiagnostics.await(5, TimeUnit.SECONDS), "Latest diagnostics were not published")
            val symbols = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))).get()
                .mapNotNull { it.right?.name }
            assertTrue("latest" in symbols, "Document symbols: $symbols")
            assertFalse("stale" in symbols || "outOfOrder" in symbols, "Document symbols: $symbols")

            val prepared = service.prepareRename(
                PrepareRenameParams(TextDocumentIdentifier(uri), Position(0, 6))
            ).get()
            assertTrue(prepared.isSecond)
            assertEquals("latest", prepared.second.placeholder)

            val foldingRanges = service.foldingRange(
                FoldingRangeRequestParams(TextDocumentIdentifier(uri))
            ).get()
            assertTrue(foldingRanges.any { it.startLine == 0 && it.endLine == 3 }, "Folding ranges: $foldingRanges")

            val invalidRename = service.rename(
                RenameParams(TextDocumentIdentifier(uri), Position(0, 6), "not valid")
            ).get()
            assertTrue(invalidRename.changes.isNullOrEmpty())
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `changing a declaration republishes diagnostics for dependent open documents`() {
        val declarationAnalyzed = CountDownLatch(1)
        val initialDependentDiagnostics = CountDownLatch(1)
        val updatedDependentDiagnostics = CountDownLatch(1)
        val dependentPhase = AtomicReference(initialDependentDiagnostics)
        val dependentMessages = AtomicReference<List<String>>(emptyList())
        val declarationUri = "file:///declaration.mcfpp"
        val dependentUri = "file:///dependent.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(CountDownLatch(0)) { params ->
            when (params.uri) {
                declarationUri -> declarationAnalyzed.countDown()
                dependentUri -> {
                    dependentMessages.set(params.diagnostics.map { it.message })
                    dependentPhase.get().countDown()
                }
            }
            false
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val declarationV1 = "namespace demo; func convert(value as string) -> string { return value; }"
        val declarationV2 = "namespace demo; func convert(value as int) -> int { return value; }"
        val dependent = "namespace demo; func main() { var value as string = convert(\"ok\"); }"

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(declarationUri, "mcfpp", 1, declarationV1)))
            assertTrue(declarationAnalyzed.await(5, TimeUnit.SECONDS), "Declaration analysis did not finish")
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(dependentUri, "mcfpp", 1, dependent)))
            assertTrue(initialDependentDiagnostics.await(5, TimeUnit.SECONDS), "Dependent analysis did not finish")
            assertFalse(dependentMessages.get().any { it.contains("convert") }, "Diagnostics: ${dependentMessages.get()}")

            dependentPhase.set(updatedDependentDiagnostics)
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(declarationUri, 2),
                    listOf(TextDocumentContentChangeEvent(declarationV2))
                )
            )

            assertTrue(
                updatedDependentDiagnostics.await(5, TimeUnit.SECONDS),
                "Changing a declaration did not republish dependent diagnostics"
            )
            assertTrue(dependentMessages.get().any { it.contains("convert") }, "Diagnostics: ${dependentMessages.get()}")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `rename does not cross local function scopes`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///rename-scopes.mcfpp"
        val text = """
            func first(){
                var value = 1;
                value = 2;
            }
            func second(){
                var value = 3;
                value = 4;
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val edit = service.rename(
                RenameParams(TextDocumentIdentifier(uri), Position(2, 6), "renamed")
            ).get()
            val editedLines = edit.changes.orEmpty()[uri].orEmpty().map { it.range.start.line }.toSet()

            assertEquals(setOf(1, 2), editedLines)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `signature help selects overload and tracks readonly and normal parameters`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///signature-help.mcfpp"
        val text = """
            func add(a as int, b as int) -> int { return a + b; }
            func add(a as string, b as string) -> string { return a + b; }
            func generic<limit as int>(value as int, fallback as int = 0) -> int { return value; }
            func main(){
                add("x", );
                generic<5>(1, );
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val overloadHelp = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), Position(4, 13))
            ).get()
            assertEquals(2, overloadHelp.signatures.size)
            assertEquals(1, overloadHelp.activeSignature)
            assertEquals(1, overloadHelp.activeParameter)
            assertTrue(overloadHelp.signatures[1].label.startsWith("add(a as string, b as string)"))

            val readonlyHelp = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), Position(5, 13))
            ).get()
            assertEquals(0, readonlyHelp.activeParameter)
            assertTrue(readonlyHelp.signatures.single().label.startsWith("generic<limit as int>"))

            val normalHelp = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), Position(5, 18))
            ).get()
            assertEquals(2, normalHelp.activeParameter)
            assertTrue(normalHelp.signatures.single().label.contains("fallback as int = 0"))
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `call diagnostics honor overloads readonly arguments and default parameters`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///call-diagnostics.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            func choose<limit as int>(value as int, fallback as int = 0) -> int { return value; }
            func convert(value as int) -> int { return value; }
            func convert(value as string) -> string { return value; }
            func makeString() -> string { return "x"; }
            func wrap(value as int) -> int { return value; }
            func wrap(value as string) -> string { return value; }
            func main() {
                var chosen as int = choose<"wrong">(1);
                var converted as string = convert("x");
                var nested as string = wrap(makeString());
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }

            assertFalse(messages.any { it.contains("Argument count mismatch for `choose`") }, "Diagnostics: $messages")
            assertTrue(
                messages.any { it.contains("Read-only argument type mismatch for `choose`") && it.contains("expected `int`") },
                "Diagnostics: $messages"
            )
            assertFalse(messages.any { it.contains("`convert`") }, "Diagnostics: $messages")
            assertFalse(messages.any { it.contains("`wrap`") || it.contains("`nested`") }, "Diagnostics: $messages")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `generic function calls substitute parameter and return types`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///generic-function-calls.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            func identity<T as Type>(value as T) -> T { return value; }
            func main() {
                var valid as int = identity<int>(1);
                var invalid as int = identity<int>("wrong");
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }

            assertFalse(messages.any { it.contains("`valid`") }, "Diagnostics: $messages")
            assertTrue(
                messages.any { it.contains("Argument type mismatch for `identity`") && it.contains("expected `int`") && it.contains("found `string`") },
                "Diagnostics: $messages"
            )

            val callLine = text.lines()[2]
            val position = Position(2, callLine.indexOf("(1") + "(1".length)
            val help = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), position)
            ).get()
            assertEquals("identity<T as Type>(value as T) -> T", help.signatures.single().label)
            assertEquals(1, help.activeParameter)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `generic type parameters appear in type completion`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///generic-type-completion.mcfpp"
        val text = """
            func identity<T as Type>(value as T) -> T {
                var copy as T = value;
                return copy;
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val line = text.lines()[1]
            val result = service.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(1, line.indexOf("T =")))
            ).get()
            val items = if (result.isLeft) result.left else result.right.items
            val typeParameter = items.single { it.label == "T" }

            assertEquals(org.eclipse.lsp4j.CompletionItemKind.TypeParameter, typeParameter.kind)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `duplicate diagnostics distinguish overloads from identical signatures`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///duplicate-overloads.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            func duplicate(value as int) {}
            func duplicate(other as int) {}
            func duplicate(value as string) {}
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val duplicateDiagnostics = publishedDiagnostics.get()
                .filter { it.message == "Duplicate declaration of `duplicate`" }
            assertEquals(1, duplicateDiagnostics.size, "Diagnostics: ${publishedDiagnostics.get().map { it.message }}")
            assertEquals(1, duplicateDiagnostics.single().range.start.line)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `return diagnostics enforce value and control flow contracts`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///return-diagnostics.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            func missing(flag as bool) -> int {
                if(flag) { return 1; }
            }
            func complete(flag as bool) -> int {
                if(flag) { return 1; } else { return 2; }
            }
            func needsValue() -> int { return; }
            func noValue() { return 1; }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }

            assertTrue(messages.any { it.contains("Function `missing` must return a value on every path") }, "Diagnostics: $messages")
            assertFalse(messages.any { it.contains("Function `complete`") }, "Diagnostics: $messages")
            assertTrue(messages.any { it.contains("Return value required: expected `int`") }, "Diagnostics: $messages")
            assertTrue(messages.any { it.contains("Void function cannot return a value") }, "Diagnostics: $messages")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `type diagnostics and overloads follow transitive inheritance`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///transitive-types.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            data Base {}
            data Middle: Base {}
            data Leaf: Middle {}
            func choose(value as string) -> string { return value; }
            func choose(value as Base) -> int { return 1; }
            func upcast(value as Leaf) -> Base { return value; }
            func main(value as Leaf) {
                var result as int = choose(value);
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }

            assertFalse(messages.any { it.contains("Return type mismatch") }, "Diagnostics: $messages")
            assertFalse(messages.any { it.contains("choose") || it.contains("result") }, "Diagnostics: $messages")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `type aliases and native functions participate in semantic diagnostics`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///native-alias-diagnostics.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            typealias int as Count;
            func nativeAdd(value as Count) -> Count = example.NativeBridge.add;
            func main() {
                var count as Count = 1;
                var result as Count = nativeAdd(count);
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            assertEquals(emptyList(), publishedDiagnostics.get().map { it.message })
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `default and explicit constructors resolve calls and signature help`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///constructor-calls.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            data Empty {}
            data Wrapped as int;
            data Explicit {
                constructor(value as string) {}
            }
            func main() {
                var empty as Empty = Empty();
                var wrapped as Wrapped = Wrapped(1);
                var explicit as Explicit = Explicit("x");
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            assertEquals(emptyList(), publishedDiagnostics.get().map { it.message })

            val wrappedLine = text.lines()[7]
            val helpPosition = Position(7, wrappedLine.indexOf("Wrapped(1)") + "Wrapped(1".length)
            val signature = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), helpPosition)
            ).get()
            assertEquals("Wrapped(value as int)", signature.signatures.single().label)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `generic constructor signature help and diagnostics substitute type parameters`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///generic-constructor-calls.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            data Box<T as Type> {
                constructor(value as T) {}
            }
            func main() {
                var valid as Box = Box<int>(1);
                var invalid as Box = Box<int>("wrong");
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }
            assertFalse(messages.any { it.contains("`valid`") || (it.contains("`Box`") && it.contains("found `int`")) }, "Diagnostics: $messages")
            assertTrue(
                messages.any { it.contains("Argument type mismatch for `Box`") && it.contains("expected `int`") && it.contains("found `string`") },
                "Diagnostics: $messages"
            )

            val readOnlyLine = text.lines()[4]
            val readOnlyPosition = Position(4, readOnlyLine.indexOf("<int") + "<int".length)
            val readOnlyHelp = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), readOnlyPosition)
            ).get()
            assertEquals("Box<T as Type>(value as T)", readOnlyHelp.signatures.single().label)
            assertEquals(0, readOnlyHelp.activeParameter)

            val normalPosition = Position(4, readOnlyLine.indexOf("(1") + "(1".length)
            val normalHelp = service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(uri), normalPosition)
            ).get()
            assertEquals("Box<T as Type>(value as T)", normalHelp.signatures.single().label)
            assertEquals(1, normalHelp.activeParameter)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `inherited members support completion navigation and call diagnostics`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///inherited-members.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            data Base {
                var field as int;
                func value() -> int { return this.field; }
            }
            data Middle: Base {}
            data Leaf: Middle {}
            func main(leaf as Leaf) {
                var fieldValue as int = leaf.field;
                leaf.value("wrong");
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            assertTrue(
                publishedDiagnostics.get().any { it.message.contains("Argument count mismatch for `value`") },
                "Diagnostics: ${publishedDiagnostics.get().map { it.message }}"
            )

            val fieldLine = text.lines()[7]
            val memberPosition = Position(7, fieldLine.indexOf("leaf.field") + "leaf.".length)
            val completionResult = service.completion(
                CompletionParams(TextDocumentIdentifier(uri), memberPosition)
            ).get()
            val completionLabels = if (completionResult.isLeft) {
                completionResult.left.map { it.label }
            } else {
                completionResult.right.items.map { it.label }
            }
            assertTrue("field" in completionLabels, "Completions: $completionLabels")
            assertTrue("value" in completionLabels, "Completions: $completionLabels")

            val definition = service.definition(
                DefinitionParams(TextDocumentIdentifier(uri), Position(7, fieldLine.indexOf("field;") + 1))
            ).get()
            assertEquals(1, definition.left.single().range.start.line)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `member access control filters completion and reports violations`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val backgroundLog = AtomicReference<String?>(null)
        val uri = "file:///member-access.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(
            RecordingLanguageClient(
                diagnosticsPublished = analyzed,
                onLogMessage = { backgroundLog.set(it.message) },
                matches = { params ->
                    if (params.uri == uri) {
                        publishedDiagnostics.set(params.diagnostics)
                        true
                    } else {
                        false
                    }
                }
            )
        )
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            data Base {
                private var hidden as int;
                protected var inherited as int;
                public var visible as int;
                func own(other as Base) { var allowed as int = other.hidden; }
            }
            data Child: Base {
                func ownChild(other as Child) {
                    var allowed as int = other.inherited;
                    var denied as int = other.hidden;
                }
            }
            data Secret {
                private constructor() {}
            }
            func main(base as Base, child as Child) {
                var publicValue as int = base.visible;
                var privateValue as int = base.hidden;
                var protectedValue as int = child.inherited;
                var secret as Secret = Secret();
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish: ${backgroundLog.get()}")
            val accessMessages = publishedDiagnostics.get().map { it.message }.filter { it.contains("not accessible here") }
            assertEquals(4, accessMessages.size, "Diagnostics: ${publishedDiagnostics.get().map { it.message }}")
            assertEquals(2, accessMessages.count { it.startsWith("Private member `hidden`") })
            assertEquals(1, accessMessages.count { it.startsWith("Protected member `inherited`") })
            assertEquals(1, accessMessages.count { it.startsWith("Private constructor `Secret`") })

            fun labelsAfter(lineNumber: Int, receiver: String): List<String> {
                val line = text.lines()[lineNumber]
                val position = Position(lineNumber, line.indexOf("$receiver.") + receiver.length + 1)
                val completion = service.completion(CompletionParams(TextDocumentIdentifier(uri), position)).get()
                return if (completion.isLeft) completion.left.map { it.label } else completion.right.items.map { it.label }
            }

            val externalLabels = labelsAfter(16, "base")
            assertTrue("visible" in externalLabels, "Completions: $externalLabels")
            assertFalse("hidden" in externalLabels || "inherited" in externalLabels, "Completions: $externalLabels")

            val ownerLabels = labelsAfter(4, "other")
            assertTrue(setOf("hidden", "inherited", "visible").all { it in ownerLabels }, "Completions: $ownerLabels")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `undefined diagnostics include members and namespace qualified calls`() {
        val analyzed = CountDownLatch(1)
        val publishedDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val uri = "file:///undefined-members.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == uri) {
                publishedDiagnostics.set(params.diagnostics)
                true
            } else {
                false
            }
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val text = """
            namespace sample;
            data Item { private var hidden as int; }
            func main(item as Item) {
                var first = item.missing;
                var second = item.hidden;
                sample:absent();
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")
            val messages = publishedDiagnostics.get().map { it.message }

            assertTrue("Undefined symbol `missing`" in messages, "Diagnostics: $messages")
            assertTrue("Undefined symbol `absent`" in messages, "Diagnostics: $messages")
            assertEquals(1, messages.count { it.contains("`hidden`") }, "Diagnostics: $messages")
            assertTrue(messages.single { it.contains("`hidden`") }.startsWith("Private member"))
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `completion respects namespace visibility and explicit qualification`() {
        val betaAnalyzed = CountDownLatch(1)
        val alphaAnalyzed = CountDownLatch(1)
        val betaUri = "file:///beta-completion.mcfpp"
        val alphaUri = "file:///alpha-completion.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(CountDownLatch(0)) { params ->
            when (params.uri) {
                betaUri -> betaAnalyzed.countDown()
                alphaUri -> alphaAnalyzed.countDown()
            }
            false
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val alphaText = """
            namespace alpha;
            func local() {}
            func main() { beta:secret(); }
        """.trimIndent()

        try {
            service.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(betaUri, "mcfpp", 1, "namespace beta; func secret() {}")
                )
            )
            assertTrue(betaAnalyzed.await(5, TimeUnit.SECONDS), "Beta analysis did not finish")
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(alphaUri, "mcfpp", 1, alphaText)))
            assertTrue(alphaAnalyzed.await(5, TimeUnit.SECONDS), "Alpha analysis did not finish")

            fun labelsAt(position: Position): List<String> {
                val result = service.completion(CompletionParams(TextDocumentIdentifier(alphaUri), position)).get()
                return if (result.isLeft) result.left.map { it.label } else result.right.items.map { it.label }
            }

            val callLine = alphaText.lines()[2]
            val defaultLabels = labelsAt(Position(2, callLine.indexOf("beta")))
            assertTrue("local" in defaultLabels, "Completions: $defaultLabels")
            assertFalse("secret" in defaultLabels, "Completions leaked beta namespace: $defaultLabels")

            val qualifiedLabels = labelsAt(Position(2, callLine.indexOf("beta:") + "beta:".length))
            assertTrue("secret" in qualifiedLabels, "Qualified completions: $qualifiedLabels")
            assertFalse("local" in qualifiedLabels, "Qualified completions: $qualifiedLabels")

            val definition = service.definition(
                DefinitionParams(TextDocumentIdentifier(alphaUri), Position(2, callLine.indexOf("secret") + 1))
            ).get().left.single()
            assertEquals(betaUri, definition.uri)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `import aliases resolve diagnostics completion and navigation`() {
        val libraryAnalyzed = CountDownLatch(1)
        val consumerAnalyzed = CountDownLatch(1)
        val consumerDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val libraryUri = "file:///alias-library.mcfpp"
        val consumerUri = "file:///alias-consumer.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(CountDownLatch(0)) { params ->
            when (params.uri) {
                libraryUri -> libraryAnalyzed.countDown()
                consumerUri -> {
                    consumerDiagnostics.set(params.diagnostics)
                    consumerAnalyzed.countDown()
                }
            }
            false
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val consumerText = """
            namespace consumer;
            import library:secret as convert;
            func main() { var result as string = convert(); }
        """.trimIndent()

        try {
            service.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(libraryUri, "mcfpp", 1, "namespace library; func secret() -> string { return \"ok\"; }")
                )
            )
            assertTrue(libraryAnalyzed.await(5, TimeUnit.SECONDS), "Library analysis did not finish")
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumerUri, "mcfpp", 1, consumerText)))
            assertTrue(consumerAnalyzed.await(5, TimeUnit.SECONDS), "Consumer analysis did not finish")
            assertEquals(emptyList(), consumerDiagnostics.get().map { it.message })

            val callLine = consumerText.lines()[2]
            val completion = service.completion(
                CompletionParams(TextDocumentIdentifier(consumerUri), Position(2, callLine.indexOf("convert")))
            ).get()
            val labels = if (completion.isLeft) completion.left.map { it.label } else completion.right.items.map { it.label }
            assertTrue("convert" in labels, "Completions: $labels")
            assertFalse("secret" in labels, "Completions: $labels")

            val definition = service.definition(
                DefinitionParams(TextDocumentIdentifier(consumerUri), Position(2, callLine.indexOf("convert") + 1))
            ).get().left.single()
            assertEquals(libraryUri, definition.uri)
            assertEquals(0, definition.range.start.line)

            val prepareRename = service.prepareRename(
                PrepareRenameParams(TextDocumentIdentifier(consumerUri), Position(2, callLine.indexOf("convert") + 1))
            ).get()
            assertEquals("convert", prepareRename.second.placeholder)

            val rename = service.rename(
                RenameParams(TextDocumentIdentifier(consumerUri), Position(2, callLine.indexOf("convert") + 1), "transform")
            ).get()
            val changes = rename.changes.orEmpty()
            val edits = changes.getValue(consumerUri)
            assertEquals(setOf(1, 2), edits.map { it.range.start.line }.toSet())
            assertTrue(edits.all { it.newText == "transform" })
            assertFalse(changes.containsKey(libraryUri), "Alias rename modified the imported declaration")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `member completion follows exact imports call results and chained receivers`() {
        val libraryAnalyzed = CountDownLatch(1)
        val consumerAnalyzed = CountDownLatch(1)
        val libraryUri = "file:///member-import-library.mcfpp"
        val consumerUri = "file:///member-import-consumer.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(CountDownLatch(0)) { params ->
            when (params.uri) {
                libraryUri -> libraryAnalyzed.countDown()
                consumerUri -> consumerAnalyzed.countDown()
            }
            false
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val library = """
            namespace models;
            data Child {
                var name as string;
            }
            data Factory {
                func create() -> Child { return Child(); }
            }
            func makeFactory() -> Factory { return Factory(); }
            func unrelated() {}
        """.trimIndent()
        val consumer = """
            namespace app;
            import models:Child;
            import models:Factory;
            import models:makeFactory;
            func main() {
                var factory as Factory;
                factory.
                makeFactory().
                makeFactory().create().
            }
        """.trimIndent()

        val indexedLibrary = MCFPPDocumentIndex.analyze(libraryUri, library)
        val indexedConsumer = MCFPPDocumentIndex.analyze(consumerUri, consumer)
        assertTrue(indexedLibrary.typeMembers["Factory"].orEmpty().any { it.name == "create" })
        assertTrue(indexedConsumer.variables["factory"].orEmpty().startsWith("Factory"))
        assertEquals("factory", indexedConsumer.receiverBefore(Position(6, consumer.lines()[6].length)))

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(libraryUri, "mcfpp", 1, library)))
            assertTrue(libraryAnalyzed.await(5, TimeUnit.SECONDS), "Library analysis did not finish")
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumerUri, "mcfpp", 1, consumer)))
            assertTrue(consumerAnalyzed.await(5, TimeUnit.SECONDS), "Consumer analysis did not finish")

            fun labelsAt(lineNumber: Int): List<String> {
                val line = consumer.lines()[lineNumber]
                val result = service.completion(
                    CompletionParams(TextDocumentIdentifier(consumerUri), Position(lineNumber, line.length))
                ).get()
                return if (result.isLeft) result.left.map { it.label } else result.right.items.map { it.label }
            }

            val variableMembers = labelsAt(6)
            assertTrue("create" in variableMembers, "Variable members: $variableMembers")
            assertFalse("unrelated" in variableMembers || "func" in variableMembers, "Leaked globals: $variableMembers")

            val callMembers = labelsAt(7)
            assertTrue("create" in callMembers, "Call-result members: $callMembers")
            assertFalse("unrelated" in callMembers || "func" in callMembers, "Leaked globals: $callMembers")

            val chainedMembers = labelsAt(8)
            assertTrue("name" in chainedMembers, "Chained members: $chainedMembers")
            assertFalse("unrelated" in chainedMembers || "func" in chainedMembers, "Leaked globals: $chainedMembers")
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `wildcard import aliases qualify completion without leaking globals`() {
        val libraryAnalyzed = CountDownLatch(1)
        val consumerAnalyzed = CountDownLatch(1)
        val libraryUri = "file:///wildcard-alias-library.mcfpp"
        val consumerUri = "file:///wildcard-alias-consumer.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(CountDownLatch(0)) { params ->
            when (params.uri) {
                libraryUri -> libraryAnalyzed.countDown()
                consumerUri -> consumerAnalyzed.countDown()
            }
            false
        })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val consumer = """
            namespace app;
            import models:* as model;
            func main() {
                model:
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(libraryUri, "mcfpp", 1, "namespace models; func build() {}")))
            assertTrue(libraryAnalyzed.await(5, TimeUnit.SECONDS), "Library analysis did not finish")
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumerUri, "mcfpp", 1, consumer)))
            assertTrue(consumerAnalyzed.await(5, TimeUnit.SECONDS), "Consumer analysis did not finish")

            fun labelsAt(line: Int, character: Int): List<String> {
                val result = service.completion(
                    CompletionParams(TextDocumentIdentifier(consumerUri), Position(line, character))
                ).get()
                return if (result.isLeft) result.left.map { it.label } else result.right.items.map { it.label }
            }

            assertTrue("build" in labelsAt(3, consumer.lines()[3].length))
            assertFalse("build" in labelsAt(2, consumer.lines()[2].indexOf('{')))
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `dependency changes invalidate cached semantic tokens and request refresh`() {
        val libraryAnalyzed = CountDownLatch(1)
        val consumerAnalyzed = CountDownLatch(1)
        val changedRefresh = CountDownLatch(1)
        val watchRefresh = AtomicBoolean(false)
        val refreshCount = AtomicInteger(0)
        val libraryUri = "file:///semantic-library.mcfpp"
        val consumerUri = "file:///semantic-consumer.mcfpp"
        val server = SimpleLanguageServer()
        server.connect(
            RecordingLanguageClient(
                diagnosticsPublished = CountDownLatch(0),
                onSemanticTokensRefresh = {
                    refreshCount.incrementAndGet()
                    if (watchRefresh.get()) changedRefresh.countDown()
                },
                matches = { params ->
                    when (params.uri) {
                        libraryUri -> libraryAnalyzed.countDown()
                        consumerUri -> consumerAnalyzed.countDown()
                    }
                    false
                }
            )
        )
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val consumer = "namespace app; import library:paint; func main() { paint(); }"

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(libraryUri, "mcfpp", 1, "namespace library; func paint() {}")))
            assertTrue(libraryAnalyzed.await(5, TimeUnit.SECONDS))
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumerUri, "mcfpp", 1, consumer)))
            assertTrue(consumerAnalyzed.await(5, TimeUnit.SECONDS))
            val initial = decodeSemanticTokens(
                service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(consumerUri))).get().data
            )
            assertTrue(initial.any { it.type == "function" && !it.declaration }, "Initial tokens: $initial")

            watchRefresh.set(true)
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(libraryUri, 2),
                    listOf(TextDocumentContentChangeEvent("namespace library; func renamed() {}"))
                )
            )
            assertTrue(changedRefresh.await(5, TimeUnit.SECONDS), "Semantic token refresh was not requested")
            val updated = decodeSemanticTokens(
                service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(consumerUri))).get().data
            )
            assertFalse(updated.any { it.type == "function" && !it.declaration }, "Stale tokens: $updated")
            assertTrue(refreshCount.get() > 0)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `complete project fixture supports imports members navigation and semantic tokens`() {
        val fixture = Path.of("..", "examples", "complete-mcfpp-project").toAbsolutePath().normalize()
        val counterPath = fixture.resolve("src/main/mcfpp/library/counter.mcfpp")
        val mathPath = fixture.resolve("src/main/mcfpp/library/math.mcfpp")
        val mainPath = fixture.resolve("src/main/mcfpp/main.mcfpp")
        val counterText = counterPath.toFile().readText()
        val mathText = mathPath.toFile().readText()
        val mainText = mainPath.toFile().readText()
        val counterUri = counterPath.toUri().toString()
        val mathUri = mathPath.toUri().toString()
        val mainUri = mainPath.toUri().toString()
        val analyzed = CountDownLatch(3)
        val analyzedUris = ConcurrentHashMap.newKeySet<String>()
        val mainDiagnostics = AtomicReference<List<Diagnostic>>(emptyList())
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { params ->
            if (params.uri == mainUri) mainDiagnostics.set(params.diagnostics)
            params.uri in setOf(counterUri, mathUri, mainUri) && analyzedUris.add(params.uri)
        })
        server.initialize(InitializeParams().apply { rootUri = fixture.toUri().toString() }).get()
        val service = server.getTextDocumentService()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(counterUri, "mcfpp", 1, counterText)))
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mathUri, "mcfpp", 1, mathText)))
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "mcfpp", 1, mainText)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Fixture analysis did not finish")
            assertEquals(emptyList(), mainDiagnostics.get().map { it.message })

            val memberLine = mainText.lines().indexOfFirst { "counter.increment" in it }
            val memberText = mainText.lines()[memberLine]
            val completion = service.completion(
                CompletionParams(
                    TextDocumentIdentifier(mainUri),
                    Position(memberLine, memberText.indexOf("counter.") + "counter.".length)
                )
            ).get()
            val labels = if (completion.isLeft) completion.left.map { it.label } else completion.right.items.map { it.label }
            assertTrue("increment" in labels, "Fixture member completions: $labels")
            assertFalse("twice" in labels || "func" in labels, "Fixture member completions leaked globals: $labels")

            val aliasLine = mainText.lines().indexOfFirst { "twice(next)" in it }
            val aliasText = mainText.lines()[aliasLine]
            val definition = service.definition(
                DefinitionParams(TextDocumentIdentifier(mainUri), Position(aliasLine, aliasText.indexOf("twice") + 1))
            ).get().left.single()
            assertEquals(mathUri, definition.uri)

            val semanticTokens = decodeSemanticTokens(
                service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(mainUri))).get().data
            )
            assertTrue(semanticTokens.any { it.line == aliasLine && it.type == "function" && !it.declaration })
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `call hierarchy groups incoming and outgoing call sites`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///call-hierarchy.mcfpp"
        val text = """
            func leaf(value as int) {
            }
            func middle() {
                leaf(1);
                leaf(2);
            }
            func root() {
                middle();
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val leaf = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(uri), Position(0, 6))
            ).get().single()
            assertEquals(1, leaf.range.end.line)
            val incomingLeaf = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(leaf)).get().single()
            assertEquals("middle", incomingLeaf.from.name)
            assertEquals(setOf(3, 4), incomingLeaf.fromRanges.map { it.start.line }.toSet())

            val middle = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(uri), Position(2, 6))
            ).get().single()
            val outgoingMiddle = service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(middle)).get().single()
            assertEquals("leaf", outgoingMiddle.to.name)
            assertEquals(2, outgoingMiddle.fromRanges.size)

            val incomingMiddle = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(middle)).get().single()
            assertEquals("root", incomingMiddle.from.name)
            assertEquals(7, incomingMiddle.fromRanges.single().start.line)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `type hierarchy resolves direct supertypes and subtypes`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///type-hierarchy.mcfpp"
        val text = """
            data Base {
            }
            interface Contract {
            }
            data Middle: Base, Contract {
            }
            data Leaf: Middle {
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val middle = service.prepareTypeHierarchy(
                TypeHierarchyPrepareParams(TextDocumentIdentifier(uri), Position(4, 5))
            ).get().single()
            val supertypes = service.typeHierarchySupertypes(TypeHierarchySupertypesParams(middle)).get()
            assertEquals(setOf("Base", "Contract"), supertypes.map { it.name }.toSet())

            val base = service.prepareTypeHierarchy(
                TypeHierarchyPrepareParams(TextDocumentIdentifier(uri), Position(0, 5))
            ).get().single()
            val baseSubtypes = service.typeHierarchySubtypes(TypeHierarchySubtypesParams(base)).get()
            assertEquals(listOf("Middle"), baseSubtypes.map { it.name })

            val implementations = service.implementation(
                ImplementationParams(TextDocumentIdentifier(uri), Position(0, 5))
            ).get().left
            assertEquals(setOf(Position(4, 5), Position(6, 5)), implementations.map { it.range.start }.toSet())

            val middleSubtypes = service.typeHierarchySubtypes(TypeHierarchySubtypesParams(middle)).get()
            assertEquals(listOf("Leaf"), middleSubtypes.map { it.name })
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `implementation follows transitive inheritance and exact overload signatures`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///method-implementations.mcfpp"
        val text = """
            data Base {
                func process(value as int) {}
                func process(value as string) {}
            }
            data Middle: Base {}
            data Leaf: Middle {
                override func process(item as int) {}
                override func process(item as string) {}
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val implementations = service.implementation(
                ImplementationParams(TextDocumentIdentifier(uri), Position(1, 10))
            ).get().left
            assertEquals(listOf(Position(6, 18)), implementations.map { it.range.start })
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `type definition follows a variable declared type`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///type-definition.mcfpp"
        val text = """
            data Item {
            }
            func main() {
                var item as Item;
                item;
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val locations = service.typeDefinition(
                TypeDefinitionParams(TextDocumentIdentifier(uri), Position(4, 6))
            ).get().left
            assertEquals(Position(0, 5), locations.single().range.start)
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `semantic tokens classify declarations references and ranges`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///semantic-tokens.mcfpp"
        val text = """
            func helper(value as int) {
                var local as int = value;
            }
            func main() {
                helper(1);
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val full = decodeSemanticTokens(
                service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).get().data
            )
            assertTrue(full.any { it.line == 0 && it.character == 5 && it.type == "function" && it.declaration })
            assertTrue(full.any { it.line == 0 && it.type == "parameter" && it.declaration })
            assertTrue(full.any { it.line == 1 && it.type == "variable" && it.declaration })
            assertTrue(full.any { it.line == 4 && it.character == 4 && it.type == "function" && !it.declaration })

            val range = decodeSemanticTokens(
                service.semanticTokensRange(
                    SemanticTokensRangeParams(
                        TextDocumentIdentifier(uri),
                        Range(Position(3, 0), Position(5, 1))
                    )
                ).get().data
            )
            assertTrue(range.isNotEmpty())
            assertTrue(range.all { it.line in 3..5 }, "Range tokens: $range")
            assertTrue(range.any { it.line == 4 && it.type == "function" })
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `inlay hints expose inferred types and callable parameter names`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///inlay-hints.mcfpp"
        val text = """
            func mix<limit as int>(value as int, label as string) {
            }
            func main() {
                var inferred = 5;
                mix<4>(inferred, "x");
            }
        """.trimIndent()

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val hints = service.inlayHint(
                InlayHintParams(TextDocumentIdentifier(uri), Range(Position(0, 0), Position(6, 0)))
            ).get()
            assertTrue(hints.any {
                it.position == Position(3, 16) && it.label.left == " as int" && it.kind == InlayHintKind.Type
            }, "Inlay hints: $hints")
            assertEquals(
                listOf("limit:", "value:", "label:"),
                hints.filter { it.kind == InlayHintKind.Parameter }.map { it.label.left }
            )
        } finally {
            server.shutdown().get()
        }
    }

    @Test
    fun `formatting uses lexer braces and range formatting respects requested lines`() {
        val analyzed = CountDownLatch(1)
        val server = SimpleLanguageServer()
        server.connect(RecordingLanguageClient(analyzed) { true })
        server.initialize(InitializeParams()).get()
        val service = server.getTextDocumentService()
        val uri = "file:///formatting.mcfpp"
        val text = """
            func main(){
            var value = 1;
            if(value > 0){
            /say {"text":"ok"}
            }
            }
        """.trimIndent()
        val options = FormattingOptions(2, true)

        try {
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "mcfpp", 1, text)))
            assertTrue(analyzed.await(5, TimeUnit.SECONDS), "Document analysis did not finish")

            val edits = service.formatting(
                DocumentFormattingParams(TextDocumentIdentifier(uri), options)
            ).get()
            assertEquals(
                mapOf(1 to "  ", 2 to "  ", 3 to "    ", 4 to "  "),
                edits.associate { it.range.start.line to it.newText }
            )

            val rangeEdits = service.rangeFormatting(
                DocumentRangeFormattingParams(
                    TextDocumentIdentifier(uri),
                    options,
                    Range(Position(2, 0), Position(4, 0))
                )
            ).get()
            assertEquals(setOf(2, 3), rangeEdits.map { it.range.start.line }.toSet())
        } finally {
            server.shutdown().get()
        }
    }

    private data class DecodedToken(
        val line: Int,
        val character: Int,
        val type: String,
        val declaration: Boolean
    )

    private fun decodeSemanticTokens(data: List<Int>): List<DecodedToken> {
        var line = 0
        var character = 0
        return data.chunked(5).map { token ->
            line += token[0]
            character = if (token[0] == 0) character + token[1] else token[1]
            DecodedToken(
                line,
                character,
                MCFPPSemanticTokenLegend.tokenTypes[token[3]],
                token[4] and MCFPPSemanticTokenLegend.modifierBits("declaration") != 0
            )
        }
    }

    private class RecordingLanguageClient(
        private val diagnosticsPublished: CountDownLatch,
        private val onLogMessage: (MessageParams) -> Unit = {},
        private val onSemanticTokensRefresh: () -> Unit = {},
        private val matches: (PublishDiagnosticsParams) -> Boolean
    ) : LanguageClient {
        override fun telemetryEvent(`object`: Any?) = Unit

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            if (matches(params)) {
                diagnosticsPublished.countDown()
            }
        }

        override fun showMessage(messageParams: MessageParams) = Unit

        override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(message: MessageParams) = onLogMessage(message)

        override fun refreshSemanticTokens(): CompletableFuture<Void> {
            onSemanticTokensRefresh()
            return CompletableFuture.completedFuture(null)
        }
    }
}
