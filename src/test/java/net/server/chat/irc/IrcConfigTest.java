package net.server.chat.irc;

import config.IrcConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrcConfigTest {

    @Test
    void disabled_isValid_andHasNoChannels() {
        IrcConfigYaml yaml = new IrcConfigYaml();
        yaml.enabled = false;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.enabled());
        assertTrue(cfg.isValid());
    }

    @Test
    void enabled_requiresServer_nick_andAtLeastOneChannel() {
        IrcConfigYaml yaml = baseValid();
        yaml.server = "";
        IrcConfig cfg = IrcConfig.from(yaml);
        assertTrue(cfg.enabled());
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("server"));
    }

    @Test
    void enabled_rejectsEmptyChannels() {
        IrcConfigYaml yaml = baseValid();
        yaml.channels = Map.of();
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("channels"));
    }

    @Test
    void enabled_rejectsPortOutOfRange() {
        IrcConfigYaml yaml = baseValid();
        yaml.port = 0;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("port"));
    }

    @Test
    void enabled_rejectsPlaintextPasswordWithoutEscapeHatch() {
        IrcConfigYaml yaml = baseValid();
        yaml.tls = false;
        yaml.password = "secret";
        yaml.allow_plaintext_password = false;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("plaintext"));
    }

    @Test
    void enabled_acceptsPlaintextPasswordWithEscapeHatch() {
        IrcConfigYaml yaml = baseValid();
        yaml.tls = false;
        yaml.password = "secret";
        yaml.allow_plaintext_password = true;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertTrue(cfg.isValid());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void channels_acceptsStringKeysAsYamlbeansEmits() {
        // yamlbeans deserializes nested-map keys as Strings even when the
        // declared field type is Map<Integer, String>. Verify we coerce.
        IrcConfigYaml yaml = baseValid();
        Map raw = new java.util.HashMap();
        raw.put("0", "#cosmic-scania");
        raw.put("1", "#cosmic-bera");
        yaml.channels = raw;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertTrue(cfg.isValid(), "validation error: " + cfg.validationError());
        assertEquals(2, cfg.channels().size());
        assertEquals("#cosmic-scania", cfg.channels().get(0));
        assertEquals("#cosmic-bera", cfg.channels().get(1));
    }

    @Test
    void backoffDefaults_ifMissing() {
        IrcConfigYaml yaml = baseValid();
        yaml.reconnect_backoff_seconds = null;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertEquals(List.of(5, 10, 30, 60, 60), cfg.reconnectBackoffSeconds());
    }

    private IrcConfigYaml baseValid() {
        IrcConfigYaml y = new IrcConfigYaml();
        y.enabled = true;
        y.server = "irc.libera.chat";
        y.port = 6697;
        y.tls = true;
        y.nick = "cosmic-bridge";
        y.user = "cosmic";
        y.realname = "Cosmic Chat Bridge";
        y.password = "";
        y.allow_plaintext_password = false;
        y.channels = Map.of(0, "#cosmic-scania");
        y.outbound_queue_max = 1000;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.reconnect_backoff_seconds = List.of(5, 10, 30, 60, 60);
        return y;
    }
}
