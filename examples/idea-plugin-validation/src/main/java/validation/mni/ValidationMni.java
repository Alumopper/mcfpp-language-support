package validation.mni;

import top.mcfpp.annotations.MNIFunction;
import top.mcfpp.core.lang.MCInt;
import top.mcfpp.core.lang.nbt.MCString;

/** Real MCFPP API types, used for Java PSI completion and overload-aware MNI navigation. */
public final class ValidationMni {
    private ValidationMni() {
    }

    @MNIFunction(normalParams = {"string"})
    public static void announce(MCString message) {
        // Editor validation fixture; no generated Minecraft commands are required.
    }

    @MNIFunction(normalParams = {"int"})
    public static void convert(MCInt value) {
    }

    @MNIFunction(normalParams = {"string"})
    public static void convert(MCString value) {
    }
}
