package top.mcfpp.intellij.lang;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

final class McfppStructureViewElement implements StructureViewTreeElement, SortableTreeElement {
    private static final TreeElement[] EMPTY = new TreeElement[0];
    private final SmartPsiElementPointer<McfppFile> filePointer;
    private final McfppSymbol symbol;

    McfppStructureViewElement(@NotNull McfppFile file, McfppSymbol symbol) {
        this.filePointer = SmartPointerManager.createPointer(file);
        this.symbol = symbol;
    }

    @Override
    public Object getValue() {
        McfppFile file = filePointer.getElement();
        if (file == null || symbol == null) return file;
        PsiElement element = McfppSymbolResolver.elementFor(file, symbol);
        return element == null ? file : element;
    }

    @Override
    public void navigate(boolean requestFocus) {
        McfppFile file = filePointer.getElement();
        if (file == null) return;
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) return;
        int offset = symbol == null ? 0 : symbol.nameOffset();
        new OpenFileDescriptor(file.getProject(), virtualFile, offset).navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        McfppFile file = filePointer.getElement();
        return file != null && file.isValid() && file.getVirtualFile() != null;
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    @Override
    public @NotNull String getAlphaSortKey() {
        return symbol == null ? "" : symbol.name();
    }

    @Override
    public @NotNull ItemPresentation getPresentation() {
        McfppFile file = filePointer.getElement();
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                if (symbol == null) return file == null ? "MCFPP" : file.getName();
                return symbol.signature().isEmpty() ? symbol.name() : symbol.signature();
            }

            @Override
            public String getLocationString() {
                return symbol == null || symbol.namespace().isEmpty() ? null : symbol.namespace();
            }

            @Override
            public Icon getIcon(boolean unused) {
                return symbol == null ? McfppIcons.FILE : McfppCompletionContributor.icon(symbol.kind());
            }
        };
    }

    @Override
    public TreeElement @NotNull [] getChildren() {
        McfppFile file = filePointer.getElement();
        if (file == null) return EMPTY;
        return McfppFileModels.get(file).structureChildren(symbol).stream()
                .map(child -> new McfppStructureViewElement(file, child))
                .toArray(TreeElement[]::new);
    }
}
