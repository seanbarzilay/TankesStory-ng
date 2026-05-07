package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

public class AuditListTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ARGS_JSON_OUT = 4096;
    private static final int MAX_RESULT_SUMMARY_OUT = 1024;

    private final Supplier<Connection> conSupplier;

    public AuditListTool(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    @Override
    public String name() { return "cosmic.admin.audit.list"; }

    @Override
    public String description() { return "List MCP admin audit entries (recent first). Filterable by tool and time."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        props.putObject("tool").put("type", "string");
        props.putObject("since_iso").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        String tool = args.has("tool") && args.get("tool").isTextual() ? args.get("tool").asText() : null;
        Timestamp since = null;
        if (args.has("since_iso") && args.get("since_iso").isTextual()) {
            String s = args.get("since_iso").asText();
            try {
                since = Timestamp.from(Instant.parse(s));
            } catch (DateTimeParseException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid since_iso: " + s);
            }
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, ts, caller_ip, caller_note, tool, args_json, result_summary, ok " +
                        "FROM mcp_admin_audit WHERE 1=1");
        if (tool != null) sql.append(" AND tool = ?");
        if (since != null) sql.append(" AND ts >= ?");
        sql.append(" ORDER BY ts DESC LIMIT ?");

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode entries = out.putArray("entries");
        try (Connection c = conSupplier.get();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            if (tool != null) ps.setString(idx++, tool);
            if (since != null) ps.setTimestamp(idx++, since);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = entries.addObject();
                    n.put("id", rs.getLong("id"));
                    n.put("ts", rs.getTimestamp("ts").toInstant().toString());
                    n.put("caller_ip", rs.getString("caller_ip"));
                    n.put("caller_note", rs.getString("caller_note"));
                    n.put("tool", rs.getString("tool"));
                    String aj = rs.getString("args_json");
                    n.put("args_json", aj == null ? null : truncate(aj, MAX_ARGS_JSON_OUT));
                    String rsum = rs.getString("result_summary");
                    n.put("result_summary", rsum == null ? null : truncate(rsum, MAX_RESULT_SUMMARY_OUT));
                    n.put("ok", rs.getBoolean("ok"));
                }
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit query failed: " + e.getMessage());
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...truncated]";
    }
}
