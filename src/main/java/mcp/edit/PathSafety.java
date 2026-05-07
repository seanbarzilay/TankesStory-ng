package mcp.edit;

import java.nio.file.Path;
import java.util.Set;

public final class PathSafety {

    private static final Set<String> ALLOWED_DROPS = Set.of(
            "src/main/resources/db/data/131-reactordrops-data.sql",
            "src/main/resources/db/data/151-global-drop-data.sql",
            "src/main/resources/db/data/152-drop-data.sql"
    );
    private static final String CONFIG_PATH = "config.yaml";
    private static final String SCRIPTS_PREFIX = "scripts/";

    private PathSafety() {}

    public static Path resolveScript(Path repoRoot, String input) throws PathException {
        if (input == null || input.contains("..")) throw deny(input);
        if (!input.startsWith(SCRIPTS_PREFIX)) throw deny(input);
        if (!input.endsWith(".js")) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveConfig(Path repoRoot, String input) throws PathException {
        if (!CONFIG_PATH.equals(input)) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveDrops(Path repoRoot, String input) throws PathException {
        if (input == null || input.contains("..")) throw deny(input);
        if (!ALLOWED_DROPS.contains(input)) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveAny(Path repoRoot, String input) throws PathException {
        if (input == null) throw deny(input);
        if (CONFIG_PATH.equals(input)) return resolveConfig(repoRoot, input);
        if (input.startsWith(SCRIPTS_PREFIX)) return resolveScript(repoRoot, input);
        if (ALLOWED_DROPS.contains(input)) return resolveDrops(repoRoot, input);
        throw deny(input);
    }

    private static Path resolveWithin(Path repoRoot, String input) throws PathException {
        Path resolved = repoRoot.resolve(input).normalize();
        if (!resolved.startsWith(repoRoot.normalize())) throw deny(input);
        return resolved;
    }

    private static PathException deny(String input) {
        return new PathException("path not allowed: " + input);
    }

    public static class PathException extends Exception {
        public PathException(String msg) { super(msg); }
    }
}
