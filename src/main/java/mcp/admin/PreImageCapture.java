package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public final class PreImageCapture {

    private PreImageCapture() {}

    public static JsonNode capture(Connection con, String sql, int rowCap) throws SQLException {
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("preimage parse failed: " + e.getMessage(), e);
        }
        String table;
        String whereClause;
        if (parsed instanceof Update u) {
            table = u.getTable().getName();
            whereClause = u.getWhere() == null ? null : u.getWhere().toString();
        } else if (parsed instanceof Delete d) {
            table = d.getTable().getName();
            whereClause = d.getWhere() == null ? null : d.getWhere().toString();
        } else {
            return null;
        }
        if (whereClause == null) {
            ObjectNode warn = JsonRpc.MAPPER.createObjectNode();
            warn.put("warning", "no_where_clause");
            return warn;
        }
        String selectSql = "SELECT * FROM " + table + " WHERE " + whereClause + " LIMIT " + (rowCap + 1);
        try (PreparedStatement ps = con.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            ArrayNode rows = JsonRpc.MAPPER.createArrayNode();
            ResultSetMetaData md = rs.getMetaData();
            int count = 0;
            while (rs.next()) {
                if (count >= rowCap) {
                    ObjectNode capped = JsonRpc.MAPPER.createObjectNode();
                    capped.put("capped", true);
                    capped.put("row_count_at_least", rowCap);
                    return capped;
                }
                ObjectNode row = rows.addObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    String key = md.getColumnLabel(i);
                    if (v == null) row.putNull(key);
                    else row.put(key, v.toString());
                }
                count++;
            }
            return rows;
        }
    }
}
