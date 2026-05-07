package net.server.chat.telegram;

public interface TelegramSender {
    /** Fire-and-forget async send. Failures are logged in the implementation, never thrown. */
    void sendToChat(long chatId, String text);

    /** Bot's own username (with leading @). Kept for parity with IRC's echo-loop guard;
     *  Telegram doesn't echo our own messages back through getUpdates, so this is informational. */
    String currentBotUsername();
}
