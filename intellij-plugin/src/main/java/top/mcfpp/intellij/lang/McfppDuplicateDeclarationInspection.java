package top.mcfpp.intellij.lang;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public final class McfppDuplicateDeclarationInspection extends LocalInspectionTool {
    @Override
    public @NotNull String getDisplayName() {
        return "Duplicate MCFPP declaration";
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly
    ) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof McfppFile)) return;
                Map<String, McfppSymbol> declarations = new HashMap<>();
                for (McfppSymbol symbol : McfppFileModels.get(file).symbols()) {
                    if (!hasUniqueNameInScope(symbol.kind())) continue;
                    String localScope = symbol.kind() == McfppSymbolKind.VARIABLE ||
                            symbol.kind() == McfppSymbolKind.PARAMETER ? "|" + symbol.scopeStart() : "";
                    String key = symbol.kind() + "|" + symbol.namespace() + "|" + symbol.owner() +
                            localScope + "|" + symbol.name();
                    McfppSymbol previous = declarations.putIfAbsent(key, symbol);
                    if (previous == null) continue;
                    PsiElement target = file.findElementAt(symbol.nameOffset());
                    if (target != null) {
                        holder.registerProblem(
                                target,
                                "Duplicate " + symbol.kind().presentableName() + " '" + symbol.name() + "'"
                        );
                    }
                }
            }
        };
    }

    private static boolean hasUniqueNameInScope(McfppSymbolKind kind) {
        return kind == McfppSymbolKind.TYPE || kind == McfppSymbolKind.ENUM ||
                kind == McfppSymbolKind.TYPE_ALIAS || kind == McfppSymbolKind.FIELD ||
                kind == McfppSymbolKind.VARIABLE || kind == McfppSymbolKind.PARAMETER ||
                kind == McfppSymbolKind.ENUM_MEMBER;
    }
}
