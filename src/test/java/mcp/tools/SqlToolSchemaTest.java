package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.WriteSqlSafety;
import mcp.data.SqlSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-only tests that don't depend on a running MySQL container.
 *
 * Regression guard: OpenAI's strict function-calling validator rejects
 * `{"type": "array"}` properties that don't carry an `items` schema, so
 * any opencode/MCP-aware client forwarding our tool list to OpenAI 400s.
 * The two SQL tools (cosmic.db.select, cosmic.db.execute) declare their
 * `params` bind array — make sure both expose a valid `items` schema.
 */
class SqlToolSchemaTest {

    @Test
    void selectTool_paramsHasItemsForOpenaiStrict() {
        SqlSelectTool tool = new SqlSelectTool(() -> null, new SqlSafety(List.of()), 5, 1000);
        assertParamsItemsAnyOf(tool.inputSchema());
    }

    @Test
    void executeTool_paramsHasItemsForOpenaiStrict() {
        DbExecuteTool tool = new DbExecuteTool(() -> null,
                new WriteSqlSafety(new SqlSafety(List.of()), List.of()), null, 5);
        assertParamsItemsAnyOf(tool.inputSchema());
    }

    private static void assertParamsItemsAnyOf(ObjectNode schema) {
        JsonNode params = schema.get("properties").get("params");
        assertEquals("array", params.get("type").asText());
        assertTrue(params.has("items"), "params schema must have an `items` field");
        JsonNode items = params.get("items");
        assertTrue(items.has("anyOf"), "items must use anyOf for the heterogeneous bind values");
        assertEquals(4, items.get("anyOf").size(), "expect string|number|boolean|null branches");
    }
}
