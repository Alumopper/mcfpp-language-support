package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class McfppImportManager {
    private static final Pattern HEADER_LINE = Pattern.compile(
            "(?m)^[\\t ]*(?:namespace|import)\\b[^\\r\\n]*(?:\\R|$)"
    );

    private McfppImportManager() {
    }

    static void autoImport(InsertionContext context, String name) {
        if (!(context.getFile() instanceof McfppFile file)) return;
        context.commitDocument();
        ImportCandidate candidate = uniqueCandidate(file, name);
        if (candidate == null) return;
        insertImport(context.getDocument(), candidate.namespace(), candidate.name());
        PsiDocumentManager.getInstance(context.getProject()).commitDocument(context.getDocument());
    }

    static @Nullable ImportCandidate uniqueCandidate(McfppFile file, String name) {
        McfppFileModel current = McfppFileModels.get(file);
        Set<ImportCandidate> candidates = new LinkedHashSet<>();
        for (PsiElement element : McfppSymbolResolver.findProjectSymbols(file.getProject(), name, true)) {
            if (!(element.getContainingFile() instanceof McfppFile candidateFile)) continue;
            McfppSymbol symbol = McfppFileModels.get(candidateFile).declarationAt(element.getTextOffset());
            if (symbol == null || symbol.owner() != null || symbol.namespace().isEmpty() ||
                    McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) ||
                    symbol.namespace().equals(current.namespace()) ||
                    current.importExposes(symbol.namespace(), symbol.name(), name)) continue;
            candidates.add(new ImportCandidate(symbol.namespace(), symbol.name()));
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    static int insertImport(Document document, String namespace, String name) {
        String statement = "import " + namespace + ':' + name + ";\n";
        String source = document.getText();
        if (source.matches("(?s).*?(?m)^[\\t ]*import[\\t ]+" + Pattern.quote(namespace + ':' + name) +
                "[\\t ]*;?.*")) return -1;
        Matcher matcher = HEADER_LINE.matcher(source);
        int offset = 0;
        while (matcher.find()) offset = matcher.end();
        document.insertString(offset, statement);
        return offset;
    }

    record ImportCandidate(String namespace, String name) {
    }
}
