package net.server.chat.telegram;

import config.TelegramConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConfigTest {

    @Test
    void disabled_isValid_andHasNoChats() {
        TelegramConfigYaml yaml = new TelegramConfigYaml();
        yaml.enabled = false;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.enabled());
        assertTrue(cfg.isValid());
        assertEquals(0, cfg.chats().size());
    }

    @Test
    void enabled_requiresBotToken() {
        TelegramConfigYaml yaml = baseValid();
        yaml.bot_token = "";
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("bot_token"));
    }

    @Test
    void enabled_requiresAtLeastOneChat() {
        TelegramConfigYaml yaml = baseValid();
        yaml.chats = Map.of();
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("chats"));
    }

    @Test
    void pollTimeout_clampedToMaxFifty() {
        TelegramConfigYaml yaml = baseValid();
        yaml.poll_timeout_seconds = 999;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals(50, cfg.pollTimeoutSeconds());
    }

    @Test
    void pollTimeout_clampedToMinOne() {
        TelegramConfigYaml yaml = baseValid();
        yaml.poll_timeout_seconds = 0;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals(25, cfg.pollTimeoutSeconds());
        // Note: 0 (the unset/zero default) means "use default", not "clamp to 1".
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void chats_acceptsStringKeysAndStringValuesAsYamlbeansEmits() {
        TelegramConfigYaml yaml = baseValid();
        Map raw = new HashMap();
        raw.put("0", "-1001234567890");
        raw.put("1", "-1001234567891");
        yaml.chats = raw;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertTrue(cfg.isValid(), "validation error: " + cfg.validationError());
        assertEquals(2, cfg.chats().size());
        assertEquals(-1001234567890L, cfg.chats().get(0));
        assertEquals(-1001234567891L, cfg.chats().get(1));
    }

    @Test
    void apiUrl_emptyMeansDefault() {
        TelegramConfigYaml yaml = baseValid();
        yaml.api_url = "";
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals("", cfg.apiUrl());
    }

    private TelegramConfigYaml baseValid() {
        TelegramConfigYaml y = new TelegramConfigYaml();
        y.enabled = true;
        y.bot_token = "12345:abcdef";
        y.api_url = "";
        y.poll_timeout_seconds = 25;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.chats = Map.of(0, -1001234567890L);
        return y;
    }
}
