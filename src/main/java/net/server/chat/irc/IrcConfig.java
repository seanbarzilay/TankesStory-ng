package net.server.chat.irc;

import config.IrcConfigYaml;

import java.util.List;
import java.util.Map;

public final class IrcConfig {

    private static final List<Integer> DEFAULT_BACKOFF = List.of(5, 10, 30, 60, 60);
    private static final int DEFAULT_QUEUE_MAX = 1000;
    private static final int DEFAULT_RATE_PER_MINUTE = 6;
    private static final int DEFAULT_MAX_LENGTH = 200;

    private final boolean enabled;
    private final String server;
    private final int port;
    private final boolean tls;
    private final String nick;
    private final String user;
    private final String realname;
    private final String password;
    private final boolean allowPlaintextPassword;
    private final Map<Integer, String> channels;
    private final int outboundQueueMax;
    private final int rateLimitPerMinute;
    private final int maxLength;
    private final List<Integer> backoff;
    private final String validationError;

    private IrcConfig(boolean enabled, String server, int port, boolean tls,
                      String nick, String user, String realname,
                      String password, boolean allowPlaintextPassword,
                      Map<Integer, String> channels,
                      int outboundQueueMax, int rateLimitPerMinute, int maxLength,
                      List<Integer> backoff, String validationError) {
        this.enabled = enabled;
        this.server = server;
        this.port = port;
        this.tls = tls;
        this.nick = nick;
        this.user = user;
        this.realname = realname;
        this.password = password;
        this.allowPlaintextPassword = allowPlaintextPassword;
        this.channels = channels;
        this.outboundQueueMax = outboundQueueMax;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxLength = maxLength;
        this.backoff = backoff;
        this.validationError = validationError;
    }

    public static IrcConfig from(IrcConfigYaml y) {
        if (y == null || !y.enabled) {
            return new IrcConfig(false, "", 0, false, "", "", "", "", false,
                    Map.of(), DEFAULT_QUEUE_MAX, DEFAULT_RATE_PER_MINUTE, DEFAULT_MAX_LENGTH,
                    DEFAULT_BACKOFF, null);
        }

        String err = validate(y);

        Map<Integer, String> channels = y.channels == null ? Map.of() : Map.copyOf(y.channels);
        List<Integer> backoff = (y.reconnect_backoff_seconds == null || y.reconnect_backoff_seconds.isEmpty())
                ? DEFAULT_BACKOFF
                : List.copyOf(y.reconnect_backoff_seconds);
        int queueMax = y.outbound_queue_max > 0 ? y.outbound_queue_max : DEFAULT_QUEUE_MAX;
        int ratePerMinute = y.worldchat_rate_per_minute > 0 ? y.worldchat_rate_per_minute : DEFAULT_RATE_PER_MINUTE;
        int maxLen = y.worldchat_max_length > 0 ? y.worldchat_max_length : DEFAULT_MAX_LENGTH;

        return new IrcConfig(true,
                nullToEmpty(y.server), y.port, y.tls,
                nullToEmpty(y.nick), nullToEmpty(y.user), nullToEmpty(y.realname),
                nullToEmpty(y.password), y.allow_plaintext_password,
                channels, queueMax, ratePerMinute, maxLen, backoff, err);
    }

    private static String validate(IrcConfigYaml y) {
        if (y.server == null || y.server.isBlank()) return "server is required";
        if (y.nick == null || y.nick.isBlank()) return "nick is required";
        if (y.port < 1 || y.port > 65535) return "port out of range";
        if (y.channels == null || y.channels.isEmpty()) return "channels must not be empty";
        if (y.password != null && !y.password.isEmpty() && !y.tls && !y.allow_plaintext_password) {
            return "plaintext password requires allow_plaintext_password: true";
        }
        return null;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public boolean enabled() { return enabled; }
    public boolean isValid() { return enabled ? validationError == null : true; }
    public String validationError() { return validationError; }
    public String server() { return server; }
    public int port() { return port; }
    public boolean tls() { return tls; }
    public String nick() { return nick; }
    public String user() { return user; }
    public String realname() { return realname; }
    public String password() { return password; }
    public Map<Integer, String> channels() { return channels; }
    public int outboundQueueMax() { return outboundQueueMax; }
    public int rateLimitPerMinute() { return rateLimitPerMinute; }
    public int maxLength() { return maxLength; }
    public List<Integer> reconnectBackoffSeconds() { return backoff; }
}
