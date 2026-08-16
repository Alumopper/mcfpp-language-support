package top.mcfpp.intellij.lang;

import com.intellij.psi.tree.IElementType;

public final class McfppElementTypes {
    public static final IElementType FUNCTION_DECLARATION = new McfppElementType("FUNCTION_DECLARATION");
    public static final IElementType TYPE_DECLARATION = new McfppElementType("TYPE_DECLARATION");
    public static final IElementType ENUM_DECLARATION = new McfppElementType("ENUM_DECLARATION");
    public static final IElementType TYPE_ALIAS_DECLARATION = new McfppElementType("TYPE_ALIAS_DECLARATION");
    public static final IElementType VARIABLE_DECLARATION = new McfppElementType("VARIABLE_DECLARATION");
    public static final IElementType PARAMETER_DECLARATION = new McfppElementType("PARAMETER_DECLARATION");

    private McfppElementTypes() {
    }
}
