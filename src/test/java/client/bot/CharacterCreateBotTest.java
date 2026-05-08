package client.bot;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link Character#createBot}. These exist because
 * the original implementation produced a Character whose HP got clamped
 * to {@code localmaxhp=50} (so the brain saw a 3% HP bot and looped on
 * RETREAT) and whose face/hair were 0 (which crashed the v83 client on
 * the spawnPlayer broadcast).
 */
class CharacterCreateBotTest {

    @Test
    void hpAndMpAreNotClampedToLocalMaxDefault() {
        BotClient client = new BotClient(0, 0);
        Character bot = Character.createBot(client, -1_000_000, "Bot01",
                BotPreset.BEGINNER_LV30);
        assertEquals(BotPreset.BEGINNER_LV30.hp(), bot.getMaxHp());
        assertEquals(BotPreset.BEGINNER_LV30.hp(), bot.getHp(),
                "setHp() must not clamp to the default localmaxhp=50");
        assertEquals(BotPreset.BEGINNER_LV30.mp(), bot.getMaxMp());
        assertEquals(BotPreset.BEGINNER_LV30.mp(), bot.getMp(),
                "setMp() must not clamp to the default localmaxmp=5");
    }

    @Test
    void lookFieldsArePopulatedWithValidSpriteIds() {
        BotClient client = new BotClient(0, 0);
        Character bot = Character.createBot(client, -1_000_000, "Bot01",
                BotPreset.BEGINNER_LV30);
        // 0 face/hair point at non-existent sprites in vanilla WZ and crash the v83 client.
        assertNotEquals(0, bot.getFace(), "face must not be 0");
        assertNotEquals(0, bot.getHair(), "hair must not be 0");
        assertNotNull(bot.getSkinColor(), "skin color must not be null");
    }

    @Test
    void identityFieldsMatchInputs() {
        BotClient client = new BotClient(0, 0);
        Character bot = Character.createBot(client, -1_234_567, "Bot42",
                BotPreset.BEGINNER_LV30);
        assertEquals(-1_234_567, bot.getId());
        assertEquals("Bot42", bot.getName());
        assertEquals(BotPreset.BEGINNER_LV30.level(), bot.getLevel());
    }
}
