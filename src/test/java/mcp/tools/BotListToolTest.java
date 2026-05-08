package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.BotConfig;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.Bot;
import server.bot.BotManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BotListToolTest {

    private static Bot makeBot(int id, String name, int world) {
        client.Character chr = Mockito.mock(client.Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getWorld()).thenReturn(world);
        return new Bot(chr);
    }

    @Test
    void nameIsCosmicBotList() {
        BotManager mgr = new BotManager(new BotConfig());
        BotListTool tool = new BotListTool(mgr);
        assertEquals("cosmic.bot.list", tool.name());
    }

    @Test
    void schemaIsObjectWithOptionalWorld() {
        BotManager mgr = new BotManager(new BotConfig());
        BotListTool tool = new BotListTool(mgr);
        JsonNode schema = tool.inputSchema();
        assertEquals("object", schema.get("type").asText());
        // 'world' is optional, so 'required' may be absent or empty.
        var required = schema.get("required");
        if (required != null) {
            assertEquals(0, required.size());
        }
        assertNotNull(schema.get("properties").get("world"));
    }

    @Test
    void listsAllBotsAcrossWorldsWhenWorldOmitted() throws Exception {
        BotManager mgr = new BotManager(new BotConfig());
        Bot a = makeBot(-1_000_000, "Bot01", 0);
        Bot b = makeBot(-1_000_001, "Bot02", 0);
        Bot c = makeBot(-1_000_002, "Bot03", 1);
        mgr.register(a);
        mgr.register(b);
        mgr.register(c);

        BotListTool tool = new BotListTool(mgr);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertNotNull(out.get("bots"));
        assertEquals(3, out.get("bots").size());
    }

    @Test
    void filtersByWorldWhenProvided() throws Exception {
        BotManager mgr = new BotManager(new BotConfig());
        mgr.register(makeBot(-1_000_000, "Bot01", 0));
        mgr.register(makeBot(-1_000_001, "Bot02", 0));
        mgr.register(makeBot(-1_000_002, "Bot03", 1));

        BotListTool tool = new BotListTool(mgr);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world", 0);
        JsonNode out = tool.call(args);
        assertEquals(2, out.get("bots").size());
        for (JsonNode entry : out.get("bots")) {
            assertEquals(0, entry.get("world").asInt());
        }
    }

    @Test
    void entryShapeContainsExpectedFields() throws Exception {
        BotManager mgr = new BotManager(new BotConfig());
        Bot bot = makeBot(-1_000_000, "Bot01", 0);
        bot.setMode(Bot.Mode.FOLLOW);
        bot.setTargetCharId(42);
        mgr.register(bot);

        BotListTool tool = new BotListTool(mgr);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        JsonNode entry = out.get("bots").get(0);
        assertEquals(-1_000_000, entry.get("bot_id").asInt());
        assertEquals("Bot01", entry.get("name").asText());
        assertEquals("FOLLOW", entry.get("mode").asText());
        assertEquals(42, entry.get("target_char_id").asInt());
        assertEquals(0, entry.get("world").asInt());
    }

    @Test
    void targetCharIdIsNullWhenUnset() throws Exception {
        BotManager mgr = new BotManager(new BotConfig());
        mgr.register(makeBot(-1_000_000, "Bot01", 0));
        BotListTool tool = new BotListTool(mgr);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        JsonNode entry = out.get("bots").get(0);
        assertTrue(entry.get("target_char_id").isNull());
    }
}
