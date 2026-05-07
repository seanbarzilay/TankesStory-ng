package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitCommitTool implements Tool {

    private final Path repoRoot;
    private final GitRunner runner;

    public GitCommitTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitCommitTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.commit"; }

    @Override
    public String description() { return "Stage allowed paths and commit with a message. No push."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode paths = props.putObject("paths");
        paths.put("type", "array");
        paths.putObject("items").put("type", "string");
        props.putObject("message").put("type", "string");
        ArrayNode required = root.putArray("required");
        required.add("paths");
        required.add("message");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("paths") || !args.get("paths").isArray() || args.get("paths").isEmpty()) {
            throw new ToolException(McpError.INVALID_PARAMS, "paths must be a non-empty array");
        }
        if (!args.has("message") || !args.get("message").isTextual() || args.get("message").asText().isBlank()) {
            throw new ToolException(McpError.INVALID_PARAMS, "message must be non-empty");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode p : args.get("paths")) {
            String s = p.asText();
            try {
                PathSafety.resolveAny(repoRoot, s);
            } catch (PathSafety.PathException e) {
                throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
            }
            paths.add(s);
        }
        String message = args.get("message").asText();
        try {
            List<String> addCmd = new ArrayList<>();
            addCmd.add("add");
            addCmd.add("--");
            addCmd.addAll(paths);
            GitRunner.Result addResult = runner.run(addCmd.toArray(new String[0]));
            if (addResult.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git add failed: " + addResult.stderr().trim());
            }
            GitRunner.Result commitResult = runner.run("commit", "-m", message);
            if (commitResult.exitCode() != 0) {
                List<String> resetCmd = new ArrayList<>();
                resetCmd.add("reset");
                resetCmd.add("HEAD");
                resetCmd.add("--");
                resetCmd.addAll(paths);
                runner.run(resetCmd.toArray(new String[0]));
                throw new ToolException(McpError.INTERNAL_ERROR, "git commit failed: " + commitResult.stderr().trim());
            }
            GitRunner.Result shaResult = runner.run("rev-parse", "HEAD");
            if (shaResult.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git rev-parse failed: " + shaResult.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("sha", shaResult.stdout().trim());
            ArrayNode committed = out.putArray("files_committed");
            for (String p : paths) committed.add(p);
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git failed: " + e.getMessage());
        }
    }
}
