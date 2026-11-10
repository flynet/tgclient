package com.digitrooms.tgclient.storage;

/**
 * One stored message. Times are epoch milliseconds UTC so the schema stays identical
 * on SQLite and PostgreSQL.
 */
public record MessageRow(
        long chatId,
        long messageId,
        long senderId,
        String senderType,
        long date,
        long editDate,
        long replyToMessageId,
        String contentType,
        String text,
        String rawJson,
        boolean outgoing,
        boolean deleted
) {
}
