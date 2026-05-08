package net.server.chat.telegram;

import config.TelegramConfigYaml;

import java.util.HashMap;
import java.util.Map;

public final class TelegramConfig {

    private static final int DEFAULT_POLL_TIMEOUT = 25;
    private static final int DEFAULT_RATE_PER_MINUTE = 6;
    private static final int DEFAULT_MAX_LENGTH = 200;

    private final boolean enabled;
    private final String botToken;
    private final String apiUrl;
    private final int pollTimeoutSeconds;
    private final int rateLimitPerMinute;
    private final int maxLength;
    private final Map<Integer, Long> chats;
    private final String validationError;

    private TelegramConfig(boolean enabled, String botToken, String apiUrl,
                           int pollTimeoutSeconds, int rateLimitPerMinute, int maxLength,
                           Map<Integer, Long> chats, String validationError) {
        this.enabled = enabled;
        this.botToken = botToken;
        this.apiUrl = apiUrl;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxLength = maxLength;
        this.chats = chats;
        this.validationError = validationError;
    }

    public static TelegramConfig from(TelegramConfigYaml y) {
        if (y == null || !y.enabled) {
            return new TelegramConfig(false, "", "", DEFAULT_POLL_TIMEOUT,
                    DEFAULT_RATE_PER_MINUTE, DEFAULT_MAX_LENGTH, Map.of(), null);
        }

        Map<Integer, Long> chats = coerceChats(y.chats);
        String err = validate(y, chats);

        int pollTimeout = y.poll_timeout_seconds <= 0 ? DEFAULT_POLL_TIMEOUT
                : Math.max(1, Math.min(50, y.poll_timeout_seconds));
        int ratePerMinute = y.worldchat_rate_per_minute > 0 ? y.worldchat_rate_per_minute : DEFAULT_RATE_PER_MINUTE;
        int maxLen = y.worldchat_max_length > 0 ? y.worldchat_max_length : DEFAULT_MAX_LENGTH;

        return new TelegramConfig(true,
                nullToEmpty(y.bot_token), nullToEmpty(y.api_url),
                pollTimeout, ratePerMinute, maxLen, chats, err);
    }

    private static String validate(TelegramConfigYaml y, Map<Integer, Long> chats) {
        if (y.bot_token == null || y.bot_token.isBlank()) return "bot_token is required";
        if (chats.isEmpty()) return "chats must not be empty";
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<Integer, Long> coerceChats(Map raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<Integer, Long> out = new HashMap<>();
        for (Object entry : raw.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            Object key = e.getKey();
            Object value = e.getValue();
            if (key == null || value == null) continue;
            int worldId;
            long chatId;
            try {
                worldId = (key instanceof Integer i) ? i : Integer.parseInt(key.toString());
                chatId = (value instanceof Long l) ? l
                        : (value instanceof Integer i) ? i.longValue()
                        : Long.parseLong(value.toString());
            } catch (NumberFormatException nfe) {
                continue;
            }
            out.put(worldId, chatId);
        }
        return Map.copyOf(out);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public boolean enabled() { return enabled; }
    public boolean isValid() { return enabled ? validationError == null : true; }
    public String validationError() { return validationError; }
    public String botToken() { return botToken; }
    public String apiUrl() { return apiUrl; }
    public int pollTimeoutSeconds() { return pollTimeoutSeconds; }
    public int rateLimitPerMinute() { return rateLimitPerMinute; }
    public int maxLength() { return maxLength; }
    public Map<Integer, Long> chats() { return chats; }
}
