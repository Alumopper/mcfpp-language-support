package top.mcfpp.intellij.lsp;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import top.mcfpp.language.MniSymbolSnapshot;
public interface McfppExtendedServer extends LanguageServer {
    @JsonNotification("mcfpp/updateMniSymbols") void updateMniSymbols(MniSymbolSnapshot snapshot);
}

