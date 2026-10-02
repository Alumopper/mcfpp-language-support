package top.mcfpp.intellij.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class McfppSymbolResolver {
    private McfppSymbolResolver() {
    }

    static @NotNull List<PsiElement> resolve(@NotNull PsiElement usage) {
        PsiFile currentFile = usage.getContainingFile();
        if (!(currentFile instanceof McfppFile)) return List.of();
        McfppFileModel currentModel = McfppFileModels.get(currentFile);
        String usedName = usage.getText();
        int offset = usage.getTextOffset();

        UsageContext context = UsageContext.create(currentFile.getViewProvider().getContents(), usage);
        List<McfppSymbol> locals = currentModel.visibleLocalSymbols(usedName, offset);
        if (context.namespaceQualifier() == null && context.ownerQualifier() == null && !locals.isEmpty()) {
            PsiElement target = elementFor(currentFile, locals.getFirst());
            return target == null ? List.of() : List.of(target);
        }

        Project project = usage.getProject();
        if (DumbService.isDumb(project)) {
            List<PsiElement> sameFile = sameFileSymbols(currentFile, currentModel, usedName, usage);
            return sameFile.isEmpty()
                    ? externalLibrarySymbols(project, currentFile, currentModel, usedName, context, offset)
                    : sameFile;
        }

        LinkedHashSet<String> indexedNames = new LinkedHashSet<>();
        indexedNames.add(usedName);
        for (McfppImport imported : currentModel.imports()) {
            if (imported.exposes(usedName)) indexedNames.add(imported.originalName(usedName));
        }

        Set<String> seen = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        FileBasedIndex index = FileBasedIndex.getInstance();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (String indexedName : indexedNames) {
            LinkedHashSet<VirtualFile> sourceFiles = new LinkedHashSet<>(
                    index.getContainingFiles(McfppSymbolIndex.NAME, indexedName, scope));
            sourceFiles.addAll(McfppExternalLibraryIndex.filesForName(project, indexedName));
            for (VirtualFile virtualFile : sourceFiles) {
                ProgressManager.checkCanceled();
                PsiFile file = psiManager.findFile(virtualFile);
                if (!(file instanceof McfppFile)) continue;
                McfppFileModel model = McfppFileModels.get(file);
                for (McfppSymbol symbol : model.projectSymbols()) {
                    if (!symbol.name().equals(indexedName)) continue;
                    int score = score(symbol, file, currentFile, currentModel, usedName, context, offset);
                    if (score < 0) continue;
                    String identity = virtualFile.getUrl() + '#' + symbol.nameOffset();
                    if (seen.add(identity)) candidates.add(new Candidate(file, symbol, score));
                }
            }
        }

        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.symbol().qualifiedName()));
        if (candidates.isEmpty()) return List.of();
        int bestScore = candidates.getFirst().score();
        return candidates.stream()
                .filter(candidate -> candidate.score() == bestScore)
                .map(candidate -> elementFor(candidate.file(), candidate.symbol()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<PsiElement> externalLibrarySymbols(
            Project project,
            PsiFile currentFile,
            McfppFileModel currentModel,
            String usedName,
            UsageContext context,
            int offset
    ) {
        List<Candidate> candidates = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile virtualFile : McfppExternalLibraryIndex.filesForName(project, usedName)) {
            PsiFile file = psiManager.findFile(virtualFile);
            if (!(file instanceof McfppFile)) continue;
            for (McfppSymbol symbol : McfppFileModels.get(file).projectSymbols()) {
                if (!symbol.name().equals(usedName)) continue;
                int score = score(symbol, file, currentFile, currentModel, usedName, context, offset);
                if (score >= 0) candidates.add(new Candidate(file, symbol, score));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.symbol().qualifiedName()));
        if (candidates.isEmpty()) return List.of();
        int bestScore = candidates.getFirst().score();
        return candidates.stream()
                .filter(candidate -> candidate.score() == bestScore)
                .map(candidate -> elementFor(candidate.file(), candidate.symbol()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    static @NotNull List<PsiElement> findProjectSymbols(
            @NotNull Project project,
            @NotNull String name,
            boolean includeNonProjectItems
    ) {
        return findIndexedSymbols(project, name, name, includeNonProjectItems, false);
    }

    static @NotNull List<PsiElement> findTypes(
            @NotNull Project project,
            @NotNull String name,
            boolean includeNonProjectItems
    ) {
        return findIndexedSymbols(project, McfppSymbolIndex.typeKey(name), name, includeNonProjectItems, true);
    }

    static @NotNull List<McfppCallableInfo> findCallables(@NotNull PsiElement usage) {
        List<McfppCallableInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PsiElement target : resolve(usage)) {
            if (!(target.getContainingFile() instanceof McfppFile file)) continue;
            McfppFileModel model = McfppFileModels.get(file);
            McfppSymbol symbol = model.declarationAt(target.getTextOffset());
            if (symbol == null) continue;
            if (symbol.kind() == McfppSymbolKind.FUNCTION) {
                if (seen.add(symbol.qualifiedName() + '\n' + symbol.signature())) {
                    result.add(McfppCallableInfo.from(symbol));
                }
            } else if (symbol.kind().isType()) {
                List<McfppSymbol> constructors = model.symbols().stream()
                        .filter(candidate -> candidate.kind() == McfppSymbolKind.CONSTRUCTOR)
                        .filter(candidate -> symbol.name().equals(candidate.owner()))
                        .toList();
                if (constructors.isEmpty()) {
                    if (seen.add(symbol.qualifiedName() + "()")) {
                        result.add(McfppCallableInfo.implicitConstructor(symbol.name()));
                    }
                } else {
                    for (McfppSymbol constructor : constructors) {
                        String signature = symbol.name() + constructor.signature().substring("constructor".length());
                        if (seen.add(symbol.qualifiedName() + '\n' + signature)) {
                            result.add(McfppCallableInfo.fromSignature(signature));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static @NotNull List<PsiElement> findIndexedSymbols(
            @NotNull Project project,
            @NotNull String indexKey,
            @NotNull String name,
            boolean includeNonProjectItems,
            boolean typesOnly
    ) {
        if (DumbService.isDumb(project)) return List.of();
        // getNames() is backed by the all-project file index as well. Keeping the item lookup in the same scope makes
        // attached MCFPP libraries (especially the standard library) actually navigable from Navigate | Class/Symbol.
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);
        List<PsiElement> result = new ArrayList<>();
        LinkedHashSet<VirtualFile> sourceFiles = new LinkedHashSet<>(FileBasedIndex.getInstance()
                .getContainingFiles(McfppSymbolIndex.NAME, indexKey, scope));
        sourceFiles.addAll(McfppExternalLibraryIndex.filesForName(project, name));
        for (VirtualFile virtualFile : sourceFiles) {
            PsiFile file = psiManager.findFile(virtualFile);
            if (!(file instanceof McfppFile)) continue;
            for (McfppSymbol symbol : McfppFileModels.get(file).projectSymbols()) {
                if (!symbol.name().equals(name) || typesOnly && !symbol.kind().isType()) continue;
                PsiElement element = elementFor(file, symbol);
                if (element != null) result.add(element);
            }
        }
        return result;
    }

    private static List<PsiElement> sameFileSymbols(
            PsiFile file,
            McfppFileModel model,
            String name,
            PsiElement usage
    ) {
        return model.projectSymbols().stream()
                .filter(symbol -> symbol.name().equals(name))
                .map(symbol -> elementFor(file, symbol))
                .filter(java.util.Objects::nonNull)
                .filter(element -> !element.isEquivalentTo(usage))
                .toList();
    }

    private static int score(
            McfppSymbol symbol,
            PsiFile candidateFile,
            PsiFile currentFile,
            McfppFileModel currentModel,
            String usedName,
            UsageContext context,
            int usageOffset
    ) {
        int score = 0;
        String expectedOwner = context.ownerQualifier() == null
                ? null
                : resolveOwnerQualifier(context.ownerQualifier(), currentModel, usageOffset);
        boolean ownerImported = expectedOwner != null && symbol.owner() != null &&
                (currentModel.importExposes(symbol.namespace(), symbol.owner(), expectedOwner) ||
                        currentModel.importExposes(symbol.namespace(), symbol.owner(), context.ownerQualifier()));
        if (context.namespaceQualifier() != null) {
            String expectedNamespace = resolveNamespaceQualifier(context.namespaceQualifier(), currentModel);
            if (!symbol.namespace().equals(expectedNamespace)) return -1;
            score += 2_000;
        } else if (symbol.namespace().equals(currentModel.namespace())) {
            score += 1_000;
        } else if (currentModel.importExposes(symbol.namespace(), symbol.name(), usedName)) {
            score += 900;
        } else if (ownerImported) {
            score += 850;
        } else if (McfppStandardLibrary.isImplicitNamespace(symbol.namespace())) {
            // Mirrors GlobalScope in the compiler: mcfpp.lang, mcfpp.sys, and mcfpp are searched without imports.
            score += 800;
        } else if (!symbol.namespace().isEmpty()) {
            return -1;
        }

        if (expectedOwner != null) {
            if (!expectedOwner.equals(symbol.owner())) return -1;
            score += 1_500;
        } else {
            String enclosingType = enclosingType(currentModel, usageOffset);
            if (symbol.owner() == null) score += 100;
            else if (symbol.owner().equals(enclosingType)) score += 400;
            else return -1;
        }

        if (context.typePosition() && symbol.kind().isType()) score += 300;
        if (context.callPosition() && (symbol.kind() == McfppSymbolKind.FUNCTION || symbol.kind().isType())) score += 250;
        if (context.callPosition() && symbol.kind() == McfppSymbolKind.FUNCTION) {
            List<String> parameterTypes = McfppTypes.parameterTypes(symbol);
            if (parameterTypes.size() == context.argumentTypes().size()) {
                score += 120;
                for (int index = 0; index < parameterTypes.size(); index++) {
                    String expected = parameterTypes.get(index);
                    String actual = context.argumentTypes().get(index);
                    if (expected.equals(actual)) score += 80;
                    else if ("any".equals(expected)) score += 15;
                    else if (!"any".equals(actual)) score -= 30;
                }
            } else {
                score -= 120;
            }
        }
        VirtualFile candidateVirtualFile = candidateFile.getVirtualFile();
        if (candidateVirtualFile != null &&
                ProjectFileIndex.getInstance(candidateFile.getProject()).isInContent(candidateVirtualFile)) {
            score += 60;
        }
        if (candidateFile.equals(currentFile)) score += 50;
        return score;
    }

    private static String resolveNamespaceQualifier(String qualifier, McfppFileModel model) {
        for (McfppImport imported : model.imports()) {
            if (imported.isWildcard() && qualifier.equals(imported.alias())) return imported.namespace();
        }
        return qualifier;
    }

    private static String resolveOwnerQualifier(String qualifier, McfppFileModel model, int usageOffset) {
        if ("this".equals(qualifier)) {
            String owner = enclosingType(model, usageOffset);
            return owner == null ? qualifier : owner;
        }
        List<McfppSymbol> variables = model.visibleLocalSymbols(qualifier, usageOffset);
        if (!variables.isEmpty()) {
            String declaredType = McfppTypes.declaredType(variables.getFirst());
            if (declaredType != null) {
                for (McfppImport imported : model.imports()) {
                    if (!imported.isWildcard() && declaredType.equals(imported.alias())) return imported.importedName();
                }
                return declaredType;
            }
        }
        for (McfppImport imported : model.imports()) {
            if (!imported.isWildcard() && qualifier.equals(imported.alias())) return imported.importedName();
        }
        return qualifier;
    }

    private static String enclosingType(McfppFileModel model, int offset) {
        return model.symbols().stream()
                .filter(symbol -> (symbol.kind() == McfppSymbolKind.TYPE || symbol.kind() == McfppSymbolKind.ENUM) &&
                        symbol.contains(offset))
                .min(Comparator.comparingInt(symbol -> symbol.endOffset() - symbol.declarationStart()))
                .map(McfppSymbol::name)
                .orElse(null);
    }

    static PsiElement elementFor(PsiFile file, McfppSymbol symbol) {
        PsiElement leaf = file.findElementAt(symbol.nameOffset());
        if (leaf == null) return null;
        McfppNamedElement declaration = PsiTreeUtil.getParentOfType(leaf, McfppNamedElement.class, false);
        return declaration != null && symbol.name().equals(declaration.getName()) ? declaration : leaf;
    }

    private record Candidate(PsiFile file, McfppSymbol symbol, int score) {
    }

    private record UsageContext(
            String namespaceQualifier,
            String ownerQualifier,
            boolean callPosition,
            boolean typePosition,
            List<String> argumentTypes
    ) {
        static UsageContext create(CharSequence source, PsiElement element) {
            int start = element.getTextOffset();
            int end = start + element.getTextLength();
            int left = previousNonWhitespace(source, start - 1);
            int right = nextNonWhitespace(source, end);
            String namespace = null;
            String owner = null;
            if (left >= 0 && source.charAt(left) == ':') namespace = identifierBefore(source, left - 1, true);
            else if (left >= 0 && source.charAt(left) == '.') owner = identifierBefore(source, left - 1, false);
            boolean call = right < source.length() && source.charAt(right) == '(';
            String previousWord = identifierBefore(source, left, false);
            boolean type = "as".equals(previousWord) || "extends".equals(previousWord) ||
                    left >= 1 && source.charAt(left) == '>' && source.charAt(left - 1) == '-';
            List<String> argumentTypes = List.of();
            if (call && element.getContainingFile() instanceof McfppFile file) {
                McfppCallContext callContext = McfppCallContext.fromCallee(element);
                if (callContext != null) {
                    argumentTypes = McfppArgumentInference.inferTypes(file, callContext.arguments());
                }
            }
            return new UsageContext(namespace, owner, call, type, argumentTypes);
        }

        private static int previousNonWhitespace(CharSequence source, int offset) {
            while (offset >= 0 && Character.isWhitespace(source.charAt(offset))) offset--;
            return offset;
        }

        private static int nextNonWhitespace(CharSequence source, int offset) {
            while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++;
            return offset;
        }

        private static String identifierBefore(CharSequence source, int offset, boolean allowDots) {
            offset = previousNonWhitespace(source, offset);
            int end = offset + 1;
            while (offset >= 0) {
                char character = source.charAt(offset);
                if (!(Character.isUnicodeIdentifierPart(character) || character == '_' || character == '$' ||
                        allowDots && character == '.')) break;
                offset--;
            }
            return source.subSequence(offset + 1, end).toString();
        }
    }
}
