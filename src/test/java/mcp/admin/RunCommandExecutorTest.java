package mcp.admin;

import client.Client;
import client.command.Command;
import client.command.commands.gm0.HelpCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandExecutorTest {

    private static class CapturingCommand extends HelpCommand {
        String[] received;
        boolean shouldThrow;
        @Override public void execute(Client client, String[] params) {
            received = params;
            if (shouldThrow) throw new RuntimeException("boom");
        }
    }

    private CommandCatalog catalogWith(String name, Command cmd) {
        Map<String, Command> m = new LinkedHashMap<>();
        m.put(name, cmd);
        return new CommandCatalog(m);
    }

    @Test
    void parse_unknownCommand_throws() {
        CommandCatalog cat = new CommandCatalog(new LinkedHashMap<>());
        RunCommandExecutor exec = new RunCommandExecutor(cat, name -> false);
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@nope foo bar", 6));
        assertTrue(ex.getMessage().contains("unknown command"));
    }

    @Test
    void parse_notSupported_throws() {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("warpme", cmd), name -> "warpme".equals(name));
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@warpme", 6));
        assertTrue(ex.getMessage().contains("requires in-game context"));
    }

    @Test
    void run_validCommand_dispatchesWithParams() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("kick", cmd), name -> false);
        RunCommandExecutor.Result r = exec.run("@kick alice", 6);
        assertEquals(true, r.ok());
        assertEquals(1, cmd.received.length);
        assertEquals("alice", cmd.received[0]);
    }

    @Test
    void run_commandThrows_returnsNotOkWithMessage() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        cmd.shouldThrow = true;
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("foo", cmd), name -> false);
        RunCommandExecutor.Result r = exec.run("@foo", 6);
        assertEquals(false, r.ok());
        assertTrue(r.output().contains("boom"));
    }

    @Test
    void run_emptyCommand_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false);
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("   ", 6));
    }

    @Test
    void run_missingAtSign_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false);
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("kick alice", 6));
    }
}
