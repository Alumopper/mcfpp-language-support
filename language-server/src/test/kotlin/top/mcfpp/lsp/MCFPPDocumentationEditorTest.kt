package top.mcfpp.lsp

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MCFPPDocumentationEditorTest {
    @Test fun hoverAndSignatureRenderBlockTagsAndKeepFencedExamples() {
        val source = """
            /// 读取基础积分。
            /// ```mcfpp
            /// @param example
            /// ```
            /// @param value 基础积分。
            ///   可以为零。
            /// @return 当前基础积分。
            /// @custom extra
            func total(value: int): int { return value }
            func main() { total(1) }
        """.trimIndent()
        val server = MCFPPLanguageServer()
        try {
            val document = TextDocumentIdentifier("file:///documentation-fixture.mcfpp")
            val service = server.textDocumentService
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "mcfpp", 1, source)))
            val hover = service.hover(HoverParams(document, Position(9, 16))).get(10, TimeUnit.SECONDS).contents.right.value
            assertContains(hover, "```mcfpp\n@param example\n```")
            assertContains(hover, "**Returns**\n\n当前基础积分。")
            assertContains(hover, "**`@custom`**\n\nextra")
            assertFalse("@return" in hover)

            val native = assertNotNull(server.documentation(HoverParams(document, Position(9, 16))).get(10, TimeUnit.SECONDS))
            assertEquals("total(value: int): int", native.signature())
            assertContains(native.markdown(), "@return 当前基础积分。")

            val signature = service.signatureHelp(SignatureHelpParams(document, Position(9, 21))).get(10, TimeUnit.SECONDS)
                .signatures.single()
            assertContains(signature.documentation.right.value, "**Returns**")
            assertContains(signature.parameters.single().documentation.right.value, "可以为零。")
        } finally {
            server.shutdown().get(10, TimeUnit.SECONDS)
        }
    }
}
