package top.mcfpp.intellij.mni;

import com.intellij.openapi.project.*;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.*;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import top.mcfpp.intellij.lang.*;
import top.mcfpp.language.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public final class McfppMacroIndex {
    public static final String ANNOTATION="top.mcfpp.annotations.MNIMacro";
    private static final Pattern DIRECTIVE=Pattern.compile("^//>\\s*injectedBy\\s+(?:\"([^\"]+)\"|([\\w.$]+))\\s*$");
    private static final Key<CachedValue<List<Macro>>> CACHE=Key.create("mcfpp.macro.index");
    public record Macro(String namespace,String name,String returnType,List<MniSymbolSnapshot.Parameter> parameters,PsiMethod method) {
        public String signature() { return "func "+name+"("+parameters.stream().map(p -> p.name+": "+p.typeName).collect(java.util.stream.Collectors.joining(", "))+"): "+returnType; }
    }
    private McfppMacroIndex() {}
    public static List<Macro> all(Project project) {
        if(DumbService.isDumb(project)) return List.of();
        return CachedValuesManager.getManager(project).getCachedValue(project,CACHE,() -> CachedValueProvider.Result.create(
            collect(project),PsiModificationTracker.MODIFICATION_COUNT,ProjectRootModificationTracker.getInstance(project)),false);
    }
    private static List<Macro> collect(Project project) {
        List<Macro> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        var manager=PsiManager.getInstance(project);
        for(var virtual:FileTypeIndex.getFiles(McfppFileType.INSTANCE,GlobalSearchScope.projectScope(project))) {
            if(!com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(virtual)) continue;
            var file=manager.findFile(virtual); if(file==null) continue;
            if(!file.getText().contains("injectedBy")) continue;
            String namespace;
            try {
                var directory=virtual.getParent(); com.intellij.openapi.vfs.VirtualFile config=null;
                while(directory!=null && config==null) { config=directory.findChild("mcfpp.toml"); if(config==null) config=directory.findChild("mcfpp.json"); directory=directory.getParent(); }
                if(config==null) continue;
                var configuration=ProjectConfiguration.parse(Path.of(config.getPath()),com.intellij.openapi.vfs.VfsUtilCore.loadText(config));
                Path source=Path.of(virtual.getPath()).toAbsolutePath().normalize();
                if(!source.startsWith(configuration.sourcePath())) continue;
                namespace=configuration.namespaceFor(source);
                var model=McfppFileModels.get(file);
                if(!model.namespace().isEmpty()) namespace=model.namespace();
            } catch(Exception ignored) { continue; }
            for(String line:file.getText().split("\\R")) {
                if(line.isBlank()) continue;
                var directive=DIRECTIVE.matcher(line.trim());
                if(!line.trim().startsWith("//>")) break;
                if(!directive.matches()) continue;
                String className=directive.group(1)!=null ? directive.group(1) : directive.group(2);
                var provider=JavaPsiFacade.getInstance(project).findClass(className,GlobalSearchScope.allScope(project));
                if(provider==null) continue;
                for(var method:provider.getMethods()) {
                    var annotation=method.getAnnotation(ANNOTATION);
                    if(annotation==null) continue;
                    String name=constant(annotation.findAttributeValue("identifier"));
                    if(name.isEmpty()) name=method.getName();
                    String type=constant(annotation.findAttributeValue("returnType")); if(type.isEmpty()) type="void";
                    var parameters=parameters(annotation);
                    String identity=namespace+":"+name+parameters.stream().map(p -> p.typeName).toList()+method.getContainingFile().getVirtualFile().getUrl()+method.getTextOffset();
                    if(seen.add(identity)) result.add(new Macro(namespace,name,type,parameters,method));
                }
            }
        }
        return List.copyOf(result);
    }
    public static List<MniSymbolSnapshot.Parameter> parameters(PsiAnnotation annotation) {
        var value=annotation.findAttributeValue("params");
        List<PsiAnnotationMemberValue> entries=value instanceof PsiArrayInitializerMemberValue array ? Arrays.asList(array.getInitializers()) : value==null ? List.of() : List.of(value);
        List<MniSymbolSnapshot.Parameter> result=new ArrayList<>();
        for(var entry:entries) if(entry instanceof PsiAnnotation param) {
            var p=new MniSymbolSnapshot.Parameter(); p.name=constant(param.findAttributeValue("name")); p.typeName=constant(param.findAttributeValue("type"));
            var mode=param.findAttributeValue("mode"); String text=mode==null ? "VALUE" : mode.getText(); p.mode=text.substring(text.lastIndexOf('.')+1);
            result.add(p);
        }
        return List.copyOf(result);
    }
    public static String constant(PsiAnnotationMemberValue value) {
        if(!(value instanceof PsiExpression e)) return "";
        Object result=JavaPsiFacade.getInstance(value.getProject()).getConstantEvaluationHelper().computeConstantExpression(e);
        return result instanceof String s ? s : "";
    }
    public static List<Macro> visible(PsiFile file,String name) {
        var model=McfppFileModels.get(file);
        return all(file.getProject()).stream().filter(m -> {
            if(m.namespace().equals(model.namespace()) && (name==null || m.name().equals(name))) return true;
            return model.imports().stream().anyMatch(i -> i.namespace().equals(m.namespace()) &&
                (name==null ? i.isWildcard() || i.importedName().equals(m.name()) : i.exposes(name) && i.originalName(name).equals(m.name())));
        }).toList();
    }
    public static String qualifier(PsiFile file,int offset) {
        var match=Pattern.compile("([\\w.]+)\\s*:\\s*$").matcher(file.getText().substring(0,Math.min(offset,file.getTextLength())));
        return match.find() ? match.group(1) : null;
    }
    public static List<Macro> visibleAt(PsiFile file,String name,int offset) {
        String qualifier=qualifier(file,offset);
        if(qualifier==null) return visible(file,name);
        var imports=McfppFileModels.get(file).imports();
        String namespace=imports.stream().filter(i -> i.isWildcard() && qualifier.equals(i.alias())).map(i -> i.namespace()).findFirst().orElse(qualifier);
        return all(file.getProject()).stream().filter(m -> namespace.equals(m.namespace()) && (name==null || name.equals(m.name()))).toList();
    }
    public static String visibleName(PsiFile file,Macro macro,int offset) {
        if(qualifier(file,offset)!=null || macro.namespace().equals(McfppFileModels.get(file).namespace())) return macro.name();
        return McfppFileModels.get(file).imports().stream().filter(i -> !i.isWildcard() && i.namespace().equals(macro.namespace()) && i.importedName().equals(macro.name()))
            .map(i -> i.alias()==null || i.alias().isEmpty() ? macro.name() : i.alias()).findFirst().orElse(macro.name());
    }
    public static MniSymbolSnapshot snapshot(Project project) {
        var snapshot=new MniSymbolSnapshot();
        snapshot.projectUri=project.getBasePath()==null ? "" : Path.of(project.getBasePath()).toUri().toString();
        snapshot.generation=PsiModificationTracker.getInstance(project).getModificationCount();
        List<MniSymbolSnapshot.Symbol> symbols=new ArrayList<>();
        for(var macro:all(project)) {
            var symbol=new MniSymbolSnapshot.Symbol(); symbol.namespace=macro.namespace(); symbol.name=macro.name();
            symbol.typeName=macro.returnType(); symbol.detail=macro.signature(); symbol.parameters=macro.parameters();
            var file=macro.method().getContainingFile(); symbol.uri=Path.of(file.getVirtualFile().getPath()).toUri().toString();
            var document=PsiDocumentManager.getInstance(project).getDocument(file);
            var identifier=macro.method().getNameIdentifier();
            if(document!=null && identifier!=null) {
                int offset=identifier.getTextOffset(); symbol.line=document.getLineNumber(offset);
                symbol.character=offset-document.getLineStartOffset(symbol.line); symbol.endLine=symbol.line; symbol.endCharacter=symbol.character+identifier.getTextLength();
            }
            var doc=macro.method().getDocComment(); if(doc!=null) symbol.documentation=doc.getText();
            symbols.add(symbol);
        }
        snapshot.symbols=List.copyOf(symbols); return snapshot;
    }
}

