package top.mcfpp.intellij.lang;

import com.intellij.psi.tree.IElementType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static top.mcfpp.intellij.lang.McfppTokenScanner.*;

/** Parses declaration headers into drafts before scope binding. */
final class McfppDeclarationParser {
    static void parseTypes(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            String text = tokens.get(index).text();
            McfppSymbolKind kind;
            int nameSearchStart;
            if ("object".equals(text)) {
                int data = nextCode(tokens, index + 1);
                if (data < 0 || !"data".equals(tokens.get(data).text())) continue;
                kind = McfppSymbolKind.TYPE;
                nameSearchStart = data + 1;
            } else if ("data".equals(text)) {
                int previous = previousCode(tokens, index - 1);
                if (previous >= 0 && "object".equals(tokens.get(previous).text())) continue;
                kind = McfppSymbolKind.TYPE;
                nameSearchStart = index + 1;
            } else if ("entity".equals(text)) {
                // `entity` introduces a template only when followed by a name; the
                // `entity var` / `entity func` member modifiers keep scanning normally.
                int previous = previousCode(tokens, index - 1);
                if (previous >= 0 && ":".equals(tokens.get(previous).text())) continue;
                int next = nextCode(tokens, index + 1);
                if (next < 0 || tokens.get(next).type() != McfppTokenTypes.IDENTIFIER) continue;
                kind = McfppSymbolKind.TYPE;
                nameSearchStart = index + 1;
            } else if ("enum".equals(text)) {
                kind = McfppSymbolKind.ENUM;
                nameSearchStart = index + 1;
            } else {
                continue;
            }

            int name = nextIdentifier(tokens, nameSearchStart, 32);
            if (name < 0) continue;
            int body = findNext(tokens, name + 1, McfppTokenTypes.LEFT_BRACE, MAX_HEADER_TOKENS, true);
            int end = tokens.get(name).end();
            if (body >= 0) {
                Integer close = matches.braces().get(body);
                end = close == null ? source.length() : tokens.get(close).end();
            }
            int declarationStart = modifierStart(tokens, index);
            int headerEnd = body < 0 ? end : tokens.get(body).start();
            Draft type = new Draft(
                    kind,
                    tokens.get(name).text(),
                    null,
                    declarationStart,
                    tokens.get(name).start(),
                    headerEnd,
                    body < 0 ? -1 : tokens.get(body).start(),
                    end,
                    declarationStart,
                    end,
                    signature(source, declarationStart, headerEnd),
                    documentationFor(tokens, documentation, index)
            );
            int angle = nextCode(tokens, name + 1);
            if (angle >= 0 && "<".equals(tokens.get(angle).text())) {
                int depth = 0;
                for (int cursor = angle; cursor < tokens.size(); cursor++) {
                    if ("<".equals(tokens.get(cursor).text())) depth++;
                    else if (">".equals(tokens.get(cursor).text()) && --depth == 0) {
                        type.openReadOnlyParameters = angle;
                        type.closeReadOnlyParameters = cursor;
                        break;
                    }
                    if (tokens.get(cursor).type() == McfppTokenTypes.LEFT_BRACE) break;
                }
            }
            drafts.add(type);
        }
    }

    static void parseAliases(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"typealias".equals(tokens.get(index).text())) continue;
            int as = findText(tokens, index + 1, "as", MAX_HEADER_TOKENS, true);
            int name = as < 0 ? -1 : nextIdentifier(tokens, as + 1, 16);
            if (name < 0) continue;
            int end = declarationEnd(tokens, name, source.length());
            drafts.add(new Draft(
                    McfppSymbolKind.TYPE_ALIAS,
                    tokens.get(name).text(),
                    null,
                    tokens.get(index).start(),
                    tokens.get(name).start(),
                    end,
                    -1,
                    end,
                    tokens.get(index).start(),
                    end,
                    signature(source, tokens.get(index).start(), end),
                    documentationFor(tokens, documentation, index)
            ));
        }
    }

    static void parseFunctions(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
        List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"func".equals(tokens.get(index).text())) continue;
            int open = findFunctionParameterOpen(tokens, index + 1, matches);
            if (open < 0) continue;
            int beforeParameters = previousCode(tokens, open - 1);
            int readOnlyOpen = -1;
            int readOnlyClose = -1;
            if (beforeParameters >= 0 && ">".equals(tokens.get(beforeParameters).text())) {
                readOnlyClose = beforeParameters;
                readOnlyOpen = matchingOpeningAngle(tokens, readOnlyClose, index + 1);
            }
            int name = previousIdentifier(tokens, readOnlyOpen >= 0 ? readOnlyOpen - 1 : open - 1, index);
            if (name < 0) continue;
            Integer close = matches.parentheses().get(open);
            int afterParameters = close == null ? open + 1 : close + 1;
            int body = findNext(tokens, afterParameters, McfppTokenTypes.LEFT_BRACE, MAX_HEADER_TOKENS, true);
            int assignment = findText(tokens, afterParameters, "=", MAX_HEADER_TOKENS, true);
            int semicolon = findNext(tokens, afterParameters, McfppTokenTypes.SEMICOLON, MAX_HEADER_TOKENS, false);
            if (assignment >= 0 && (body < 0 || assignment < body)) body = -1;

            int end;
            if (body >= 0) {
                Integer bodyClose = matches.braces().get(body);
                end = bodyClose == null ? source.length() : tokens.get(bodyClose).end();
            } else if (semicolon >= 0) {
                end = tokens.get(semicolon).end();
            } else {
                end = close == null ? tokens.get(name).end() : tokens.get(close).end();
            }
            int declarationStart = modifierStart(tokens, index);
            int headerBoundary = body >= 0
                    ? tokens.get(body).start()
                    : assignment >= 0 ? tokens.get(assignment).start() : end;
            int contextOpen = -1, contextClose = -1;
            int preceding = previousCode(tokens, tokenAtOffset(tokens, declarationStart) - 1);
            if (preceding >= 0 && tokens.get(preceding).type() == McfppTokenTypes.RIGHT_PARENTHESIS) {
                for (var pair : matches.parentheses().entrySet()) if (pair.getValue() == preceding) {
                    int keyword = previousCode(tokens, pair.getKey() - 1);
                    if (keyword >= 0 && tokens.get(keyword).text().equals("context")) {
                        contextOpen = pair.getKey(); contextClose = preceding;
                        declarationStart = tokens.get(keyword).start();
                    }
                    break;
                }
            }
            Draft function = new Draft(
                    McfppSymbolKind.FUNCTION,
                    tokens.get(name).text(),
                    null,
                    declarationStart,
                    tokens.get(name).start(),
                    headerBoundary,
                    body < 0 ? -1 : tokens.get(body).start(),
                    end,
                    declarationStart,
                    end,
                    signature(source, declarationStart, headerBoundary),
                    documentationFor(tokens, documentation, index)
            );
            function.openParenthesis = open;
            function.closeParenthesis = close == null ? -1 : close;
            function.openReadOnlyParameters = readOnlyOpen;
            function.closeReadOnlyParameters = readOnlyClose;
            function.openContextParameters = contextOpen;
            function.closeContextParameters = contextClose;
            drafts.add(function);
        }
    }

    static void parseConstructors(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!List.of("constructor", "new").contains(tokens.get(index).text())) continue;
            int previous = previousCode(tokens, index - 1);
            if (previous >= 0 && tokens.get(previous).type() == McfppTokenTypes.DOT) continue;
            int open = findNext(tokens, index + 1, McfppTokenTypes.LEFT_PARENTHESIS, 32, true);
            if (open < 0) continue;
            Integer close = matches.parentheses().get(open);
            int body = findNext(tokens, close == null ? open + 1 : close + 1,
                    McfppTokenTypes.LEFT_BRACE, MAX_HEADER_TOKENS, true);
            Integer bodyClose = body < 0 ? null : matches.braces().get(body);
            int end = bodyClose == null ? (close == null ? tokens.get(index).end() : tokens.get(close).end())
                    : tokens.get(bodyClose).end();
            int headerEnd = body < 0 ? end : tokens.get(body).start();
            Draft constructor = new Draft(
                    McfppSymbolKind.CONSTRUCTOR,
                    tokens.get(index).text(),
                    null,
                    tokens.get(index).start(),
                    tokens.get(index).start(),
                    headerEnd,
                    body < 0 ? -1 : tokens.get(body).start(),
                    end,
                    tokens.get(index).start(),
                    end,
                    signature(source, tokens.get(index).start(), headerEnd),
                    documentationFor(tokens, documentation, index)
            );
            constructor.openParenthesis = open;
            constructor.closeParenthesis = close == null ? -1 : close;
            drafts.add(constructor);
        }
    }

    static String parseNamespace(List<Token> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"namespace".equals(tokens.get(index).text())) continue;
            StringBuilder result = new StringBuilder();
            for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
                Token token = tokens.get(cursor);
                if (token.type() == McfppTokenTypes.SEMICOLON || token.type() == McfppTokenTypes.LEFT_BRACE ||
                        token.newlineBefore()) break;
                if (McfppNames.isIdentifier(token.text()) || token.type() == McfppTokenTypes.DOT) {
                    result.append(token.text());
                }
            }
            return result.toString();
        }
        return "";
    }

    static List<McfppImport> parseImports(List<Token> tokens) {
        List<McfppImport> result = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!"import".equals(tokens.get(index).text())) continue;
            int next = nextCode(tokens, index + 1);
            if (next >= 0 && "var".equals(tokens.get(next).text())) continue;
            StringBuilder namespace = new StringBuilder();
            String importedName = null;
            String alias = null;
            boolean afterColon = false;
            for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
                Token token = tokens.get(cursor);
                if (token.type() == McfppTokenTypes.SEMICOLON || token.type() == McfppTokenTypes.LEFT_BRACE ||
                        token.newlineBefore()) break;
                if ("as".equals(token.text())) {
                    int aliasIndex = nextIdentifier(tokens, cursor + 1, 4);
                    alias = aliasIndex < 0 ? null : tokens.get(aliasIndex).text();
                    break;
                }
                if (token.type() == McfppTokenTypes.COLON) {
                    afterColon = true;
                } else if (!afterColon && (McfppNames.isIdentifier(token.text()) ||
                        token.type() == McfppTokenTypes.DOT)) {
                    namespace.append(token.text());
                } else if (afterColon && (McfppNames.isIdentifier(token.text()) || "*".equals(token.text()))) {
                    importedName = token.text();
                }
            }
            if (!namespace.isEmpty() && importedName != null) {
                result.add(new McfppImport(namespace.toString(), importedName, alias));
            }
        }
        return result;
    }

    static String documentationFor(
            List<Token> tokens,
            Map<Integer, String> documentation,
            int declarationKeyword
    ) {
        String direct = documentation.get(declarationKeyword);
        if (direct != null) return direct;
        for (int index = declarationKeyword - 1; index >= 0; index--) {
            Token token = tokens.get(index);
            if (token.newlineBefore() || token.type() != McfppTokenTypes.MODIFIER_KEYWORD) break;
            String value = documentation.get(index);
            if (value != null) return value;
        }
        return null;
    }

    static int modifierStart(List<Token> tokens, int keywordIndex) {
        int result = tokens.get(keywordIndex).start();
        for (int index = keywordIndex - 1; index >= 0; index--) {
            Token token = tokens.get(index);
            // `entity var` and `entity func` are members of the entity reference.
            // The `entity` token starts its own line, so allow that one newline
            // boundary while other modifiers must stay on the same line.
            boolean entityMember = "entity".equals(token.text()) &&
                    (token.type() == McfppTokenTypes.TYPE_KEYWORD || token.type() == McfppTokenTypes.MODIFIER_KEYWORD ||
                            token.type() == McfppTokenTypes.DECLARATION_KEYWORD);
            if (tokens.get(index + 1).newlineBefore() && !(entityMember && index == keywordIndex - 1)) break;
            if (token.type() != McfppTokenTypes.MODIFIER_KEYWORD && !entityMember) break;
            result = token.start();
        }
        return result;
    }

    static int declarationEnd(List<Token> tokens, int fromIndex, int fallback) {
        for (int index = fromIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.SEMICOLON) return token.end();
            if (token.newlineBefore() || token.type() == McfppTokenTypes.LEFT_BRACE ||
                    token.type() == McfppTokenTypes.RIGHT_BRACE) return token.start();
        }
        return fallback;
    }

    static int fieldDeclarationEnd(List<Token> tokens, int fromIndex, int fallback) {
        int braces = 0, parentheses = 0, brackets = 0;
        int end = tokens.get(fromIndex).end();
        for (int index = fromIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            IElementType type = token.type();
            boolean balanced = braces == 0 && parentheses == 0 && brackets == 0;
            if (balanced) {
                if (type == McfppTokenTypes.SEMICOLON) return token.end();
                if (type == McfppTokenTypes.RIGHT_BRACE) return end;
                String previous = tokens.get(index - 1).text();
                if (token.newlineBefore() && !Set.of("=", ":", ",", "->").contains(previous)) return end;
            }
            if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            else if (type == McfppTokenTypes.RIGHT_BRACE) braces--;
            else if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS) parentheses--;
            else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
            else if (type == McfppTokenTypes.RIGHT_BRACKET) brackets--;
            end = token.end();
        }
        return Math.min(end, fallback);
    }

    static final class Draft {
        final McfppSymbolKind kind;
        final String name;
        String owner;
        final int declarationStart;
        final int nameOffset;
        final int headerEnd;
        final int bodyStart;
        final int endOffset;
        final int scopeStart;
        final int scopeEnd;
        final String signature;
        final String documentation;
        int openParenthesis = -1;
        int closeParenthesis = -1;
        int openReadOnlyParameters = -1;
        int closeReadOnlyParameters = -1;
        int openContextParameters = -1;
        int closeContextParameters = -1;

        Draft(
                McfppSymbolKind kind,
                String name,
                String owner,
                int declarationStart,
                int nameOffset,
                int headerEnd,
                int bodyStart,
                int endOffset,
                int scopeStart,
                int scopeEnd,
                String signature,
                String documentation
        ) {
            this.kind = kind;
            this.name = name;
            this.owner = owner;
            this.declarationStart = declarationStart;
            this.nameOffset = nameOffset;
            this.headerEnd = headerEnd;
            this.bodyStart = bodyStart;
            this.endOffset = endOffset;
            this.scopeStart = scopeStart;
            this.scopeEnd = scopeEnd;
            this.signature = signature;
            this.documentation = documentation;
        }

        McfppSymbol toSymbol(String namespace) {
            return new McfppSymbol(
                    kind, name, namespace, owner, declarationStart, nameOffset, headerEnd, bodyStart, endOffset,
                    scopeStart, scopeEnd, signature, documentation
            );
        }
    }
}
