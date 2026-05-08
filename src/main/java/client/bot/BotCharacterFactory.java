package client.bot;

import client.Character;

class BotCharacterFactory {
    static Character create(int world, int channel, int id, String name, BotPreset preset) {
        // World/channel are taken from the BotClient; the bot's own client carries
        // them via super(...) in BotClient(int, int).
        BotClient client = new BotClient(world, channel);
        return Character.createBot(client, id, name, preset);
    }
}
