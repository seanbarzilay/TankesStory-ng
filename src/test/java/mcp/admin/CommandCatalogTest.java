package mcp.admin;

import client.command.Command;
import client.command.commands.gm0.HelpCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCatalogTest {

    private static class FakeHelp extends HelpCommand {
        public FakeHelp() { setRank(0); setDescription("show help"); }
    }

    @Test
    void snapshot_returnsAllCommands() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        reg.put("commands", help);
        CommandCatalog cat = new CommandCatalog(reg);
        List<CommandCatalog.Entry> entries = cat.list(null, null);
        assertEquals(2, entries.size());
        assertEquals("commands", entries.get(0).name()); // sorted alphabetically
        assertEquals(0, entries.get(0).gmLevel());
    }

    @Test
    void list_filterBySubstring_matches() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        reg.put("kick", help);
        reg.put("kickout", help);
        CommandCatalog cat = new CommandCatalog(reg);
        List<CommandCatalog.Entry> hits = cat.list("kick", null);
        assertEquals(2, hits.size());
    }

    @Test
    void list_filterByGmLevel_matches() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp lvl0 = new FakeHelp(); lvl0.setRank(0);
        FakeHelp lvl3 = new FakeHelp() {{ setRank(3); }};
        reg.put("a", lvl0);
        reg.put("b", lvl3);
        CommandCatalog cat = new CommandCatalog(reg);
        assertEquals(1, cat.list(null, 0).size());
        assertEquals(1, cat.list(null, 3).size());
    }

    @Test
    void find_returnsCommandByName() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        CommandCatalog cat = new CommandCatalog(reg);
        assertNotNull(cat.find("help"));
        assertTrue(cat.find("nope").isEmpty());
    }
}
