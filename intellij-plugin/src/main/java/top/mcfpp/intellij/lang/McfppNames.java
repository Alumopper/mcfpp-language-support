package top.mcfpp.intellij.lang;

final class McfppNames {
    private McfppNames() {
    }

    static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty()) return false;
        int first = value.codePointAt(0);
        if (!(Character.isUnicodeIdentifierStart(first) || first == '_' || first == '$')) return false;
        for (int offset = Character.charCount(first); offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!(Character.isUnicodeIdentifierPart(codePoint) || codePoint == '_' || codePoint == '$')) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
