package config;

import java.util.List;

public class McpConfigYaml {
    public boolean enabled;
    public String bind_addr;
    public int port;
    public String auth_token;
    public String tls_cert;
    public String tls_key;
    public boolean sql_enabled;
    public int sql_timeout_seconds;
    public int sql_row_cap;
    public List<String> sql_pii_denylist;
    public boolean request_log;
    public boolean edit_enabled;
    public String repo_root;
    public boolean admin_enabled;
    public boolean db_execute_enabled;
    public List<String> sql_writable_tables;
}
