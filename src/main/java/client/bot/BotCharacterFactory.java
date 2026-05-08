package client.bot;

import client.Character;

class BotCharacterFactory {
    static Character create(int world, int channel, int id, String name, BotPreset preset) {
        // Task 19 replaces this body with the real construction documented in
        // docs/superpowers/notes/2026-05-08-player-bot-investigation.md section B.
        // The chosen path: a package-private static factory `Character.createBot(...)`
        // co-located with the existing `Character.getDefault(Client)` factory.
        throw new UnsupportedOperationException(
                "BotCharacterFactory.create not implemented yet — Task 19 fills this in");
    }
}
