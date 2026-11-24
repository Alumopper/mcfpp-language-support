package top.mcfpp.intellij.mni;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.lang.*;

import java.util.Map;

public final class McfppMacroCompletionContributor extends CompletionContributor {
    public McfppMacroCompletionContributor() {
        extend(CompletionType.BASIC,PlatformPatterns.psiElement().withLanguage(McfppLanguage.INSTANCE),new CompletionProvider<>() {
            @Override protected void addCompletions(@NotNull CompletionParameters p,@NotNull ProcessingContext context,@NotNull CompletionResultSet result) {
                if(!(p.getOriginalFile() instanceof McfppFile file)) return;
                int start=p.getOffset()-result.getPrefixMatcher().getPrefix().length();
                boolean member = isMemberAccess(file,start);
                String receiver = member ? McfppMacroIndex.receiverTypeAt(file,start) : null;
                for(var macro:McfppMacroIndex.visibleAt(file,null,start)) {
                    if(member && !McfppMacroIndex.receiverMatches(macro,receiver)) continue;
                    if(!member && macro.receiverType()!=null && !macro.receiverType().isBlank()) continue;
                    Map<String,String> bindings = member ? McfppMacroIndex.receiverBindings(macro,receiver) : Map.of();
                    String signature = macro.callSignature(bindings);
                    result.addElement(LookupElementBuilder.create(macro.method(),McfppMacroIndex.visibleName(file,macro,start))
                    .withIcon(com.intellij.icons.AllIcons.Nodes.Method).withTailText(signature.substring(macro.name().length()),true)
                    .withTypeText(macro.namespace(),true).withInsertHandler((insertion,item) -> {
                        int tail=insertion.getTailOffset();
                        if(tail>=insertion.getDocument().getTextLength() || insertion.getDocument().getCharsSequence().charAt(tail)!='(') {
                            insertion.getDocument().insertString(tail,"()");
                            insertion.getEditor().getCaretModel().moveToOffset(tail+(macro.parameters().isEmpty()?2:1));
                        }
                    }));
                }
            }
        });
    }

    public static boolean provides(com.intellij.psi.PsiFile file,int offset,String name,String signature) {
        if(!(file instanceof McfppFile) || signature==null) return false;
        int start=Math.min(offset,file.getTextLength());
        while(start>0 && Character.isUnicodeIdentifierPart(file.getText().charAt(start-1))) start--;
        boolean member = isMemberAccess(file,start);
        String receiver = member ? McfppMacroIndex.receiverTypeAt(file,start) : null;
        for(var macro:McfppMacroIndex.visibleAt(file,null,start)) {
            if(member && !McfppMacroIndex.receiverMatches(macro,receiver)) continue;
            if(!member && macro.receiverType()!=null && !macro.receiverType().isBlank()) continue;
            if(!McfppMacroIndex.visibleName(file,macro,start).equals(name)) continue;
            Map<String,String> bindings = member ? McfppMacroIndex.receiverBindings(macro,receiver) : Map.of();
            String expected=macro.callSignature(bindings,name);
            if(expected.endsWith(": void")) expected=expected.substring(0,expected.length()-6);
            if(expected.equals(signature)) return true;
        }
        return false;
    }

    private static boolean isMemberAccess(com.intellij.psi.PsiFile file,int start) {
        int cursor=Math.min(start,file.getTextLength())-1;
        while(cursor>=0 && Character.isWhitespace(file.getText().charAt(cursor))) cursor--;
        return cursor>=0 && file.getText().charAt(cursor)=='.';
    }
}

