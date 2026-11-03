import readline from 'node:readline';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

if (Number(process.versions.node.split('.')[0]) < 20) throw new Error('MCFPP formatting requires Node.js 20 or newer.');
const bundled = fileURLToPath(new URL('./mcfpp-linter/', import.meta.url));
const linterHome = process.env.MCFPP_LINTER_HOME || bundled;
const importAt = name => import(pathToFileURL(path.join(linterHome, name)).href);
const { lint, applyFixes } = await importAt('src/lint.mjs');
const { loadConfig, createIgnoreMatcher } = await importAt('src/config.mjs');
const { collectFiles } = await importAt('src/files.mjs');
const plugin = (await importAt('src/prettier-plugin.mjs')).default;
const require = createRequire(path.join(linterHome, 'package.json'));
const prettierEntry = process.env.MCFPP_PRETTIER_HOME ? path.join(process.env.MCFPP_PRETTIER_HOME, 'index.mjs') : require.resolve('prettier');
const prettierModule = await import(pathToFileURL(prettierEntry).href);
const prettier = prettierModule.default || prettierModule;
if (!prettier.version.startsWith('3.')) throw new Error('MCFPP requires Prettier 3.');

// Layout is handled by formatting. It does not make otherwise valid editor text a warning.
const formattingRules = new Set(['no-trailing-whitespace', 'final-newline', 'no-mixed-indentation']);
const editorDiagnostics = diagnostics => diagnostics.filter(d => !formattingRules.has(d.ruleId));

export async function handle(request) {
  const filename = path.resolve(request.filePath);
  const original = request.source;
  if (typeof original !== 'string') throw new Error('source must be a string');
  const configuration = await loadConfig({ cwd: path.dirname(filename) });
  const lintIgnored = createIgnoreMatcher(configuration.ignore, configuration.baseDir)(filename);
  const diagnostics = lintIgnored ? [] : lint(original, configuration.options);
  if (request.operation === 'lint') return { source: original, diagnostics: editorDiagnostics(diagnostics), ignored: lintIgnored };
  let source = request.fix && !lintIgnored ? applyFixes(original, diagnostics) : original;
  let cursor = Math.max(0, Math.min(request.cursorOffset ?? 0, original.length));
  if (request.fix && !lintIgnored) {
    let delta = 0;
    for (const { fix } of diagnostics.filter(d => d.fix).sort((a, b) => a.fix.start - b.fix.start)) {
      if (cursor < fix.start) break;
      if (cursor <= fix.end) { cursor = fix.start + delta + Math.min(cursor - fix.start, fix.text.length); delta = 0; break; }
      delta += fix.text.length - (fix.end - fix.start);
    }
    cursor += delta;
  }
  if (request.operation === 'fix') return { source, cursorOffset: cursor, diagnostics: lintIgnored ? [] : editorDiagnostics(lint(source, configuration.options)) };
  if (request.operation !== 'format') throw new Error('Unknown operation: ' + request.operation);
  const ignorePaths = [];
  let directory = path.dirname(filename);
  while (true) {
    const candidate = path.join(directory, '.prettierignore');
    if (existsSync(candidate)) { ignorePaths.push(candidate); break; }
    const parent = path.dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  const info = await prettier.getFileInfo(filename, { ignorePath: ignorePaths, resolveConfig: false });
  if (info.ignored) return { source: original, diagnostics: editorDiagnostics(diagnostics), ignored: true };
  const config = await prettier.resolveConfig(filename, { editorconfig: true, useCache: false }) || {};
  const plugins = [plugin];
  for (const item of config.plugins || []) {
    if (typeof item === 'string' && !item.includes('mcfpp-linter')) {
      const resolver = createRequire(filename);
      plugins.push(resolver.resolve(item));
    }
  }
  const options = { tabWidth: 4, printWidth: 100, endOfLine: 'auto', ...config, plugins, parser: 'mcfpp', filepath: filename };
  const cursorOffset = Math.max(0, Math.min(cursor, source.length));
  const result = await prettier.formatWithCursor(source, { ...options, cursorOffset });
  return { source: result.formatted, cursorOffset: result.cursorOffset,
    diagnostics: lintIgnored ? [] : editorDiagnostics(lint(result.formatted, configuration.options)), ignored: false };
}

async function checkFiles(mode, root) {
  const config = await loadConfig({ cwd: root });
  const selected = await collectFiles([root], config);
  let failed = selected.errors.length > 0;
  for (const error of selected.errors) process.stderr.write(error.message + '\n');
  for (const filename of selected.files) {
    try {
      const source = await readFile(filename, 'utf8');
      const result = await handle({ operation: mode === '--check-format' ? 'format' : 'lint', filePath: filename, source, fix: false });
      if (mode === '--check-format' && result.source !== source) { failed = true; process.stdout.write(filename + ':1:1: Formatting differs\n'); }
      if (mode === '--check-lint') for (const d of result.diagnostics) {
        process.stdout.write(filename + ':' + d.line + ':' + d.column + ': ' + d.severity + ': ' + d.message + ' (' + d.ruleId + ')\n');
        if (d.severity === 'error') failed = true;
      }
    } catch (error) { failed = true; process.stderr.write(filename + ': ' + error.message + '\n'); }
  }
  process.stdout.write(failed ? 'MCFPP check failed.\n' : 'MCFPP check passed.\n');
  process.exitCode = failed ? 1 : 0;
}

if (process.argv[2]?.startsWith('--check-')) await checkFiles(process.argv[2], path.resolve(process.argv[3] || '.'));
else {
  const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of input) {
    let request;
    try {
      request = JSON.parse(line);
      const result = await handle(request);
      process.stdout.write(JSON.stringify({ id: request.id, ok: true, ...result }) + '\n');
    } catch (error) {
      process.stdout.write(JSON.stringify({ id: request?.id, ok: false, error: error.message }) + '\n');
    }
  }
}

