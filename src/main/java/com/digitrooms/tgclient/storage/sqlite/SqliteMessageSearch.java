package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * FTS5-backed search. This class is the whole SQLite-specific part of searching;
 * the PostgreSQL adapter will implement the same interface with tsvector + GIN.
 */
final class SqliteMessageSearch implements Storage.MessageSearch {

    private final SqliteStorage storage;

    SqliteMessageSearch(SqliteStorage storage) {
        this.storage = storage;
    }

    @Override
    public void index(List<MessageRow> rows) {
        List<MessageRow> withText = rows.stream()
                .filter(r -> r.text() != null && !r.text().isBlank())
                .toList();
        if (withText.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            // Edited messages arrive as an upsert, so drop the old index entry first.
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM messages_fts WHERE chat_id = ? AND message_id = ?");
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO messages_fts (text, chat_id, message_id) VALUES (?,?,?)")) {
                for (MessageRow r : withText) {
                    del.setLong(1, r.chatId());
                    del.setLong(2, r.messageId());
                    del.addBatch();

                    ins.setString(1, r.text());
                    ins.setLong(2, r.chatId());
                    ins.setLong(3, r.messageId());
                    ins.addBatch();
                }
                del.executeBatch();
                ins.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void removeFromIndex(long chatId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM messages_fts WHERE chat_id = ? AND message_id = ?")) {
                for (Long id : messageIds) {
                    ps.setLong(1, chatId);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<MessageRow> query(String text, Long chatId, int limit) {
        String matchExpr = toMatchExpression(text);
        if (matchExpr == null) {
            return List.of();
        }
        String sql = """
                SELECT m.* FROM messages_fts f
                JOIN messages m ON m.chat_id = f.chat_id AND m.message_id = f.message_id
                WHERE messages_fts MATCH ?
                """
                + (chatId != null ? " AND m.chat_id = ? " : "")
                + " AND m.is_deleted = 0 ORDER BY m.date DESC LIMIT ?";

        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, matchExpr);
                if (chatId != null) {
                    ps.setLong(i++, chatId);
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<MessageRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(SqliteStorage.readMessage(rs));
                    }
                    return out;
                }
            }
        });
    }

    /**
     * FTS5 has its own query syntax; user input containing operators would raise a
     * syntax error, so the query is quoted into a single phrase.
     */
    private static String toMatchExpression(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return '"' + text.trim().replace("\"", "\"\"") + '"';
    }
}
