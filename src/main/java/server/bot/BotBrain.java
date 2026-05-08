package server.bot;

public interface BotBrain {
    void tick(Bot bot, long now);
}
