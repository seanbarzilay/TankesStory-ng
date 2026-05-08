package config;

import java.util.Map;

public class TelegramConfigYaml {
    public boolean enabled;
    public String bot_token;
    public String api_url;
    public int poll_timeout_seconds;
    public int worldchat_rate_per_minute;
    public int worldchat_max_length;
    public Map<Integer, Long> chats;
}
