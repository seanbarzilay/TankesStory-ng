package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreImageCaptureTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — PreImageCaptureTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE characters (id INT PRIMARY KEY, level INT, name VARCHAR(50))");
        }
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE characters");
        }
    }

    @Test
    void capture_updateWithSmallWhere_returnsRows() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("INSERT INTO characters VALUES (1, 50, 'Foo')");
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 51 WHERE id = 1", 100);
            assertNotNull(before);
            assertEquals(1, before.size());
            assertEquals(50, before.get(0).get("level").asInt());
        }
    }

    @Test
    void capture_updateWithoutWhere_returnsWarning() throws Exception {
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99", 100);
            assertNotNull(before);
            assertEquals("no_where_clause", before.get("warning").asText());
        }
    }

    @Test
    void capture_insert_returnsNull() throws Exception {
        try (Connection c = conSupplier.get()) {
            assertNull(PreImageCapture.capture(c, "INSERT INTO characters VALUES (1, 1, 'a')", 100));
        }
    }

    @Test
    void capture_largeUpdate_returnsCappedMarker() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            for (int i = 0; i < 150; i++) s.execute("INSERT INTO characters VALUES (" + i + ", 1, 'x')");
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99", 100);
            assertEquals("no_where_clause", before.get("warning").asText());
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99 WHERE level = 1", 100);
            assertTrue(before.get("capped").asBoolean());
            assertTrue(before.get("row_count_at_least").asInt() >= 100);
        }
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
