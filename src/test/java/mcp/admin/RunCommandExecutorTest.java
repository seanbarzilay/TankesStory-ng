package mcp.admin;

import client.Character;
import client.Client;
import client.command.Command;
import client.command.commands.gm0.HelpCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunCommandExecutorTest {

    private static class CapturingCommand extends HelpCommand {
        Client receivedClient;
        String[] received;
        boolean shouldThrow;
        @Override public void execute(Client client, String[] params) {
            receivedClient = client;
            received = params;
            if (shouldThrow) throw new RuntimeException("boom");
        }
    }

    private CommandCatalog catalogWith(String name, Command cmd) {
        Map<String, Command> m = new LinkedHashMap<>();
        m.put(name, cmd);
        return new CommandCatalog(m);
    }

    private RunCommandExecutor.CharacterResolver always(Character chr) {
        return (asCharacter, minGm) -> Optional.of(chr);
    }

    private RunCommandExecutor.CharacterResolver never() {
        return (asCharacter, minGm) -> Optional.empty();
    }

    private Character mockChar() {
        Character c = mock(Character.class);
        Client cl = mock(Client.class);
        when(c.getClient()).thenReturn(cl);
        when(c.getName()).thenReturn("AdminBot");
        return c;
    }

    @Test
    void parse_unknownCommand_throws() {
        CommandCatalog cat = new CommandCatalog(new LinkedHashMap<>());
        RunCommandExecutor exec = new RunCommandExecutor(cat, name -> false, never());
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@nope foo bar", 6, null));
        assertTrue(ex.getMessage().contains("unknown command"));
    }

    @Test
    void parse_notSupported_throws() {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("warpme", cmd), name -> "warpme".equals(name), always(mockChar()));
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@warpme", 6, null));
        assertTrue(ex.getMessage().contains("requires in-game context"));
    }

    @Test
    void run_validCommand_dispatchesWithRealClient() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        Character chr = mockChar();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("kick", cmd), name -> false, always(chr));
        RunCommandExecutor.Result r = exec.run("@kick alice", 6, null);
        assertEquals(true, r.ok());
        assertEquals(1, cmd.received.length);
        assertEquals("alice", cmd.received[0]);
        assertEquals(chr.getClient(), cmd.receivedClient);
        assertTrue(r.output().contains("AdminBot"));
    }

    @Test
    void run_noOnlineGm_throws() {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("kick", cmd), name -> false, never());
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@kick alice", 6, null));
        assertTrue(ex.getMessage().contains("no GM character online"));
    }

    @Test
    void run_commandThrows_returnsNotOkWithMessage() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        cmd.shouldThrow = true;
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("foo", cmd), name -> false, always(mockChar()));
        RunCommandExecutor.Result r = exec.run("@foo", 6, null);
        assertEquals(false, r.ok());
        assertTrue(r.output().contains("boom"));
    }

    @Test
    void run_emptyCommand_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false, always(mockChar()));
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("   ", 6, null));
    }

    @Test
    void run_missingAtSign_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false, always(mockChar()));
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("kick alice", 6, null));
    }
}
