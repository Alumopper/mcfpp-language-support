package top.mcfpp.intellij.lang;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.language.McfppDocumentationData;

/** The optional language server supplies symbols to IDEA's own document renderer. */
public interface McfppRemoteDocumentation {
    boolean isAvailable();

    /** Called by IDEA's background documentation computation, outside its PSI read action. */
    @Nullable McfppDocumentationData fetch(VirtualFile file, int line, int column);
}
