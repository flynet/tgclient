package com.digitrooms.tgclient.storage;

/**
 * One reaction bucket on a message.
 *
 * @param reaction       the emoji, or {@code custom:<id>} / {@code paid} for the
 *                       non-emoji reaction types
 * @param chosen         whether this account is one of the reactors
 * @param recentSenderIds comma-separated ids of recent reactors; Telegram only sends a
 *                       few of them, and in large chats none at all
 */
public record ReactionRow(
        long chatId,
        long messageId,
        String reaction,
        int totalCount,
        boolean chosen,
        String recentSenderIds,
        long updatedAt
) {
}
