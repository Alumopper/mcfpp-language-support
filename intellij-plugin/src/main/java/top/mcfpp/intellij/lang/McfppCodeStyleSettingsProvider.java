package top.mcfpp.intellij.lang;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;

public final class McfppCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {
    @Override
    protected void customizeDefaults(
            @NotNull CommonCodeStyleSettings commonSettings,
            @NotNull CommonCodeStyleSettings.IndentOptions indentOptions
    ) {
        commonSettings.SPACE_AROUND_ASSIGNMENT_OPERATORS = true;
        commonSettings.SPACE_AROUND_LOGICAL_OPERATORS = true;
        commonSettings.SPACE_AROUND_EQUALITY_OPERATORS = true;
        commonSettings.SPACE_AROUND_RELATIONAL_OPERATORS = true;
        commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS = true;
        commonSettings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = true;
        commonSettings.SPACE_AROUND_LAMBDA_ARROW = true;
        commonSettings.SPACE_AFTER_COMMA = true;
        commonSettings.SPACE_BEFORE_METHOD_LBRACE = true;
        indentOptions.INDENT_SIZE = 4;
        indentOptions.CONTINUATION_INDENT_SIZE = 8;
        indentOptions.TAB_SIZE = 4;
    }

    @Override
    public @NotNull Language getLanguage() {
        return McfppLanguage.INSTANCE;
    }

    @Override
    public void customizeSettings(
            @NotNull CodeStyleSettingsCustomizable consumer,
            @NotNull SettingsType settingsType
    ) {
        if (settingsType == SettingsType.SPACING_SETTINGS) {
            consumer.showStandardOptions(
                    "SPACE_AROUND_ASSIGNMENT_OPERATORS",
                    "SPACE_AROUND_LOGICAL_OPERATORS",
                    "SPACE_AROUND_EQUALITY_OPERATORS",
                    "SPACE_AROUND_RELATIONAL_OPERATORS",
                    "SPACE_AROUND_ADDITIVE_OPERATORS",
                    "SPACE_AROUND_MULTIPLICATIVE_OPERATORS",
                    "SPACE_AROUND_UNARY_OPERATOR",
                    "SPACE_AROUND_LAMBDA_ARROW",
                    "SPACE_AROUND_METHOD_REF_DBL_COLON",
                    "SPACE_AFTER_COMMA",
                    "SPACE_BEFORE_COMMA",
                    "SPACE_BEFORE_METHOD_LBRACE"
            );
        }
    }

    @Override
    public @NotNull String getCodeSample(@NotNull SettingsType settingsType) {
        return new McfppIndentOptionsProvider().getPreviewText();
    }
}
