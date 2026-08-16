package top.mcfpp.intellij.lang;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.command.MinecraftCommandCompletionSupport;
import top.mcfpp.intellij.command.MinecraftFunctionCommandContext;

public final class MinecraftFunctionCompletionContributor extends CompletionContributor implements DumbAware {
    public MinecraftFunctionCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(MinecraftFunctionLanguage.INSTANCE),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(
                            @NotNull CompletionParameters parameters,
                            @NotNull ProcessingContext context,
                            @NotNull CompletionResultSet result
                    ) {
                        String source = parameters.getOriginalFile().getViewProvider().getContents().toString();
                        MinecraftFunctionCommandContext command = MinecraftFunctionCommandContext.at(source, parameters.getOffset());
                        if (command == null) return;
                        MinecraftCommandCompletionSupport.addCompletions(
                                parameters.getPosition().getProject(),
                                result,
                                command.buffer(),
                                command.cursor(),
                                command.documentBaseOffset(),
                                command.caretOffset(),
                                source
                        );
                    }
                }
        );
    }
}
