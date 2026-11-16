package top.mcfpp.language;

import java.util.*;
import java.util.regex.Pattern;

/** Editor descriptions of compiler-owned entity interfaces, shared by IDEA and the LSP. */
public final class EntityBuiltins {
    private EntityBuiltins() {}
    /** Build-time resource entrypoint; no compiler declarations are added to user projects. */
    public static void main(String[] args) throws java.io.IOException {
        var output = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, source(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // Kept in sync with compiler EntityTypes by the language-server contract test.
    private static final String IDS = """
        allay area_effect_cloud armadillo armor_stand arrow axolotl bat bee blaze block_display boat
        bogged breeze breeze_wind_charge camel cat cave_spider chest_boat chest_minecart chicken cod cow
        creaking creeper dolphin donkey dragon_fireball drowned egg elder_guardian end_crystal ender_dragon
        ender_pearl enderman endermite evoker evoker_fangs experience_bottle experience_orb eye_of_ender
        falling_block fireball firework_rocket fishing_bobber fox frog furnace_minecart ghast giant
        glow_item_frame glow_squid goat guardian happy_ghast hoglin hopper_minecart horse husk illusioner
        interaction iron_golem item item_display item_frame leash_knot lightning_bolt llama llama_spit
        magma_cube marker minecart mooshroom mule ocelot ominous_item_spawner painting panda parrot
        phantom pig piglin piglin_brute pillager player polar_bear potion pufferfish rabbit ravager
        salmon sheep shulker shulker_bullet silverfish skeleton skeleton_horse slime small_fireball
        sniffer snow_golem snowball spawner_minecart spectral_arrow spider squid stray strider
        tadpole text_display tnt tnt_minecart trader_llama trident tropical_fish turtle vex villager
        vindicator wandering_trader warden wind_charge witch wither wither_skeleton wither_skull wolf
        zoglin zombie zombie_horse zombie_villager splash_potion lingering_potion
        acacia_boat acacia_chest_boat bamboo_raft bamboo_chest_raft birch_boat birch_chest_boat
        cherry_boat cherry_chest_boat dark_oak_boat dark_oak_chest_boat jungle_boat jungle_chest_boat
        mangrove_boat mangrove_chest_boat oak_boat oak_chest_boat pale_oak_boat pale_oak_chest_boat
        spruce_boat spruce_chest_boat
        """;
    private static final Set<String> DISPLAYS = Set.of("ItemDisplay", "BlockDisplay", "TextDisplay");
    public static final List<String> TYPES;
    private static final Map<String, List<Member>> MEMBERS = new LinkedHashMap<>();
    public record Member(String name, String type, String writeType, boolean function, String signature, String documentation) {}

    static {
        List<String> types = new ArrayList<>(List.of("DisplayEntity"));
        for (String id : IDS.strip().split("\\s+")) {
            StringBuilder name = new StringBuilder();
            for (String part : id.split("_")) name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            types.add(name.toString());
        }
        TYPES = List.copyOf(types);
        MEMBERS.put("entity", List.of(
            new Member("exists", "bool", null, true, "func exists(): bool", "Queries whether the target currently exists."),
            new Member("checkType", "bool", null, true, "func checkType<T>(): bool", "Checks the actual entity kind. Missing or mismatched targets return false; DisplayEntity accepts all three display kinds.")));
        MEMBERS.put("DisplayEntity", List.of(
            view("transform", "DisplayTransform", null),
            field("billboard", "DisplayBillboard"), field("brightness", "DisplayBrightness"),
            field("viewRange", "float"), field("shadowRadius", "float"), field("shadowStrength", "float"),
            field("width", "float"), field("height", "float"), field("glowColorOverride", "int"),
            field("interpolationDuration", "int"), field("teleportDuration", "int"),
            new Member("startInterpolation", "NBTRef<int>", "int", false, "startInterpolation: int { set }", "Write-only interpolation trigger; maps to start_interpolation.")));
        MEMBERS.put("ItemDisplay", List.of(field("item", "ItemStackData"), field("itemDisplayMode", "ItemDisplayMode")));
        MEMBERS.put("BlockDisplay", List.of(field("blockState", "BlockState")));
        MEMBERS.put("TextDisplay", List.of(field("text", "text"), field("lineWidth", "int"), field("textOpacity", "int"),
            field("background", "int"), field("defaultBackground", "bool"), field("shadow", "bool"),
            field("seeThrough", "bool"), field("alignment", "TextAlignment")));
        MEMBERS.put("DisplayTransform", List.of(field("x", "float"), field("y", "float"), field("z", "float"),
            view("scale", "DisplayScale", "DisplayVector3"), view("leftRotation", "DisplayLeftRotation", "DisplayQuaternion"),
            view("rightRotation", "DisplayRightRotation", "DisplayQuaternion")));
        MEMBERS.put("DisplayScale", List.of(field("x", "float"), field("y", "float"), field("z", "float")));
        for (String rotation : List.of("DisplayLeftRotation", "DisplayRightRotation"))
            MEMBERS.put(rotation, List.of(field("x", "float"), field("y", "float"), field("z", "float"), field("w", "float")));
        var entityMembers = new ArrayList<>(MEMBERS.get("entity"));
        entityMembers.addAll(ContextBuiltins.entityMembers());
        MEMBERS.put("entity", List.copyOf(entityMembers));
    }

    private static Member field(String name, String type) {
        return new Member(name, "NBTRef<" + type + ">", type, false, name + ": " + type + " { get; set }",
            "A live display property. Assign to write the entity; tryRead reads a value snapshot. Const references may mutate the entity.");
    }
    private static Member view(String name, String type, String writeType) {
        return new Member(name, type, writeType, false, name + ": " + type,
            "A live transformation view. Component assignments preserve the other components.");
    }
    public static String simple(String type) { return type == null ? "" : type.substring(type.lastIndexOf(':') + 1).strip(); }
    public static boolean isEntity(String type) { return "entity".equals(simple(type)) || TYPES.contains(simple(type)); }
    /** The template name of an {@code EntityRef<T>} type, or null for other types. */
    public static String entityRefTemplate(String type) {
        if (type == null) return null;
        var matcher = java.util.regex.Pattern.compile(
            "^(?:[\\w.]*:)?EntityRef\\s*<\\s*([\\w.]+)\\s*>$").matcher(type.strip());
        return matcher.find() ? matcher.group(1).substring(matcher.group(1).lastIndexOf(':') + 1) : null;
    }
    public static boolean isEntityRef(String type) {
        if (entityRefTemplate(type) != null) return true;
        String name = simple(type);
        int angle = name.indexOf('<');
        if (angle >= 0) name = name.substring(0, angle);
        return "EntityRef".equals(name.strip());
    }
    public static String parent(String type) {
        type = simple(type);
        if (DISPLAYS.contains(type)) return "DisplayEntity";
        return TYPES.contains(type) ? "entity" : null;
    }
    public static List<String> owners(String type) {
        if (ContextBuiltins.isType(type)) return ContextBuiltins.owners(type);
        // A template reference keeps the entity interface; user entity members are
        // supplied by the editor's declaration model.
        if (isEntityRef(type)) return List.of("entity");
        List<String> result = new ArrayList<>();
        for (String current = simple(type); current != null; current = parent(current)) result.add(current);
        return result;
    }
    public static List<Member> members(String type) {
        if (ContextBuiltins.isType(type)) return ContextBuiltins.members(type);
        List<Member> result = new ArrayList<>();
        for (String owner : owners(type)) result.addAll(MEMBERS.getOrDefault(owner, List.of()));
        return List.copyOf(result);
    }
    public static Member member(String type, String name) {
        return members(type).stream().filter(it -> it.name().equals(name)).findFirst().orElse(null);
    }
    public static Map<String, List<Member>> declaredMembers() { return Collections.unmodifiableMap(MEMBERS); }
    public static String factoryType(String expression) {
        var typed = Pattern.compile("^(?:[\\w.]+:)?entityByUUID\\s*<\\s*([\\w.:]+)\\s*>\\s*\\(").matcher(expression.strip());
        if (typed.find()) return simple(typed.group(1));
        var call = Pattern.compile("^(?:[\\w.]+:)?([\\w]+)\\s*\\(").matcher(expression.strip());
        if (!call.find()) return null;
        return switch (call.group(1)) {
            case "entityByUUID", "entityByName" -> "entity";
            case "UUID" -> "UUID";
            case "DisplayBrightness", "DisplayVector3", "DisplayQuaternion" -> call.group(1);
            default -> null;
        };
    }
    /** Navigable editor declarations; these describe intrinsics and are never compiler input. */
    public static String source() {
        StringBuilder text = new StringBuilder("namespace mcfpp.lang\n/// Four-int UUID value.\ndata UUID {}\n");
        LinkedHashSet<String> types = new LinkedHashSet<>(List.of("entity"));
        types.addAll(TYPES); types.addAll(MEMBERS.keySet());
        for (String type : types) {
            text.append("/// Compiler-owned entity interface. Declaring its type does not check the runtime kind.\ndata ")
                .append(type.equals("entity") ? "EntityReference" : type);
            if (parent(type) != null) text.append(": ").append(parent(type).equals("entity") ? "EntityReference" : parent(type));
            text.append(" {\n");
            for (Member member : MEMBERS.getOrDefault(type, List.of())) {
                text.append("    /// ").append(member.documentation()).append('\n').append("    ");
                if (member.function()) text.append(member.signature()).append(" {}\n");
                else text.append("var ").append(member.name()).append(": ").append(member.type()).append(";\n");
            }
            text.append("}\n");
        }
        return text.append(ContextBuiltins.source()).append(NbtBuiltins.source()).toString();
    }
}
