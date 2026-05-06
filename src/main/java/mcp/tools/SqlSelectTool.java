package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.SqlSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.function.Supplier;

public class SqlSelectTool implements Tool {

    private final Supplier<Connection> conSupplier;
    private final SqlSafety safety;
    private final int timeoutSeconds;
    private final int rowCap;

    public SqlSelectTool(Supplier<Connection> conSupplier, SqlSafety safety,
                         int timeoutSeconds, int rowCap) {
        this.conSupplier = conSupplier;
        this.safety = safety;
        this.timeoutSeconds = timeoutSeconds;
        this.rowCap = rowCap;
    }

    @Override
    public String name() { return "cosmic.db.select"; }

    @Override
    public String description() { return "Run a read-only SELECT (capped, denylisted PII columns blocked)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("sql").put("type", "string");
        props.putObject("params").put("type", "array");
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
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode rows = out.putArray("rows");
        ArrayNode columns = out.putArray("columns");
        boolean truncated = false;
        try (Connection con = conSupplier.get()) {
            con.setReadOnly(true);
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
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int c = 1; c <= md.getColumnCount(); c++) columns.add(md.getColumnLabel(c));
                    int count = 0;
                    while (rs.next()) {
                        if (count >= rowCap) { truncated = true; break; }
                        ObjectNode row = rows.addObject();
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            Object v = rs.getObject(c);
                            String key = md.getColumnLabel(c);
                            if (v == null) row.putNull(key);
                            else if (v instanceof Number n) row.put(key, n.toString());
                            else row.put(key, v.toString());
                        }
                        count++;
                    }
                }
            }
        } catch (SQLTimeoutException e) {
            throw new ToolException(McpError.QUERY_TIMEOUT, "query_timeout");
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        }
        out.put("truncated", truncated);
        return out;
    }
}
