package top.mcfpp.language;

import java.util.Map;

/** Read-only properties shared by all MCFPP enum values. */
public final class EnumProperties {
    private EnumProperties() {}
    public static final Map<String, String> TYPES = Map.of(
            "value", "int", "identifier", "string", "data", "nbt");
}
