package top.mcfpp.intellij.lang;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import top.mcfpp.intellij.mni.MniJavaTargetParser;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps declaration names to containing MCFPP files without loading PSI during indexing. */
public final class McfppSymbolIndex extends ScalarIndexExtension<String> {
    public static final ID<String, Void> NAME = ID.create("top.mcfpp.intellij.symbols");
    private static final String TYPE_KEY_PREFIX = "\u0001type:";
    private static final String MEMBER_KEY_PREFIX = "\u0001member:";
    private static final String NAMESPACE_KEY_PREFIX = "\u0001namespace:";
    private static final String MEMBER_OWNER_KEY_PREFIX = "\u0001member-owner:";
    private static final String NAMESPACE_OWNER_KEY_PREFIX = "\u0001namespace-owner:";
    private static final String MNI_METHOD_KEY_PREFIX = "\u0001mni-method:";
    private static final String MNI_CLASS_KEY_PREFIX = "\u0001mni-class:";
    private static final int VERSION = 6;

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            Map<String, Void> result = new LinkedHashMap<>();
            McfppFileModel model = McfppFileModel.parse(inputData.getContentAsText());
            if (model.namespace().isEmpty()) {
                model = model.withNamespace(McfppNamespaceResolver.infer(inputData.getFile()).namespace());
            }
            for (McfppSymbol symbol : model.symbols()) {
                if (symbol.kind().isProjectSymbol()) {
                    result.put(symbol.name(), null);
                    if (symbol.kind().isType()) result.put(typeKey(symbol.name()), null);
                    if (symbol.owner() == null && !model.namespace().isEmpty()) {
                        result.put(namespaceKey(model.namespace(), symbol.name()), null);
                        result.put(namespaceOwnerKey(model.namespace()), null);
                    }
                }
                if (symbol.owner() != null && (symbol.kind() == McfppSymbolKind.FUNCTION ||
                        symbol.kind() == McfppSymbolKind.FIELD || symbol.kind() == McfppSymbolKind.ENUM_MEMBER)) {
                    result.put(memberKey(symbol.owner(), symbol.name()), null);
                    result.put(memberOwnerKey(symbol.owner()), null);
                }
            }
            for (MniJavaTargetParser.Target target : MniJavaTargetParser.parse(inputData.getContentAsText())) {
                String key = target.kind() == MniJavaTargetParser.Kind.JAVA_METHOD
                        ? mniMethodKey(target.qualifiedName())
                        : mniClassKey(target.qualifiedName());
                result.put(key, null);
            }
            return result;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(McfppFileType.INSTANCE);
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    static String typeKey(String name) {
        return TYPE_KEY_PREFIX + name;
    }

    static boolean isTypeKey(String key) {
        return key.startsWith(TYPE_KEY_PREFIX);
    }

    static String memberKey(String owner, String name) {
        return memberPrefix(owner) + name;
    }

    static String memberPrefix(String owner) {
        return MEMBER_KEY_PREFIX + owner + ':';
    }

    static String namespaceKey(String namespace, String name) {
        return namespacePrefix(namespace) + name;
    }

    static String namespacePrefix(String namespace) {
        return NAMESPACE_KEY_PREFIX + namespace + ':';
    }

    static String memberOwnerKey(String owner) {
        return MEMBER_OWNER_KEY_PREFIX + owner;
    }

    static String namespaceOwnerKey(String namespace) {
        return NAMESPACE_OWNER_KEY_PREFIX + namespace;
    }

    public static String mniMethodKey(String qualifiedMethodName) {
        return MNI_METHOD_KEY_PREFIX + qualifiedMethodName;
    }

    public static String mniClassKey(String qualifiedClassName) {
        return MNI_CLASS_KEY_PREFIX + qualifiedClassName;
    }

    static boolean isInternalKey(String key) {
        return key.startsWith("\u0001");
    }
}
