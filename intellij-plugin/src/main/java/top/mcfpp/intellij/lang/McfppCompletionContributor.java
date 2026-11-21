package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.ProcessingContext;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.command.McfppCommandContext;
import top.mcfpp.intellij.command.MinecraftCommandCompletionSupport;
import top.mcfpp.language.VersionPreprocessor;

import javax.swing.Icon;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class McfppCompletionContributor extends CompletionContributor implements DumbAware {
    private static final int PROJECT_RESULT_LIMIT = 512;
    private static final List<String> KEYWORDS = List.of(
            "namespace", "import", "from", "as", "typealias", "data", "entity", "object", "enum", "func", "new",
            "operator", "var", "global", "const", "external", "inline", "final", "abstract", "override",
            "public", "protected", "private", "if", "else", "while", "do", "for", "try", "store", "context",
            "return", "break", "continue", "this", "super", "get", "set", "true", "false", "null"
    );

    public McfppCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(
                            @NotNull CompletionParameters parameters,
                            @NotNull ProcessingContext context,
                            @NotNull CompletionResultSet result
                    ) {
                        complete(parameters, result);
                    }
                }
        );
    }

    private static void complete(CompletionParameters parameters, CompletionResultSet result) {
        PsiElement position = parameters.getPosition();
        if (!(position.getContainingFile() instanceof McfppFile file) ||
                McfppReferenceContributor.isNativeJavaTarget(position)) return;

        int offset = parameters.getOffset();
        PsiFile originalFile = parameters.getOriginalFile();
        String originalSource = originalFile.getViewProvider().getContents().toString();
        if (VersionPreprocessor.directivePrefixStart(originalSource, offset) >= 0) {
            for (String directive : List.of("if", "elif", "else", "endif")) {
                String condition = directive.equals("if") || directive.equals("elif") ? " MC >= 26.3" : "";
                result.addElement(LookupElementBuilder.create(directive)
                        .withPresentableText("#" + directive + condition)
                        .withTypeText("Minecraft version", true)
                        .withInsertHandler((context, item) -> {
                            int tail = context.getTailOffset();
                            context.getDocument().insertString(tail, condition);
                            context.setTailOffset(tail + condition.length());
                            context.getEditor().getCaretModel().moveToOffset(context.getTailOffset());
                        }));
            }
            return;
        }
        McfppCommandContext commandContext = McfppCommandContext.at(originalSource, offset);
        if (commandContext != null) {
            completeMinecraftCommand(position, result, commandContext, originalSource);
            return;
        }
        // When the MCFPP LSP server is actively running, defer general MCFPP completions to LSP
        // to prevent duplicate candidates (IntelliJ-exclusive DSB commands above still run).
        if (McfppLspState.isRunning(file.getProject())) {
            return;
        }
        McfppFileModel model = McfppFileModels.get(originalFile instanceof McfppFile ? originalFile : file);
        if (McfppTemplateLiterals.complete(originalFile, offset, result)) return;
        McfppCompletionContext completionContext = McfppCompletionContext.create(parameters, result, model);
        Set<String> seen = new HashSet<>();
        if (completionContext.kind() == McfppCompletionContext.Kind.MEMBER ||
                completionContext.kind() == McfppCompletionContext.Kind.VALUE ||
                completionContext.kind() == McfppCompletionContext.Kind.GLOBAL) {
            int start = Math.max(0, offset - result.getPrefixMatcher().getPrefix().length());
            int dot = start - 1;
            while (dot >= 0 && Character.isWhitespace(originalSource.charAt(dot))) dot--;
            String receiver = top.mcfpp.language.MemberExpressions.receiverBefore(originalSource, dot);
            var enumeration = McfppTemplateLiterals.enumAccess(originalFile, receiver, start);
            if (enumeration != null) {
                if (enumeration.value()) top.mcfpp.language.EnumProperties.TYPES.forEach((name, type) ->
                        result.addElement(LookupElementBuilder.create(name).withTypeText(type, true)
                                .withTailText(" (read-only)", true).withIcon(icon(McfppSymbolKind.FIELD))));
                else for (var member : enumeration.members()) addSymbol(result, seen, member, member.name(), false, 600);
                if (receiver != null) return;
            }
        }
        if (completionContext.kind() == McfppCompletionContext.Kind.GLOBAL) {
            int prefixStart = Math.min(offset, originalSource.length());
            while (prefixStart > 0 && Character.isUnicodeIdentifierPart(originalSource.charAt(prefixStart - 1))) prefixStart--;
            String before = originalSource.substring(0, Math.min(prefixStart, originalSource.length())).stripTrailing();
            addKeywords(result, seen, before.matches("(?s).*\\b(?:const|external)$"),
                    McfppDeclarationSyntax.endsWithValueDeclarationName(before));
        } else if (completionContext.kind() == McfppCompletionContext.Kind.VALUE) {
            for (String keyword : top.mcfpp.language.CompletionScope.VALUE_KEYWORDS)
                result.addElement(LookupElementBuilder.create(keyword).bold());
        } else if (completionContext.kind() == McfppCompletionContext.Kind.TYPE) {
            for (String type : List.of("int", "bool", "byte", "short", "long", "float", "double", "string",
                    "entity", "selector", "text", "nbt", "any", "list", "map", "dict", "type", "vec",
                    "void", "ByteArray", "IntArray", "LongArray", "ScoreRef", "NBTRef", "EntityRef")) {
                result.addElement(LookupElementBuilder.create(type));
            }
        }
        if (completionContext.kind() == McfppCompletionContext.Kind.TYPE) {
            for (String type : top.mcfpp.language.EntityBuiltins.TYPES)
                result.addElement(LookupElementBuilder.create(type).withTypeText("entity subtype", true));
            for (String type : top.mcfpp.language.ContextBuiltins.TYPES)
                result.addElement(LookupElementBuilder.create(type).withTypeText("scoped context", true));
        }
        if (completionContext.qualifier() == null)
            result.addElement(LookupElementBuilder.create("UUID").withTypeText("four-int UUID value", true));
        if (completionContext.kind() == McfppCompletionContext.Kind.MEMBER) {
            String qualifier = completionContext.qualifier();
            for (var field : McfppTemplateLiterals.memberFields(originalFile, qualifier, offset)) {
                if (!seen.add("template-field:" + field.symbol().name())) continue;
                PsiElement target = McfppSymbolResolver.elementFor(field.file(), field.symbol());
                var lookup = target == null ? LookupElementBuilder.create(field.symbol().name())
                        : LookupElementBuilder.create(target, field.symbol().name());
                result.addElement(lookup.withTypeText(field.type(), true).withIcon(icon(McfppSymbolKind.FIELD))
                        .withInsertHandler(McfppCompletionInsertHandler.projectSymbol(field.symbol(), false)));
            }
            String entityRef = top.mcfpp.language.EntityBuiltins.entityRefTemplate(qualifier);
            String builtinOwner = qualifier;
            if (entityRef != null) {
                for (McfppSymbol symbol : entityMembers(model, entityRef)) {
                    if (result.getPrefixMatcher().prefixMatches(symbol.name())) {
                        addSymbol(result, seen, symbol, symbol.name(), false, 150);
                    }
                }
                builtinOwner = entityKind(model, entityRef);
            } else if (McfppTypes.isEntityTemplate(model, qualifier)) {
                result.addElement(LookupElementBuilder.create("summon")
                        .withTailText("(d: nbt): EntityRef<" + qualifier + ">", true)
                        .withTypeText("entity template", true));
                builtinOwner = null;
            }
            var builtinMembers = new java.util.ArrayList<>(top.mcfpp.language.EntityBuiltins.members(builtinOwner));
            builtinMembers.addAll(top.mcfpp.language.NbtBuiltins.members(builtinOwner));
            for (var member : builtinMembers) {
                seen.add("builtin:" + member.name());
                var lookup = LookupElementBuilder.create(member.name())
                    .withTypeText(member.writeType() == null ? member.type() : member.writeType(), true)
                    .withTailText(member.function() ? member.signature().substring(("func " + member.name()).length())
                        : member.name().equals("startInterpolation") ? " { set }"
                        : member.writeType() == null ? "" : " { get; set }", true);
                if (member.function()) lookup = lookup.withInsertHandler(top.mcfpp.language.ContextBuiltins.isEntry(member.name())
                    ? McfppCompletionInsertHandler.contextEntry(member.name().equals("withFacingCtx"))
                    : McfppCompletionInsertHandler.entityMethod(member.name()));
                result.addElement(lookup);
            }
        }

        String referenceType = completionContext.qualifier() == null ? "" : completionContext.qualifier().split("<", 2)[0];
        if (completionContext.kind() == McfppCompletionContext.Kind.MEMBER &&
                Set.of("ScoreRef", "NBTRef").contains(referenceType)) {
            String valueType = "ScoreRef".equals(completionContext.qualifier()) ? "int" : "T";
            result.addElement(LookupElementBuilder.create("tryRead")
                    .withTailText(" { value -> ... }", true).withTypeText("bool; synchronous " + valueType + " snapshot", true));
            result.addElement(LookupElementBuilder.create("set")
                    .withTailText("(value: " + valueType + ")", true).withTypeText("void; writes the target", true));
        }
        if (completionContext.kind() == McfppCompletionContext.Kind.GLOBAL ||
                completionContext.kind() == McfppCompletionContext.Kind.VALUE) {
            Set<String> entries = new HashSet<>();
            for (var entry : top.mcfpp.language.ContextBuiltins.globals()) if (entries.add(entry.name()))
                result.addElement(LookupElementBuilder.create(entry.name()).withTypeText("synchronous context", true)
                    .withTailText(" { ctx -> ... }", true)
                    .withInsertHandler(McfppCompletionInsertHandler.contextEntry(!entry.name().equals("withCurrentCtx"))));
            for (String factory : List.of("scoreRef", "nbtStorageRef", "nbtEntityRef", "nbtBlockRef")) {
                result.addElement(LookupElementBuilder.create(factory).withTypeText("explicit Minecraft address", true));
            }
        }

        model.symbols().stream().sorted(java.util.Comparator.comparingInt(McfppSymbol::nameOffset).reversed())
                .filter(symbol -> isVisible(symbol, offset, completionContext, model))
                .forEach(symbol -> addSymbol(result, seen, symbol, symbol.name(),
                        completionContext.kind() == McfppCompletionContext.Kind.GLOBAL ||
                                completionContext.kind() == McfppCompletionContext.Kind.VALUE ||
                                completionContext.kind() == McfppCompletionContext.Kind.TYPE, 500));

        if (DumbService.isDumb(position.getProject())) return;
        if (completionContext.kind() == McfppCompletionContext.Kind.MEMBER ||
                completionContext.kind() == McfppCompletionContext.Kind.NAMESPACE) {
            addContextualIndexedSymbols(position, result, seen, completionContext);
            return;
        }

        Set<String> names = new java.util.LinkedHashSet<>();
        FileBasedIndex.getInstance().processAllKeys(McfppSymbolIndex.NAME, key -> {
            ProgressManager.checkCanceled();
            if (!McfppSymbolIndex.isInternalKey(key) && result.getPrefixMatcher().prefixMatches(key)) names.add(key);
            return true;
        }, position.getProject());
        for (String name : McfppExternalLibraryIndex.names(position.getProject())) {
            if (result.getPrefixMatcher().prefixMatches(name)) names.add(name);
        }
        for (McfppImport imported : model.imports()) {
            if (!imported.isWildcard() && imported.alias() != null &&
                    result.getPrefixMatcher().prefixMatches(imported.alias())) names.add(imported.importedName());
        }
        int count = 0;
        for (String name : names) {
            ProgressManager.checkCanceled();
            for (PsiElement element : McfppSymbolResolver.findProjectSymbols(position.getProject(), name, true)) {
                if (!(element.getContainingFile() instanceof McfppFile candidateFile)) continue;
                McfppSymbol symbol = McfppFileModels.get(candidateFile).declarationAt(element.getTextOffset());
                if (symbol == null || !symbol.isTopLevel()) continue;
                if (completionContext.kind() == McfppCompletionContext.Kind.TYPE && !symbol.kind().isType()) continue;
                if (completionContext.kind() != McfppCompletionContext.Kind.TYPE && symbol.kind().isType() &&
                        top.mcfpp.language.EntityBuiltins.TYPES.contains(symbol.name())) continue;
                String exposed = McfppImportManager.exposedName(model, symbol);
                if (!result.getPrefixMatcher().prefixMatches(exposed)) continue;
                int priority = symbol.namespace().equals(model.namespace()) ? 400
                        : model.importExposes(symbol.namespace(), symbol.name(), exposed) ? 350
                        : McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) ? 300 : 100;
                if (priority == 100 && result.getPrefixMatcher().getPrefix().isEmpty() &&
                        parameters.getInvocationCount() < 2) continue;
                if (addSymbol(result, seen, symbol, exposed, true, priority) && ++count >= PROJECT_RESULT_LIMIT) return;
            }
        }
    }

    private static boolean addSymbol(CompletionResultSet result, Set<String> seen, McfppSymbol symbol,
                                     String exposedName, boolean unqualified, int priority) {
        if (symbol.kind() == McfppSymbolKind.FIELD && seen.contains("template-field:" + exposedName)) return false;
        boolean local = symbol.kind() == McfppSymbolKind.PARAMETER || symbol.kind() == McfppSymbolKind.VARIABLE;
        String identity = local ? "local:" + exposedName
                : symbol.kind() + "|" + symbol.qualifiedName() + "|" + symbol.signature() + "|" + exposedName;
        if (!seen.add(identity)) return false;
        LookupElementBuilder lookup = LookupElementBuilder.create(identity, exposedName)
                .withIcon(icon(symbol.kind())).withTypeText(symbol.qualifiedName(), true)
                .withInsertHandler(McfppCompletionInsertHandler.projectSymbol(symbol, unqualified));
        if (!symbol.signature().equals(symbol.name())) lookup = lookup.withTailText("  " + symbol.signature(), true);
        result.addElement(PrioritizedLookupElement.withPriority(lookup, priority));
        return true;
    }

    private static void completeMinecraftCommand(
            PsiElement position,
            CompletionResultSet result,
            McfppCommandContext context,
            String originalSource
    ) {
        MinecraftCommandCompletionSupport.addCompletions(
                position.getProject(),
                result,
                context.buffer(),
                context.cursor(),
                context.documentBaseOffset(),
                context.caretOffset(),
                originalSource
        );
    }

    private static void addContextualIndexedSymbols(
            PsiElement position,
            CompletionResultSet result,
            Set<String> seen,
            McfppCompletionContext context
    ) {
        String qualifier = context.qualifier();
        if (qualifier == null) return;
        String memberOwner = top.mcfpp.language.EntityBuiltins.entityRefTemplate(qualifier);
        String bucketKey = context.kind() == McfppCompletionContext.Kind.MEMBER
                ? McfppSymbolIndex.memberOwnerKey(simpleOwner(memberOwner == null ? qualifier : memberOwner))
                : McfppSymbolIndex.namespaceOwnerKey(qualifier);
        FileBasedIndex index = FileBasedIndex.getInstance();
        PsiManager psiManager = PsiManager.getInstance(position.getProject());
        int count = 0;
        Set<VirtualFile> files = new java.util.LinkedHashSet<>(index.getContainingFiles(
                McfppSymbolIndex.NAME, bucketKey, GlobalSearchScope.allScope(position.getProject())));
        for (String name : McfppExternalLibraryIndex.names(position.getProject())) {
            ProgressManager.checkCanceled();
            if (result.getPrefixMatcher().prefixMatches(name)) {
                files.addAll(McfppExternalLibraryIndex.filesForName(position.getProject(), name));
            }
        }
        for (VirtualFile virtualFile : files) {
            ProgressManager.checkCanceled();
            PsiFile indexedFile = psiManager.findFile(virtualFile);
            if (!(indexedFile instanceof McfppFile)) continue;
            for (McfppSymbol symbol : McfppFileModels.get(indexedFile).symbols()) {
                if (!isVisible(symbol, Integer.MAX_VALUE, context, McfppFileModels.get(indexedFile)) ||
                        !result.getPrefixMatcher().prefixMatches(symbol.name())) {
                    continue;
                }
                if (addSymbol(result, seen, symbol, symbol.name(), false, 100) && ++count >= PROJECT_RESULT_LIMIT) return;
            }
        }
    }

    private static void addKeywords(CompletionResultSet result, Set<String> seen, boolean directDeclaration,
                                    boolean declarationName) {
        for (String keyword : KEYWORDS) {
            if (directDeclaration && keyword.equals("var")) continue;
            if (declarationName && keyword.equals("as")) continue;
            if (seen.add(keyword)) result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(keyword).bold(),
                    50
            ));
        }
    }

    private static boolean isVisible(McfppSymbol symbol, int offset, McfppCompletionContext context, McfppFileModel model) {
        if (context.kind() != McfppCompletionContext.Kind.TYPE && context.qualifier() == null &&
                symbol.kind().isType() && top.mcfpp.language.EntityBuiltins.TYPES.contains(symbol.name())) return false;
        if (context.kind() == McfppCompletionContext.Kind.TYPE && !symbol.kind().isType() &&
                !(symbol.kind() == McfppSymbolKind.PARAMETER && "type".equals(McfppTypes.declaredType(symbol)))) return false;
        if (context.kind() == McfppCompletionContext.Kind.MEMBER) {
            boolean memberKind = symbol.kind() == McfppSymbolKind.FUNCTION || symbol.kind() == McfppSymbolKind.FIELD || symbol.kind() == McfppSymbolKind.CONSTRUCTOR ||
                    symbol.kind() == McfppSymbolKind.ENUM_MEMBER;
            String entityRef = top.mcfpp.language.EntityBuiltins.entityRefTemplate(context.qualifier());
            if (entityRef != null) {
                return memberKind && symbol.owner() != null &&
                        (symbol.owner().equals(entityRef) || entityAncestors(model, entityRef).contains(symbol.owner())) &&
                        isEntityMember(symbol);
            }
            if (McfppTypes.isEntityTemplate(model, context.qualifier())) {
                return memberKind && context.qualifier().equals(symbol.owner()) && !isEntityMember(symbol);
            }
            String qualifier = context.qualifier().split("<", 2)[0];
            int namespace = qualifier.lastIndexOf(':');
            return simpleOwner(qualifier).equals(symbol.owner()) && memberKind &&
                    (namespace < 0 || qualifier.substring(0, namespace).equals(symbol.namespace()));
        }
        if (context.kind() == McfppCompletionContext.Kind.NAMESPACE) {
            return context.qualifier().equals(symbol.namespace()) && symbol.owner() == null &&
                    symbol.kind().isProjectSymbol();
        }
        if (symbol.kind() == McfppSymbolKind.PARAMETER || symbol.kind() == McfppSymbolKind.VARIABLE ||
                symbol.kind() == McfppSymbolKind.FIELD) {
            return symbol.nameOffset() < offset && symbol.scopeContains(offset);
        }
        if (symbol.owner() != null) {
            return model.symbols().stream().anyMatch(owner -> owner.kind().isType() &&
                    owner.name().equals(symbol.owner()) && owner.contains(offset));
        }
        return symbol.kind().isProjectSymbol();
    }

    private static String simpleOwner(String type) {
        String base = type.split("<", 2)[0];
        return base.substring(base.lastIndexOf(':') + 1);
    }

    /** Entity members (`entity var`, `entity func`) live on the reference, not the template value. */
    private static boolean isEntityMember(McfppSymbol symbol) {
        return symbol.signature().matches("(?s).*\\bentity\\s+(?:(?:override|abstract)\\s+)?(?:var|const|func)\\b.*");
    }

    private static java.util.List<McfppSymbol> entityMembers(McfppFileModel model, String template) {
        java.util.Set<String> owners = new java.util.LinkedHashSet<>(entityAncestors(model, template));
        return model.symbols().stream()
                .filter(McfppCompletionContributor::isEntityMember)
                .filter(symbol -> symbol.owner() != null && owners.contains(symbol.owner()))
                .toList();
    }

    private static java.util.List<String> entityAncestors(McfppFileModel model, String template) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        pending.add(template.substring(template.lastIndexOf(':') + 1).strip());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.isEmpty() || !result.add(current)) continue;
            for (McfppSymbol symbol : model.symbols()) {
                if (symbol.kind() != McfppSymbolKind.TYPE || !symbol.name().equals(current)) continue;
                int colon = symbol.signature().indexOf(':');
                if (colon < 0) continue;
                String head = symbol.signature().substring(colon + 1);
                int brace = head.indexOf('{');
                if (brace >= 0) head = head.substring(0, brace);
                for (String candidate : head.split(",")) {
                    String name = candidate.replaceAll("<[^<>]*>", "").replaceAll("[\\[\\]{}?]", "").strip();
                    int space = name.lastIndexOf(' ');
                    if (space >= 0) name = name.substring(space + 1);
                    name = name.substring(name.lastIndexOf(':') + 1);
                    if (!name.isEmpty()) pending.addLast(name);
                }
            }
        }
        return java.util.List.copyOf(result);
    }

    private static String entityKind(McfppFileModel model, String template) {
        for (String owner : entityAncestors(model, template)) {
            if (top.mcfpp.language.EntityBuiltins.TYPES.contains(owner)) return owner;
        }
        return "entity";
    }

    static Icon icon(McfppSymbolKind kind) {
        return switch (kind) {
            case TYPE -> AllIcons.Nodes.Class;
            case ENUM -> AllIcons.Nodes.Enum;
            case TYPE_ALIAS -> AllIcons.Nodes.Type;
            case FUNCTION, CONSTRUCTOR -> AllIcons.Nodes.Function;
            case FIELD, ENUM_MEMBER -> AllIcons.Nodes.Field;
            case VARIABLE, PARAMETER -> AllIcons.Nodes.Variable;
        };
    }
}
