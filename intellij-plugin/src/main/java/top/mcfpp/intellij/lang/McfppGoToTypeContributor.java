package top.mcfpp.intellij.lang;

import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.TreeSet;

/** Exposes MCFPP data types, enums, and aliases through Navigate | Class. */
public final class McfppGoToTypeContributor implements ChooseByNameContributor {
    @Override
    public String @NotNull [] getNames(@NotNull Project project, boolean includeNonProjectItems) {
        if (DumbService.isDumb(project)) return new String[0];
        TreeSet<String> names = new TreeSet<>(McfppExternalLibraryIndex.typeNames(project));
        FileBasedIndex.getInstance().getAllKeys(McfppSymbolIndex.NAME, project).stream()
                .filter(McfppSymbolIndex::isTypeKey)
                .map(key -> key.substring(McfppSymbolIndex.typeKey("").length()))
                .forEach(names::add);
        return names.toArray(String[]::new);
    }

    @Override
    public NavigationItem @NotNull [] getItemsByName(
            @NotNull String name,
            @NotNull String pattern,
            @NotNull Project project,
            boolean includeNonProjectItems
    ) {
        return McfppSymbolResolver.findTypes(project, name, includeNonProjectItems).stream()
                .map(McfppNavigationItem::from)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(item -> item.getPresentation().getLocationString(),
                        Comparator.nullsFirst(String::compareTo)))
                .toArray(NavigationItem[]::new);
    }
}
