package net.server.chat.telegram;

/**
 * Decoded inbound text message from a bridged Telegram chat.
 * The polling thread builds these and hands them to WorldChatService.
 */
public record TelegramInbound(int worldId, String sender, String text) {
}
