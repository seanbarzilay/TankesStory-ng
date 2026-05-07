package mcp.config;

import config.McpConfigYaml;

import java.util.List;

public record McpConfig(
        boolean enabled,
        String bindAddr,
        int port,
        String authToken,
        String tlsCert,
        String tlsKey,
        boolean sqlEnabled,
        int sqlTimeoutSeconds,
        int sqlRowCap,
        List<String> sqlPiiDenylist,
        boolean requestLog,
        boolean editEnabled,
        String repoRoot
) {

    private static final int    MIN_TOKEN_LENGTH      = 16;
    private static final String DEFAULT_BIND_ADDR    = "127.0.0.1";
    private static final int    DEFAULT_PORT          = 8765;
    private static final int    DEFAULT_SQL_TIMEOUT_S = 5;
    private static final int    DEFAULT_SQL_ROW_CAP   = 1000;
    private static final String DEFAULT_REPO_ROOT     = ".";

    public static McpConfig from(McpConfigYaml y) {
        if (y == null) {
            return new McpConfig(false, "", 0, "", "", "", false, 0, 0, List.of(), false, false, "");
        }
        if (!y.enabled) {
            return new McpConfig(false, "", 0, "", "", "", false, 0, 0, List.of(), false, false, "");
        }
        if (y.auth_token == null || y.auth_token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "mcp.auth_token must be at least " + MIN_TOKEN_LENGTH + " characters when mcp.enabled=true");
        }
        return new McpConfig(
                true,
                y.bind_addr == null ? DEFAULT_BIND_ADDR : y.bind_addr,
                y.port == 0 ? DEFAULT_PORT : y.port,
                y.auth_token,
                y.tls_cert == null ? "" : y.tls_cert,
                y.tls_key == null ? "" : y.tls_key,
                y.sql_enabled,
                y.sql_timeout_seconds == 0 ? DEFAULT_SQL_TIMEOUT_S : y.sql_timeout_seconds,
                y.sql_row_cap == 0 ? DEFAULT_SQL_ROW_CAP : y.sql_row_cap,
                y.sql_pii_denylist == null ? List.of() : List.copyOf(y.sql_pii_denylist),
                y.request_log,
                y.edit_enabled,
                y.repo_root == null || y.repo_root.isBlank() ? DEFAULT_REPO_ROOT : y.repo_root
        );
    }

    public boolean tlsEnabled() {
        return !tlsCert.isEmpty() && !tlsKey.isEmpty();
    }
}
