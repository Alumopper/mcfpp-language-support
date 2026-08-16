package top.mcfpp.intellij.lang;

import com.intellij.lang.BracePair;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McfppBraceMatcherTest {
    @Test
    void registersAllThreeBracePairs() {
        BracePair[] pairs = new McfppBraceMatcher().getPairs();

        assertEquals(3, pairs.length);
        assertTrue(Arrays.stream(pairs).anyMatch(pair ->
                pair.getLeftBraceType() == McfppTokenTypes.LEFT_BRACE &&
                        pair.getRightBraceType() == McfppTokenTypes.RIGHT_BRACE));
        assertTrue(Arrays.stream(pairs).anyMatch(pair ->
                pair.getLeftBraceType() == McfppTokenTypes.LEFT_BRACKET &&
                        pair.getRightBraceType() == McfppTokenTypes.RIGHT_BRACKET));
        assertTrue(Arrays.stream(pairs).anyMatch(pair ->
                pair.getLeftBraceType() == McfppTokenTypes.LEFT_PARENTHESIS &&
                        pair.getRightBraceType() == McfppTokenTypes.RIGHT_PARENTHESIS));
    }
}
