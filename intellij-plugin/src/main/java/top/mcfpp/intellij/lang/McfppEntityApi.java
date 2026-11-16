package top.mcfpp.intellij.lang;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import top.mcfpp.language.EntityBuiltins;

/** Navigable descriptions for interfaces whose implementation lives in the compiler. */
final class McfppEntityApi {
    private static final Key<PsiFile> FILE = Key.create("mcfpp.entity.builtins");
    private static PsiFile file(Project project) {
        PsiFile file = project.getUserData(FILE);
        if (file == null) {
            var virtual = new com.intellij.testFramework.LightVirtualFile("entity-builtins.mcfpp", McfppFileType.INSTANCE, EntityBuiltins.source());
            virtual.setWritable(false);
            file = java.util.Objects.requireNonNull(PsiManager.getInstance(project).findFile(virtual));
            project.putUserData(FILE, file);
        }
        return file;
    }
    static PsiElement type(Project project, String name) {
        if (!EntityBuiltins.TYPES.contains(name) && !top.mcfpp.language.ContextBuiltins.isType(name) && !"UUID".equals(name)) return null;
        PsiFile file = file(project);
        return McfppFileModels.get(file).symbols().stream().filter(symbol -> symbol.kind().isType() && symbol.name().equals(name))
            .findFirst().map(symbol -> McfppSymbolResolver.elementFor(file, symbol)).orElse(null);
    }
    static PsiElement member(Project project, String owner, String name) {
        if (top.mcfpp.language.NbtBuiltins.member(owner, name) != null) {
            PsiFile file = file(project);
            return McfppFileModels.get(file).symbols().stream()
                    .filter(symbol -> name.equals(symbol.name()) && top.mcfpp.language.NbtBuiltins.SOURCE_OWNER.equals(symbol.owner()))
                    .findFirst().map(symbol -> McfppSymbolResolver.elementFor(file, symbol)).orElse(null);
        }
        if (EntityBuiltins.member(owner, name) == null) return null;
        PsiFile file = file(project);
        for (String type : EntityBuiltins.owners(owner)) {
            String sourceOwner = type.equals("entity") ? "EntityReference" : type;
            for (var symbol : McfppFileModels.get(file).symbols())
                if (name.equals(symbol.name()) && sourceOwner.equals(symbol.owner())) return McfppSymbolResolver.elementFor(file, symbol);
            // Intrinsic names such as text are lexer keywords in declaration position.
            int start = file.getText().indexOf("data " + sourceOwner + " ");
            int field = start < 0 ? -1 : file.getText().indexOf("var " + name + ":", start);
            int end = start < 0 ? -1 : file.getText().indexOf("\n}", start);
            if (field >= 0 && field < end) return file.findElementAt(field + 4);
        }
        return null;
    }
    static PsiElement function(Project project, String name) {
        if (!top.mcfpp.language.ContextBuiltins.ENTRIES.contains(name)) return null;
        PsiFile file = file(project);
        return McfppFileModels.get(file).symbols().stream().filter(symbol -> symbol.owner() == null && symbol.name().equals(name))
            .findFirst().map(symbol -> McfppSymbolResolver.elementFor(file, symbol)).orElse(null);
    }
}
