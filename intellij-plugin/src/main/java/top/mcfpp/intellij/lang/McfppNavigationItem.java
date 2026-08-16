package top.mcfpp.intellij.lang;

import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

final class McfppNavigationItem implements NavigationItem {
    private final SmartPsiElementPointer<PsiElement> pointer;
    private final McfppSymbol symbol;

    McfppNavigationItem(@NotNull PsiElement element, @NotNull McfppSymbol symbol) {
        this.pointer = SmartPointerManager.createPointer(element);
        this.symbol = symbol;
    }

    @Override
    public @Nullable String getName() {
        return symbol.name();
    }

    @Override
    public @NotNull ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public String getPresentableText() {
                return symbol.signature().isEmpty() ? symbol.name() : symbol.signature();
            }

            @Override
            public String getLocationString() {
                return symbol.namespace().isEmpty() ? null : symbol.namespace();
            }

            @Override
            public Icon getIcon(boolean unused) {
                return McfppCompletionContributor.icon(symbol.kind());
            }
        };
    }

    @Override
    public void navigate(boolean requestFocus) {
        PsiElement element = pointer.getElement();
        if (element == null) return;
        VirtualFile file = element.getContainingFile().getVirtualFile();
        if (file != null) new OpenFileDescriptor(element.getProject(), file, element.getTextOffset()).navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
        PsiElement element = pointer.getElement();
        return element != null && element.isValid() && element.getContainingFile().getVirtualFile() != null;
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    static @Nullable McfppNavigationItem from(@NotNull PsiElement element) {
        if (!(element.getContainingFile() instanceof McfppFile file)) return null;
        McfppSymbol symbol = McfppFileModels.get(file).declarationAt(element.getTextOffset());
        return symbol == null ? null : new McfppNavigationItem(element, symbol);
    }
}
