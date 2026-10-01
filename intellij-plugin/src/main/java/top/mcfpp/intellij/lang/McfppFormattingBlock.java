package top.mcfpp.intellij.lang;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.Wrap;
import com.intellij.formatting.WrapType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class McfppFormattingBlock extends AbstractBlock {
    private static final int ANGLE_SCAN_LIMIT = 512;
    private static final Set<String> PREFIX_OPERATORS = Set.of("!", "~", "++", "--");
    private static final Set<String> POSTFIX_OPERATORS = Set.of("++", "--");
    private static final Set<String> ASSIGNMENT_OPERATORS = Set.of("=", "+=", "-=", "*=", "/=", "%=");
    private static final Set<String> EQUALITY_OPERATORS = Set.of("==", "!=", "~=");
    private static final Set<String> RELATIONAL_OPERATORS = Set.of("<", ">", "<=", ">=");
    private static final Set<String> MULTIPLICATIVE_OPERATORS = Set.of("*", "/", "%");

    private final CodeStyleSettings settings;
    private final Indent indent;
    private final boolean root;

    private McfppFormattingBlock(
            ASTNode node,
            CodeStyleSettings settings,
            Indent indent,
            boolean root
    ) {
        super(node, Wrap.createWrap(WrapType.NONE, false), Alignment.createAlignment());
        this.settings = settings;
        this.indent = indent;
        this.root = root;
    }

    static McfppFormattingBlock root(ASTNode node, CodeStyleSettings settings) {
        return new McfppFormattingBlock(node, settings, Indent.getNoneIndent(), true);
    }

    @Override
    protected @NotNull List<Block> buildChildren() {
        if (!root) return EMPTY;
        List<Block> children = new ArrayList<>();
        Depth depth = new Depth();
        int indentSize = Math.max(1, settings.getIndentSize(McfppFileType.INSTANCE));
        CommonCodeStyleSettings.IndentOptions options = settings.getIndentOptions(McfppFileType.INSTANCE);
        int continuationIndent = options == null ? indentSize * 2 : Math.max(1, options.CONTINUATION_INDENT_SIZE);
        for (ASTNode child = myNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            appendLeafBlocks(child, children, depth, indentSize, continuationIndent);
        }
        return children;
    }

    private void appendLeafBlocks(
            ASTNode node,
            List<Block> result,
            Depth depth,
            int indentSize,
            int continuationIndent
    ) {
        ASTNode firstChild = node.getFirstChildNode();
        if (firstChild != null) {
            for (ASTNode child = firstChild; child != null; child = child.getTreeNext()) {
                appendLeafBlocks(child, result, depth, indentSize, continuationIndent);
            }
            return;
        }
        IElementType type = node.getElementType();
        if (type == TokenType.WHITE_SPACE) return;
        if (type == McfppTokenTypes.RIGHT_BRACE) depth.braces = Math.max(0, depth.braces - 1);
        if (type == McfppTokenTypes.RIGHT_PARENTHESIS || type == McfppTokenTypes.RIGHT_BRACKET) {
            depth.continuations = Math.max(0, depth.continuations - 1);
        }
        result.add(new McfppFormattingBlock(
                node,
                settings,
                Indent.getSpaceIndent(depth.braces * indentSize + depth.continuations * continuationIndent),
                false
        ));
        if (type == McfppTokenTypes.LEFT_BRACE) depth.braces++;
        if (type == McfppTokenTypes.LEFT_PARENTHESIS || type == McfppTokenTypes.LEFT_BRACKET) {
            depth.continuations++;
        }
    }

    @Override
    public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        if (!(child1 instanceof McfppFormattingBlock left) || !(child2 instanceof McfppFormattingBlock right)) {
            return null;
        }
        IElementType leftType = left.myNode.getElementType();
        IElementType rightType = right.myNode.getElementType();
        String leftText = left.myNode.getText();
        String rightText = right.myNode.getText();
        CommonCodeStyleSettings common = settings.getCommonSettings(McfppLanguage.INSTANCE);

        if (leftType == McfppTokenTypes.COMMENT || leftType == McfppTokenTypes.DOC_COMMENT ||
                leftType == McfppTokenTypes.VERSION_DIRECTIVE || rightType == McfppTokenTypes.VERSION_DIRECTIVE ||
                leftType == McfppTokenTypes.COMMAND || rightType == McfppTokenTypes.COMMAND) {
            return null;
        }
        if ((rightText.equals("<") && isStructuralAngle(right.myNode)) ||
                (leftText.equals("<") && isStructuralAngle(left.myNode)) ||
                (rightText.equals(">") && isStructuralAngle(right.myNode))) {
            return spacing(0);
        }
        if (leftText.equals(">") && isStructuralAngle(left.myNode)) {
            if (rightType == McfppTokenTypes.LEFT_BRACE) return spacing(common.SPACE_BEFORE_METHOD_LBRACE);
            return spacing(isWord(rightType) ? 1 : 0);
        }
        if (rightType == McfppTokenTypes.COMMA) return spacing(common.SPACE_BEFORE_COMMA);
        if (rightType == McfppTokenTypes.SEMICOLON || rightType == McfppTokenTypes.DOT ||
                rightType == McfppTokenTypes.RIGHT_PARENTHESIS ||
                rightType == McfppTokenTypes.RIGHT_BRACKET) {
            return spacing(0);
        }
        if (leftType == McfppTokenTypes.DOT || leftType == McfppTokenTypes.LEFT_PARENTHESIS ||
                leftType == McfppTokenTypes.LEFT_BRACKET) {
            return spacing(0);
        }
        if (leftType == McfppTokenTypes.COMMA) return spacing(common.SPACE_AFTER_COMMA);
        if (leftType == McfppTokenTypes.COLON) {
            return spacing(isInheritanceColon(left.myNode) || isForeachColon(left.myNode));
        }
        if (leftType == McfppTokenTypes.SEMICOLON || leftType == McfppTokenTypes.LEFT_BRACE) {
            return spacing(1);
        }
        if (leftType == McfppTokenTypes.RIGHT_BRACE &&
                (rightText.equals("else") || rightText.equals("while") || rightText.equals("store"))) {
            return spacing(1);
        }
        if (rightType == McfppTokenTypes.LEFT_BRACE) return spacing(common.SPACE_BEFORE_METHOD_LBRACE);
        if (rightType == McfppTokenTypes.COLON) return spacing(isForeachColon(right.myNode));
        if (rightType == McfppTokenTypes.LEFT_PARENTHESIS) {
            return spacing(isWord(leftType) && leftType != McfppTokenTypes.IDENTIFIER ? 1 : 0);
        }
        if (leftType == McfppTokenTypes.OPERATOR && PREFIX_OPERATORS.contains(leftText)) {
            return spacing(common.SPACE_AROUND_UNARY_OPERATOR);
        }
        if (rightType == McfppTokenTypes.OPERATOR && POSTFIX_OPERATORS.contains(rightText)) {
            return spacing(common.SPACE_AROUND_UNARY_OPERATOR);
        }
        if (leftType == McfppTokenTypes.OPERATOR || rightType == McfppTokenTypes.OPERATOR) {
            return spacing(spaceAroundOperator(leftType == McfppTokenTypes.OPERATOR ? leftText : rightText, common));
        }
        return spacing(isWord(leftType) && isWord(rightType) ? 1 : 0);
    }

    private static boolean isWord(IElementType type) {
        return type == McfppTokenTypes.IDENTIFIER || type == McfppTokenTypes.STRING ||
                type == McfppTokenTypes.NUMBER || type == McfppTokenTypes.KEYWORD ||
                type == McfppTokenTypes.CONTROL_KEYWORD || type == McfppTokenTypes.DECLARATION_KEYWORD ||
                type == McfppTokenTypes.MODIFIER_KEYWORD || type == McfppTokenTypes.TYPE_KEYWORD ||
                type == McfppTokenTypes.BOOLEAN_LITERAL || type == McfppTokenTypes.NULL_LITERAL ||
                type == McfppTokenTypes.ANNOTATION || type == McfppTokenTypes.TARGET_SELECTOR;
    }

    private static Spacing spacing(int spaces) {
        return Spacing.createSpacing(spaces, spaces, 0, true, 2);
    }

    private static Spacing spacing(boolean enabled) {
        return spacing(enabled ? 1 : 0);
    }

    private static boolean spaceAroundOperator(String operator, CommonCodeStyleSettings common) {
        if (ASSIGNMENT_OPERATORS.contains(operator)) {
            return common.SPACE_AROUND_ASSIGNMENT_OPERATORS;
        }
        if (operator.equals("&&") || operator.equals("||")) return common.SPACE_AROUND_LOGICAL_OPERATORS;
        if (EQUALITY_OPERATORS.contains(operator)) return common.SPACE_AROUND_EQUALITY_OPERATORS;
        if (RELATIONAL_OPERATORS.contains(operator)) return common.SPACE_AROUND_RELATIONAL_OPERATORS;
        if (operator.equals("+") || operator.equals("-")) return common.SPACE_AROUND_ADDITIVE_OPERATORS;
        if (MULTIPLICATIVE_OPERATORS.contains(operator)) return common.SPACE_AROUND_MULTIPLICATIVE_OPERATORS;
        if (operator.equals("->") || operator.equals("=>")) return common.SPACE_AROUND_LAMBDA_ARROW;
        if (operator.equals("::")) return common.SPACE_AROUND_METHOD_REF_DBL_COLON;
        return true;
    }

    private static boolean isInheritanceColon(ASTNode colon) {
        for (ASTNode previous = previousLeaf(colon); previous != null; previous = previousLeaf(previous)) {
            if (previous.getElementType() == TokenType.WHITE_SPACE && containsNewline(previous.getText())) return false;
            if (previous.getElementType() == McfppTokenTypes.SEMICOLON ||
                    previous.getElementType() == McfppTokenTypes.LEFT_BRACE ||
                    previous.getElementType() == McfppTokenTypes.RIGHT_BRACE) return false;
            String text = previous.getText();
            if (text.equals("data") || text.equals("object")) return true;
            if (text.equals("import") || text.equals("namespace")) return false;
        }
        return false;
    }

    private static boolean isForeachColon(ASTNode colon) {
        int parentheses = 0;
        for (ASTNode previous = previousSignificantLeaf(colon);
             previous != null;
             previous = previousSignificantLeaf(previous)) {
            IElementType type = previous.getElementType();
            if (type == McfppTokenTypes.RIGHT_PARENTHESIS) {
                parentheses++;
            } else if (type == McfppTokenTypes.LEFT_PARENTHESIS) {
                if (parentheses == 0) {
                    ASTNode keyword = previousSignificantLeaf(previous);
                    return keyword != null && "for".equals(keyword.getText());
                }
                parentheses--;
            }
            if (parentheses == 0 && (type == McfppTokenTypes.SEMICOLON ||
                    type == McfppTokenTypes.LEFT_BRACE || type == McfppTokenTypes.RIGHT_BRACE)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isStructuralAngle(ASTNode angle) {
        if (angle.getElementType() != McfppTokenTypes.OPERATOR) return false;
        if (angle.getText().equals(">")) {
            ASTNode opening = matchingOpeningAngle(angle);
            return opening != null && isStructuralAngle(opening);
        }
        if (!angle.getText().equals("<")) return false;

        ASTNode previous = previousSignificantLeaf(angle);
        if (previous == null) return false;
        IElementType previousType = previous.getElementType();
        if (previousType == McfppTokenTypes.ANNOTATION || previousType == McfppTokenTypes.TYPE_KEYWORD) return true;
        if (previousType != McfppTokenTypes.IDENTIFIER) return false;

        ASTNode beforeIdentifier = previousSignificantLeaf(previous);
        if (beforeIdentifier != null) {
            String beforeText = beforeIdentifier.getText();
            if (beforeText.equals("func") || beforeText.equals("data") || beforeText.equals("object") ||
                    beforeText.equals("as") || beforeText.equals("->") || beforeText.equals(":")) {
                return true;
            }
            if (beforeText.equals("<") && isStructuralAngle(beforeIdentifier)) return true;
        }

        ASTNode closing = matchingClosingAngle(angle);
        ASTNode afterClosing = closing == null ? null : nextSignificantLeaf(closing);
        return afterClosing != null && afterClosing.getElementType() == McfppTokenTypes.LEFT_PARENTHESIS;
    }

    private static ASTNode matchingOpeningAngle(ASTNode closing) {
        int depth = 0;
        int scanned = 0;
        for (ASTNode leaf = closing; leaf != null && scanned++ < ANGLE_SCAN_LIMIT; leaf = previousLeaf(leaf)) {
            if (leaf.getElementType() != McfppTokenTypes.OPERATOR) continue;
            if (leaf.getText().equals(">")) depth++;
            else if (leaf.getText().equals("<") && --depth == 0) return leaf;
        }
        return null;
    }

    private static ASTNode matchingClosingAngle(ASTNode opening) {
        int depth = 0;
        int scanned = 0;
        for (ASTNode leaf = opening; leaf != null && scanned++ < ANGLE_SCAN_LIMIT; leaf = nextLeaf(leaf)) {
            if (leaf.getElementType() != McfppTokenTypes.OPERATOR) continue;
            if (leaf.getText().equals("<")) depth++;
            else if (leaf.getText().equals(">") && --depth == 0) return leaf;
        }
        return null;
    }

    private static ASTNode previousSignificantLeaf(ASTNode node) {
        ASTNode result = previousLeaf(node);
        while (result != null && result.getElementType() == TokenType.WHITE_SPACE) result = previousLeaf(result);
        return result;
    }

    private static ASTNode nextSignificantLeaf(ASTNode node) {
        ASTNode result = nextLeaf(node);
        while (result != null && result.getElementType() == TokenType.WHITE_SPACE) result = nextLeaf(result);
        return result;
    }

    private static ASTNode previousLeaf(ASTNode node) {
        ASTNode current = node;
        while (current != null) {
            ASTNode previous = current.getTreePrev();
            if (previous != null) {
                while (previous.getLastChildNode() != null) previous = previous.getLastChildNode();
                return previous;
            }
            current = current.getTreeParent();
        }
        return null;
    }

    private static ASTNode nextLeaf(ASTNode node) {
        ASTNode current = node;
        while (current != null) {
            ASTNode next = current.getTreeNext();
            if (next != null) {
                while (next.getFirstChildNode() != null) next = next.getFirstChildNode();
                return next;
            }
            current = current.getTreeParent();
        }
        return null;
    }

    private static boolean containsNewline(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        if (!root) return super.getChildAttributes(newChildIndex);
        int braces = 0;
        int continuations = 0;
        List<Block> children = getSubBlocks();
        for (int index = 0; index < Math.min(newChildIndex, children.size()); index++) {
            IElementType type = ((McfppFormattingBlock) children.get(index)).myNode.getElementType();
            if (type == McfppTokenTypes.LEFT_BRACE) braces++;
            if (type == McfppTokenTypes.RIGHT_BRACE) braces = Math.max(0, braces - 1);
            if (type == McfppTokenTypes.LEFT_PARENTHESIS || type == McfppTokenTypes.LEFT_BRACKET) continuations++;
            if (type == McfppTokenTypes.RIGHT_PARENTHESIS || type == McfppTokenTypes.RIGHT_BRACKET) {
                continuations = Math.max(0, continuations - 1);
            }
        }
        int indentSize = Math.max(1, settings.getIndentSize(McfppFileType.INSTANCE));
        CommonCodeStyleSettings.IndentOptions options = settings.getIndentOptions(McfppFileType.INSTANCE);
        int continuationIndent = options == null ? indentSize * 2 : Math.max(1, options.CONTINUATION_INDENT_SIZE);
        return new ChildAttributes(Indent.getSpaceIndent(
                braces * indentSize + continuations * continuationIndent
        ), null);
    }

    @Override
    public @Nullable Indent getIndent() {
        return indent;
    }

    @Override
    public boolean isLeaf() {
        return !root;
    }

    private static final class Depth {
        private int braces;
        private int continuations;
    }
}
