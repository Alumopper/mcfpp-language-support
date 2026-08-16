package example.mni;

import top.mcfpp.annotations.MNIFunction;
import top.mcfpp.command.Command;
import top.mcfpp.core.lang.nbt.MCString;
import top.mcfpp.model.function.Function;

/** Java implementation indexed by the Red Hat Java extension for MCFPP MNI navigation. */
public final class ProjectMni {
    private ProjectMni() {
    }

    @MNIFunction(normalParams = {"string"})
    public static void announce(MCString message) {
        Function.addCommand(new Command("tellraw @a").build(message.toCommandPart()));
    }
}
