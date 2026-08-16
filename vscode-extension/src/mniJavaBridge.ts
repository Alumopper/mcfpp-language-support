import * as vscode from 'vscode';

interface MniJavaReference {
    text: string;
    range: vscode.Range;
    segmentRange: vscode.Range;
}

interface JavaTypeResolution {
    symbol: vscode.SymbolInformation;
    document: vscode.TextDocument;
    documentSymbol?: vscode.DocumentSymbol;
    consumedSegments: number;
}

const JAVA_REFERENCE = /[A-Za-z_$][A-Za-z0-9_$.]*/g;
const JAVA_TYPE_KINDS = new Set([
    vscode.SymbolKind.Class,
    vscode.SymbolKind.Interface,
    vscode.SymbolKind.Enum,
    vscode.SymbolKind.Struct
]);
const JAVA_MEMBER_KINDS = new Set([
    vscode.SymbolKind.Method,
    vscode.SymbolKind.Constructor,
    vscode.SymbolKind.Field,
    vscode.SymbolKind.Property,
    vscode.SymbolKind.EnumMember
]);

function completionKind(kind: vscode.SymbolKind): vscode.CompletionItemKind {
    switch (kind) {
        case vscode.SymbolKind.Method: return vscode.CompletionItemKind.Method;
        case vscode.SymbolKind.Constructor: return vscode.CompletionItemKind.Constructor;
        case vscode.SymbolKind.Field: return vscode.CompletionItemKind.Field;
        case vscode.SymbolKind.Property: return vscode.CompletionItemKind.Property;
        case vscode.SymbolKind.Interface: return vscode.CompletionItemKind.Interface;
        case vscode.SymbolKind.Enum: return vscode.CompletionItemKind.Enum;
        case vscode.SymbolKind.EnumMember: return vscode.CompletionItemKind.EnumMember;
        case vscode.SymbolKind.Struct: return vscode.CompletionItemKind.Struct;
        default: return vscode.CompletionItemKind.Class;
    }
}

function referenceAt(document: vscode.TextDocument, position: vscode.Position): MniJavaReference | undefined {
    const line = document.lineAt(position.line).text;
    let match: RegExpExecArray | null;
    JAVA_REFERENCE.lastIndex = 0;
    while ((match = JAVA_REFERENCE.exec(line)) !== null) {
        const start = match.index;
        const end = start + match[0].length;
        if (position.character < start || position.character > end) {
            continue;
        }
        const before = line.slice(0, start);
        const isNativeTarget = /\bfunc\b[^{};]*=\s*$/.test(before);
        const isJvmAccess = /::\s*$/.test(before);
        const isFromAnnotation = /@From\s*<\s*["']?\s*$/.test(before);
        if (!isNativeTarget && !isJvmAccess && !isFromAnnotation) {
            return undefined;
        }
        const range = new vscode.Range(position.line, start, position.line, end);
        const lastDot = match[0].lastIndexOf('.', Math.max(0, position.character - start - 1));
        const segmentStart = start + lastDot + 1;
        return {
            text: match[0],
            range,
            segmentRange: new vscode.Range(position.line, segmentStart, position.line, end)
        };
    }
    return undefined;
}

function packageName(document: vscode.TextDocument): string {
    return /^\s*package\s+([A-Za-z_$][A-Za-z0-9_$.]*)\s*;/m.exec(document.getText())?.[1] ?? '';
}

function typeFqn(document: vscode.TextDocument, symbol: vscode.SymbolInformation): string {
    const packagePrefix = packageName(document);
    const container = symbol.containerName?.replace(/\$/g, '.') ?? '';
    const name = symbol.name.replace(/\$/g, '.');
    if (name.includes('.')) {
        return name;
    }
    if (container && container !== packagePrefix && !container.endsWith(packagePrefix)) {
        return [packagePrefix, container, name].filter(Boolean).join('.');
    }
    return [packagePrefix, name].filter(Boolean).join('.');
}

async function documentSymbols(document: vscode.TextDocument): Promise<vscode.DocumentSymbol[]> {
    const symbols = await vscode.commands.executeCommand<Array<vscode.DocumentSymbol | vscode.SymbolInformation>>(
        'vscode.executeDocumentSymbolProvider',
        document.uri
    ) ?? [];
    return symbols.filter((symbol): symbol is vscode.DocumentSymbol => 'selectionRange' in symbol);
}

function flattenSymbols(symbols: readonly vscode.DocumentSymbol[]): vscode.DocumentSymbol[] {
    return symbols.flatMap((symbol) => [symbol, ...flattenSymbols(symbol.children)]);
}

async function resolveJavaType(text: string): Promise<JavaTypeResolution | undefined> {
    const segments = text.replace(/\$/g, '.').split('.').filter(Boolean);
    for (let consumed = segments.length; consumed >= 1; consumed--) {
        const requestedFqn = segments.slice(0, consumed).join('.');
        const simpleName = segments[consumed - 1];
        const candidates = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
            'vscode.executeWorkspaceSymbolProvider',
            simpleName
        ) ?? [];
        for (const symbol of candidates) {
            if (!JAVA_TYPE_KINDS.has(symbol.kind) || symbol.location.uri.scheme !== 'file') {
                continue;
            }
            const document = await vscode.workspace.openTextDocument(symbol.location.uri);
            const fqn = typeFqn(document, symbol);
            if (fqn !== requestedFqn && !fqn.endsWith(`.${requestedFqn}`)) {
                continue;
            }
            const symbols = flattenSymbols(await documentSymbols(document));
            const documentSymbol = symbols.find((candidate) =>
                JAVA_TYPE_KINDS.has(candidate.kind) &&
                candidate.name === simpleName &&
                candidate.range.contains(symbol.location.range.start)
            );
            return { symbol, document, documentSymbol, consumedSegments: consumed };
        }
    }
    return undefined;
}

async function typeCompletions(reference: MniJavaReference, position: vscode.Position): Promise<vscode.CompletionList> {
    const prefix = reference.text.slice(0, Math.max(0, position.character - reference.range.start.character));
    const resolution = await resolveJavaType(prefix);
    const segments = prefix.replace(/\$/g, '.').split('.');
    if (resolution && resolution.consumedSegments < segments.length) {
        const memberPrefix = segments.slice(resolution.consumedSegments).join('.');
        const members = resolution.documentSymbol?.children ?? [];
        const items = members
            .filter((symbol) => JAVA_MEMBER_KINDS.has(symbol.kind) && symbol.name.startsWith(memberPrefix))
            .map((symbol) => {
                const item = new vscode.CompletionItem(symbol.name, completionKind(symbol.kind));
                item.detail = symbol.detail || `${resolution.symbol.name} member`;
                item.textEdit = vscode.TextEdit.replace(reference.segmentRange, symbol.name);
                return item;
            });
        return new vscode.CompletionList(items, false);
    }

    const simplePrefix = segments.at(-1) ?? '';
    const candidates = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
        'vscode.executeWorkspaceSymbolProvider',
        simplePrefix
    ) ?? [];
    const items: vscode.CompletionItem[] = [];
    const seen = new Set<string>();
    for (const symbol of candidates) {
        if (!JAVA_TYPE_KINDS.has(symbol.kind) || symbol.location.uri.scheme !== 'file') {
            continue;
        }
        const document = await vscode.workspace.openTextDocument(symbol.location.uri);
        const fqn = typeFqn(document, symbol);
        if (!fqn.startsWith(prefix) || seen.has(fqn)) {
            continue;
        }
        seen.add(fqn);
        const item = new vscode.CompletionItem(symbol.name, completionKind(symbol.kind));
        item.detail = fqn;
        item.textEdit = vscode.TextEdit.replace(reference.segmentRange, symbol.name);
        items.push(item);
    }
    return new vscode.CompletionList(items, false);
}

async function javaTarget(reference: MniJavaReference): Promise<vscode.Location | undefined> {
    const resolution = await resolveJavaType(reference.text);
    if (!resolution) {
        return undefined;
    }
    const segments = reference.text.replace(/\$/g, '.').split('.').filter(Boolean);
    const memberName = segments.slice(resolution.consumedSegments).at(-1);
    if (memberName) {
        const member = (resolution.documentSymbol?.children ?? []).find((symbol) =>
            JAVA_MEMBER_KINDS.has(symbol.kind) && symbol.name === memberName
        );
        if (member) {
            return new vscode.Location(resolution.document.uri, member.selectionRange);
        }
    }
    return resolution.symbol.location;
}

export function registerMniJavaBridge(): vscode.Disposable {
    const selector: vscode.DocumentSelector = [{ language: 'mcfpp', scheme: 'file' }];
    return vscode.Disposable.from(
        vscode.languages.registerCompletionItemProvider(selector, {
            provideCompletionItems: async (document, position, token) => {
                if (token.isCancellationRequested) return undefined;
                const reference = referenceAt(document, position);
                return reference ? typeCompletions(reference, position) : undefined;
            }
        }, '.', '$'),
        vscode.languages.registerDefinitionProvider(selector, {
            provideDefinition: async (document, position, token) => {
                if (token.isCancellationRequested) return undefined;
                const reference = referenceAt(document, position);
                return reference ? javaTarget(reference) : undefined;
            }
        }),
        vscode.languages.registerHoverProvider(selector, {
            provideHover: async (document, position, token) => {
                if (token.isCancellationRequested) return undefined;
                const reference = referenceAt(document, position);
                const target = reference ? await javaTarget(reference) : undefined;
                if (!target) return undefined;
                const hovers = await vscode.commands.executeCommand<vscode.Hover[]>(
                    'vscode.executeHoverProvider',
                    target.uri,
                    target.range.start
                );
                return hovers?.[0];
            }
        })
    );
}
