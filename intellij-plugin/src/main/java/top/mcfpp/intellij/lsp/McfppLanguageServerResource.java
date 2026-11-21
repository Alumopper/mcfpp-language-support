package top.mcfpp.intellij.lsp;

import com.intellij.openapi.diagnostic.Logger;
import top.mcfpp.intellij.tooling.McfppToolchain;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Extracts the bundled MCFPP language server into IDEA's unified system tools directory
 * (instead of OS tmpdir) using McfppToolchain's cached, manifest-verified extractor.
 */
final class McfppLanguageServerResource {
    private static final Logger LOG = Logger.getInstance(McfppLanguageServerResource.class);

    private McfppLanguageServerResource() {
    }

    static Path extract() {
        try {
            return McfppToolchain.languageServer();
        } catch (IOException exception) {
            LOG.error("Unable to extract the bundled MCFPP language server", exception);
            throw new IllegalStateException("Unable to prepare the MCFPP language server", exception);
        }
    }
}
