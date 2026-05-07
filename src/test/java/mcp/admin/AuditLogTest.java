package mcp.admin;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — AuditLogTest skipped");
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
    }

    @AfterAll
    static void down() {
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void insert_writesRowAndReturnsId() throws Exception {
        AuditLog log = new AuditLog(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@kick playerX");
        AuditEntry entry = new AuditEntry("127.0.0.1", "test", "cosmic.admin.run_command",
                args, "ok", null, null, true);
        long id = log.insert(entry);
        assertTrue(id > 0);
        try (Connection c = conSupplier.get();
             var ps = c.prepareStatement("SELECT tool, ok, args_json FROM mcp_admin_audit WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("cosmic.admin.run_command", rs.getString("tool"));
                assertEquals(true, rs.getBoolean("ok"));
                assertNotNull(rs.getString("args_json"));
            }
        }
    }

    @Test
    void insertInTransaction_rollsBackOnFailure() throws Exception {
        AuditLog log = new AuditLog(conSupplier);
        try (Connection c = conSupplier.get()) {
            c.setAutoCommit(false);
            AuditEntry entry = new AuditEntry("127.0.0.1", null, "cosmic.db.execute",
                    JsonRpc.MAPPER.createObjectNode(), null, null, null, true);
            log.insertInConnection(c, entry);
            c.rollback();
        }
        try (Connection c = conSupplier.get(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM mcp_admin_audit")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "no rows after rollback");
        }
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
