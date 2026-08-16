package top.mcfpp.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

final class MinecraftFunctionTokenType extends IElementType {
    MinecraftFunctionTokenType(@NonNls @NotNull String debugName) {
        super(debugName, MinecraftFunctionLanguage.INSTANCE);
    }
}
