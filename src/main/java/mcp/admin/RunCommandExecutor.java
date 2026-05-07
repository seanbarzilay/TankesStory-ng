package mcp.admin;

import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Predicate;

public class RunCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunCommandExecutor.class);

    public record Result(boolean ok, String output) {}

    public static class RunException extends Exception {
        public RunException(String msg) { super(msg); }
    }

    private final CommandCatalog catalog;
    private final Predicate<String> notSupported;

    public RunCommandExecutor(CommandCatalog catalog, Predicate<String> notSupported) {
        this.catalog = catalog;
        this.notSupported = notSupported;
    }

    public Result run(String commandLine, int asGmLevel) throws RunException {
        if (commandLine == null || commandLine.isBlank()) {
            throw new RunException("empty command");
        }
        String trimmed = commandLine.trim();
        if (!trimmed.startsWith("@")) {
            throw new RunException("command must start with @");
        }
        String[] parts = trimmed.substring(1).split("\\s+");
        String name = parts[0].toLowerCase();
        if (notSupported.test(name)) {
            throw new RunException("command requires in-game context: " + name);
        }
        Optional<Command> opt = catalog.find(name);
        if (opt.isEmpty()) {
            throw new RunException("unknown command: " + name + " (use cosmic.admin.commands.list)");
        }
        String[] params = new String[parts.length - 1];
        System.arraycopy(parts, 1, params, 0, params.length);
        Client client = synthesizeAdminClient(asGmLevel);
        try {
            opt.get().execute(client, params);
            return new Result(true, "");
        } catch (Throwable t) {
            log.warn("run_command failed for {}", name, t);
            return new Result(false, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    Client synthesizeAdminClient(int gmLevel) {
        return null;
    }
}
