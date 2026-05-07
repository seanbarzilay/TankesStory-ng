package mcp.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffBuilderTest {

    @Test
    void diff_identical_returnsEmpty() {
        String d = DiffBuilder.unified("foo.txt", "abc\n", "abc\n");
        assertEquals("", d);
    }

    @Test
    void diff_singleLineChange_returnsUnifiedDiff() {
        String d = DiffBuilder.unified("foo.txt", "hello\n", "world\n");
        assertTrue(d.contains("--- foo.txt"));
        assertTrue(d.contains("+++ foo.txt"));
        assertTrue(d.contains("-hello"));
        assertTrue(d.contains("+world"));
    }

    @Test
    void diff_creation_showsAllAsAdditions() {
        String d = DiffBuilder.unified("new.js", null, "var x = 1;\n");
        assertTrue(d.contains("--- /dev/null"));
        assertTrue(d.contains("+++ new.js"));
        assertTrue(d.contains("+var x = 1;"));
    }
}
