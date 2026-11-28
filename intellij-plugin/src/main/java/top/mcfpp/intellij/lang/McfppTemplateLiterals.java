package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import top.mcfpp.language.TemplateLiterals;
import top.mcfpp.language.VersionPreprocessor;

import java.util.*;
import java.util.regex.Pattern;

/** Binds the shared literal context to cached PSI declarations, including attached libraries. */
final class McfppTemplateLiterals implements TemplateLiterals.Resolver {
    record Field(PsiFile file, McfppSymbol symbol, String type) {}
    record EnumAccess(PsiFile file, List<McfppSymbol> members, boolean value) {}

    static EnumAccess enumAccess(PsiFile file, String receiver, int offset) {
        var context = new McfppTemplateLiterals(file);
        if (receiver == null) {
            var types = context.literals.expectedValueTypes(offset).stream().distinct().toList();
            return types.size() == 1 ? context.enumType(types.getFirst(), false) : null;
        }
        if (context.model.visibleLocalSymbols(receiver, offset).isEmpty()) {
            var declaration = context.enumType(receiver, false);
            if (declaration != null) return declaration;
        }
        return context.enumType(context.valueType(receiver, offset), true);
    }

    private EnumAccess enumType(String type, boolean value) {
        if (type == null || !type.matches("[\\p{L}_$][\\p{L}\\p{N}_.:$]*")) return null;
        var target = typeDefinition(type, file, model);
        if (target == null) return null;
        var declarations = McfppFileModels.get(target.getContainingFile());
        var owner = declarations.declarationAt(target.getTextOffset());
        if (owner == null || owner.kind() != McfppSymbolKind.ENUM) return null;
        return new EnumAccess(target.getContainingFile(), declarations.symbols().stream().filter(it ->
                it.kind() == McfppSymbolKind.ENUM_MEMBER && owner.name().equals(it.owner())).toList(), value);
    }
    private final PsiFile file;
    private final McfppFileModel model;
    private final TemplateLiterals literals;
    private final Map<String, List<Field>> schemas = new HashMap<>();

    private McfppTemplateLiterals(PsiFile file) {
        this.file = file;
        this.model = McfppFileModels.get(file);
        String source = file.getViewProvider().getContents().toString();
        try { source = VersionPreprocessor.process(source, McfppNamespaceResolver.infer(file).targetVersion()); }
        catch (VersionPreprocessor.Error ignored) { }
        literals = new TemplateLiterals(source, this);
    }

    static boolean complete(PsiFile file, int offset, CompletionResultSet result) {
        var context = new McfppTemplateLiterals(file);
        var literal = context.literals.at(offset);
        if (literal == null) return false;
        var site = context.literals.keySite(literal, offset);
        if (site == null || context.literals.templateTypes(literal).isEmpty()) return false;
        var candidates = context.literals.bestTypes(literal);
        for (var field : context.literals.completions(literal, site)) {
            Field declaration = candidates.stream().flatMap(type -> context.fieldSymbols(type, new HashSet<>()).stream())
                    .filter(it -> it.symbol.name().equals(field.name())).findFirst().orElseThrow();
            PsiElement target = McfppSymbolResolver.elementFor(declaration.file, declaration.symbol);
            var lookup = target == null ? LookupElementBuilder.create(field.name()) : LookupElementBuilder.create(target, field.name());
            result.addElement(lookup.withTypeText(field.type(), true)
                    .withIcon(McfppCompletionContributor.icon(McfppSymbolKind.FIELD))
                    .withTailText(field.hasDefault() ? " (default)" : field.type().endsWith("?") ? " (optional)" : " (required)", true)
                    .withInsertHandler((insertion, item) -> {
                        int tail = insertion.getTailOffset();
                        var document = insertion.getDocument();
                        int keyStart = site.start();
                        char first = document.getCharsSequence().charAt(keyStart);
                        if (first == '\'' || first == '"') {
                            int keyEnd = keyStart + 1;
                            while (keyEnd < document.getTextLength() && document.getCharsSequence().charAt(keyEnd) != first) keyEnd++;
                            if (keyEnd == document.getTextLength()) keyEnd = tail - 1;
                            document.replaceString(keyStart, keyEnd + 1, field.name());
                            tail = keyStart + field.name().length();
                        } else {
                            int keyEnd = tail;
                            while (keyEnd < document.getTextLength() && Character.isUnicodeIdentifierPart(document.getCharsSequence().charAt(keyEnd))) keyEnd++;
                            if (keyEnd > tail) document.deleteString(tail, keyEnd);
                        }
                        insertion.setTailOffset(tail);
                        int next = tail;
                        while (next < document.getTextLength() && Character.isWhitespace(document.getCharsSequence().charAt(next))) next++;
                        if (next >= document.getTextLength() || document.getCharsSequence().charAt(next) != ':') {
                            document.insertString(tail, ": ");
                            insertion.setTailOffset(tail + 2);
                            insertion.getEditor().getCaretModel().moveToOffset(tail + 2);
                        } else insertion.getEditor().getCaretModel().moveToOffset(tail);
                        if (insertion.getCompletionChar() == ':') insertion.setAddCompletionChar(false);
                    }));
        }
        result.stopHere();
        return true;
    }

    static String expressionType(PsiFile file, String expression, int offset) {
        var context = new McfppTemplateLiterals(file);
        String type = context.valueType(expression, offset);
        return type == null ? null : context.qualify(type, file, context.model);
    }

    static List<Field> memberFields(PsiFile file, String type, int offset) {
        var context = new McfppTemplateLiterals(file);
        var fields = context.fieldSymbols(type, new HashSet<>());
        return fields == null ? List.of() : fields.stream().filter(field -> context.accessible(field, offset)).toList();
    }

    static List<PsiElement> memberTargets(PsiFile file, int offset, String name) {
        String source = file.getViewProvider().getContents().toString();
        int separator = offset - 1;
        while (separator >= 0 && Character.isWhitespace(source.charAt(separator))) separator--;
        String receiver = top.mcfpp.language.MemberExpressions.receiverBefore(source, separator);
        if (receiver == null) return List.of();
        String type = expressionType(file, receiver, offset);
        return type == null ? List.of() : memberFields(file, type, offset).stream()
                .filter(field -> field.symbol.name().equals(name))
                .map(field -> McfppSymbolResolver.elementFor(field.file, field.symbol)).filter(Objects::nonNull).toList();
    }

    /** Null means this is not a typed literal key. Empty means an unknown key in a typed literal. */
    static List<PsiElement> fieldTargets(PsiFile file, int offset) {
        if (!(file instanceof McfppFile)) return null;
        var context = new McfppTemplateLiterals(file);
        var literal = context.literals.at(offset);
        if (literal == null || context.literals.templateTypes(literal).isEmpty()) return null;
        var entry = context.literals.entries(literal).stream()
                .filter(it -> it.colon() >= 0 && it.keyStart() <= offset && offset <= it.keyEnd()).findFirst().orElse(null);
        if (entry == null) return null;
        return context.literals.bestTypes(literal).stream()
                .flatMap(type -> context.fieldSymbols(type, new HashSet<>()).stream())
                .filter(it -> it.symbol.name().equals(entry.name()))
                .map(it -> McfppSymbolResolver.elementFor(it.file, it.symbol)).filter(Objects::nonNull).distinct().toList();
    }

    @Override public List<TemplateLiterals.Field> fields(String type, int offset) {
        List<Field> fields = fieldSymbols(type, new HashSet<>());
        return fields == null ? null : fields.stream().map(it -> new TemplateLiterals.Field(
                it.symbol.name(), it.type, it.symbol.signature().contains("="), accessible(it, offset))).toList();
    }

    private boolean accessible(Field field, int offset) {
        if (!field.symbol.signature().matches("(?s).*\\b(?:private|protected)\\b.*")) return true;
        return field.file.equals(file) && model.symbols().stream().anyMatch(it -> it.kind().isType() &&
                it.name().equals(field.symbol.owner()) && it.contains(offset));
    }

    private List<Field> fieldSymbols(String type, Set<String> seen) {
        type = type.strip().replaceFirst("\\?$", "");
        if (!seen.add(type)) return null;
        if (schemas.containsKey(type)) return schemas.get(type);
        var objectFields = top.mcfpp.language.StructuralTypes.fields(type);
        if (objectFields != null) {
            List<Field> fields = new ArrayList<>();
            int origin = Math.max(0, file.getText().indexOf(type));
            for (var field : objectFields) {
                int nameOffset = file.getText().indexOf(field.name(), origin);
                if (nameOffset < 0) nameOffset = origin;
                var symbol = new McfppSymbol(McfppSymbolKind.FIELD, field.name(), model.namespace(), null,
                        nameOffset, nameOffset, nameOffset + field.name().length(), -1, nameOffset + field.name().length(),
                        0, file.getTextLength(), "var " + field.name() + ": " + field.type(), null);
                fields.add(new Field(file, symbol, field.type()));
            }
            return fields;
        }
        for (char operator : new char[]{'|', '&'}) {
            var components = top.mcfpp.language.StructuralTypes.components(type, operator);
            if (components.isEmpty()) continue;
            List<List<Field>> branches = new ArrayList<>();
            for (String component : components) {
                var branch = fieldSymbols(component, new HashSet<>(seen));
                if (branch == null) return null;
                branches.add(branch);
            }
            Map<String, List<Field>> grouped = new LinkedHashMap<>();
            for (var branch : branches) for (var field : branch) grouped.computeIfAbsent(field.symbol.name(), key -> new ArrayList<>()).add(field);
            List<Field> fields = new ArrayList<>();
            for (var entry : grouped.entrySet()) {
                if (operator == '|' && branches.stream().anyMatch(branch -> branch.stream().noneMatch(field -> field.symbol.name().equals(entry.getKey())))) continue;
                var first = entry.getValue().getFirst();
                var types = entry.getValue().stream().map(Field::type).distinct().toList();
                fields.add(new Field(first.file, first.symbol, types.size() == 1 ? types.getFirst() : "(" + String.join(" " + operator + " ", types) + ")"));
            }
            return fields;
        }
        PsiElement target = typeDefinition(type, file, model);
        if (target == null) return null;
        PsiFile ownerFile = target.getContainingFile();
        McfppFileModel ownerModel = McfppFileModels.get(ownerFile);
        McfppSymbol owner = ownerModel.declarationAt(target.getTextOffset());
        if (owner == null) return null;
        if (owner.kind() == McfppSymbolKind.TYPE_ALIAS) {
            var alias = Pattern.compile("(?s)\\btypealias\\s+(.+?)\\s+as\\s+").matcher(owner.signature());
            return !alias.find() ? null : fieldSymbols(qualify(alias.group(1).strip(), ownerFile, ownerModel), seen);
        }
        if (!owner.signature().matches("(?s)^(?:(?:abstract|final)\\s+)?data\\b.*")) return null;
        Map<String, String> substitutions = new HashMap<>();
        String head = owner.signature().substring(owner.signature().indexOf(owner.name()) + owner.name().length()).strip();
        if (head.startsWith("<")) {
            int end = matchingAngle(head);
            if (end >= 0) {
                List<String> parameters = splitTypes(head.substring(1, end));
                List<String> arguments = type.endsWith(">") ? splitTypes(type.substring(type.indexOf('<') + 1, type.length() - 1)) : List.of();
                for (int i = 0; i < Math.min(parameters.size(), arguments.size()); i++)
                    substitutions.put(parameters.get(i).split("[:=]", 2)[0].strip(), arguments.get(i));
                head = head.substring(end + 1).strip();
            }
        }
        List<Field> fields = new ArrayList<>();
        for (McfppSymbol symbol : ownerModel.symbols()) {
            if (symbol.kind() != McfppSymbolKind.FIELD || !owner.name().equals(symbol.owner()) ||
                    symbol.signature().matches("(?s).*\\b(?:static|entity)\\s+(?:var|const)\\b.*")) continue;
            String fieldType = declaredType(symbol.signature());
            if (fieldType == null) fieldType = "any";
            fields.add(new Field(ownerFile, symbol, qualify(substitute(fieldType, substitutions), ownerFile, ownerModel)));
        }
        if (head.startsWith(":")) for (String parent : splitTypes(head.substring(1))) {
            List<Field> inherited = fieldSymbols(qualify(substitute(parent, substitutions), ownerFile, ownerModel), new HashSet<>(seen));
            if (inherited != null) for (Field field : inherited)
                if (fields.stream().noneMatch(it -> it.symbol.name().equals(field.symbol.name()))) fields.add(field);
        }
        schemas.put(type, List.copyOf(fields));
        return schemas.get(type);
    }

    private PsiElement typeDefinition(String type, PsiFile contextFile, McfppFileModel contextModel) {
        String base = type.substring(0, type.indexOf('<') < 0 ? type.length() : type.indexOf('<')).strip().replaceFirst("\\?$", "");
        String name = base.substring(base.lastIndexOf(':') + 1);
        String namespace = base.contains(":") ? base.substring(0, base.lastIndexOf(':')) : null;
        if (namespace != null) for (var imported : contextModel.imports())
            if (imported.isWildcard() && namespace.equals(imported.alias())) { namespace = imported.namespace(); break; }
        for (var imported : contextModel.imports()) if (imported.exposes(name) && !imported.isWildcard()) {
            name = imported.originalName(name); namespace = imported.namespace(); break;
        }
        for (McfppSymbol symbol : contextModel.symbols()) if (symbol.kind().isType() && symbol.name().equals(name) &&
                (namespace == null || namespace.equals(symbol.namespace()))) return McfppSymbolResolver.elementFor(contextFile, symbol);
        for (PsiElement element : McfppSymbolResolver.findTypes(file.getProject(), name, true)) {
            var symbol = McfppFileModels.get(element.getContainingFile()).declarationAt(element.getTextOffset());
            if (symbol != null && (namespace != null ? namespace.equals(symbol.namespace()) :
                    contextModel.namespace().equals(symbol.namespace()) || contextModel.importExposes(symbol.namespace(), symbol.name(), base) ||
                            McfppStandardLibrary.isImplicitNamespace(symbol.namespace()))) return element;
        }
        return null;
    }

    private String qualify(String type, PsiFile contextFile, McfppFileModel contextModel) {
        PsiElement target = typeDefinition(type, contextFile, contextModel);
        if (target == null) return type;
        var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
        int generic = type.indexOf('<');
        return symbol == null ? type : symbol.qualifiedName() + (generic < 0 ? type.endsWith("?") ? "?" : "" : type.substring(generic));
    }

    @Override public List<String> argumentTypes(int expressionStart) {
        var call = McfppCallContext.atCaret(file, expressionStart);
        if (call == null) return List.of();
        var before = file.getText().substring(call.openParenthesis().getTextOffset() + 1, expressionStart);
        var named = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=\\s*$").matcher(before);
        String argumentName = named.find() ? named.group(1) : null;
        List<McfppCallableInfo> callables = new ArrayList<>(McfppSymbolResolver.findCallables(call.callee()));
        List<PsiElement> targets = McfppSymbolResolver.resolve(call.callee());
        Map<String, PsiFile> origins = new HashMap<>();
        for (PsiElement target : targets) {
            var symbol = McfppFileModels.get(target.getContainingFile()).declarationAt(target.getTextOffset());
            if (symbol != null) origins.put(symbol.signature(), target.getContainingFile());
            if (symbol != null && (symbol.kind() == McfppSymbolKind.VARIABLE || symbol.kind() == McfppSymbolKind.PARAMETER)) {
                String type = McfppTypes.declaredType(symbol);
                if (type != null && type.startsWith("(")) callables.add(McfppCallableInfo.fromSignature("func value" + type));
            }
        }
        List<String> result = new ArrayList<>();
        for (var callable : callables) {
            PsiFile origin = origins.getOrDefault(callable.signature(), file);
            McfppFileModel originModel = McfppFileModels.get(origin);
            Map<String, String> substitutions = new HashMap<>();
            int generic = callable.signature().indexOf('<');
            int normal = callable.signature().indexOf('(');
            if (generic >= 0 && generic < normal) {
                String suffix = callable.signature().substring(generic);
                int end = matchingAngle(suffix);
                if (end >= 0) {
                    var names = splitTypes(suffix.substring(1, end));
                    for (int i = 0; i < Math.min(names.size(), call.readOnlyArguments().size()); i++)
                        substitutions.put(names.get(i).split(":", 2)[0].strip(), call.readOnlyArguments().get(i).text());
                }
            }
            var parameters = callable.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                String parameter = McfppParameterSyntax.declaration(parameters.get(i).substring(callable.signature())).text();
                String name = parameter.split(":", 2)[0].strip();
                if (argumentName == null ? i != call.currentArgument() : !argumentName.equals(name)) continue;
                String type = declaredType(parameter);
                if (type == null && !parameter.contains(":")) type = parameter.strip();
                if (type != null) result.add(qualify(substitute(type, substitutions), origin, originModel));
            }
        }
        return result;
    }

    @Override public String valueType(String expression, int offset) {
        expression = expression.strip();
        if (expression.endsWith("!!")) expression = expression.substring(0, expression.length() - 2).strip();
        expression = expression.replace("?.", ".");
        var locals = model.visibleLocalSymbols(expression, offset);
        if (!locals.isEmpty()) {
            var local = locals.getFirst();
            if (local.kind() == McfppSymbolKind.VARIABLE && McfppTypes.declaredType(local) == null) {
                int equals = local.signature().indexOf('=');
                if (equals >= 0 && local.nameOffset() < offset) {
                    String initializer = local.signature().substring(equals + 1).strip();
                    if (initializer.matches("[\\p{L}_$][\\p{L}\\p{N}_.:$]*")) {
                        String inferred = valueType(initializer, local.nameOffset() - 1);
                        if (enumType(inferred, true) != null) return inferred;
                    }
                }
            }
            if ("<lambda>".equals(local.owner()) && McfppTypes.declaredType(local) == null) {
                String receiver = top.mcfpp.language.MemberExpressions.tryReadReceiver(file.getText(), local.scopeStart());
                if (receiver != null) {
                    String ref = valueType(receiver, local.scopeStart() - 1);
                    if (ref != null && ref.startsWith("NBTRef<") && ref.endsWith(">")) return ref.substring(7, ref.length() - 1);
                }
            }
        }
        var nbtFactory = java.util.regex.Pattern.compile("^(?:[\\w.]+:)?nbt(?:Storage|Block|Entity)Ref\\s*<(.+)>\\s*\\(").matcher(expression);
        if (nbtFactory.find() && top.mcfpp.language.MemberExpressions.endsAtCall(expression, nbtFactory.end() - 1))
            return "NBTRef<" + nbtFactory.group(1).strip() + ">";
        if (expression.equals("true") || expression.equals("false")) return "bool";
        if (expression.equals("null")) return "null";
        if (expression.startsWith("\"") || expression.startsWith("'")) return "string";
        if (expression.matches("[-+]?\\d+")) return "int";
        if (expression.matches("[-+]?\\d+\\.\\d+[fF]?")) return "float";
        int separator = expression.lastIndexOf('.');
        if (separator > 0) {
            String receiver = expression.substring(0, separator);
            String member = expression.substring(separator + 1).strip();
            String owner = valueType(receiver, offset);
            var enumeration = enumType(owner, true);
            if (enumeration != null) {
                if (model.visibleLocalSymbols(receiver, offset).isEmpty() && enumType(receiver, false) != null)
                    return enumeration.members.stream().anyMatch(it -> it.name().equals(member)) ? owner : null;
                return top.mcfpp.language.EnumProperties.TYPES.get(member);
            }
            List<Field> fields = owner == null ? null : fieldSymbols(owner, new HashSet<>());
            if (fields != null) for (var field : fields) if (field.symbol.name().equals(expression.substring(separator + 1))) return field.type;
        }
        return McfppTypes.receiverType(expression, model, offset);
    }
    @Override public List<String> returnTypes(int offset) {
        return model.symbols().stream().filter(it -> it.contains(offset) &&
                (it.kind() == McfppSymbolKind.FUNCTION || it.kind() == McfppSymbolKind.CONSTRUCTOR))
                .min(Comparator.comparingInt(it -> it.endOffset() - it.declarationStart()))
                .map(it -> it.kind() == McfppSymbolKind.CONSTRUCTOR ? it.owner() : McfppCallableInfo.from(it).returnType())
                .map(List::of).orElse(List.of());
    }
    @Override public boolean isAssignable(String expected, String actual) {
        if (actual.equals("null")) return expected.endsWith("?") || expected.equals("any");
        return expected.equals(actual) || expected.equals("any") || expected.endsWith(":" + actual) ||
                List.of("float", "double", "long").contains(expected) && actual.equals("int");
    }
    private static String declaredType(String signature) {
        int colon = signature.indexOf(':');
        if (colon < 0) return null;
        String type = signature.substring(colon + 1).strip();
        int assignment = type.indexOf('=');
        if (assignment >= 0) type = type.substring(0, assignment).strip();
        return type;
    }
    private static String substitute(String type, Map<String, String> substitutions) {
        var matcher = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*").matcher(type);
        return matcher.replaceAll(it -> java.util.regex.Matcher.quoteReplacement(substitutions.getOrDefault(it.group(), it.group())));
    }
    private static int matchingAngle(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) { if (text.charAt(i) == '<') depth++; if (text.charAt(i) == '>' && --depth == 0) return i; }
        return -1;
    }
    private static List<String> splitTypes(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            if (c == '>' || c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) { result.add(text.substring(start, i).strip()); start = i + 1; }
        }
        if (start < text.length()) result.add(text.substring(start).strip());
        return result;
    }
}
