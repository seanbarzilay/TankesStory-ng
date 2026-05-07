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

class DropsEditSqlToolTest {

    @TempDir
    Path repoRoot;

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.drops.edit_sql", new DropsEditSqlTool(repoRoot).name());
    }

    @Test
    void call_findReplaceOnAllowed_succeeds() throws Exception {
        Path f = repoRoot.resolve("src/main/resources/db/data/152-drop-data.sql");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "INSERT INTO drop_data VALUES (100100, 1002357, 1, 1, 0, 50000);\n");
        DropsEditSqlTool tool = new DropsEditSqlTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/resources/db/data/152-drop-data.sql");
        args.put("old_string", "50000");
        args.put("new_string", "60000");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("INSERT INTO drop_data VALUES (100100, 1002357, 1, 1, 0, 60000);\n",
                Files.readString(f));
    }

    @Test
    void call_unknownDataFile_throws() {
        DropsEditSqlTool tool = new DropsEditSqlTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/resources/db/data/161-admin-data.sql");
        args.put("content", "x");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
