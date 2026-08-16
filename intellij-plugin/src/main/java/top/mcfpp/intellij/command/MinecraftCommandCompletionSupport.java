package top.mcfpp.intellij.command;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared IntelliJ presentation and insertion behavior for Datapack Sandbox completions. */
public final class MinecraftCommandCompletionSupport {
    private MinecraftCommandCompletionSupport() {
    }

    public static void addCompletions(
            @NotNull Project project,
            @NotNull CompletionResultSet result,
            @NotNull String buffer,
            int cursor,
            int documentBaseOffset,
            int caretOffset,
            @NotNull String originalSource
    ) {
        ProgressManager.checkCanceled();
        McfppCommandService.CompletionResult completion = McfppCommandService.getInstance(project)
                .complete(buffer, cursor);
        ProgressManager.checkCanceled();

        CompletionResultSet commandResult = result.withPrefixMatcher("");
        if (!completion.multilineHints().isEmpty()) {
            commandResult.addLookupAdvertisement("Minecraft · " + completion.multilineHints().getFirst());
        } else if (!completion.inlineHint().isBlank()) {
            commandResult.addLookupAdvertisement("Minecraft · " + completion.inlineHint());
        }

        Set<String> seen = new HashSet<>();
        for (McfppCommandService.CompletionSuggestion suggestion : completion.suggestions()) {
            String identity = suggestion.value() + '\0' + suggestion.start() + '\0' + suggestion.end();
            if (!seen.add(identity)) continue;

            int start = documentBaseOffset + bounded(suggestion.start(), buffer.length());
            int end = documentBaseOffset + bounded(suggestion.end(), buffer.length());
            boolean addSpace = suggestion.appendSpace() &&
                    (end >= originalSource.length() || !Character.isWhitespace(originalSource.charAt(end)));
            String replacement = suggestion.value() + (addSpace ? " " : "");
            CompletionKind kind = CompletionKind.from(suggestion, buffer, cursor);

            LookupElementBuilder lookup = LookupElementBuilder.create(suggestion.value())
                    .withCaseSensitivity(false)
                    .withIcon(kind.icon)
                    .withTypeText(kind.label, true)
                    .withInsertHandler(new McfppCommandCompletionInsertHandler(
                            originalSource,
                            start,
                            end,
                            caretOffset,
                            replacement
                    ));
            if (kind.bold) lookup = lookup.bold();
            if (!suggestion.description().isBlank() && !suggestion.description().equals(suggestion.group())) {
                lookup = lookup.withTailText("  " + suggestion.description(), true);
            }
            commandResult.addElement(PrioritizedLookupElement.withPriority(lookup, kind.priority));
        }
        commandResult.stopHere();
    }

    private static int bounded(int offset, int length) {
        return Math.max(0, Math.min(offset, length));
    }

    private enum CompletionKind {
        TARGET("目标选择器", AllIcons.Nodes.Parameter, 3_000, true),
        SELECTOR_FIELD("选择器参数", AllIcons.Nodes.Field, 2_800, true),
        LITERAL("命令分支", AllIcons.Nodes.Function, 2_400, true),
        BLOCK("方块", AllIcons.Nodes.Class, 1_800, false),
        ITEM("物品", AllIcons.Nodes.Constant, 1_800, false),
        ENTITY("实体类型", AllIcons.Nodes.Class, 1_700, false),
        RESOURCE("资源位置", AllIcons.Nodes.Class, 1_600, false),
        VALUE("参数值", AllIcons.Nodes.Variable, 1_400, false),
        GENERIC("Minecraft 参数", AllIcons.Nodes.Parameter, 1_000, false);

        private final String label;
        private final Icon icon;
        private final double priority;
        private final boolean bold;

        CompletionKind(String label, Icon icon, double priority, boolean bold) {
            this.label = label;
            this.icon = icon;
            this.priority = priority;
            this.bold = bold;
        }

        private static CompletionKind from(
                McfppCommandService.CompletionSuggestion suggestion,
                String buffer,
                int cursor
        ) {
            String value = suggestion.value().toLowerCase(Locale.ROOT);
            String group = suggestion.group().toLowerCase(Locale.ROOT);
            String description = suggestion.description().toLowerCase(Locale.ROOT);
            String combined = group + ' ' + description;
            if (value.matches("@[paresn]") || combined.contains("selector") || combined.contains("target")) {
                return value.startsWith("@") ? TARGET : SELECTOR_FIELD;
            }
            if (value.endsWith("=") && insideSelector(buffer, cursor)) return SELECTOR_FIELD;
            if (combined.contains("block")) return BLOCK;
            if (combined.contains("item")) return ITEM;
            if (combined.contains("entity") || combined.contains("entities")) return ENTITY;
            if (combined.contains("resource") || combined.contains("function") || combined.contains("predicate") ||
                    combined.contains("loot") || combined.contains("sound") || combined.contains("particle") ||
                    combined.contains("advancement")) return RESOURCE;
            if (group.contains("literal") || suggestion.behavior().equalsIgnoreCase("literal")) return LITERAL;
            if (group.contains("value") || group.contains("number") || group.contains("boolean")) return VALUE;
            if (value.indexOf(':') > 0) return RESOURCE;
            return GENERIC;
        }

        private static boolean insideSelector(String buffer, int cursor) {
            int bounded = Math.max(0, Math.min(cursor, buffer.length()));
            int open = buffer.lastIndexOf('[', bounded - 1);
            int close = buffer.lastIndexOf(']', bounded - 1);
            return open > close && buffer.lastIndexOf('@', open) >= 0;
        }
    }
}
