package top.mcfpp.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class McfppElementType extends IElementType {
    public McfppElementType(@NonNls @NotNull String debugName) {
        super(debugName, McfppLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "MCFPP_" + super.toString();
    }
}
