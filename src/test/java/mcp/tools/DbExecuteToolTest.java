package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditLog;
import mcp.admin.WriteSqlSafety;
import mcp.data.SqlSafety;
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
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExecuteToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;
    static WriteSqlSafety safety;
    static AuditLog auditLog;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — DbExecuteToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE characters (id INT PRIMARY KEY, level INT, name VARCHAR(50))");
            s.execute("CREATE TABLE accounts (id INT PRIMARY KEY, password VARCHAR(50))");
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
        safety = new WriteSqlSafety(new SqlSafety(List.of("accounts.password")), List.of("characters"));
        auditLog = new AuditLog(conSupplier);
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE characters");
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void call_validUpdate_writesRowAndAudit() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("INSERT INTO characters VALUES (1, 50, 'Foo')");
        }
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "UPDATE characters SET level = 51 WHERE id = 1");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("rows_affected").asInt());
        assertTrue(out.get("audit_id").asLong() > 0);
        try (Connection c = conSupplier.get(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT level FROM characters WHERE id = 1")) {
            rs.next();
            assertEquals(51, rs.getInt(1));
        }
    }

    @Test
    void call_tableNotAllowed_rejected() {
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "UPDATE accounts SET password = 'x'");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_select_rejected() {
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT * FROM characters");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
