package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditListToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;
    static AuditLog auditLog;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — AuditListToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE mcp_admin_audit (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      ts DATETIME(3) NOT NULL,
                      caller_ip VARCHAR(64),
                      caller_note VARCHAR(255),
                      tool VARCHAR(64) NOT NULL,
                      args_json JSON,
                      result_summary TEXT,
                      before_json JSON,
                      after_summary TEXT,
                      ok BOOLEAN NOT NULL,
                      INDEX idx_audit_ts (ts)
                    )
                    """);
        }
        auditLog = new AuditLog(conSupplier);
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void call_returnsRecentEntries() throws Exception {
        for (int i = 0; i < 3; i++) {
            ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
            argsJson.put("i", i);
            auditLog.insert(new AuditEntry("127.0.0.1", null, "cosmic.admin.run_command",
                    argsJson, "ok", null, null, true));
        }
        AuditListTool tool = new AuditListTool(conSupplier);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(3, out.get("entries").size());
    }

    @Test
    void call_filterByTool() throws Exception {
        auditLog.insert(new AuditEntry(null, null, "cosmic.admin.run_command",
                JsonRpc.MAPPER.createObjectNode(), null, null, null, true));
        auditLog.insert(new AuditEntry(null, null, "cosmic.db.execute",
                JsonRpc.MAPPER.createObjectNode(), null, null, null, true));
        AuditListTool tool = new AuditListTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("tool", "cosmic.db.execute");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("entries").size());
        assertEquals("cosmic.db.execute", out.get("entries").get(0).get("tool").asText());
    }

    @Test
    void call_invalidSinceIso_throws() {
        AuditListTool tool = new AuditListTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("since_iso", "not-a-date");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("invalid since_iso"));
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
