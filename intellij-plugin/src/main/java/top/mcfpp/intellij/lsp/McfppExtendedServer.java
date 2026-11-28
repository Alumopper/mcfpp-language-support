package top.mcfpp.intellij.lsp;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import top.mcfpp.language.McfppDocumentationData;
import top.mcfpp.language.MniSymbolSnapshot;
import java.util.concurrent.CompletableFuture;
public interface McfppExtendedServer extends LanguageServer {
    @JsonNotification("mcfpp/updateMniSymbols") void updateMniSymbols(MniSymbolSnapshot snapshot);
    @JsonRequest("mcfpp/documentation") CompletableFuture<McfppDocumentationData> documentation(HoverParams params);
}

