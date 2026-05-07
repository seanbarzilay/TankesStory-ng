package mcp.tools;

import client.command.commands.gm0.HelpCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.CommandCatalog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsListToolTest {

    private static class FakeHelp extends HelpCommand {
        public FakeHelp(int rank, String desc) { setRank(rank); setDescription(desc); }
    }

    private CommandCatalog catalog() {
        Map<String, client.command.Command> m = new LinkedHashMap<>();
        m.put("help", new FakeHelp(0, "show help"));
        m.put("kick", new FakeHelp(3, "kick a player"));
        m.put("ban", new FakeHelp(5, "ban a player"));
        return new CommandCatalog(m);
    }

    @Test
    void call_returnsAllCommands() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(3, out.get("commands").size());
    }

    @Test
    void call_filterSubstring() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("filter_substring", "k");
        JsonNode out = tool.call(args);
        assertTrue(out.get("commands").size() >= 1);
    }

    @Test
    void call_filterGmLevel() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("gm_level", 5);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("commands").size());
        assertEquals("ban", out.get("commands").get(0).get("name").asText());
    }
}
