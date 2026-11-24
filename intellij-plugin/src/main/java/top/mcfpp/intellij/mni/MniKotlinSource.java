package top.mcfpp.intellij.mni;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Source fallback for a Kotlin provider when Java light classes are unavailable. */
final class MniKotlinSource {
    private static final Pattern FUNCTION = Pattern.compile(
            "\\bfun\\s+(?:<[^{}()]*?>\\s+)?(?:[^\\s(]+\\.)?`?([\\w$]+)`?\\s*\\(");
    private static final Pattern CLASS = Pattern.compile("\\b(?:class|object|interface)\\s+([\\w$]+)");
    private static final Pattern MNI = Pattern.compile("@(?:[\\w.]+\\.)?(?:MNIFunction|MNIMacro)\\s*(?:\\(|\\b)");
    private static final Pattern NORMAL_PARAMS = Pattern.compile("\\bnormalParams\\s*=\\s*\\[([^]]*)]", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private MniKotlinSource() {}

    static List<PsiElement> resolve(PsiManager manager, List<VirtualFile> roots, String qualifiedName,
                                    String method, Optional<MniFunctionSignature> signature) {
        String path = qualifiedName.replace('.', '/');
        String owner = path.substring(path.lastIndexOf('/') + 1);
        List<PsiElement> candidates = new ArrayList<>();
        List<PsiElement> exact = new ArrayList<>();
        for (VirtualFile root : roots) {
            VirtualFile file = root.findFileByRelativePath(path + ".kt");
            if (file == null && owner.endsWith("Kt")) {
                file = root.findFileByRelativePath(path.substring(0, path.length() - 2) + ".kt");
            }
            if (file == null) continue;
            PsiFile source = manager.findFile(file);
            if (source == null) continue;
            String text = source.getText();
            if (method == null) {
                var classes = CLASS.matcher(text);
                while (classes.find()) {
                    if (classes.group(1).equals(owner)) return leaf(source, classes.start(1));
                }
                if (owner.endsWith("Kt")) return List.of(source);
                continue;
            }
            var functions = FUNCTION.matcher(text);
            int previousHeader = 0;
            while (functions.find()) {
                int end = previousHeader;
                previousHeader = functions.end();
                if (!functions.group(1).equals(method)) continue;
                String prefix = text.substring(end, functions.start());
                var annotations = MNI.matcher(prefix);
                int annotationStart = -1;
                while (annotations.find()) annotationStart = annotations.start();
                if (annotationStart < 0 && !prefix.contains("@JvmStatic")) continue;
                List<PsiElement> target = leaf(source, functions.start(1));
                candidates.addAll(target);
                if (signature.isPresent() && annotationStart >= 0) {
                    var params = NORMAL_PARAMS.matcher(prefix.substring(annotationStart));
                    List<String> types = params.find() ? STRING.matcher(params.group(1)).results()
                            .map(item -> MniFunctionSignature.normalizeType(item.group(1))).toList() : List.of();
                    if (types.equals(signature.get().normalTypes())) exact.addAll(target);
                }
            }
        }
        return List.copyOf(exact.isEmpty() ? candidates : exact);
    }

    private static List<PsiElement> leaf(PsiFile source, int offset) {
        PsiElement element = source.findElementAt(offset);
        return element == null ? List.of() : List.of(element);
    }
}
