package client.bot;

public record BotPreset(String name, int jobId, int level, int hp, int mp) {
    public static final BotPreset BEGINNER_LV30 =
            new BotPreset("Beginner Lv 30", 0, 30, 1500, 200);
}
