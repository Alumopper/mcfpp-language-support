package top.mcfpp.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static top.mcfpp.intellij.lang.McfppTokenScanner.*;
import static top.mcfpp.intellij.lang.McfppDeclarationParser.*;

/** Resolves declaration ownership and creates locals, parameters, and enum members. */
final class McfppScopeBinder {
    static record RangeOwner(String name, int start, int end) { }

    static void assignOwners(List<Draft> drafts, List<RangeOwner> typeOwners) {
        for (Draft draft : drafts) {
            if (draft.kind == McfppSymbolKind.TYPE || draft.kind == McfppSymbolKind.ENUM ||
                    draft.kind == McfppSymbolKind.TYPE_ALIAS) continue;
            draft.owner = innermostOwner(typeOwners, draft.nameOffset);
        }
    }

    static void parseFields(
            String source,
            List<Token> tokens,
            Map<Integer, String> documentation,
            MatchData matches,
            List<RangeOwner> typeOwners,
            List<RangeOwner> functionOwners,
            List<Draft> drafts
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = tokens.get(index).text();
            if (!List.of("var", "const", "external").contains(keyword)) continue;
            int previous = previousCode(tokens, index - 1);
            if (previous >= 0 && "var".equals(keyword) &&
                    List.of("const", "external").contains(tokens.get(previous).text())) continue;
            int next = nextCode(tokens, index + 1);
            if (next >= 0 && "func".equals(tokens.get(next).text())) continue;
            int name = next;
            if (name < 0 || tokens.get(name).type() != McfppTokenTypes.IDENTIFIER) continue;
            int afterName = nextCode(tokens, name + 1);
            if (afterName >= 0 && "as".equals(tokens.get(afterName).text())) continue;
            String functionOwner = innermostOwner(functionOwners, tokens.get(name).start());
            String typeOwner = innermostOwner(typeOwners, tokens.get(name).start());
            McfppSymbolKind kind = functionOwner == null ? McfppSymbolKind.FIELD : McfppSymbolKind.VARIABLE;
            RangeOwner scope = innermostRange(functionOwner == null ? typeOwners : functionOwners, tokens.get(name).start());
            if (functionOwner != null) {
                for (var brace : matches.braces().entrySet()) {
                    int open = brace.getKey();
                    int close = brace.getValue();
                    if (open >= close || tokens.get(open).start() >= tokens.get(name).start() ||
                            tokens.get(close).end() <= tokens.get(name).start()) continue;
                    int start = tokens.get(open).start();
                    int finish = tokens.get(close).end();
                    if (scope == null || finish - start < scope.end() - scope.start()) {
                        scope = new RangeOwner(functionOwner, start, finish);
                    }
                }
            }
            int end = fieldDeclarationEnd(tokens, name, source.length());
            drafts.add(new Draft(
                    kind,
                    tokens.get(name).text(),
                    typeOwner,
                    modifierStart(tokens, index),
                    tokens.get(name).start(),
                    end,
                    -1,
                    end,
                    scope == null ? 0 : scope.start(),
                    scope == null ? source.length() : scope.end(),
                    signature(source, modifierStart(tokens, index), end),
                    documentationFor(tokens, documentation, index)
            ));
        }
    }

    static void parseParameters(
            String source,
            List<Token> tokens,
            MatchData matches,
            List<RangeOwner> typeOwners,
            List<Draft> drafts
    ) {
        List<Draft> containers = new ArrayList<>(drafts.stream()
                .filter(draft -> draft.kind == McfppSymbolKind.FUNCTION || draft.kind == McfppSymbolKind.CONSTRUCTOR ||
                        draft.kind == McfppSymbolKind.TYPE && draft.openReadOnlyParameters >= 0)
                .toList());
        for (Draft container : containers) {
            parseParameterRange(source, tokens, container,
                    container.openReadOnlyParameters, container.closeReadOnlyParameters, drafts);
            parseParameterRange(source, tokens, container,
                    container.openParenthesis, container.closeParenthesis, drafts);
            parseParameterRange(source, tokens, container,
                    container.openContextParameters, container.closeContextParameters, drafts);
        }
    }

    static void parseParameterRange(
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
                addParameter(source, tokens, container, segmentStart, index,
                        open == container.openReadOnlyParameters, drafts);
                segmentStart = index + 1;
            }
        }
    }

    static void parseLambdaParameters(String source, List<Token> tokens, MatchData matches, List<Draft> drafts) {
        Map<Integer, String> contextTypes = new LinkedHashMap<>();
        for (var pair : matches.braces().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            int open = pair.getKey(), close = pair.getValue();
            if (close <= open || drafts.stream().anyMatch(d -> d.bodyStart == tokens.get(open).start())) continue;
            String entry = top.mcfpp.language.ContextBuiltins.entryBefore(source, tokens.get(open).start());
            String ambient = null;
            for (var context : contextTypes.entrySet())
                if (context.getKey() < open && matches.braces().get(context.getKey()) > close) ambient = context.getValue();
            String contextType = entry == null ? null : top.mcfpp.language.ContextBuiltins.callbackType(entry, ambient);
            if (contextType != null) contextTypes.put(open, contextType);
            int arrow = -1;
            for (int i = open + 1; i < close; i++) {
                Token token = tokens.get(i);
                if (token.type() == McfppTokenTypes.LEFT_BRACE || token.type() == McfppTokenTypes.SEMICOLON) break;
                if ("->".equals(token.text())) { arrow = i; break; }
                if ("=".equals(token.text())) break;
            }
            int end = tokens.get(close).end();
            if (arrow > 0) {
                int start = open + 1;
                for (int i = start; i <= arrow; i++) {
                    if (i < arrow && tokens.get(i).type() != McfppTokenTypes.COMMA) continue;
                    int name = nextCode(tokens, start);
                    if (name >= 0 && name < i && tokens.get(name).type() == McfppTokenTypes.IDENTIFIER) {
                        String signature = source.substring(tokens.get(name).start(), tokens.get(i).start()).trim();
                        if (contextType != null && !signature.contains(":")) signature += ": " + contextType;
                        drafts.add(new Draft(McfppSymbolKind.PARAMETER, tokens.get(name).text(), "<lambda>",
                                tokens.get(name).start(), tokens.get(name).start(), tokens.get(name).end(), -1,
                                tokens.get(name).end(), tokens.get(open).start(), end, signature, "Lambda parameter"));
                    }
                    start = i + 1;
                }
            } else {
                if (contextType != null) continue;
                // An implicit parameter exists only in a callback-shaped expression, not a control-flow block.
                int before = previousCode(tokens, open - 1);
                if (before < 0 || !Set.of(")", "=", "(", ",", "tryRead").contains(tokens.get(before).text())) continue;
                if (")".equals(tokens.get(before).text())) {
                    int left = before - 1, depth = 1;
                    for (; left >= 0; left--) {
                        if (")".equals(tokens.get(left).text())) depth++;
                        if ("(".equals(tokens.get(left).text()) && --depth == 0) break;
                    }
                    int keyword = previousCode(tokens, left - 1);
                    if (keyword >= 0 && Set.of("if", "while", "for", "execute", "store").contains(tokens.get(keyword).text())) continue;
                }
                for (int i = open + 1; i < close; i++) if ("it".equals(tokens.get(i).text())) {
                    drafts.add(new Draft(McfppSymbolKind.PARAMETER, "it", "<lambda>", tokens.get(open).start(),
                            tokens.get(open).start(), tokens.get(open).end(), -1, tokens.get(open).end(),
                            tokens.get(open).start(), end, "it", "Implicit lambda parameter"));
                    break;
                }
            }
        }
    }

    static void addParameter(
            String source,
            List<Token> tokens,
            Draft container,
            int start,
            int end,
            boolean readOnly,
            List<Draft> drafts
    ) {
        int name = nextCode(tokens, start);
        while (name >= 0 && name < end && "var".equals(tokens.get(name).text())) {
            name = nextCode(tokens, name + 1);
        }
        if (name < 0 || name >= end || tokens.get(name).type() != McfppTokenTypes.IDENTIFIER) return;
        int afterName = nextCode(tokens, name + 1);
        boolean annotation = afterName >= 0 && afterName < end && tokens.get(afterName).type() == McfppTokenTypes.COLON;
        boolean implicitType = readOnly && (afterName < 0 || afterName >= end || "=".equals(tokens.get(afterName).text()));
        if (!annotation && !implicitType) return;
        int first = nextCode(tokens, start);
        int last = previousCode(tokens, end - 1);
        String parameterSignature = first < 0 || last < first
                ? tokens.get(name).text()
                : signature(source, tokens.get(first).start(), tokens.get(last).end());
        drafts.add(new Draft(
                McfppSymbolKind.PARAMETER,
                tokens.get(name).text(),
                container.kind == McfppSymbolKind.TYPE ? container.name : container.owner,
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

    static void parseForeachVariables(
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

    static void parseEnumMembers(
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

    static List<RangeOwner> ownerRanges(List<Draft> drafts, McfppSymbolKind... kinds) {
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

    static @Nullable String innermostOwner(List<RangeOwner> ranges, int offset) {
        RangeOwner range = innermostRange(ranges, offset);
        return range == null ? null : range.name();
    }

    static @Nullable RangeOwner innermostRange(List<RangeOwner> ranges, int offset) {
        for (RangeOwner range : ranges) if (range.start() < offset && offset < range.end()) return range;
        return null;
    }
}
