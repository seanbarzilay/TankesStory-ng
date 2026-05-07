package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Supplier;

public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int MAX_RESULT_SUMMARY = 1024;
    private static final int MAX_ARGS_JSON = 4096;

    private final Supplier<Connection> conSupplier;

    public AuditLog(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    public long insert(AuditEntry entry) throws SQLException {
        try (Connection c = conSupplier.get()) {
            return insertInConnection(c, entry);
        }
    }

    public long insertInConnection(Connection c, AuditEntry entry) throws SQLException {
        String sql = "INSERT INTO mcp_admin_audit " +
                "(ts, caller_ip, caller_note, tool, args_json, result_summary, before_json, after_summary, ok) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, entry.callerIp());
            ps.setString(3, entry.callerNote());
            ps.setString(4, entry.tool());
            ps.setString(5, jsonToString(entry.argsJson(), MAX_ARGS_JSON));
            ps.setString(6, truncate(entry.resultSummary(), MAX_RESULT_SUMMARY));
            ps.setString(7, jsonToString(entry.beforeJson(), Integer.MAX_VALUE));
            ps.setString(8, truncate(entry.afterSummary(), MAX_RESULT_SUMMARY));
            ps.setBoolean(9, entry.ok());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("no generated key");
                return keys.getLong(1);
            }
        }
    }

    private static String jsonToString(JsonNode node, int max) {
        if (node == null || node.isNull()) return null;
        try {
            String s = JsonRpc.MAPPER.writeValueAsString(node);
            return truncate(s, max);
        } catch (Exception e) {
            log.warn("audit jsonToString failed", e);
            return "\"<serialization-error>\"";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...truncated]";
    }
}
