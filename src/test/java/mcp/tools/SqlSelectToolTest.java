package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.SqlSafety;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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

class SqlSelectToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — SqlSelectToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE account (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(50))");
            s.execute("INSERT INTO account VALUES (1, 'admin', 'secret')");
        }
    }

    @AfterAll
    static void down() {
        if (mysql != null) mysql.stop();
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Test
    void call_select_returnsRows() throws Exception {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT id, name FROM account");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("rows").size());
        assertEquals("admin", out.get("rows").get(0).get("name").asText());
        assertEquals(false, out.get("truncated").asBoolean());
    }

    @Test
    void call_piiColumn_rejected() {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT password FROM account");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("password"));
    }

    @Test
    void call_insert_rejected() {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of()), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "INSERT INTO account VALUES (2, 'x', 'y')");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_truncatedAtCap() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            for (int i = 2; i < 12; i++) s.execute("INSERT INTO account VALUES (" + i + ", 'u" + i + "', 'p')");
        }
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 3);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT id, name FROM account ORDER BY id");
        JsonNode out = tool.call(args);
        assertEquals(3, out.get("rows").size());
        assertEquals(true, out.get("truncated").asBoolean());
    }
}
