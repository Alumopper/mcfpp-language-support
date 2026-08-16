package top.mcfpp.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A compact, error-tolerant declaration model shared by indexing and editor features. */
public final class McfppFileModel {
    private static final int MAX_HEADER_TOKENS = 256;

    private final String namespace;
    private final List<McfppImport> imports;
    private final List<McfppSymbol> symbols;

    private McfppFileModel(String namespace, List<McfppImport> imports, List<McfppSymbol> symbols) {
        this.namespace = namespace;
        this.imports = List.copyOf(imports);
        this.symbols = List.copyOf(symbols);
    }

    public static @NotNull McfppFileModel parse(@NotNull CharSequence sourceSequence) {
        String source = sourceSequence.toString();
        Lexed lexed = lex(source);
        List<Token> tokens = lexed.tokens();
        MatchData matches = matchDelimiters(tokens);
        String namespace = parseNamespace(tokens);
        List<McfppImport> imports = parseImports(tokens);

        List<Draft> drafts = new ArrayList<>();
        parseTypes(source, tokens, lexed.documentationBefore(), matches, drafts);
        parseAliases(source, tokens, lexed.documentationBefore(), drafts);
        parseFunctions(source, tokens, lexed.documentationBefore(), matches, drafts);
        parseConstructors(source, tokens, lexed.documentationBefore(), matches, drafts);

        List<RangeOwner> typeOwners = ownerRanges(drafts, McfppSymbolKind.TYPE, McfppSymbolKind.ENUM);
        List<RangeOwner> functionOwners = ownerRanges(drafts, McfppSymbolKind.FUNCTION, McfppSymbolKind.CONSTRUCTOR);
        assignOwners(drafts, typeOwners);
        parseFields(source, tokens, lexed.documentationBefore(), typeOwners, functionOwners, drafts);
        parseForeachVariables(source, tokens, matches, typeOwners, functionOwners, drafts);
        parseImplicitFields(source, tokens, lexed.documentationBefore(), matches, drafts);
        parseParameters(source, tokens, matches, typeOwners, drafts);
        parseEnumMembers(source, tokens, matches, drafts);

        List<McfppSymbol> symbols = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) symbols.add(draft.toSymbol(namespace));
        symbols.sort(Comparator.comparingInt(McfppSymbol::nameOffset).thenComparing(symbol -> symbol.kind().ordinal()));
        return new McfppFileModel(namespace, imports, symbols);
    }

    public @NotNull String namespace() {
        return namespace;
    }

    public @NotNull List<McfppImport> imports() {
        return imports;
    }

    public @NotNull List<McfppSymbol> symbols() {
        return symbols;
    }

    public @NotNull McfppFileModel withNamespace(@NotNull String inferredNamespace) {
        if (!namespace.isEmpty() || inferredNamespace.isEmpty()) return this;
        List<McfppSymbol> namespacedSymbols = symbols.stream()
                .map(symbol -> new McfppSymbol(
                        symbol.kind(), symbol.name(), inferredNamespace, symbol.owner(), symbol.declarationStart(),
                        symbol.nameOffset(), symbol.headerEnd(), symbol.bodyStart(), symbol.endOffset(),
                        symbol.scopeStart(), symbol.scopeEnd(), symbol.signature(), symbol.documentation()
                ))
                .toList();
        return new McfppFileModel(inferredNamespace, imports, namespacedSymbols);
    }

    public @NotNull List<McfppSymbol> projectSymbols() {
        return symbols.stream().filter(symbol -> symbol.kind().isProjectSymbol()).toList();
    }

    public @Nullable McfppSymbol declarationAt(int offset) {
        for (McfppSymbol symbol : symbols) {
            if (symbol.nameOffset() <= offset && offset < symbol.nameOffset() + symbol.name().length()) return symbol;
        }
        return null;
    }

    public @NotNull List<McfppSymbol> visibleLocalSymbols(String name, int offset) {
        return symbols.stream()
                .filter(symbol -> symbol.name().equals(name))
                .filter(symbol -> symbol.kind() == McfppSymbolKind.PARAMETER ||
                        symbol.kind() == McfppSymbolKind.VARIABLE || symbol.kind() == McfppSymbolKind.FIELD)
                .filter(symbol -> symbol.nameOffset() < offset && symbol.scopeContains(offset))
                .sorted(Comparator.comparingInt(McfppSymbol::nameOffset).reversed())
                .toList();
    }

    public @NotNull List<McfppSymbol> structureChildren(@Nullable McfppSymbol parent) {
        if (parent == null) {
            return symbols.stream()
                    .filter(symbol -> symbol.isTopLevel() && symbol.kind() != McfppSymbolKind.FIELD)
                    .toList();
        }
        if (parent.kind() != McfppSymbolKind.TYPE && parent.kind() != McfppSymbolKind.ENUM) return List.of();
        return symbols.stream()
                .filter(symbol -> parent.name().equals(symbol.owner()))
                .filter(symbol -> symbol.kind() != McfppSymbolKind.PARAMETER && symbol.kind() != McfppSymbolKind.VARIABLE)
                .toList();
    }

    public boolean importExposes(String candidateNamespace, String candidateName, String usedName) {
        for (McfppImport value : imports) {
            if (value.namespace().equals(candidateNamespace) && value.exposes(usedName) &&
                    value.originalName(usedName).equals(candidateName)) return true;
        }
        return false;
    }

    private static void parseTypes(
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
            drafts.add(new Draft(
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
            ));
        }
    }

    private static void parseAliases(
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

    private static void parseFunctions(
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
            drafts.add(function);
        }
    }

    private static void parseConstructors(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"constructor".equals(tokens.get(index).text())) continue;
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
                    "constructor",
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

    private static void assignOwners(List<Draft> drafts, List<RangeOwner> typeOwners) {
        for (Draft draft : drafts) {
            if (draft.kind == McfppSymbolKind.TYPE || draft.kind == McfppSymbolKind.ENUM ||
                    draft.kind == McfppSymbolKind.TYPE_ALIAS) continue;
            draft.owner = innermostOwner(typeOwners, draft.nameOffset);
        }
    }

    private static void parseFields(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            List<RangeOwner> typeOwners,
            List<RangeOwner> functionOwners,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = tokens.get(index).text();
            if (!("var".equals(keyword) || "global".equals(keyword) || "dynamic".equals(keyword) ||
                    "const".equals(keyword))) continue;
            int next = nextCode(tokens, index + 1);
            if (next >= 0 && "func".equals(tokens.get(next).text())) continue;
            int name = nextIdentifier(tokens, index + 1, 16);
            if (name < 0) continue;
            String functionOwner = innermostOwner(functionOwners, tokens.get(name).start());
            String typeOwner = innermostOwner(typeOwners, tokens.get(name).start());
            McfppSymbolKind kind = functionOwner == null ? McfppSymbolKind.FIELD : McfppSymbolKind.VARIABLE;
            RangeOwner scope = innermostRange(functionOwner == null ? typeOwners : functionOwners, tokens.get(name).start());
            int end = declarationEnd(tokens, name, source.length());
            drafts.add(new Draft(
                    kind,
                    tokens.get(name).text(),
                    typeOwner,
                    tokens.get(index).start(),
                    tokens.get(name).start(),
                    end,
                    -1,
                    end,
                    scope == null ? 0 : scope.start(),
                    scope == null ? source.length() : scope.end(),
                    signature(source, tokens.get(index).start(), end),
                    documentationFor(tokens, documentation, index)
            ));
        }
    }

    private static void parseParameters(
            String source,
            List<Token> tokens,
            MatchData matches,
            List<RangeOwner> typeOwners,
            List<Draft> drafts
    ) {
        List<Draft> containers = new ArrayList<>(drafts.stream()
                .filter(draft -> draft.kind == McfppSymbolKind.FUNCTION || draft.kind == McfppSymbolKind.CONSTRUCTOR)
                .toList());
        for (Draft container : containers) {
            parseParameterRange(source, tokens, container,
                    container.openReadOnlyParameters, container.closeReadOnlyParameters, drafts);
            parseParameterRange(source, tokens, container,
                    container.openParenthesis, container.closeParenthesis, drafts);
        }
    }

    private static void parseParameterRange(
            String source,
            List<Token> tokens,
            Draft container,
            int open,
            int close,
            List<Draft> drafts
    ) {
        if (open < 0 || close <= open) return;
        int segmentStart = open + 1;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        for (int index = segmentStart; index <= close; index++) {
            Token token = index < close ? tokens.get(index) : null;
            IElementType type = token == null ? null : token.type();
            if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS) parentheses--;
            else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
            else if (type == McfppTokenTypes.RIGHT_BRACKET) brackets--;
            else if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            else if (type == McfppTokenTypes.RIGHT_BRACE) braces--;
            else if (token != null && "<".equals(token.text())) angles++;
            else if (token != null && ">".equals(token.text()) && angles > 0) angles--;

            if (index == close || type == McfppTokenTypes.COMMA &&
                    parentheses == 0 && brackets == 0 && braces == 0 && angles == 0) {
                addParameter(source, tokens, container, segmentStart, index, drafts);
                segmentStart = index + 1;
            }
        }
    }

    private static void addParameter(
            String source,
            List<Token> tokens,
            Draft container,
            int start,
            int end,
            List<Draft> drafts
    ) {
        int as = findTextInRange(tokens, start, end, "as");
        int name = as < 0 ? -1 : firstIdentifier(tokens, start, as);
        if (name < 0) return;
        int first = nextCode(tokens, start);
        int last = previousCode(tokens, end - 1);
        String parameterSignature = first < 0 || last < first
                ? tokens.get(name).text()
                : signature(source, tokens.get(first).start(), tokens.get(last).end());
        drafts.add(new Draft(
                McfppSymbolKind.PARAMETER,
                tokens.get(name).text(),
                container.owner,
                tokens.get(name).start(),
                tokens.get(name).start(),
                tokens.get(name).end(),
                -1,
                tokens.get(name).end(),
                container.declarationStart,
                container.endOffset,
                parameterSignature,
                null
        ));
    }

    private static void parseForeachVariables(
            String source,
            List<Token> tokens,
            MatchData matches,
            List<RangeOwner> typeOwners,
            List<RangeOwner> functionOwners,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"for".equals(tokens.get(index).text())) continue;
            int open = findNext(tokens, index + 1, McfppTokenTypes.LEFT_PARENTHESIS, 16, true);
            Integer close = open < 0 ? null : matches.parentheses().get(open);
            if (open < 0 || close == null) continue;
            int name = nextIdentifier(tokens, open + 1, Math.min(16, close - open));
            int colon = name < 0 ? -1 : findTypeInRange(tokens, name + 1, close, McfppTokenTypes.COLON);
            if (name < 0 || colon < 0) continue;

            int body = nextCode(tokens, close + 1);
            if (body < 0) continue;
            int scopeStart = tokens.get(body).start();
            int scopeEnd;
            if (tokens.get(body).type() == McfppTokenTypes.LEFT_BRACE) {
                Integer bodyClose = matches.braces().get(body);
                scopeEnd = bodyClose == null ? source.length() : tokens.get(bodyClose).end();
            } else {
                RangeOwner function = innermostRange(functionOwners, tokens.get(name).start());
                int fallback = function == null ? source.length() : function.end();
                scopeEnd = declarationEnd(tokens, body, fallback);
            }
            drafts.add(new Draft(
                    McfppSymbolKind.VARIABLE,
                    tokens.get(name).text(),
                    innermostOwner(typeOwners, tokens.get(name).start()),
                    tokens.get(index).start(),
                    tokens.get(name).start(),
                    tokens.get(name).end(),
                    -1,
                    tokens.get(name).end(),
                    scopeStart,
                    scopeEnd,
                    tokens.get(name).text(),
                    null
            ));
        }
    }

    private static void parseImplicitFields(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
            List<Draft> drafts
    ) {
        List<Draft> containers = drafts.stream()
                .filter(draft -> draft.kind == McfppSymbolKind.TYPE && draft.bodyStart >= 0)
                .toList();
        List<RangeOwner> callableRanges = ownerRanges(
                drafts, McfppSymbolKind.FUNCTION, McfppSymbolKind.CONSTRUCTOR);
        java.util.Set<Integer> existingOffsets = drafts.stream()
                .filter(draft -> draft.kind == McfppSymbolKind.FIELD)
                .map(draft -> draft.nameOffset)
                .collect(java.util.stream.Collectors.toSet());
        for (Draft container : containers) {
            int open = tokenAtOffset(tokens, container.bodyStart);
            Integer close = open < 0 ? null : matches.braces().get(open);
            if (open < 0 || close == null) continue;
            int braces = 0;
            int parentheses = 0;
            int brackets = 0;
            for (int index = open + 1; index < close; index++) {
                Token token = tokens.get(index);
                IElementType type = token.type();
                if (type == McfppTokenTypes.LEFT_BRACE) braces++;
                else if (type == McfppTokenTypes.RIGHT_BRACE) braces--;
                else if (type == McfppTokenTypes.LEFT_PARENTHESIS) parentheses++;
                else if (type == McfppTokenTypes.RIGHT_PARENTHESIS) parentheses--;
                else if (type == McfppTokenTypes.LEFT_BRACKET) brackets++;
                else if (type == McfppTokenTypes.RIGHT_BRACKET) brackets--;
                if (braces != 0 || parentheses != 0 || brackets != 0 ||
                        type != McfppTokenTypes.IDENTIFIER || existingOffsets.contains(token.start()) ||
                        innermostRange(callableRanges, token.start()) != null) continue;
                int next = nextCode(tokens, index + 1);
                if (next < 0 || !"as".equals(tokens.get(next).text())) continue;

                int declarationStartIndex = lineStartToken(tokens, index, open + 1);
                int end = declarationEnd(tokens, next, source.length());
                drafts.add(new Draft(
                        McfppSymbolKind.FIELD,
                        token.text(),
                        container.name,
                        tokens.get(declarationStartIndex).start(),
                        token.start(),
                        end,
                        -1,
                        end,
                        container.declarationStart,
                        container.endOffset,
                        signature(source, tokens.get(declarationStartIndex).start(), end),
                        documentationInRange(documentation, declarationStartIndex, index)
                ));
                existingOffsets.add(token.start());
            }
        }
    }

    private static void parseEnumMembers(
            String source,
            List<Token> tokens,
            MatchData matches,
            List<Draft> drafts
    ) {
        List<Draft> enums = drafts.stream().filter(draft -> draft.kind == McfppSymbolKind.ENUM).toList();
        for (Draft enumDraft : enums) {
            int open = tokenAtOffset(tokens, enumDraft.bodyStart);
            Integer close = open < 0 ? null : matches.braces().get(open);
            if (open < 0 || close == null) continue;
            int nesting = 0;
            boolean expectMember = true;
            for (int index = open + 1; index < close; index++) {
                IElementType type = tokens.get(index).type();
                if (type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.LEFT_PARENTHESIS ||
                        type == McfppTokenTypes.LEFT_BRACKET) nesting++;
                if (type == McfppTokenTypes.RIGHT_BRACE || type == McfppTokenTypes.RIGHT_PARENTHESIS ||
                        type == McfppTokenTypes.RIGHT_BRACKET) nesting--;
                if (nesting == 0 && expectMember && type == McfppTokenTypes.IDENTIFIER) {
                    Token token = tokens.get(index);
                    drafts.add(new Draft(
                            McfppSymbolKind.ENUM_MEMBER,
                            token.text(),
                            enumDraft.name,
                            token.start(),
                            token.start(),
                            token.end(),
                            -1,
                            token.end(),
                            enumDraft.declarationStart,
                            enumDraft.endOffset,
                            token.text(),
                            null
                    ));
                    expectMember = false;
                } else if (nesting == 0 && type == McfppTokenTypes.COMMA) {
                    expectMember = true;
                }
            }
        }
    }

    private static String parseNamespace(List<Token> tokens) {
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

    private static List<McfppImport> parseImports(List<Token> tokens) {
        List<McfppImport> result = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!"import".equals(tokens.get(index).text())) continue;
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

    private static List<RangeOwner> ownerRanges(List<Draft> drafts, McfppSymbolKind... kinds) {
        List<RangeOwner> result = new ArrayList<>();
        for (Draft draft : drafts) {
            boolean matches = false;
            for (McfppSymbolKind kind : kinds) if (draft.kind == kind) matches = true;
            if (matches && draft.endOffset > draft.declarationStart) {
                result.add(new RangeOwner(draft.name, draft.declarationStart, draft.endOffset));
            }
        }
        result.sort(Comparator.comparingInt(range -> range.end() - range.start()));
        return result;
    }

    private static @Nullable String innermostOwner(List<RangeOwner> ranges, int offset) {
        RangeOwner range = innermostRange(ranges, offset);
        return range == null ? null : range.name();
    }

    private static @Nullable RangeOwner innermostRange(List<RangeOwner> ranges, int offset) {
        for (RangeOwner range : ranges) if (range.start() < offset && offset < range.end()) return range;
        return null;
    }

    private static MatchData matchDelimiters(List<Token> tokens) {
        Map<Integer, Integer> braces = new HashMap<>();
        Map<Integer, Integer> parentheses = new HashMap<>();
        Deque<Integer> braceStack = new ArrayDeque<>();
        Deque<Integer> parenthesisStack = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            IElementType type = tokens.get(index).type();
            if (type == McfppTokenTypes.LEFT_BRACE) braceStack.push(index);
            else if (type == McfppTokenTypes.RIGHT_BRACE && !braceStack.isEmpty()) {
                int open = braceStack.pop();
                braces.put(open, index);
                braces.put(index, open);
            } else if (type == McfppTokenTypes.LEFT_PARENTHESIS) parenthesisStack.push(index);
            else if (type == McfppTokenTypes.RIGHT_PARENTHESIS && !parenthesisStack.isEmpty()) {
                int open = parenthesisStack.pop();
                parentheses.put(open, index);
                parentheses.put(index, open);
            }
        }
        return new MatchData(Map.copyOf(braces), Map.copyOf(parentheses));
    }

    private static Lexed lex(String source) {
        Lexer lexer = new McfppLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        Map<Integer, String> docs = new LinkedHashMap<>();
        boolean newline = false;
        String pendingDocumentation = null;
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            String text = source.substring(start, end);
            if (type == McfppTokenTypes.WHITE_SPACE) {
                newline |= text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
            } else if (type == McfppTokenTypes.DOC_COMMENT) {
                pendingDocumentation = normalizeDocumentation(text);
                newline = true;
            } else if (type != McfppTokenTypes.COMMENT) {
                int tokenIndex = tokens.size();
                tokens.add(new Token(type, text, start, end, newline));
                if (pendingDocumentation != null) docs.put(tokenIndex, pendingDocumentation);
                pendingDocumentation = null;
                newline = false;
            }
            lexer.advance();
        }
        return new Lexed(List.copyOf(tokens), Map.copyOf(docs));
    }

    private static String normalizeDocumentation(String text) {
        String value = text;
        if (value.startsWith("#{") && value.endsWith("}#")) value = value.substring(2, value.length() - 2);
        else if (value.startsWith("###") && value.endsWith("###") && value.length() >= 6) {
            value = value.substring(3, value.length() - 3);
        } else if (value.startsWith("###")) value = value.substring(3);
        return value.strip();
    }

    private static String documentationFor(
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

    private static String documentationInRange(Map<Integer, String> documentation, int start, int end) {
        for (int index = start; index <= end; index++) {
            String value = documentation.get(index);
            if (value != null) return value;
        }
        return null;
    }

    private static int lineStartToken(List<Token> tokens, int index, int lowerBound) {
        int result = index;
        for (int cursor = index - 1; cursor >= lowerBound; cursor--) {
            Token token = tokens.get(cursor);
            if (tokens.get(result).newlineBefore() || token.type() == McfppTokenTypes.SEMICOLON ||
                    token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.RIGHT_BRACE) break;
            result = cursor;
        }
        return result;
    }

    private static int modifierStart(List<Token> tokens, int keywordIndex) {
        int result = tokens.get(keywordIndex).start();
        for (int index = keywordIndex - 1; index >= 0; index--) {
            Token token = tokens.get(index);
            if (token.newlineBefore() || token.type() != McfppTokenTypes.MODIFIER_KEYWORD) break;
            result = token.start();
        }
        return result;
    }

    private static int declarationEnd(List<Token> tokens, int fromIndex, int fallback) {
        for (int index = fromIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.SEMICOLON) return token.end();
            if (token.newlineBefore() || token.type() == McfppTokenTypes.LEFT_BRACE ||
                    token.type() == McfppTokenTypes.RIGHT_BRACE) return token.start();
        }
        return fallback;
    }

    private static int nextIdentifier(List<Token> tokens, int start, int limit) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (token.type() == McfppTokenTypes.IDENTIFIER) return index;
            if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    private static int previousIdentifier(List<Token> tokens, int start, int lowerBound) {
        for (int index = start; index > lowerBound; index--) {
            if (tokens.get(index).type() == McfppTokenTypes.IDENTIFIER) return index;
        }
        return -1;
    }

    private static int firstIdentifier(List<Token> tokens, int start, int end) {
        for (int index = start; index < end; index++) {
            if (tokens.get(index).type() == McfppTokenTypes.IDENTIFIER) return index;
        }
        return -1;
    }

    private static int nextCode(List<Token> tokens, int index) {
        return index < tokens.size() ? index : -1;
    }

    private static int previousCode(List<Token> tokens, int index) {
        return index >= 0 ? index : -1;
    }

    private static int findNext(
            List<Token> tokens,
            int start,
            IElementType wanted,
            int limit,
            boolean stopAtSemicolon
    ) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            IElementType type = tokens.get(index).type();
            if (type == wanted) return index;
            if (type == McfppTokenTypes.RIGHT_BRACE || stopAtSemicolon && type == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    private static int findFunctionParameterOpen(List<Token> tokens, int start, MatchData matches) {
        int end = Math.min(tokens.size(), start + MAX_HEADER_TOKENS);
        int angles = 0;
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if ("<".equals(token.text())) {
                angles++;
                continue;
            }
            if (">".equals(token.text()) && angles > 0) {
                angles--;
                continue;
            }
            if (token.type() == McfppTokenTypes.LEFT_PARENTHESIS && angles == 0 &&
                    matches.parentheses().containsKey(index)) {
                int previous = previousCode(tokens, index - 1);
                if (previous >= start && tokens.get(previous).type() == McfppTokenTypes.IDENTIFIER) return index;
                if (previous >= start && ">".equals(tokens.get(previous).text())) {
                    int readOnlyOpen = matchingOpeningAngle(tokens, previous, start);
                    int name = previousIdentifier(tokens, readOnlyOpen - 1, start - 1);
                    if (readOnlyOpen >= start && name >= start) return index;
                }
            }
            if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    private static int matchingOpeningAngle(List<Token> tokens, int close, int lowerBound) {
        int depth = 0;
        for (int index = close; index >= lowerBound; index--) {
            String text = tokens.get(index).text();
            if (">".equals(text)) depth++;
            else if ("<".equals(text) && --depth == 0) return index;
        }
        return -1;
    }

    private static int findText(List<Token> tokens, int start, String wanted, int limit, boolean stopAtSemicolon) {
        int end = Math.min(tokens.size(), start + limit);
        for (int index = start; index < end; index++) {
            Token token = tokens.get(index);
            if (wanted.equals(token.text())) return index;
            if (token.type() == McfppTokenTypes.RIGHT_BRACE ||
                    stopAtSemicolon && token.type() == McfppTokenTypes.SEMICOLON) return -1;
        }
        return -1;
    }

    private static int findTextInRange(List<Token> tokens, int start, int end, String wanted) {
        for (int index = start; index < end; index++) if (wanted.equals(tokens.get(index).text())) return index;
        return -1;
    }

    private static int findTypeInRange(
            List<Token> tokens,
            int start,
            int end,
            IElementType wanted
    ) {
        for (int index = start; index < end; index++) if (tokens.get(index).type() == wanted) return index;
        return -1;
    }

    private static int tokenAtOffset(List<Token> tokens, int offset) {
        for (int index = 0; index < tokens.size(); index++) if (tokens.get(index).start() == offset) return index;
        return -1;
    }

    private static String signature(String source, int start, int end) {
        if (start < 0 || end < start || start > source.length()) return "";
        String value = source.substring(start, Math.min(end, source.length()));
        return value.replaceAll("\\s+", " ").strip();
    }

    private record Token(IElementType type, String text, int start, int end, boolean newlineBefore) {
    }

    private record Lexed(List<Token> tokens, Map<Integer, String> documentationBefore) {
    }

    private record MatchData(Map<Integer, Integer> braces, Map<Integer, Integer> parentheses) {
    }

    private record RangeOwner(String name, int start, int end) {
    }

    private static final class Draft {
        private final McfppSymbolKind kind;
        private final String name;
        private String owner;
        private final int declarationStart;
        private final int nameOffset;
        private final int headerEnd;
        private final int bodyStart;
        private final int endOffset;
        private final int scopeStart;
        private final int scopeEnd;
        private final String signature;
        private final String documentation;
        private int openParenthesis = -1;
        private int closeParenthesis = -1;
        private int openReadOnlyParameters = -1;
        private int closeReadOnlyParameters = -1;

        private Draft(
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

        private McfppSymbol toSymbol(String namespace) {
            return new McfppSymbol(
                    kind, name, namespace, owner, declarationStart, nameOffset, headerEnd, bodyStart, endOffset,
                    scopeStart, scopeEnd, signature, documentation
            );
        }
    }
}
