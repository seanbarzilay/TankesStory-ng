package mcp.admin;

import client.Character;
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

    /**
     * Strategy for finding an online character to use as the calling context.
     * Implementations should look up by exact name (if provided) or auto-pick
     * the first online character whose gmLevel meets the required minimum.
     */
    @FunctionalInterface
    public interface CharacterResolver {
        Optional<Character> find(String asCharacter, int minGmLevel);
    }

    private final CommandCatalog catalog;
    private final Predicate<String> notSupported;
    private final CharacterResolver resolver;

    public RunCommandExecutor(CommandCatalog catalog, Predicate<String> notSupported, CharacterResolver resolver) {
        this.catalog = catalog;
        this.notSupported = notSupported;
        this.resolver = resolver;
    }

    public Result run(String commandLine, int asGmLevel, String asCharacter) throws RunException {
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
        Optional<Character> charOpt = resolver.find(asCharacter, asGmLevel);
        if (charOpt.isEmpty()) {
            throw new RunException("no GM character online; pass as_character or log in a GM at level >= " + asGmLevel);
        }
        Client client = charOpt.get().getClient();
        String[] params = new String[parts.length - 1];
        System.arraycopy(parts, 1, params, 0, params.length);
        try {
            opt.get().execute(client, params);
            return new Result(true, "executed via " + charOpt.get().getName());
        } catch (Throwable t) {
            log.warn("run_command failed for {}", name, t);
            return new Result(false, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }
}
