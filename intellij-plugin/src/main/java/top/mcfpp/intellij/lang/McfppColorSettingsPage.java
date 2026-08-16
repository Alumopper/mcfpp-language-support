package top.mcfpp.intellij.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public final class McfppColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
            descriptor("Keywords//Control flow", McfppSyntaxHighlighter.CONTROL_KEYWORD),
            descriptor("Keywords//Declarations", McfppSyntaxHighlighter.DECLARATION_KEYWORD),
            descriptor("Keywords//Modifiers", McfppSyntaxHighlighter.MODIFIER_KEYWORD),
            descriptor("Keywords//Built-in types", McfppSyntaxHighlighter.TYPE_KEYWORD),
            descriptor("Identifiers//Function declaration", McfppSyntaxHighlighter.FUNCTION_DECLARATION),
            descriptor("Identifiers//Function call", McfppSyntaxHighlighter.FUNCTION_CALL),
            descriptor("Identifiers//Type", McfppSyntaxHighlighter.TYPE_NAME),
            descriptor("Identifiers//Variable", McfppSyntaxHighlighter.VARIABLE),
            descriptor("Identifiers//Parameter", McfppSyntaxHighlighter.PARAMETER),
            descriptor("Identifiers//Field", McfppSyntaxHighlighter.FIELD),
            descriptor("Identifiers//Enum member", McfppSyntaxHighlighter.ENUM_MEMBER),
            descriptor("Identifiers//Namespace", McfppSyntaxHighlighter.NAMESPACE),
            descriptor("Annotations", McfppSyntaxHighlighter.ANNOTATION),
            descriptor("Target selectors", McfppSyntaxHighlighter.TARGET_SELECTOR),
            descriptor("Minecraft commands", McfppSyntaxHighlighter.COMMAND),
            descriptor("Minecraft commands//Root", McfppSyntaxHighlighter.COMMAND_ROOT),
            descriptor("Minecraft commands//Subcommands", McfppSyntaxHighlighter.COMMAND_KEYWORD),
            descriptor("Minecraft commands//Resources", McfppSyntaxHighlighter.COMMAND_RESOURCE),
            descriptor("Minecraft commands//Properties", McfppSyntaxHighlighter.COMMAND_PROPERTY),
            descriptor("Minecraft commands//Arguments", McfppSyntaxHighlighter.COMMAND_ARGUMENT),
            descriptor("Minecraft commands//Coordinates", McfppSyntaxHighlighter.COMMAND_COORDINATE),
            descriptor("Minecraft commands//Selector fields", McfppSyntaxHighlighter.COMMAND_SELECTOR_KEY),
            descriptor("Minecraft commands//Selector values", McfppSyntaxHighlighter.COMMAND_SELECTOR_VALUE),
            descriptor("Minecraft commands//Macros", McfppSyntaxHighlighter.COMMAND_MACRO),
            descriptor("Strings", McfppSyntaxHighlighter.STRING),
            descriptor("Numbers", McfppSyntaxHighlighter.NUMBER),
            descriptor("Comments//Line and block", McfppSyntaxHighlighter.COMMENT),
            descriptor("Comments//Documentation", McfppSyntaxHighlighter.DOC_COMMENT),
            descriptor("Braces", McfppSyntaxHighlighter.BRACES),
            descriptor("Brackets", McfppSyntaxHighlighter.BRACKETS),
            descriptor("Parentheses", McfppSyntaxHighlighter.PARENTHESES),
            descriptor("Operators", McfppSyntaxHighlighter.OPERATOR)
    };

    private static AttributesDescriptor descriptor(String name, TextAttributesKey key) {
        return new AttributesDescriptor(name, key);
    }

    @Override
    public @Nullable Icon getIcon() {
        return McfppIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new McfppSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                #{ MCFPP documentation }#
                namespace <namespace>demo.example</namespace>

                @From<\"top.mcfpp.mni.minecraft.MinecraftObject\">
                data <type>PlayerInfo</type> {
                    var <field>score</field> as int = 0
                    func <function>update</function>(<parameter>target</parameter> as entity) -> bool {
                        var <variable>active</variable> as bool = true
                        return <field>score</field> >= 0 && active
                    }
                }
                enum <type>Direction</type> { <enumMember>NORTH</enumMember>, SOUTH }
                /<commandRoot>execute</commandRoot> as <selector>@e</selector>[type=<commandResource>minecraft:zombie</commandResource>] run give @s <commandResource>minecraft:stone</commandResource>
                """;
    }

    @Override
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.ofEntries(
                Map.entry("function", McfppSyntaxHighlighter.FUNCTION_DECLARATION),
                Map.entry("parameter", McfppSyntaxHighlighter.PARAMETER),
                Map.entry("type", McfppSyntaxHighlighter.TYPE_NAME),
                Map.entry("variable", McfppSyntaxHighlighter.VARIABLE),
                Map.entry("field", McfppSyntaxHighlighter.FIELD),
                Map.entry("enumMember", McfppSyntaxHighlighter.ENUM_MEMBER),
                Map.entry("namespace", McfppSyntaxHighlighter.NAMESPACE),
                Map.entry("commandRoot", McfppSyntaxHighlighter.COMMAND_ROOT),
                Map.entry("commandResource", McfppSyntaxHighlighter.COMMAND_RESOURCE),
                Map.entry("selector", McfppSyntaxHighlighter.TARGET_SELECTOR)
        );
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "MCFPP";
    }
}
