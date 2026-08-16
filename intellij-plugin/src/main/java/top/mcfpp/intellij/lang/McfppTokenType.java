package top.mcfpp.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.ILeafElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class McfppTokenType extends IElementType implements ILeafElementType {
    public McfppTokenType(@NonNls @NotNull String debugName) {
        super(debugName, McfppLanguage.INSTANCE);
    }

    @Override
    public @NotNull ASTNode createLeafNode(@NotNull CharSequence leafText) {
        return new McfppLeafPsiElement(this, leafText);
    }

    @Override
    public String toString() {
        return "MCFPP_" + super.toString();
    }
}
