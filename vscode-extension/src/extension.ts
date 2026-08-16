import * as path from 'path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from 'vscode-languageclient/node';
import { registerMniJavaBridge } from './mniJavaBridge';

let client: LanguageClient | undefined;

const COMMAND_VIRTUAL_SCHEME = 'mcfpp-mcfunction';
const SPYGLASS_TOKEN_TYPES = [
    'namespace',
    'type',
    'class',
    'enum',
    'interface',
    'struct',
    'typeParameter',
    'parameter',
    'variable',
    'property',
    'enumMember',
    'event',
    'function',
    'method',
    'macro',
    'keyword',
    'modifier',
    'comment',
    'string',
    'number',
    'regexp',
    'operator',
    'decorator',
    'error',
    'escape',
    'literal',
    'resourceLocation',
    'vector'
] as const;

const SPYGLASS_TOKEN_MODIFIERS = [
    'declaration',
    'definition',
    'readonly',
    'static',
    'deprecated',
    'abstract',
    'async',
    'modification',
    'documentation',
    'defaultLibrary'
] as const;

interface CommandLineEntry {
    sourceLine: number;
    slashColumn: number;
    commandText: string;
}

interface CommandBridgeDocument {
    sourceUri: vscode.Uri;
    virtualUri: vscode.Uri;
    lines: CommandLineEntry[];
    syntheticContent: string;
}

interface CommandPositionMapping {
    lineEntryIndex: number;
    characterInCommand: number;
    slashColumn: number;
    commandText: string;
}

interface DecodedSemanticToken {
    line: number;
    startCharacter: number;
    length: number;
    tokenType: string;
    tokenModifiers: string[];
}

class CommandVirtualDocumentProvider implements vscode.TextDocumentContentProvider {
    private readonly emitter = new vscode.EventEmitter<vscode.Uri>();
    private readonly contents = new Map<string, string>();

    readonly onDidChange = this.emitter.event;

    provideTextDocumentContent(uri: vscode.Uri): string {
        return this.contents.get(uri.toString()) ?? '';
    }

    update(uri: vscode.Uri, content: string): void {
        if (this.contents.get(uri.toString()) === content) {
            return;
        }
        this.contents.set(uri.toString(), content);
        this.emitter.fire(uri);
    }

    remove(uri: vscode.Uri): void {
        this.contents.delete(uri.toString());
        this.emitter.fire(uri);
    }
}

function decodeSemanticTokens(data: readonly number[]): DecodedSemanticToken[] {
    const decoded: DecodedSemanticToken[] = [];
    let line = 0;
    let character = 0;

    for (let index = 0; index < data.length; index += 5) {
        line += data[index];
        character = data[index] === 0 ? character + data[index + 1] : data[index + 1];
        const length = data[index + 2];
        const tokenTypeIndex = data[index + 3];
        const tokenModifierBits = data[index + 4];

        const tokenModifiers = SPYGLASS_TOKEN_MODIFIERS.filter((_, bitIndex) =>
            (tokenModifierBits & (1 << bitIndex)) !== 0
        );

        decoded.push({
            line,
            startCharacter: character,
            length,
            tokenType: SPYGLASS_TOKEN_TYPES[tokenTypeIndex] ?? 'variable',
            tokenModifiers
        });
    }

    return decoded;
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    const javaExecutablePath = process.env.JAVA_HOME
        ? path.join(process.env.JAVA_HOME, 'bin', 'java')
        : 'java';

    const serverOptions: ServerOptions = {
        command: javaExecutablePath,
        args: [
            '-jar',
            path.join(context.extensionPath, 'server', 'mcfpp-language-server.jar')
        ],
        transport: TransportKind.stdio
    };

    let mergeCommandSemanticTokens = async (
        _document: vscode.TextDocument,
        _range: vscode.Range | undefined,
        base: vscode.SemanticTokens | null | undefined
    ): Promise<vscode.SemanticTokens | null | undefined> => base;

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'mcfpp' }],
        synchronize: {
            configurationSection: 'mcfpp',
            fileEvents: [
                vscode.workspace.createFileSystemWatcher('**/*.mcfpp'),
                vscode.workspace.createFileSystemWatcher('**/*.json'),
                vscode.workspace.createFileSystemWatcher('**/*.mclib'),
                vscode.workspace.createFileSystemWatcher('**/*.jar'),
                vscode.workspace.createFileSystemWatcher('**/*.zip'),
                vscode.workspace.createFileSystemWatcher('**/module.json')
            ]
        },
        middleware: {
            provideDocumentSemanticTokens: async (document, token, next) => {
                const base = await next(document, token);
                return token.isCancellationRequested
                    ? base
                    : mergeCommandSemanticTokens(document, undefined, base);
            },
            provideDocumentRangeSemanticTokens: async (document, range, token, next) => {
                const base = await next(document, range, token);
                return token.isCancellationRequested
                    ? base
                    : mergeCommandSemanticTokens(document, range, base);
            }
        }
    };

    client = new LanguageClient(
        'mcfppLanguageServer',
        'MCFPP Language Server',
        serverOptions,
        clientOptions
    );

    context.subscriptions.push(client);
    await client.start();

    let mniJavaBridge: vscode.Disposable | undefined;
    const updateMniJavaBridge = (): void => {
        const javaExtensionAvailable = vscode.extensions.getExtension('redhat.java') !== undefined;
        if (javaExtensionAvailable && !mniJavaBridge) {
            mniJavaBridge = registerMniJavaBridge();
        } else if (!javaExtensionAvailable && mniJavaBridge) {
            mniJavaBridge.dispose();
            mniJavaBridge = undefined;
        }
    };
    updateMniJavaBridge();
    context.subscriptions.push(
        vscode.extensions.onDidChange(updateMniJavaBridge),
        new vscode.Disposable(() => mniJavaBridge?.dispose())
    );

    const commandProvider = new CommandVirtualDocumentProvider();
    const commandBridgeDiagnostics = vscode.languages.createDiagnosticCollection('mcfpp-spyglass-commands');
    const commandSemanticLegend = new vscode.SemanticTokensLegend([...SPYGLASS_TOKEN_TYPES], [...SPYGLASS_TOKEN_MODIFIERS]);
    const sourceToVirtual = new Map<string, CommandBridgeDocument>();
    const virtualToSource = new Map<string, string>();
    const pendingBridgeSyncs = new Map<string, ReturnType<typeof setTimeout>>();

    context.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(COMMAND_VIRTUAL_SCHEME, commandProvider));
    context.subscriptions.push(commandBridgeDiagnostics);

    const createVirtualUri = (sourceUri: vscode.Uri): vscode.Uri => {
        const encoded = encodeURIComponent(sourceUri.toString());
        return vscode.Uri.parse(`${COMMAND_VIRTUAL_SCHEME}:/${encoded}.mcfunction`);
    };

    const extractCommandLines = (text: string): CommandLineEntry[] => {
        return text.split(/\r?\n/).flatMap((line, index) => {
            const match = line.match(/^(\s*)\/(.*)$/);
            if (!match) {
                return [];
            }
            const slashColumn = match[1].length;
            return [{
                sourceLine: index,
                slashColumn,
                commandText: match[2]
            }];
        });
    };

    const buildSyntheticMcfunctionContent = (lines: CommandLineEntry[]): string => {
        if (lines.length === 0) {
            return '';
        }
        return [
            '# synthetic mcfunction generated from mcfpp command bridge',
            ...lines.map((entry) => entry.commandText)
        ].join('\n');
    };

    const syncCommandBridge = async (sourceDocument: vscode.TextDocument): Promise<void> => {
        if (sourceDocument.languageId !== 'mcfpp' || sourceDocument.uri.scheme !== 'file') {
            return;
        }
        const sourceKey = sourceDocument.uri.toString();
        const pendingSync = pendingBridgeSyncs.get(sourceKey);
        if (pendingSync) {
            clearTimeout(pendingSync);
            pendingBridgeSyncs.delete(sourceKey);
        }
        const lines = extractCommandLines(sourceDocument.getText());
        if (lines.length === 0) {
            const existing = sourceToVirtual.get(sourceKey);
            if (existing) {
                sourceToVirtual.delete(sourceKey);
                virtualToSource.delete(existing.virtualUri.toString());
                commandProvider.remove(existing.virtualUri);
                commandBridgeDiagnostics.delete(sourceDocument.uri);
            }
            return;
        }

        const virtualUri = sourceToVirtual.get(sourceKey)?.virtualUri ?? createVirtualUri(sourceDocument.uri);
        const bridgeDoc: CommandBridgeDocument = {
            sourceUri: sourceDocument.uri,
            virtualUri,
            lines,
            syntheticContent: buildSyntheticMcfunctionContent(lines)
        };
        sourceToVirtual.set(sourceKey, bridgeDoc);
        virtualToSource.set(virtualUri.toString(), sourceKey);
        commandProvider.update(virtualUri, bridgeDoc.syntheticContent);

        const virtualDocument = await vscode.workspace.openTextDocument(virtualUri);
        if (virtualDocument.languageId !== 'mcfunction') {
            await vscode.languages.setTextDocumentLanguage(virtualDocument, 'mcfunction');
        }
    };

    const scheduleCommandBridgeSync = (document: vscode.TextDocument): void => {
        if (document.languageId !== 'mcfpp' || document.uri.scheme !== 'file') {
            return;
        }
        const sourceKey = document.uri.toString();
        const pendingSync = pendingBridgeSyncs.get(sourceKey);
        if (pendingSync) {
            clearTimeout(pendingSync);
        }
        pendingBridgeSyncs.set(sourceKey, setTimeout(() => {
            pendingBridgeSyncs.delete(sourceKey);
            void syncCommandBridge(document);
        }, 75));
    };

    const mapRangeToSource = (range: vscode.Range, bridgeDoc: CommandBridgeDocument): vscode.Range | undefined => {
        const startLineEntry = bridgeDoc.lines[range.start.line - 1];
        const endLineEntry = bridgeDoc.lines[range.end.line - 1];
        if (!startLineEntry || !endLineEntry) {
            return undefined;
        }
        return new vscode.Range(
            new vscode.Position(startLineEntry.sourceLine, startLineEntry.slashColumn + 1 + range.start.character),
            new vscode.Position(endLineEntry.sourceLine, endLineEntry.slashColumn + 1 + range.end.character)
        );
    };

    const mapRangeToVirtual = (range: vscode.Range, bridgeDoc: CommandBridgeDocument): vscode.Range | undefined => {
        const inclusiveEndLine = range.end.character === 0 && range.end.line > range.start.line
            ? range.end.line - 1
            : range.end.line;
        const entries = bridgeDoc.lines
            .map((entry, index) => ({ entry, index }))
            .filter(({ entry }) => entry.sourceLine >= range.start.line && entry.sourceLine <= inclusiveEndLine);
        const first = entries[0];
        const last = entries[entries.length - 1];
        if (!first || !last) {
            return undefined;
        }

        const startCharacter = first.entry.sourceLine === range.start.line
            ? Math.max(0, Math.min(first.entry.commandText.length, range.start.character - first.entry.slashColumn - 1))
            : 0;
        const endCharacter = last.entry.sourceLine === range.end.line
            ? Math.max(0, Math.min(last.entry.commandText.length, range.end.character - last.entry.slashColumn - 1))
            : last.entry.commandText.length;
        return new vscode.Range(first.index + 1, startCharacter, last.index + 1, endCharacter);
    };

    const getCommandPositionMapping = (
        document: vscode.TextDocument,
        position: vscode.Position,
        bridgeDoc: CommandBridgeDocument
    ): CommandPositionMapping | undefined => {
        const lineText = document.lineAt(position.line).text;
        const match = lineText.match(/^(\s*)\/(.*)$/);
        if (!match) {
            return undefined;
        }

        const slashColumn = match[1].length;
        if (position.character <= slashColumn) {
            return undefined;
        }

        const lineEntryIndex = bridgeDoc.lines.findIndex((entry) => entry.sourceLine === position.line);
        if (lineEntryIndex < 0) {
            return undefined;
        }

        return {
            lineEntryIndex: lineEntryIndex + 1,
            characterInCommand: Math.max(0, position.character - slashColumn - 1),
            slashColumn,
            commandText: match[2]
        };
    };

    const refreshMappedDiagnostics = (virtualUri: vscode.Uri): void => {
        const sourceKey = virtualToSource.get(virtualUri.toString());
        if (!sourceKey) {
            return;
        }
        const bridgeDoc = sourceToVirtual.get(sourceKey);
        if (!bridgeDoc) {
            return;
        }
        const mapped = vscode.languages.getDiagnostics(virtualUri)
            .map((diagnostic) => {
                const mappedRange = mapRangeToSource(diagnostic.range, bridgeDoc);
                if (!mappedRange) {
                    return undefined;
                }
                const cloned = new vscode.Diagnostic(mappedRange, diagnostic.message, diagnostic.severity);
                cloned.code = diagnostic.code;
                cloned.source = diagnostic.source ?? 'spyglass';
                cloned.relatedInformation = diagnostic.relatedInformation;
                cloned.tags = diagnostic.tags;
                return cloned;
            })
            .filter((diagnostic): diagnostic is vscode.Diagnostic => diagnostic !== undefined);
        commandBridgeDiagnostics.set(bridgeDoc.sourceUri, mapped);
    };

    const buildMappedSemanticTokens = async (document: vscode.TextDocument, range?: vscode.Range): Promise<vscode.SemanticTokens | undefined> => {
        const sourceKey = document.uri.toString();
        await syncCommandBridge(document);
        const bridgeDoc = sourceToVirtual.get(sourceKey);
        if (!bridgeDoc) {
            return undefined;
        }

        const virtualRange = range ? mapRangeToVirtual(range, bridgeDoc) : undefined;
        if (range && !virtualRange) {
            return undefined;
        }

        const result = await vscode.commands.executeCommand<vscode.SemanticTokens | undefined>(
            virtualRange
                ? 'vscode.provideDocumentRangeSemanticTokens'
                : 'vscode.provideDocumentSemanticTokens',
            bridgeDoc.virtualUri,
            ...(virtualRange ? [virtualRange] : []),
            commandSemanticLegend
        );

        if (!result?.data?.length) {
            return undefined;
        }

        const mappedBuilder = new vscode.SemanticTokensBuilder(commandSemanticLegend);
        for (const token of decodeSemanticTokens(Array.from(result.data))) {
            const lineEntry = bridgeDoc.lines[token.line - 1];
            if (!lineEntry) {
                continue;
            }
            const mappedLine = lineEntry.sourceLine;
            const mappedStart = lineEntry.slashColumn + 1 + token.startCharacter;
            mappedBuilder.push(
                new vscode.Range(mappedLine, mappedStart, mappedLine, mappedStart + token.length),
                token.tokenType,
                token.tokenModifiers
            );
        }

        return mappedBuilder.build();
    };

    const mergeSemanticTokens = (
        base: vscode.SemanticTokens | null | undefined,
        commandTokens: vscode.SemanticTokens | undefined
    ): vscode.SemanticTokens | null | undefined => {
        if (!base) {
            return commandTokens;
        }
        if (!commandTokens) {
            return base;
        }

        const mergedBuilder = new vscode.SemanticTokensBuilder(commandSemanticLegend);
        const seen = new Set<string>();
        const decodedCommands = decodeSemanticTokens(Array.from(commandTokens.data));
        const commandLines = new Set(decodedCommands.map((token) => token.line));
        [
            ...decodeSemanticTokens(Array.from(base.data)).filter((token) => !commandLines.has(token.line)),
            ...decodedCommands
        ]
            .sort((left, right) =>
                left.line - right.line ||
                left.startCharacter - right.startCharacter ||
                left.length - right.length
            )
            .forEach((semanticToken) => {
                const key = `${semanticToken.line}:${semanticToken.startCharacter}:${semanticToken.length}`;
                if (seen.has(key)) {
                    return;
                }
                seen.add(key);
                mergedBuilder.push(
                    new vscode.Range(
                        semanticToken.line,
                        semanticToken.startCharacter,
                        semanticToken.line,
                        semanticToken.startCharacter + semanticToken.length
                    ),
                    semanticToken.tokenType,
                    semanticToken.tokenModifiers
                );
            });
        return mergedBuilder.build();
    };

    mergeCommandSemanticTokens = async (document, range, base) => {
        const commandTokens = await buildMappedSemanticTokens(document, range);
        return mergeSemanticTokens(base, commandTokens);
    };

    context.subscriptions.push(vscode.languages.onDidChangeDiagnostics((event) => {
        event.uris
            .filter((uri) => uri.scheme === COMMAND_VIRTUAL_SCHEME)
            .forEach(refreshMappedDiagnostics);
    }));

    context.subscriptions.push(vscode.workspace.onDidOpenTextDocument((document) => {
        void syncCommandBridge(document);
    }));

    context.subscriptions.push(vscode.workspace.onDidChangeTextDocument((event) => {
        scheduleCommandBridgeSync(event.document);
    }));

    context.subscriptions.push(vscode.workspace.onDidCloseTextDocument((document) => {
        if (document.languageId !== 'mcfpp' || document.uri.scheme !== 'file') {
            return;
        }
        const sourceKey = document.uri.toString();
        const pendingSync = pendingBridgeSyncs.get(sourceKey);
        if (pendingSync) {
            clearTimeout(pendingSync);
            pendingBridgeSyncs.delete(sourceKey);
        }
        const existing = sourceToVirtual.get(sourceKey);
        if (!existing) {
            return;
        }
        sourceToVirtual.delete(sourceKey);
        virtualToSource.delete(existing.virtualUri.toString());
        commandProvider.remove(existing.virtualUri);
        commandBridgeDiagnostics.delete(document.uri);
    }));

    // Register completion provider for mcfunction commands (lines starting with /)
    // This delegates to Spyglass's mcfunction completion provider
    context.subscriptions.push(vscode.languages.registerCompletionItemProvider(
        { language: 'mcfpp', scheme: 'file' },
        {
            provideCompletionItems: async (document, position, _token, completionContext) => {
                const sourceKey = document.uri.toString();

                // Sync command bridge to ensure we have the latest commands
                await syncCommandBridge(document);

                const bridgeDoc = sourceToVirtual.get(sourceKey);
                if (!bridgeDoc) {
                    return undefined;
                }

                const mapping = getCommandPositionMapping(document, position, bridgeDoc);
                if (!mapping) {
                    return undefined;
                }

                const refreshedBridge = sourceToVirtual.get(sourceKey);
                if (!refreshedBridge) {
                    return undefined;
                }

                const virtualPosition = new vscode.Position(
                    mapping.lineEntryIndex,
                    mapping.characterInCommand
                );

                // Execute Spyglass's completion provider for mcfunction
                const requestedTriggerCharacter = completionContext.triggerCharacter;
                const effectiveTriggerCharacter = requestedTriggerCharacter === ' ' ? undefined : requestedTriggerCharacter;

                const initialResult = await vscode.commands.executeCommand<vscode.CompletionList>(
                    'vscode.executeCompletionItemProvider',
                    refreshedBridge.virtualUri,
                    virtualPosition,
                    effectiveTriggerCharacter
                );
                const result = (!initialResult || (initialResult.items.length === 0 && requestedTriggerCharacter === ' '))
                    ? await vscode.commands.executeCommand<vscode.CompletionList>(
                        'vscode.executeCompletionItemProvider',
                        refreshedBridge.virtualUri,
                        virtualPosition,
                        undefined
                    )
                    : initialResult;
                refreshMappedDiagnostics(refreshedBridge.virtualUri);

                if (!result) {
                    return undefined;
                }

                const items = result.items.map((item) => {
                    const cloned = new vscode.CompletionItem(item.label, item.kind);
                    cloned.tags = item.tags;
                    cloned.detail = item.detail;
                    cloned.documentation = item.documentation;
                    cloned.sortText = item.sortText;
                    cloned.filterText = item.filterText;
                    cloned.preselect = item.preselect;
                    cloned.commitCharacters = item.commitCharacters;
                    cloned.command = item.command;
                    cloned.keepWhitespace = item.keepWhitespace;

                    if (typeof item.insertText === 'string' || item.insertText instanceof vscode.SnippetString) {
                        cloned.insertText = item.insertText;
                    }

                    if (item.textEdit && 'range' in item.textEdit) {
                        const mappedEditRange = mapRangeToSource(item.textEdit.range, refreshedBridge);
                        if (mappedEditRange) {
                            cloned.textEdit = new vscode.TextEdit(mappedEditRange, item.textEdit.newText);
                        }
                    }

                    if (item.additionalTextEdits) {
                        cloned.additionalTextEdits = item.additionalTextEdits
                            .map((edit) => {
                                const mappedRange = mapRangeToSource(edit.range, refreshedBridge);
                                return mappedRange ? new vscode.TextEdit(mappedRange, edit.newText) : undefined;
                            })
                            .filter((edit): edit is vscode.TextEdit => edit !== undefined);
                    }

                    return cloned;
                });

                const prioritizedItems = items.map((item) => {
                        item.sortText = `\u0000${item.sortText ?? ''}`;
                        return item;
                    });

                return new vscode.CompletionList(prioritizedItems, result.isIncomplete);
            }
        },
        // Trigger characters for mcfunction command completion
        '/', ' ', ':', '@', '[', '{', '"', '\'', '~', '^', '-', '.', ',', '=', '!'
    ));

    vscode.workspace.textDocuments
        .filter((document) => document.languageId === 'mcfpp' && document.uri.scheme === 'file')
        .forEach((document) => {
            void syncCommandBridge(document);
        });
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
