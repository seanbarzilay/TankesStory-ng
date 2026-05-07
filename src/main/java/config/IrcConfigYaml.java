package config;

import java.util.List;
import java.util.Map;

public class IrcConfigYaml {
    public boolean enabled;
    public String server;
    public int port;
    public boolean tls;
    public String nick;
    public String user;
    public String realname;
    public String password;
    public boolean allow_plaintext_password;
    public Map<Integer, String> channels;
    public int outbound_queue_max;
    public int worldchat_rate_per_minute;
    public int worldchat_max_length;
    public List<Integer> reconnect_backoff_seconds;
}
