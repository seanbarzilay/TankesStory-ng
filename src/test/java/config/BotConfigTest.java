package config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {

    @Test
    void defaultsAreDisabledAndConservative() {
        BotConfig c = new BotConfig();
        assertFalse(c.enabled);
        assertEquals(200, c.tick_ms);
        assertEquals(50, c.max_per_world);
        assertEquals(2000000, c.hp_pot_item_id);
        assertEquals(2000003, c.mp_pot_item_id);
        assertEquals(50, c.hp_pct_threshold);
        assertEquals(30, c.mp_pct_threshold);
        assertEquals(100, c.follow_radius);
        assertEquals(800, c.grind_radius);
        assertEquals(3000, c.revive_delay_ms);
        assertEquals("Bot", c.name_prefix);
        assertTrue(c.auto_accept_party);
    }
}
