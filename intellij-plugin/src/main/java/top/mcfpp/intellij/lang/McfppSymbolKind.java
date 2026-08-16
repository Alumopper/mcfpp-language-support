package top.mcfpp.intellij.lang;

public enum McfppSymbolKind {
    TYPE("data type"),
    ENUM("enum"),
    TYPE_ALIAS("type alias"),
    FUNCTION("function"),
    CONSTRUCTOR("constructor"),
    FIELD("field"),
    VARIABLE("variable"),
    PARAMETER("parameter"),
    ENUM_MEMBER("enum member");

    private final String presentableName;

    McfppSymbolKind(String presentableName) {
        this.presentableName = presentableName;
    }

    public String presentableName() {
        return presentableName;
    }

    public boolean isType() {
        return this == TYPE || this == ENUM || this == TYPE_ALIAS;
    }

    public boolean isProjectSymbol() {
        return isType() || this == FUNCTION;
    }
}
