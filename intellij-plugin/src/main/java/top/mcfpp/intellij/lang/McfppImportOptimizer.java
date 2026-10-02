package top.mcfpp.intellij.lang;

import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;

/** Conservatively sorts isolated import lines; comments and directives are block boundaries. */
public final class McfppImportOptimizer implements ImportOptimizer {
    @Override public boolean supports(@NotNull PsiFile file) { return file instanceof McfppFile; }

    @Override public @NotNull Runnable processFile(@NotNull PsiFile file) {
        return () -> {
            PsiDocumentManager manager = PsiDocumentManager.getInstance(file.getProject());
            Document document = manager.getDocument(file);
            if (document == null) return;
            String source = document.getText();
            String organized = organize(source);
            if (!source.equals(organized)) {
                document.setText(organized);
                manager.commitDocument(document);
            }
        };
    }

    static String organize(String source) {
        List<McfppImportLayout.Entry> entries = McfppImportLayout.entries(source);
        java.util.Set<Integer> attached = new java.util.HashSet<>();
        var tokens = McfppSourceTokens.lex(source);
        for (int i = 1; i < tokens.size(); i++) {
            var previous = tokens.get(i - 1);
            var token = tokens.get(i);
            String gap = source.substring(previous.end(), token.start());
            if (token.text().equals("import") && previous.trivia() && gap.isBlank() &&
                    gap.chars().filter(value -> value == '\n').count() <= 1 &&
                    source.substring(lineStart(source, previous.start()), previous.start()).isBlank()) {
                attached.add(token.start());
            }
        }
        List<List<McfppImportLayout.Entry>> blocks = new ArrayList<>();
        List<McfppImportLayout.Entry> block = new ArrayList<>();
        for (var entry : entries) {
            int start = lineStart(source, entry.start());
            int end = lineEnd(source, entry.end());
            if (!source.substring(start, entry.start()).isBlank() ||
                    !source.substring(entry.end(), end).isBlank()) {
                if (!block.isEmpty()) blocks.add(block);
                block = new ArrayList<>();
                continue;
            }
            if (!block.isEmpty() && !source.substring(block.getLast().end(), entry.start()).isBlank()) {
                blocks.add(block);
                block = new ArrayList<>();
            }
            if (attached.contains(entry.start())) {
                if (!block.isEmpty()) blocks.add(block);
                blocks.add(List.of(entry));
                block = new ArrayList<>();
            } else {
                block.add(entry);
            }
        }
        if (!block.isEmpty()) blocks.add(block);
        StringBuilder result = new StringBuilder(source);
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        for (int i = blocks.size() - 1; i >= 0; i--) {
            block = blocks.get(i);
            if (block.size() < 2) continue;
            LinkedHashMap<McfppImport, String> unique = new LinkedHashMap<>();
            for (var entry : block) unique.putIfAbsent(entry.value(),
                    source.substring(lineStart(source, entry.start()), entry.end()));
            String sorted = unique.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().namespace() + ':' + e.getKey().importedName() +
                            " as " + (e.getKey().alias() == null ? "" : e.getKey().alias())))
                    .map(java.util.Map.Entry::getValue).collect(java.util.stream.Collectors.joining(newline));
            result.replace(lineStart(source, block.getFirst().start()), block.getLast().end(), sorted);
        }
        return result.toString();
    }

    private static int lineStart(String source, int offset) { return source.lastIndexOf('\n', offset - 1) + 1; }
    private static int lineEnd(String source, int offset) {
        int end = source.indexOf('\n', offset);
        return end < 0 ? source.length() : end;
    }
}
