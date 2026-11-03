package top.mcfpp.intellij.lsp;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import org.eclipse.lsp4j.CompletionItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mcfpp.intellij.mni.McfppMacroCompletionContributor;

final class McfppLspCompletionSupport extends LspCompletionSupport {
    @Override
    public @Nullable LookupElement createLookupElement(@NotNull CompletionParameters parameters,
                                                        @NotNull CompletionItem item) {
        var javaPlugin=PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"));
        // Keep the Java PSI candidate's insertion and navigation while preserving other LSP overloads.
        if(javaPlugin!=null && javaPlugin.isEnabled() && McfppMacroCompletionContributor.provides(
                parameters.getOriginalFile(),parameters.getOffset(),item.getLabel(),item.getDetail())) return null;
        return super.createLookupElement(parameters,item);
    }
}
