package top.mcfpp.intellij.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.language.VersionPreprocessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A compact, error-tolerant declaration model shared by indexing and editor features. */
public final class McfppFileModel {
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
        McfppTokenScanner.Lexed lexed = McfppTokenScanner.lex(source);
        List<McfppTokenScanner.Token> tokens = lexed.tokens();
        McfppTokenScanner.MatchData matches = McfppTokenScanner.matchDelimiters(tokens);
        String namespace = McfppDeclarationParser.parseNamespace(tokens);
        List<McfppImport> imports = McfppDeclarationParser.parseImports(tokens);

        List<McfppDeclarationParser.Draft> drafts = new ArrayList<>();
        McfppDeclarationParser.parseTypes(source, tokens, lexed.documentationBefore(), matches, drafts);
        McfppDeclarationParser.parseAliases(source, tokens, lexed.documentationBefore(), drafts);
        McfppDeclarationParser.parseFunctions(source, tokens, lexed.documentationBefore(), matches, drafts);
        McfppDeclarationParser.parseConstructors(source, tokens, lexed.documentationBefore(), matches, drafts);

        List<McfppScopeBinder.RangeOwner> typeOwners = McfppScopeBinder.ownerRanges(
                drafts, McfppSymbolKind.TYPE, McfppSymbolKind.ENUM);
        List<McfppScopeBinder.RangeOwner> functionOwners = McfppScopeBinder.ownerRanges(
                drafts, McfppSymbolKind.FUNCTION, McfppSymbolKind.CONSTRUCTOR);
        McfppScopeBinder.assignOwners(drafts, typeOwners);
        McfppScopeBinder.parseFields(source, tokens, lexed.documentationBefore(), matches,
                typeOwners, functionOwners, drafts);
        McfppScopeBinder.parseForeachVariables(source, tokens, matches, typeOwners, functionOwners, drafts);
        McfppScopeBinder.parseParameters(source, tokens, matches, typeOwners, drafts);
        McfppScopeBinder.parseLambdaParameters(source, tokens, matches, drafts);
        McfppScopeBinder.parseEnumMembers(source, tokens, matches, drafts);

        List<McfppSymbol> symbols = new ArrayList<>(drafts.size());
        for (McfppDeclarationParser.Draft draft : drafts) symbols.add(draft.toSymbol(namespace));
        symbols.sort(Comparator.comparingInt(McfppSymbol::nameOffset).thenComparing(symbol -> symbol.kind().ordinal()));
        return new McfppFileModel(namespace, imports, symbols);
    }

    public static @NotNull McfppFileModel parse(@NotNull CharSequence source, @NotNull String targetVersion) {
        try {
            return parse(VersionPreprocessor.process(source.toString(), targetVersion));
        } catch (VersionPreprocessor.Error error) {
            // Keep incomplete files navigable; the language server reports directive errors.
            return parse(source);
        }
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
}
