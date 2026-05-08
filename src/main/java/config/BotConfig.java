package config;

public class BotConfig {
    public boolean enabled = false;
    public int tick_ms = 200;
    public int max_per_world = 50;
    public int hp_pot_item_id = 2000000;
    public int mp_pot_item_id = 2000003;
    public int hp_pct_threshold = 50;
    public int mp_pct_threshold = 30;
    public int follow_radius = 100;
    public int grind_radius = 800;
    public int revive_delay_ms = 3000;
    public String name_prefix = "Bot";
    public boolean auto_accept_party = true;
}
