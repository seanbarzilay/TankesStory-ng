package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFinderToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsByItemIdReference() throws Exception {
        Path npc = Files.createDirectories(scriptsRoot.resolve("npc"));
        Files.writeString(npc.resolve("9201000.js"), "cm.gainItem(1002357, 1);\n");
        Files.writeString(npc.resolve("9201001.js"), "// nothing relevant\n");
        ScriptFinderTool tool = new ScriptFinderTool(scriptsRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "1002357");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("matches").size());
        assertTrue(out.get("matches").get(0).get("file").asText().endsWith("9201000.js"));
        assertEquals(1, out.get("matches").get(0).get("line").asInt());
    }

    @Test
    void call_capLimit() throws Exception {
        Path q = Files.createDirectories(scriptsRoot.resolve("quest"));
        for (int i = 0; i < 200; i++) {
            Files.writeString(q.resolve(i + ".js"), "var KEY = \"banana\";\n");
        }
        ScriptFinderTool tool = new ScriptFinderTool(scriptsRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "banana");
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("matches").size());
    }
}
