package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;

public class GitRevertTool implements Tool {

    private final Path repoRoot;
    private final GitRunner runner;

    public GitRevertTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitRevertTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.revert"; }

    @Override
    public String description() { return "Discard uncommitted changes for an allowed path (working tree only)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        try {
            PathSafety.resolveAny(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        try {
            GitRunner.Result r = runner.run("checkout", "--", input);
            if (r.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git checkout failed: " + r.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("path", input);
            out.put("reverted", true);
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git checkout failed: " + e.getMessage());
        }
    }
}
