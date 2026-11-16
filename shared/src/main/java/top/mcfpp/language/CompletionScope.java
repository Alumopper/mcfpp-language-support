package top.mcfpp.language;

import java.util.Set;
import java.util.regex.Pattern;

/** Context checks shared by the native IDEA and language-server completion providers. */
public final class CompletionScope {
    public static final Set<String> VALUE_KEYWORDS = Set.of("this", "super", "true", "false", "null");
    private static final Pattern TYPE_ARGUMENT = Pattern.compile(
        "\\b(?:entityByUUID|checkType|summon|list|map|dict|ScoreRef|NBTRef|EntityRef)\\s*<[^<>]*$");
    private static final Pattern CAST_TYPE = Pattern.compile("\\bas\\s+$");
    private static final Pattern VALUE = Pattern.compile("(?:\\breturn|[=,(\\[+*/%!&|?^-])\\s*$");

    private CompletionScope() {}

    /** The input ends immediately before the identifier being completed. */
    public static boolean isTypeArgumentOrCast(String before) {
        return TYPE_ARGUMENT.matcher(before).find() || CAST_TYPE.matcher(before).find();
    }

    public static boolean isValuePosition(String before) {
        return VALUE.matcher(before).find();
    }
}
