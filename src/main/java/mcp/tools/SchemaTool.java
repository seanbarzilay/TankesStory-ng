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
import java.util.function.Supplier;

public class SchemaTool implements Tool {

    private final Supplier<Connection> conSupplier;

    public SchemaTool(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    @Override
    public String name() { return "cosmic.db.schema"; }

    @Override
    public String description() { return "List DB tables, or describe one (columns + FKs)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("table").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        try (Connection con = conSupplier.get()) {
            if (args.has("table") && args.get("table").isTextual()) {
                describe(con, args.get("table").asText(), out);
            } else {
                listTables(con, out);
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        }
        return out;
    }

    private void listTables(Connection con, ObjectNode out) throws SQLException {
        ArrayNode arr = out.putArray("tables");
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) arr.add(rs.getString(1));
        }
    }

    private void describe(Connection con, String table, ObjectNode out) throws SQLException, ToolException {
        out.put("table", table);
        ArrayNode cols = out.putArray("columns");
        boolean any = false;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    ObjectNode col = cols.addObject();
                    col.put("name", rs.getString(1));
                    col.put("type", rs.getString(2));
                    col.put("nullable", "YES".equals(rs.getString(3)));
                    col.put("default", rs.getString(4));
                    col.put("key", rs.getString(5));
                }
            }
        }
        if (!any) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such table: " + table);
        }
        ArrayNode fks = out.putArray("foreignKeys");
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode fk = fks.addObject();
                    fk.put("column", rs.getString(1));
                    fk.put("refTable", rs.getString(2));
                    fk.put("refColumn", rs.getString(3));
                }
            }
        }
    }
}
