package mcp.tools;

import client.bot.BotFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.BotConfig;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.BotIdAllocator;
import server.bot.BotManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotSpawnToolTest {

    @Test
    void disabledReturnsToolException() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = false;
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a, b, c, d) -> {});
        AuditLog audit = Mockito.mock(AuditLog.class);
        BotSpawnTool tool = new BotSpawnTool(factory, mgr, audit);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world", 0);
        args.put("channel", 0);
        args.put("map", 100000000);
        args.put("x", 0);
        args.put("y", 0);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertTrue(ex.getMessage().toLowerCase().contains("disabled"));
    }

    @Test
    void schemaIsObjectWithRequiredKeys() {
        BotConfig cfg = new BotConfig();
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a, b, c, d) -> {});
        BotSpawnTool tool = new BotSpawnTool(factory, mgr, Mockito.mock(AuditLog.class));
        JsonNode schema = tool.inputSchema();
        assertEquals("object", schema.get("type").asText());
        var required = schema.get("required");
        assertNotNull(required);
        assertTrue(required.size() >= 5);
    }

    @Test
    void nameIsCosmicBotSpawn() {
        BotConfig cfg = new BotConfig();
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a, b, c, d) -> {});
        BotSpawnTool tool = new BotSpawnTool(factory, mgr, Mockito.mock(AuditLog.class));
        assertEquals("cosmic.bot.spawn", tool.name());
    }
}
