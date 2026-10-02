package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class McfppImportManager {
    private McfppImportManager() {}

    static List<ImportCandidate> candidates(McfppFile file, String name) {
        McfppFileModel current = McfppFileModels.get(file);
        Set<ImportCandidate> candidates = new LinkedHashSet<>();
        for (PsiElement element : McfppSymbolResolver.findProjectSymbols(file.getProject(), name, true)) {
            if (!(element.getContainingFile() instanceof McfppFile candidateFile)) continue;
            McfppSymbol symbol = McfppFileModels.get(candidateFile).declarationAt(element.getTextOffset());
            if (symbol == null || !symbol.isTopLevel() || symbol.namespace().isEmpty() ||
                    McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) ||
                    symbol.namespace().equals(current.namespace()) ||
                    current.importExposes(symbol.namespace(), symbol.name(), name)) continue;
            candidates.add(new ImportCandidate(symbol.namespace(), symbol.name()));
        }
        return candidates.stream().sorted(Comparator.comparing(ImportCandidate::qualifiedName)).toList();
    }

    static @Nullable ImportCandidate uniqueCandidate(McfppFile file, String name) {
        List<ImportCandidate> candidates = candidates(file, name);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    static String exposedName(McfppFileModel model, McfppSymbol symbol) {
        for (McfppImport imported : model.imports()) {
            if (imported.namespace().equals(symbol.namespace()) && imported.importedName().equals(symbol.name()) &&
                    imported.alias() != null) return imported.alias();
        }
        return symbol.name();
    }

    static String referenceText(McfppFile file, ImportCandidate candidate, int offset) {
        McfppFileModel model = McfppFileModels.get(file);
        for (McfppImport imported : model.imports()) {
            if (!imported.namespace().equals(candidate.namespace())) continue;
            if (imported.importedName().equals(candidate.name()) && imported.alias() != null &&
                    !conflicts(file, model, candidate, imported.alias(), offset)) return imported.alias();
            if (imported.isWildcard() && imported.alias() != null &&
                    model.visibleLocalSymbols(imported.alias(), offset).isEmpty()) {
                return imported.alias() + ':' + candidate.name();
            }
        }
        return !candidate.namespace().isEmpty() && conflicts(file, model, candidate, candidate.name(), offset)
                ? candidate.qualifiedName() : candidate.name();
    }

    private static boolean conflicts(McfppFile file, McfppFileModel model, ImportCandidate candidate, String name, int offset) {
        if (model.visibleLocalSymbols(name, offset).stream().anyMatch(symbol ->
                symbol.kind() != McfppSymbolKind.FIELD || symbol.owner() != null ||
                        !symbol.namespace().equals(candidate.namespace()) || !symbol.name().equals(candidate.name()))) return true;
        for (McfppSymbol symbol : model.projectSymbols()) {
            if (symbol.name().equals(name) && (!symbol.namespace().equals(candidate.namespace()) ||
                    !symbol.name().equals(candidate.name()) || symbol.owner() != null)) return true;
        }
        if (!candidate.namespace().equals(model.namespace()) &&
                !com.intellij.openapi.project.DumbService.isDumb(file.getProject())) {
            for (PsiElement target : McfppSymbolResolver.findProjectSymbols(file.getProject(), name, true)) {
                McfppSymbol symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
                if (symbol != null && symbol.isTopLevel() && symbol.namespace().equals(model.namespace())) return true;
            }
        }
        for (McfppImport imported : model.imports()) {
            if (imported.exposes(name) && (!imported.namespace().equals(candidate.namespace()) ||
                    !imported.originalName(name).equals(candidate.name()))) return true;
        }
        return false;
    }

    static int insertImport(Document document, String namespace, String name) {
        String source = document.getText();
        // Only unconditional header imports make a new global import redundant.
        int offset = McfppImportLayout.insertionOffset(source);
        for (McfppImportLayout.Entry entry : McfppImportLayout.entries(source)) {
            McfppImport value = entry.value();
            if (entry.end() <= offset && value.namespace().equals(namespace) && value.alias() == null &&
                    (value.importedName().equals(name) || value.isWildcard())) return -1;
        }
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String prefix = offset > 0 && source.charAt(offset - 1) != '\n' ? newline : "";
        document.insertString(offset, prefix + "import " + namespace + ':' + name + ';' + newline);
        return offset;
    }

    record ImportCandidate(String namespace, String name) {
        String qualifiedName() { return namespace + ':' + name; }
    }
}
