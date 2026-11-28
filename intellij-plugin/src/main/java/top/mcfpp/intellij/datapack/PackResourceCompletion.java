package top.mcfpp.intellij.datapack;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.api.resources.PackResources;
import top.mcfpp.intellij.lang.McfppFile;
import top.mcfpp.intellij.lang.McfppLspState;
import top.mcfpp.intellij.lang.McfppResourceArguments;

import java.util.LinkedHashSet;

public final class PackResourceCompletion extends CompletionContributor {
    private static boolean idChar(char c) { return Character.isLetterOrDigit(c) || "_./:-#".indexOf(c) >= 0; }
    @Override public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        var file = parameters.getOriginalFile();
        var index = PackResourceIndex.forFile(file);
        String text = parameters.getEditor().getDocument().getText();
        int caret = parameters.getOffset(), start = caret, end = caret;
        if (file instanceof McfppFile && !McfppLspState.isRunning(file.getProject())) {
            var argument = McfppResourceArguments.atCaret(file, caret);
            if (argument != null) {
                completeArgument(argument, index, caret, result);
                result.stopHere();
                return;
            }
        }
        if (index == null) return;
        while (start > 0 && idChar(text.charAt(start - 1))) start--;
        while (end < text.length() && idChar(text.charAt(end))) end++;
        boolean tag = start < text.length() && text.charAt(start) == '#';
        String probe = (tag ? "#" : "") + "mcfpp_completion:target";
        String candidate = text.substring(0, start) + probe + text.substring(end);
        var refs = file.getName().equals("sounds.json") ? PackResources.soundReferences(candidate)
                : PackResources.references(candidate, index.owner(file), file instanceof McfppFile);
        final int tokenStart = start, remainder = end - caret;
        var context = refs.stream().filter(ref -> ref.start() <= caret && ref.end() >= tokenStart && ref.key().id().equals("mcfpp_completion:target")).findFirst().orElse(null);
        if (context == null) return;
        String prefix = text.substring(start, caret);
        var scoped = result.withPrefixMatcher(prefix);
        for (var entry : index.definitions.entrySet()) if (entry.getKey().kind().equals(context.key().kind())) {
            String value = (tag ? "#" : "") + entry.getKey().id() + context.suffix();
            scoped.addElement(LookupElementBuilder.create(value).withTypeText(context.key().kind(), true)
                    .withTailText(entry.getValue().stream().anyMatch(PackResourceIndex.Target::entrypoint) ? "  MCFPP entrypoint" : "", true)
                    .withInsertHandler((insert, item) -> replace(insert, tokenStart, remainder, value)));
        }
    }

    private static void completeArgument(McfppResourceArguments.Use argument,
                                         PackResourceIndex index, int caret, CompletionResultSet result) {
        String prefix = argument.value().substring(0, caret - argument.start());
        boolean tag = prefix.startsWith("#");
        String kind = tag && !argument.kind().startsWith("tag/") ? "tag/" + argument.kind() : argument.kind();
        var ids = new LinkedHashSet<>(PackResourceIndex.vanilla(index).ids(kind));
        if (index != null) {
            index.definitions.keySet().stream().filter(key -> key.kind().equals(kind)).map(PackResources.Key::id).forEach(ids::add);
            index.generatedFunctions.stream().filter(key -> key.kind().equals(kind)).map(PackResources.Key::id).forEach(ids::add);
        }
        var scoped = result.withPrefixMatcher(prefix);
        for (String id : ids) {
            String value = (tag ? "#" : "") + id;
            var item = LookupElementBuilder.create(value).withTypeText(kind, true)
                    .withInsertHandler((insert, element) -> replace(insert, argument.start(), argument.end() - caret, value));
            if (id.startsWith("minecraft:")) item = item.withLookupString((tag ? "#" : "") + id.substring("minecraft:".length()));
            scoped.addElement(item);
        }
    }

    private static void replace(InsertionContext insertion, int start, int remainder, String value) {
        var document = insertion.getDocument();
        int replaceEnd = Math.min(document.getTextLength(), insertion.getTailOffset() + remainder);
        document.replaceString(start, replaceEnd, value);
        insertion.getEditor().getCaretModel().moveToOffset(start + value.length());
        insertion.setTailOffset(start + value.length());
    }
}
