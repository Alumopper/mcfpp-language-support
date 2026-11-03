package top.mcfpp.intellij.mni;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import top.mcfpp.intellij.typesyntax.mcfppLexer;
import top.mcfpp.intellij.typesyntax.mcfppParser;

import java.util.*;

/** Parses source types without evaluating readonly arguments or loading a provider. */
final class MniTypeSyntax {
    static final Set<String> BUILTINS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "int", "bool", "byte", "short", "long", "float", "double", "string", "entity", "text", "nbt",
            "any", "object", "pos2", "pos3", "range", "Type", "ByteArray", "IntArray", "LongArray", "ScoreRef",
            "NBTRef", "list", "map", "dict", "ImmutableList", "void")));
    record Name(String text, int start, int end, int arguments, boolean applied) {
        String simpleName() { return text.substring(text.lastIndexOf(':') + 1); }
        String namespace() { int colon = text.lastIndexOf(':'); return colon < 0 ? null : text.substring(0, colon); }
        int identifierStart() { return end - simpleName().length(); }
        boolean builtin() { return BUILTINS.contains(text) || text.matches("vec[0-9]+"); }
    }
    record Problem(int start, int end, String message) {}
    record Result(List<Name> names, List<Problem> problems, int typeEnd) {}
    record Arity(int required, int total) {}

    static Arity arity(com.intellij.psi.PsiElement target) {
        if (target == null || !(target.getContainingFile() instanceof top.mcfpp.intellij.lang.McfppFile)) return null;
        String source = target.getContainingFile().getText().substring(target.getTextOffset());
        var lexer = new mcfppLexer(CharStreams.fromString(source)); lexer.removeErrorListeners();
        var parser = new mcfppParser(new CommonTokenStream(lexer)); parser.removeErrorListeners();
        var declaration = parser.declarationName();
        if (parser.getNumberOfSyntaxErrors() > 0) return null;
        var params = declaration.readOnlyParams();
        if (params == null || params.readOnlyParameterList() == null) return new Arity(0, 0);
        var entries = params.readOnlyParameterList().readOnlyParameter();
        return new Arity((int) entries.stream().filter(entry -> entry.value() == null).count(), entries.size());
    }

    static Result parse(String source, boolean parameter, boolean macro, boolean allowEmpty) {
        List<Name> names = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        String input = source;
        if (parameter) {
            // Native metadata uses the first '=' as the default-value delimiter.
            int equals = input.indexOf('=');
            if (equals >= 0) input = input.substring(0, equals);
        }
        int end = input.stripTrailing().length();
        if (input.isBlank()) {
            if (!allowEmpty) problems.add(new Problem(0, Math.max(1, end), "Missing MCFPP type"));
            return new Result(names, problems, end);
        }
        String trimmed = input.trim();
        int start = input.indexOf(trimmed);
        if (BUILTINS.contains(trimmed) || trimmed.equals("null")) {
            names.add(new Name(trimmed, start, end, 0, false));
            return new Result(names, problems, end);
        }
        if (macro) input = maskCallbackModes(input);
        var lexer = new mcfppLexer(CharStreams.fromString(input));
        var errors = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object offending, int line,
                                              int column, String message, RecognitionException failure) {
                int offset = offending instanceof Token token ? Math.max(0, token.getStartIndex()) : Math.min(end, column);
                problems.add(new Problem(Math.min(offset, end), Math.min(end, offset + 1), "Invalid MCFPP type: " + message));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(errors);
        var tokens = new CommonTokenStream(lexer);
        var parser = new mcfppParser(tokens);
        parser.removeErrorListeners(); parser.addErrorListener(errors);
        var tree = parser.functionReturnType();
        if (tokens.LA(1) != Token.EOF) {
            Token token = tokens.LT(1);
            problems.add(new Problem(token.getStartIndex(), Math.min(end, token.getStopIndex() + 1), "Unexpected text after MCFPP type"));
        }
        collect(tree, names);
        for (Name name : names) if (name.text.equals("NBTRef") && name.applied && name.arguments != 1)
            problems.add(new Problem(name.start, name.end, "NBTRef requires exactly one type argument"));
        return new Result(List.copyOf(names), List.copyOf(problems), end);
    }

    private static void collect(ParseTree tree, List<Name> names) {
        if (tree instanceof mcfppParser.TypeBodyContext body) {
            var head = body.className();
            Token first = head == null ? body.start : head.start;
            Token last = head == null ? body.start : head.stop;
            if (head != null || body.Identifier() != null || body.normalType() != null || body.VecType() != null ||
                    body.LIST() != null || body.IMMUTABLE_LIST() != null || body.MAP() != null || body.DICT() != null) {
                if (body.normalType() != null) last = body.normalType().stop;
                int count = body.readOnlyArgs() == null || body.readOnlyArgs().expressionList() == null ? 0 :
                        body.readOnlyArgs().expressionList().expression().size();
                if (body.type() != null) count = 1;
                names.add(new Name(head == null ? first.getText() : head.getText(), first.getStartIndex(),
                        last.getStopIndex() + 1, count, body.readOnlyArgs() != null || body.type() != null));
            }
        }
        // Named type arguments can be parsed as identifier expressions by the source grammar.
        if (tree instanceof mcfppParser.ExpressionContext expression &&
                expression.getParent() instanceof mcfppParser.ExpressionListContext &&
                expression.getParent().getParent() instanceof mcfppParser.ReadOnlyArgsContext &&
                expression.getText().matches("[\\p{L}_][\\p{L}\\p{N}_]*(?:[.:][\\p{L}_][\\p{L}\\p{N}_]*)*")) {
            names.add(new Name(expression.getText(), expression.start.getStartIndex(), expression.stop.getStopIndex() + 1, 0, false));
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) collect(tree.getChild(i), names);
    }

    private static String maskCallbackModes(String input) {
        var lexer = new mcfppLexer(CharStreams.fromString(input));
        lexer.removeErrorListeners();
        var tokens = lexer.getAllTokens();
        var result = new StringBuilder(input);
        Token previous = null;
        for (Token token : tokens) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (previous != null && Set.of("(", ",").contains(previous.getText()) &&
                    Set.of("ref", "const", "value", "inline").contains(token.getText()) &&
                    token.getStopIndex() + 1 < input.length() && Character.isWhitespace(input.charAt(token.getStopIndex() + 1))) {
                for (int i = token.getStartIndex(); i <= token.getStopIndex(); i++) result.setCharAt(i, ' ');
            }
            previous = token;
        }
        return result.toString();
    }
}
