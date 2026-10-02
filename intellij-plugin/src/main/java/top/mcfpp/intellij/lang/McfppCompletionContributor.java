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
            "namespace", "import", "from", "as", "typealias", "data", "object", "enum", "func", "constructor",
            "operator", "var", "global", "const", "dynamic", "inline", "final", "abstract", "override", "static",
            "public", "protected", "private", "if", "else", "while", "do", "for", "try", "store", "execute",
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
        McfppFileModel model = McfppFileModels.get(originalFile instanceof McfppFile ? originalFile : file);
        McfppCompletionContext completionContext = McfppCompletionContext.create(parameters, result, model);
        Set<String> seen = new HashSet<>();
        if (completionContext.kind() == McfppCompletionContext.Kind.GLOBAL) addKeywords(result, seen);

        model.symbols().stream().sorted(java.util.Comparator.comparingInt(McfppSymbol::nameOffset).reversed())
                .filter(symbol -> isVisible(symbol, offset, completionContext, model))
                .forEach(symbol -> addSymbol(result, seen, symbol, symbol.name(),
                        completionContext.kind() == McfppCompletionContext.Kind.GLOBAL, 500));

        if (DumbService.isDumb(position.getProject())) return;
        if (completionContext.kind() != McfppCompletionContext.Kind.GLOBAL) {
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
                String exposed = McfppImportManager.exposedName(model, symbol);
                if (!result.getPrefixMatcher().prefixMatches(exposed)) continue;
                int priority = symbol.namespace().equals(model.namespace()) ? 400
                        : model.importExposes(symbol.namespace(), symbol.name(), exposed) ? 350
                        : McfppStandardLibrary.isImplicitNamespace(symbol.namespace()) ? 300 : 100;
                if (addSymbol(result, seen, symbol, exposed, true, priority) && ++count >= PROJECT_RESULT_LIMIT) return;
            }
        }
    }

    private static boolean addSymbol(CompletionResultSet result, Set<String> seen, McfppSymbol symbol,
                                     String exposedName, boolean unqualified, int priority) {
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
        String bucketKey = context.kind() == McfppCompletionContext.Kind.MEMBER
                ? McfppSymbolIndex.memberOwnerKey(qualifier)
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

    private static void addKeywords(CompletionResultSet result, Set<String> seen) {
        for (String keyword : KEYWORDS) {
            if (seen.add(keyword)) result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(keyword).bold(),
                    50
            ));
        }
    }

    private static boolean isVisible(McfppSymbol symbol, int offset, McfppCompletionContext context, McfppFileModel model) {
        if (context.kind() == McfppCompletionContext.Kind.MEMBER) {
            return context.qualifier().equals(symbol.owner()) &&
                    (symbol.kind() == McfppSymbolKind.FUNCTION || symbol.kind() == McfppSymbolKind.FIELD ||
                            symbol.kind() == McfppSymbolKind.ENUM_MEMBER);
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
