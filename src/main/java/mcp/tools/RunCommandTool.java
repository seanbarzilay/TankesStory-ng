package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.RunCommandExecutor;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.SQLException;

public class RunCommandTool implements Tool {

    private final RunCommandExecutor executor;
    private final AuditLog auditLog;

    public RunCommandTool(RunCommandExecutor executor, AuditLog auditLog) {
        this.executor = executor;
        this.auditLog = auditLog;
    }

    @Override
    public String name() { return "cosmic.admin.run_command"; }

    @Override
    public String description() { return "Run any Cosmic in-game @-command via MCP. Records to audit log."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("command").put("type", "string");
        props.putObject("as_gm_level").put("type", "integer").put("minimum", 0).put("maximum", 6);
        props.putObject("caller_note").put("type", "string");
        root.putArray("required").add("command");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("command") || !args.get("command").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'command'");
        }
        String command = args.get("command").asText();
        int asGmLevel = args.has("as_gm_level") && args.get("as_gm_level").isInt() ? args.get("as_gm_level").asInt() : 6;
        String callerNote = args.has("caller_note") && args.get("caller_note").isTextual() ? args.get("caller_note").asText() : null;

        RunCommandExecutor.Result result;
        try {
            result = executor.run(command, asGmLevel);
        } catch (RunCommandExecutor.RunException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }

        ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
        argsJson.put("command", command);
        argsJson.put("as_gm_level", asGmLevel);
        AuditEntry entry = new AuditEntry(null, callerNote, "cosmic.admin.run_command",
                argsJson, result.output(), null, null, result.ok());
        long auditId;
        try {
            auditId = auditLog.insert(entry);
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("ok", result.ok());
        out.put("output", result.output() == null ? "" : result.output());
        out.put("audit_id", auditId);
        return out;
    }
}
