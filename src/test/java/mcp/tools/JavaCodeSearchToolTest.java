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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeSearchToolTest {

    @TempDir
    Path srcRoot;

    @Test
    void call_textMode_findsStringMatches() throws IOException, Tool.ToolException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("Demo.java"), "class Demo { String x = \"hello\"; }\n");
        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "hello");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("matches").size());
    }

    @Test
    void call_opcodeMode_resolvesHexConstant() throws IOException, Tool.ToolException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("RecvOpcode.java"),
                "package net.opcodes;\n" +
                "public enum RecvOpcode {\n" +
                "    PLAYER_LOGGEDIN((short) 0x0014),\n" +
                "    USE_ITEM((short) 0x6C);\n" +
                "    private final short code;\n" +
                "    RecvOpcode(short code) { this.code = code; }\n" +
                "}\n");
        Path handlers = Files.createDirectories(srcRoot.resolve("net/handlers"));
        Files.writeString(handlers.resolve("UseItemHandler.java"),
                "import net.opcodes.RecvOpcode;\n" +
                "class UseItemHandler { RecvOpcode op = RecvOpcode.USE_ITEM; }\n");

        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "0x6C");
        args.put("kind", "opcode");
        JsonNode out = tool.call(args);
        assertEquals("USE_ITEM", out.get("opcodeName").asText());
        assertTrue(out.get("matches").size() >= 1);
    }

    @Test
    void call_opcodeMode_unresolvedOpcode_throws() throws IOException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("RecvOpcode.java"),
                "public enum RecvOpcode { FOO((short) 0x01); RecvOpcode(short c){} }\n");
        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "0xEE");
        args.put("kind", "opcode");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
