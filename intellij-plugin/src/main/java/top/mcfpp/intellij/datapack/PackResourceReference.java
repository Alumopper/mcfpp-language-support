package top.mcfpp.intellij.datapack;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.api.resources.PackResources;

public final class PackResourceReference extends PsiPolyVariantReferenceBase<PsiElement> {
    final PackResources.Reference resource;
    public PackResourceReference(PsiElement element, TextRange range, PackResources.Reference resource) {
        super(element, range, true); this.resource = resource;
    }
    @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        var index = PackResourceIndex.forFile(myElement.getContainingFile());
        if (index == null) return ResolveResult.EMPTY_ARRAY;
        return index.definitions.getOrDefault(resource.key(), java.util.List.of()).stream()
                .map(PackResourceIndex.Target::element).filter(java.util.Objects::nonNull)
                .map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
    }
    @Override public PsiElement handleElementRename(@NotNull String newName) {
        if (resource.key().kind().equals("sound_event")) return myElement;
        // The exported ID is stable across MCFPP declaration renames. Only its config target changes.
        var index = PackResourceIndex.forFile(myElement.getContainingFile());
        if (index != null && resource.key().kind().equals("function") && index.configuration.entrypoints().containsKey(resource.key().id())) return myElement;
        String old = resource.key().id();
        String extension = newName.endsWith(".mcfunction") ? ".mcfunction" : newName.endsWith(".json") ? ".json"
                : newName.endsWith(".png") ? ".png" : newName.endsWith(".ogg") ? ".ogg" : "";
        String name = newName.substring(0, newName.length() - extension.length());
        int separator = Math.max(old.lastIndexOf('/'), old.indexOf(':'));
        String replacement = old.substring(0, separator + 1) + name;
        if (resource.key().kind().startsWith("tag/")) replacement = "#" + replacement;
        replace(myElement, getRangeInElement(), replacement + resource.suffix());
        return myElement;
    }
    static void replace(PsiElement element, TextRange range, String replacement) {
        PsiFile file = element.getContainingFile();
        var document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document != null) {
            int start = element.getTextRange().getStartOffset() + range.getStartOffset();
            document.replaceString(start, start + range.getLength(), replacement);
            PsiDocumentManager.getInstance(element.getProject()).commitDocument(document);
        }
    }
    public static PsiReference at(PsiFile file, int offset) {
        var index = PackResourceIndex.forFile(file);
        if (index != null) for (var ref : index.references(file)) if (ref.start() <= offset && offset < ref.end())
            return new PackResourceReference(file, new TextRange(ref.start(), ref.end()), ref);
        return null;
    }
}
