package mcp.config;

import config.McpConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigTest {

    @Test
    void from_null_returnsDisabled() {
        McpConfig c = McpConfig.from(null);
        assertFalse(c.enabled());
    }

    @Test
    void from_disabled_returnsDisabled() {
        McpConfigYaml y = new McpConfigYaml();
        y.enabled = false;
        y.auth_token = "leaked-token";
        y.port = 9999;
        McpConfig c = McpConfig.from(y);
        assertFalse(c.enabled());
        assertEquals("", c.authToken());
        assertEquals(0, c.port());
    }

    @Test
    void from_enabledWithoutToken_throws() {
        McpConfigYaml y = baseEnabled();
        y.auth_token = "";
        assertThrows(IllegalArgumentException.class, () -> McpConfig.from(y));
    }

    @Test
    void from_enabledWithShortToken_throws() {
        McpConfigYaml y = baseEnabled();
        y.auth_token = "tooshort";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> McpConfig.from(y));
        assertTrue(ex.getMessage().toLowerCase().contains("token"));
    }

    @Test
    void from_enabledValid_returnsConfig() {
        McpConfigYaml y = baseEnabled();
        McpConfig c = McpConfig.from(y);
        assertEquals("192.168.1.42", c.bindAddr());
        assertEquals(8765, c.port());
        assertEquals(5, c.sqlTimeoutSeconds());
        assertEquals(1000, c.sqlRowCap());
        assertEquals(List.of("account.password"), c.sqlPiiDenylist());
    }

    @Test
    void from_enabledWithZeroDefaults_appliesDefaults() {
        McpConfigYaml y = new McpConfigYaml();
        y.enabled = true;
        y.auth_token = "01234567890123456789abcd";
        // Leave bind_addr null, port 0, sql_timeout_seconds 0, sql_row_cap 0, sql_pii_denylist null
        McpConfig c = McpConfig.from(y);
        assertEquals("127.0.0.1", c.bindAddr());
        assertEquals(8765, c.port());
        assertEquals(5, c.sqlTimeoutSeconds());
        assertEquals(1000, c.sqlRowCap());
        assertEquals(java.util.List.of(), c.sqlPiiDenylist());
    }

    private McpConfigYaml baseEnabled() {
        McpConfigYaml y = new McpConfigYaml();
        y.enabled = true;
        y.bind_addr = "192.168.1.42";
        y.port = 8765;
        y.auth_token = "01234567890123456789abcd";
        y.sql_enabled = true;
        y.sql_timeout_seconds = 5;
        y.sql_row_cap = 1000;
        y.sql_pii_denylist = List.of("account.password");
        y.request_log = true;
        return y;
    }
}
