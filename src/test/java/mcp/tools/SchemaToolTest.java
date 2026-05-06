package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — SchemaToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE demo_account (id INT PRIMARY KEY, name VARCHAR(50))");
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
    void call_listsTables() throws Exception {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        JsonNode out = tool.call(args);
        boolean found = false;
        for (JsonNode t : out.get("tables")) if ("demo_account".equals(t.asText())) found = true;
        assertTrue(found);
    }

    @Test
    void call_describesTable() throws Exception {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("table", "demo_account");
        JsonNode out = tool.call(args);
        assertEquals("demo_account", out.get("table").asText());
        assertTrue(out.get("columns").size() >= 2);
    }

    @Test
    void call_unknownTable_throws() {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("table", "nonexistent_table");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }
}
