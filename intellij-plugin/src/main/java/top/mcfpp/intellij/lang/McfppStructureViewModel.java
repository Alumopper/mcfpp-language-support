package top.mcfpp.intellij.lang;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class McfppStructureViewModel extends TextEditorBasedStructureViewModel {
    private final McfppFile file;

    McfppStructureViewModel(@Nullable Editor editor, @NotNull McfppFile file) {
        super(editor, file);
        this.file = file;
    }

    @Override
    public @NotNull StructureViewTreeElement getRoot() {
        return new McfppStructureViewElement(file, null);
    }

    @Override
    protected PsiFile getPsiFile() {
        return file;
    }

    @Override
    protected Class<?> @NotNull [] getSuitableClasses() {
        return new Class[]{McfppNamedElement.class};
    }
}
