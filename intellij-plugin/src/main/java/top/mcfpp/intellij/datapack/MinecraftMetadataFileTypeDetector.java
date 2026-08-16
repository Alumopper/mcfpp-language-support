package top.mcfpp.intellij.datapack;

import com.intellij.json.JsonFileType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry.FileTypeDetector;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.io.ByteSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Lets pack.mcmeta use IDEA's JSON editor without claiming every .mcmeta file. */
public final class MinecraftMetadataFileTypeDetector implements FileTypeDetector {
    @Override
    public @Nullable FileType detect(
            @NotNull VirtualFile file,
            @NotNull ByteSequence firstBytes,
            @Nullable CharSequence firstCharsIfText
    ) {
        return file.getName().equalsIgnoreCase("pack.mcmeta") ? JsonFileType.INSTANCE : null;
    }

    @Override
    public int getDesiredContentPrefixLength() {
        return 1;
    }
}
