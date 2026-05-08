package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.BotConfig;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.Bot;
import server.bot.BotManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BotDriveToolTest {

    private static class FakeAuditLog extends AuditLog {
        final List<AuditEntry> seen = new ArrayList<>();
        FakeAuditLog() { super(() -> { throw new RuntimeException("not used"); }); }
        @Override public long insert(AuditEntry e) { seen.add(e); return seen.size(); }
    }

    private static Bot makeBot(int id, String name, int world) {
        client.Character chr = Mockito.mock(client.Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getWorld()).thenReturn(world);
        return new Bot(chr);
    }

    @Test
    void nameIsCosmicBotDrive() {
        BotManager mgr = new BotManager(new BotConfig());
        BotDriveTool tool = new BotDriveTool(mgr, new FakeAuditLog());
        assertEquals("cosmic.bot.drive", tool.name());
    }

    @Test
    void schemaIsObjectWithRequiredKeys() {
        BotManager mgr = new BotManager(new BotConfig());
        BotDriveTool tool = new BotDriveTool(mgr, new FakeAuditLog());
        JsonNode schema = tool.inputSchema();
        assertEquals("object", schema.get("type").asText());
        var required = schema.get("required");
        assertNotNull(required);
        assertEquals(2, required.size());
    }

    @Test
    void followModeSetsModeAndTargetAndAudits() throws Exception {
        BotConfig cfg = new BotConfig();
        BotManager mgr = new BotManager(cfg);
        Bot bot = makeBot(-1_000_000, "Bot01", 0);
        mgr.register(bot);

        FakeAuditLog audit = new FakeAuditLog();
        BotDriveTool tool = new BotDriveTool(mgr, audit);

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -1_000_000);
        args.put("mode", "FOLLOW");
        args.put("target_char_id", 7);

        JsonNode out = tool.call(args);
        assertEquals(-1_000_000, out.get("bot_id").asInt());
        assertEquals("FOLLOW", out.get("mode").asText());
        assertTrue(out.get("ok").asBoolean());

        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(Integer.valueOf(7), bot.targetCharId());

        assertEquals(1, audit.seen.size());
        assertEquals("cosmic.bot.drive", audit.seen.get(0).tool());
        assertNotNull(audit.seen.get(0).beforeJson());
    }

    @Test
    void grindModeAcceptsMobFilter() throws Exception {
        BotConfig cfg = new BotConfig();
        BotManager mgr = new BotManager(cfg);
        Bot bot = makeBot(-1_000_001, "Bot02", 0);
        mgr.register(bot);

        FakeAuditLog audit = new FakeAuditLog();
        BotDriveTool tool = new BotDriveTool(mgr, audit);

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -1_000_001);
        args.put("mode", "GRIND");
        args.put("mob_filter", "Snail");

        JsonNode out = tool.call(args);
        assertEquals("GRIND", out.get("mode").asText());
        assertEquals(Bot.Mode.GRIND, bot.mode());
        assertEquals("Snail", bot.mobFilter());
    }

    @Test
    void unknownBotIdThrowsInvalidParams() {
        BotManager mgr = new BotManager(new BotConfig());
        BotDriveTool tool = new BotDriveTool(mgr, new FakeAuditLog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -42);
        args.put("mode", "IDLE");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void unknownModeThrowsInvalidParams() {
        BotConfig cfg = new BotConfig();
        BotManager mgr = new BotManager(cfg);
        Bot bot = makeBot(-1_000_002, "Bot03", 0);
        mgr.register(bot);

        BotDriveTool tool = new BotDriveTool(mgr, new FakeAuditLog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -1_000_002);
        args.put("mode", "DANCE");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
