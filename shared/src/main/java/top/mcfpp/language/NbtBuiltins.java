package top.mcfpp.language;

import java.util.List;

/** Editor signatures for NBT operations implemented by the compiler's native provider. */
public final class NbtBuiltins {
    private NbtBuiltins() {}

    public static final String SOURCE_OWNER = "NBTValue";
    public static final List<EntityBuiltins.Member> MEMBERS = List.of(new EntityBuiltins.Member(
            "merge", "nbt", null, true, "func merge(d: nbt): nbt",
            "Returns an independent compound copy. Recursively merges d over the receiver; " +
                    "values from d override existing values. Requires one compound NBT argument."));

    public static List<EntityBuiltins.Member> members(String type) {
        return "nbt".equals(EntityBuiltins.simple(type)) ? MEMBERS : List.of();
    }

    public static EntityBuiltins.Member member(String type, String name) {
        return members(type).stream().filter(it -> it.name().equals(name)).findFirst().orElse(null);
    }

    public static String source() {
        var text = new StringBuilder("\n/// Compiler-owned NBT operations.\ndata " + SOURCE_OWNER + " {\n");
        for (var member : MEMBERS)
            text.append("/// ").append(member.documentation()).append('\n').append(member.signature()).append(" {}\n");
        return text.append("}\n").toString();
    }
}
