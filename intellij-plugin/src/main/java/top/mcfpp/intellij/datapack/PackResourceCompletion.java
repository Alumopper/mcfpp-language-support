package top.mcfpp.intellij.datapack;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.api.resources.PackResources;
import top.mcfpp.intellij.lang.McfppFile;

public final class PackResourceCompletion extends CompletionContributor {
    private static boolean idChar(char c) { return Character.isLetterOrDigit(c) || "_./:-#".indexOf(c) >= 0; }
    @Override public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        var file = parameters.getOriginalFile(); var index = PackResourceIndex.forFile(file);
        if (index == null) return;
        String text = parameters.getEditor().getDocument().getText();
        int caret = parameters.getOffset(), start = caret, end = caret;
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
                    .withInsertHandler((insert, item) -> {
                        var document = insert.getDocument();
                        int replaceEnd = Math.min(document.getTextLength(), insert.getTailOffset() + remainder);
                        document.replaceString(tokenStart, replaceEnd, value);
                        insert.getEditor().getCaretModel().moveToOffset(tokenStart + value.length());
                        insert.setTailOffset(tokenStart + value.length());
                    }));
        }
    }
}
