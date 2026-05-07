package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitDiffTool implements Tool {

    private static final List<String> DEFAULT_PATHS = List.of(
            "scripts",
            "config.yaml",
            "src/main/resources/db/data/131-reactordrops-data.sql",
            "src/main/resources/db/data/151-global-drop-data.sql",
            "src/main/resources/db/data/152-drop-data.sql"
    );

    private final Path repoRoot;
    private final GitRunner runner;

    public GitDiffTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitDiffTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.diff"; }

    @Override
    public String description() { return "Show uncommitted diff for an allowed path, or all editable surfaces."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        List<String> argList = new ArrayList<>();
        argList.add("diff");
        argList.add("--no-color");
        argList.add("--");
        if (args.has("path") && args.get("path").isTextual()) {
            String input = args.get("path").asText();
            try {
                PathSafety.resolveAny(repoRoot, input);
            } catch (PathSafety.PathException e) {
                throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
            }
            argList.add(input);
        } else {
            argList.addAll(DEFAULT_PATHS);
        }
        try {
            GitRunner.Result r = runner.run(argList.toArray(new String[0]));
            if (r.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git diff failed: " + r.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("diff", r.stdout());
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git diff failed: " + e.getMessage());
        }
    }
}
