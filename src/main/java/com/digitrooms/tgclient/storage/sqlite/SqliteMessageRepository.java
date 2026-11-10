package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

final class SqliteMessageRepository implements Storage.MessageRepository {

    // ON CONFLICT ... DO UPDATE is understood by both SQLite and PostgreSQL;
    // INSERT OR REPLACE would not be.
    private static final String UPSERT = """
            INSERT INTO messages (chat_id, message_id, sender_id, sender_type, date, edit_date,
                                  reply_to_message_id, content_type, text, raw_json, outgoing, is_deleted)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (chat_id, message_id) DO UPDATE SET
                sender_id = excluded.sender_id,
                sender_type = excluded.sender_type,
                date = excluded.date,
                edit_date = excluded.edit_date,
                reply_to_message_id = excluded.reply_to_message_id,
                content_type = excluded.content_type,
                text = excluded.text,
                raw_json = excluded.raw_json,
                outgoing = excluded.outgoing,
                is_deleted = excluded.is_deleted
            """;

    private final SqliteStorage storage;
    private final boolean storeRaw;

    SqliteMessageRepository(SqliteStorage storage, AppConfig.Storage cfg) {
        this.storage = storage;
        this.storeRaw = cfg.storeRaw;
    }

    @Override
    public void upsertBatch(List<MessageRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (MessageRow r : rows) {
                    ps.setLong(1, r.chatId());
                    ps.setLong(2, r.messageId());
                    ps.setLong(3, r.senderId());
                    SqliteStorage.setNullableString(ps, 4, r.senderType());
                    ps.setLong(5, r.date());
                    ps.setLong(6, r.editDate());
                    ps.setLong(7, r.replyToMessageId());
                    ps.setString(8, r.contentType());
                    SqliteStorage.setNullableString(ps, 9, r.text());
                    SqliteStorage.setNullableString(ps, 10, storeRaw ? r.rawJson() : null);
                    ps.setBoolean(11, r.outgoing());
                    ps.setBoolean(12, r.deleted());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<MessageRow> findByChat(long chatId, long beforeMessageId, int limit) {
        String sql = beforeMessageId > 0
                ? "SELECT * FROM messages WHERE chat_id = ? AND message_id < ? ORDER BY message_id DESC LIMIT ?"
                : "SELECT * FROM messages WHERE chat_id = ? ORDER BY message_id DESC LIMIT ?";
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setLong(i++, chatId);
                if (beforeMessageId > 0) {
                    ps.setLong(i++, beforeMessageId);
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

    @Override
    public void markDeleted(long chatId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET is_deleted = 1 WHERE chat_id = ? AND message_id = ?")) {
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
    public void delete(long chatId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM messages WHERE chat_id = ? AND message_id = ?")) {
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
    public long count() {
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM messages");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }
}
