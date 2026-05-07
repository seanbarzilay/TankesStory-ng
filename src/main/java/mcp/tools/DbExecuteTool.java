package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.PreImageCapture;
import mcp.admin.WriteSqlSafety;
import mcp.data.SqlSafety;
import mcp.edit.EditLock;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.function.Supplier;

public class DbExecuteTool implements Tool {

    private static final int PRE_IMAGE_CAP = 100;

    private final Supplier<Connection> conSupplier;
    private final WriteSqlSafety safety;
    private final AuditLog auditLog;
    private final int timeoutSeconds;

    public DbExecuteTool(Supplier<Connection> conSupplier, WriteSqlSafety safety, AuditLog auditLog, int timeoutSeconds) {
        this.conSupplier = conSupplier;
        this.safety = safety;
        this.auditLog = auditLog;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() { return "cosmic.db.execute"; }

    @Override
    public String description() { return "Execute UPDATE/INSERT/DELETE on safelisted tables. Pre-image captured. Audited."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("sql").put("type", "string");
        props.putObject("params").put("type", "array");
        props.putObject("caller_note").put("type", "string");
        root.putArray("required").add("sql");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("sql") || !args.get("sql").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'sql'");
        }
        String sql = args.get("sql").asText();
        try {
            safety.check(sql);
        } catch (SqlSafety.UnsafeSqlException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        String callerNote = args.has("caller_note") && args.get("caller_note").isTextual() ? args.get("caller_note").asText() : null;

        if (!EditLock.INSTANCE.tryAcquire()) {
            throw new ToolException(McpError.SERVER_SHUTTING_DOWN, "edit_busy");
        }

        try (Connection con = conSupplier.get()) {
            con.setAutoCommit(false);
            try {
                JsonNode beforeJson;
                try {
                    beforeJson = PreImageCapture.capture(con, sql, PRE_IMAGE_CAP);
                } catch (SQLException e) {
                    throw new ToolException(McpError.INTERNAL_ERROR, "preimage failed: " + e.getMessage());
                }
                int affected;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setQueryTimeout(timeoutSeconds);
                    if (args.has("params") && args.get("params").isArray()) {
                        int i = 1;
                        for (JsonNode p : args.get("params")) {
                            if (p.isInt()) ps.setInt(i, p.asInt());
                            else if (p.isLong()) ps.setLong(i, p.asLong());
                            else if (p.isBoolean()) ps.setBoolean(i, p.asBoolean());
                            else ps.setString(i, p.asText());
                            i++;
                        }
                    }
                    affected = ps.executeUpdate();
                } catch (SQLTimeoutException e) {
                    con.rollback();
                    writeFailureAuditOutOfBand(callerNote, sql, "query_timeout");
                    throw new ToolException(McpError.QUERY_TIMEOUT, "query_timeout");
                } catch (SQLException e) {
                    con.rollback();
                    writeFailureAuditOutOfBand(callerNote, sql, e.getMessage());
                    throw new ToolException(McpError.INTERNAL_ERROR, "db.execute failed: " + e.getMessage());
                }

                ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
                argsJson.put("sql", sql);
                ObjectNode afterSummary = JsonRpc.MAPPER.createObjectNode();
                afterSummary.put("rows_affected", affected);
                AuditEntry entry = new AuditEntry(null, callerNote, "cosmic.db.execute",
                        argsJson, "rows_affected=" + affected, beforeJson, afterSummary.toString(), true);

                long auditId;
                try {
                    auditId = auditLog.insertInConnection(con, entry);
                } catch (SQLException e) {
                    con.rollback();
                    throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
                }
                con.commit();

                ObjectNode out = JsonRpc.MAPPER.createObjectNode();
                out.put("rows_affected", affected);
                out.put("audit_id", auditId);
                out.put("truncated_before", beforeJson != null && beforeJson.isObject()
                        && (beforeJson.has("capped") || beforeJson.has("warning")));
                return out;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        } finally {
            EditLock.INSTANCE.release();
        }
    }

    private void writeFailureAuditOutOfBand(String callerNote, String sql, String message) {
        try {
            ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
            argsJson.put("sql", sql);
            AuditEntry fail = new AuditEntry(null, callerNote, "cosmic.db.execute",
                    argsJson, message, null, null, false);
            auditLog.insert(fail);
        } catch (SQLException ignored) {
            // best-effort; failure audit is informational
        }
    }
}
