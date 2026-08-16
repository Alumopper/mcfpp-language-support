package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.tree.IElementType;
import top.mcfpp.intellij.mni.MniJavaTargetParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McfppSemanticTokens {
    private static final Set<String> TYPE_DECLARATIONS = Set.of("data", "enum", "typealias");
    private static final Set<String> VARIABLE_DECLARATIONS = Set.of("var", "global");
    private static final Set<String> TYPE_OPERATORS = Set.of("<", ">", "!", "?", "&", "|", "*");

    private McfppSemanticTokens() {
    }

    static Map<Integer, TextAttributesKey> analyze(CharSequence source) {
        return analyzeDetailed(source).attributes();
    }

    static Analysis analyzeDetailed(CharSequence source) {
        List<Token> tokens = lex(source);
        Map<Integer, TextAttributesKey> attributes = new HashMap<>();
        McfppFileModel model = McfppFileModel.parse(source);
        Set<String> declaredTypes = model.symbols().stream()
                .filter(symbol -> symbol.kind().isType())
                .map(McfppSymbol::name)
                .collect(java.util.stream.Collectors.toSet());

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.DECLARATION_KEYWORD) {
                classifyDeclaration(tokens, index, attributes);
            }
            if ((token.text().equals("as") && aliasKind(tokens, index) == AliasKind.NONE) || token.text().equals("extends")) {
                classifyTypeAfter(tokens, index, attributes);
            }
            if (token.type() == McfppTokenTypes.OPERATOR && token.text().equals("->")) {
                classifyTypeAfter(tokens, index, attributes);
            }
        }

        int parenthesisDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.LEFT_PARENTHESIS) parenthesisDepth++;
            if (token.type() == McfppTokenTypes.RIGHT_PARENTHESIS) parenthesisDepth = Math.max(0, parenthesisDepth - 1);

            if (token.text().equals("as")) {
                int previous = previousSignificant(tokens, index - 1);
                AliasKind aliasKind = aliasKind(tokens, index);
                if (aliasKind != AliasKind.NONE) {
                    int alias = nextIdentifier(tokens, index + 1, tokens.size());
                    if (alias >= 0) attributes.put(tokens.get(alias).start(), aliasKind.attributesKey());
                    continue;
                }
                if (previous >= 0 && tokens.get(previous).type() == McfppTokenTypes.IDENTIFIER) {
                    attributes.putIfAbsent(tokens.get(previous).start(), parenthesisDepth > 0
                            ? McfppSyntaxHighlighter.PARAMETER
                            : McfppSyntaxHighlighter.VARIABLE);
                }
            }

            if (VARIABLE_DECLARATIONS.contains(token.text())) {
                int name = nextIdentifier(tokens, index + 1, tokens.size());
                if (name >= 0) attributes.putIfAbsent(tokens.get(name).start(), McfppSyntaxHighlighter.VARIABLE);
            }

            if (token.type() == McfppTokenTypes.IDENTIFIER && !attributes.containsKey(token.start())) {
                if (isNamespaceQualifier(tokens, index)) {
                    attributes.put(token.start(), McfppSyntaxHighlighter.NAMESPACE);
                } else if (isFunctionCall(tokens, index)) {
                    attributes.put(token.start(), declaredTypes.contains(token.text()) || startsWithUppercase(token.text())
                            ? McfppSyntaxHighlighter.TYPE_NAME
                            : McfppSyntaxHighlighter.FUNCTION_CALL);
                } else if (isMemberAccess(tokens, index)) {
                    attributes.put(token.start(), McfppSyntaxHighlighter.FIELD);
                } else if (isMemberOwner(tokens, index)) {
                    attributes.put(token.start(), startsWithUppercase(token.text())
                            ? McfppSyntaxHighlighter.TYPE_NAME
                            : McfppSyntaxHighlighter.VARIABLE);
                }
            }
        }
        overlayModel(tokens, model, attributes);
        List<EmbeddedRange> embeddedRanges = new ArrayList<>(overlayMniTargets(source, attributes));
        embeddedRanges.addAll(McfppCommandSyntax.highlight(source));
        embeddedRanges.sort(Comparator.comparingInt(EmbeddedRange::startOffset));
        return new Analysis(Map.copyOf(attributes), List.copyOf(embeddedRanges));
    }

    private static boolean startsWithUppercase(String text) {
        return !text.isEmpty() && Character.isUpperCase(text.codePointAt(0));
    }

    private static boolean isNamespaceQualifier(List<Token> tokens, int identifierIndex) {
        int next = nextSignificant(tokens, identifierIndex + 1);
        return next >= 0 && tokens.get(next).type() == McfppTokenTypes.COLON;
    }

    private static boolean isMemberAccess(List<Token> tokens, int identifierIndex) {
        int previous = previousSignificant(tokens, identifierIndex - 1);
        return previous >= 0 && tokens.get(previous).type() == McfppTokenTypes.DOT;
    }

    private static boolean isMemberOwner(List<Token> tokens, int identifierIndex) {
        int next = nextSignificant(tokens, identifierIndex + 1);
        return next >= 0 && tokens.get(next).type() == McfppTokenTypes.DOT;
    }

    private static void classifyDeclaration(
            List<Token> tokens,
            int keywordIndex,
            Map<Integer, TextAttributesKey> attributes
    ) {
        String keyword = tokens.get(keywordIndex).text();
        if (keyword.equals("typealias")) {
            int asIndex = findText(tokens, keywordIndex + 1, "as");
            int alias = asIndex < 0 ? -1 : nextIdentifier(tokens, asIndex + 1, tokens.size());
            if (alias >= 0) attributes.put(tokens.get(alias).start(), McfppSyntaxHighlighter.TYPE_NAME);
            return;
        }
        if (keyword.equals("func")) {
            return;
        }

        if (keyword.equals("namespace") || keyword.equals("import")) {
            boolean importedSymbol = false;
            int index = nextSignificant(tokens, keywordIndex + 1);
            while (index >= 0 && index < tokens.size() && !endsDeclaration(tokens.get(index))) {
                Token token = tokens.get(index);
                if (token.type() == McfppTokenTypes.COLON) importedSymbol = true;
                if (token.type() == McfppTokenTypes.IDENTIFIER) {
                    TextAttributesKey key = !keyword.equals("import") || !importedSymbol
                            ? McfppSyntaxHighlighter.NAMESPACE
                            : startsWithUppercase(token.text())
                            ? McfppSyntaxHighlighter.TYPE_NAME
                            : McfppSyntaxHighlighter.FUNCTION_CALL;
                    attributes.putIfAbsent(token.start(), key);
                }
                index = nextSignificant(tokens, index + 1);
            }
            return;
        }

        if (keyword.equals("object")) {
            int next = nextSignificant(tokens, keywordIndex + 1);
            if (next >= 0 && tokens.get(next).text().equals("data")) keywordIndex = next;
        }
        if (TYPE_DECLARATIONS.contains(tokens.get(keywordIndex).text()) || keyword.equals("object")) {
            int name = nextIdentifier(tokens, keywordIndex + 1, tokens.size());
            if (name >= 0) attributes.put(tokens.get(name).start(), McfppSyntaxHighlighter.TYPE_NAME);
        }
    }

    private static void classifyTypeAfter(
            List<Token> tokens,
            int markerIndex,
            Map<Integer, TextAttributesKey> attributes
    ) {
        int index = nextSignificant(tokens, markerIndex + 1);
        while (index >= 0 && index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.IDENTIFIER) {
                attributes.putIfAbsent(token.start(), McfppSyntaxHighlighter.TYPE_NAME);
            } else if (token.type() != McfppTokenTypes.DOT && token.type() != McfppTokenTypes.COLON &&
                    token.type() != McfppTokenTypes.COMMA && token.type() != McfppTokenTypes.LEFT_BRACKET &&
                    token.type() != McfppTokenTypes.RIGHT_BRACKET && token.type() != McfppTokenTypes.LEFT_PARENTHESIS &&
                    token.type() != McfppTokenTypes.RIGHT_PARENTHESIS && !isTypeOperator(token)) {
                break;
            }
            index = nextSignificant(tokens, index + 1);
        }
    }

    private static boolean isTypeOperator(Token token) {
        return token.type() == McfppTokenTypes.OPERATOR && TYPE_OPERATORS.contains(token.text());
    }

    private static boolean isFunctionCall(List<Token> tokens, int identifierIndex) {
        int next = nextSignificant(tokens, identifierIndex + 1);
        if (next < 0) return false;
        if (tokens.get(next).type() == McfppTokenTypes.LEFT_PARENTHESIS) return true;
        if (!"<".equals(tokens.get(next).text())) return false;
        int close = matchingClosingAngle(tokens, next);
        int normalArguments = nextSignificant(tokens, close + 1);
        return close >= 0 && normalArguments >= 0 &&
                tokens.get(normalArguments).type() == McfppTokenTypes.LEFT_PARENTHESIS;
    }

    private static int matchingClosingAngle(List<Token> tokens, int open) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            String text = tokens.get(index).text();
            if ("<".equals(text)) depth++;
            else if (">".equals(text) && --depth == 0) return index;
            if (depth == 0 && (tokens.get(index).type() == McfppTokenTypes.SEMICOLON ||
                    tokens.get(index).type() == McfppTokenTypes.LEFT_BRACE)) return -1;
        }
        return -1;
    }

    private static void overlayModel(
            List<Token> tokens,
            McfppFileModel model,
            Map<Integer, TextAttributesKey> attributes
    ) {
        Map<Integer, McfppSymbol> declarations = new HashMap<>();
        Map<String, List<McfppSymbol>> locals = new LinkedHashMap<>();
        for (McfppSymbol symbol : model.symbols()) {
            TextAttributesKey key = attributesFor(symbol.kind());
            if (key != null) {
                declarations.put(symbol.nameOffset(), symbol);
                attributes.put(symbol.nameOffset(), key);
            }
            if (symbol.kind() == McfppSymbolKind.PARAMETER || symbol.kind() == McfppSymbolKind.VARIABLE ||
                    symbol.kind() == McfppSymbolKind.FIELD || symbol.kind() == McfppSymbolKind.ENUM_MEMBER) {
                locals.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol);
            }
            if (symbol.kind() == McfppSymbolKind.FUNCTION) {
                markExtensionOwner(tokens, symbol, attributes);
            }
        }
        for (List<McfppSymbol> candidates : locals.values()) {
            candidates.sort(Comparator.comparingInt(McfppSymbol::nameOffset).reversed());
        }

        for (Token token : tokens) {
            if (token.type() != McfppTokenTypes.IDENTIFIER || declarations.containsKey(token.start())) continue;
            List<McfppSymbol> candidates = locals.get(token.text());
            if (candidates == null) continue;
            for (McfppSymbol candidate : candidates) {
                if (candidate.nameOffset() < token.start() && candidate.scopeContains(token.start())) {
                    attributes.put(token.start(), attributesFor(candidate.kind()));
                    break;
                }
            }
        }
    }

    private static void markExtensionOwner(
            List<Token> tokens,
            McfppSymbol function,
            Map<Integer, TextAttributesKey> attributes
    ) {
        int name = tokenAt(tokens, function.nameOffset());
        if (name < 0) return;
        int functionKeyword = -1;
        for (int index = name - 1; index >= 0 && name - index <= 128; index--) {
            Token token = tokens.get(index);
            if ("func".equals(token.text())) {
                functionKeyword = index;
                break;
            }
            if (token.type() == McfppTokenTypes.SEMICOLON || token.type() == McfppTokenTypes.LEFT_BRACE ||
                    token.type() == McfppTokenTypes.RIGHT_BRACE) return;
        }
        for (int index = functionKeyword + 1; functionKeyword >= 0 && index < name; index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.IDENTIFIER) {
                attributes.putIfAbsent(token.start(), McfppSyntaxHighlighter.TYPE_NAME);
            }
        }
    }

    private static int tokenAt(List<Token> tokens, int offset) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).start() == offset) return index;
        }
        return -1;
    }

    private static TextAttributesKey attributesFor(McfppSymbolKind kind) {
        return switch (kind) {
            case TYPE, ENUM, TYPE_ALIAS -> McfppSyntaxHighlighter.TYPE_NAME;
            case FUNCTION, CONSTRUCTOR -> McfppSyntaxHighlighter.FUNCTION_DECLARATION;
            case FIELD -> McfppSyntaxHighlighter.FIELD;
            case VARIABLE -> McfppSyntaxHighlighter.VARIABLE;
            case PARAMETER -> McfppSyntaxHighlighter.PARAMETER;
            case ENUM_MEMBER -> McfppSyntaxHighlighter.ENUM_MEMBER;
        };
    }

    private static List<EmbeddedRange> overlayMniTargets(
            CharSequence source,
            Map<Integer, TextAttributesKey> attributes
    ) {
        LinkedHashSet<EmbeddedRange> embeddedRanges = new LinkedHashSet<>();
        for (MniJavaTargetParser.Target target : MniJavaTargetParser.parse(source)) {
            int owner = target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD
                    ? target.segments().size() - 2
                    : target.segments().size() - 1;
            for (MniJavaTargetParser.Segment segment : target.segments()) {
                TextAttributesKey key = segment.ordinal() < owner
                        ? McfppSyntaxHighlighter.NAMESPACE
                        : segment.ordinal() == owner
                        ? McfppSyntaxHighlighter.TYPE_NAME
                        : McfppSyntaxHighlighter.FUNCTION_CALL;
                attributes.put(segment.startOffset(), key);
                embeddedRanges.add(new EmbeddedRange(segment.startOffset(), segment.endOffset(), key));
            }
        }
        return List.copyOf(embeddedRanges);
    }

    private static AliasKind aliasKind(List<Token> tokens, int asIndex) {
        for (int index = asIndex - 1; index >= 0; index--) {
            Token token = tokens.get(index);
            String text = token.text();
            if (text.equals("typealias")) return AliasKind.TYPE;
            if (text.equals("import")) return AliasKind.IMPORT;
            if (tokens.get(index).type() == McfppTokenTypes.SEMICOLON ||
                    tokens.get(index).type() == McfppTokenTypes.LEFT_BRACE ||
                    tokens.get(index).type() == McfppTokenTypes.RIGHT_BRACE || token.newlineBefore()) {
                return AliasKind.NONE;
            }
        }
        return AliasKind.NONE;
    }

    private static boolean endsDeclaration(Token token) {
        return token.type() == McfppTokenTypes.SEMICOLON || token.type() == McfppTokenTypes.LEFT_BRACE ||
                token.type() == McfppTokenTypes.RIGHT_BRACE || token.newlineBefore();
    }

    private static int findType(List<Token> tokens, int start, IElementType type) {
        for (int index = start; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == type) return index;
            if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.SEMICOLON || token.newlineBefore()) {
                return -1;
            }
        }
        return -1;
    }

    private static int findText(List<Token> tokens, int start, String text) {
        for (int index = start; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.text().equals(text)) return index;
            if (token.type() == McfppTokenTypes.SEMICOLON || token.type() == McfppTokenTypes.LEFT_BRACE ||
                    token.newlineBefore()) return -1;
        }
        return -1;
    }

    private static int nextIdentifier(List<Token> tokens, int start, int end) {
        for (int index = start; index < end; index++) {
            if (tokens.get(index).type() == McfppTokenTypes.IDENTIFIER) return index;
            if (tokens.get(index).type() == McfppTokenTypes.LEFT_BRACE || tokens.get(index).newlineBefore()) return -1;
        }
        return -1;
    }

    private static int previousIdentifier(List<Token> tokens, int start) {
        for (int index = start; index >= 0; index--) {
            if (tokens.get(index).type() == McfppTokenTypes.IDENTIFIER) return index;
            if (tokens.get(index).type() == McfppTokenTypes.LEFT_BRACE || tokens.get(index).newlineBefore()) return -1;
        }
        return -1;
    }

    private static int nextSignificant(List<Token> tokens, int start) {
        return start < tokens.size() ? start : -1;
    }

    private static int previousSignificant(List<Token> tokens, int start) {
        return start >= 0 ? start : -1;
    }

    private static List<Token> lex(CharSequence source) {
        Lexer lexer = new McfppLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        boolean newline = false;
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            String text = source.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString();
            if (type == McfppTokenTypes.WHITE_SPACE) {
                newline |= text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
            } else if (type != McfppTokenTypes.COMMENT && type != McfppTokenTypes.DOC_COMMENT) {
                tokens.add(new Token(type, text, lexer.getTokenStart(), newline));
                newline = false;
            }
            lexer.advance();
        }
        return tokens;
    }

    private record Token(IElementType type, String text, int start, boolean newlineBefore) {
    }

    record Analysis(Map<Integer, TextAttributesKey> attributes, List<EmbeddedRange> embeddedRanges) {
    }

    record EmbeddedRange(int startOffset, int endOffset, TextAttributesKey attributesKey) {
    }

    private enum AliasKind {
        NONE(null),
        TYPE(McfppSyntaxHighlighter.TYPE_NAME),
        IMPORT(McfppSyntaxHighlighter.NAMESPACE);

        private final TextAttributesKey attributesKey;

        AliasKind(TextAttributesKey attributesKey) {
            this.attributesKey = attributesKey;
        }

        TextAttributesKey attributesKey() {
            return attributesKey;
        }
    }
}
