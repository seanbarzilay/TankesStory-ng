package server.bot;

import client.Character;

public class Bot {

    public enum Mode { IDLE, FOLLOW, GRIND }

    private final Character character;
    private volatile Mode mode = Mode.IDLE;
    private volatile Integer targetCharId;
    private volatile String mobFilter;

    public Bot(Character character) {
        this.character = character;
    }

    public Character character() { return character; }
    public int id() { return character.getId(); }
    public String name() { return character.getName(); }
    public int world() { return character.getWorld(); }

    public Mode mode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public Integer targetCharId() { return targetCharId; }
    public void setTargetCharId(Integer id) { this.targetCharId = id; }

    public String mobFilter() { return mobFilter; }
    public void setMobFilter(String filter) { this.mobFilter = filter; }
}
