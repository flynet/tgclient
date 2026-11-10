package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.storage.ReactionRow;
import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

final class SqliteReactionRepository implements Storage.ReactionRepository {

    private final SqliteStorage storage;

    SqliteReactionRepository(SqliteStorage storage) {
        this.storage = storage;
    }

    @Override
    public void replace(long chatId, long messageId, List<ReactionRow> reactions) {
        storage.inWriteTransaction(c -> {
            // Delete and re-insert in one transaction: readers never see a message with
            // half of its reactions.
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM message_reactions WHERE chat_id = ? AND message_id = ?")) {
                delete.setLong(1, chatId);
                delete.setLong(2, messageId);
                delete.executeUpdate();
            }
            if (reactions.isEmpty()) {
                return null;
            }
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO message_reactions
                        (chat_id, message_id, reaction, total_count, is_chosen, recent_sender_ids, updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (chat_id, message_id, reaction) DO UPDATE SET
                        total_count = excluded.total_count,
                        is_chosen = excluded.is_chosen,
                        recent_sender_ids = excluded.recent_sender_ids,
                        updated_at = excluded.updated_at
                    """)) {
                for (ReactionRow r : reactions) {
                    insert.setLong(1, r.chatId());
                    insert.setLong(2, r.messageId());
                    insert.setString(3, r.reaction());
                    insert.setInt(4, r.totalCount());
                    insert.setBoolean(5, r.chosen());
                    SqliteStorage.setNullableString(insert, 6, r.recentSenderIds());
                    insert.setLong(7, r.updatedAt());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<ReactionRow> findByMessages(long chatId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM message_reactions WHERE chat_id = ? AND message_id IN ("
                + "?,".repeat(messageIds.size() - 1) + "?) ORDER BY total_count DESC";
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                for (int i = 0; i < messageIds.size(); i++) {
                    ps.setLong(i + 2, messageIds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<ReactionRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ReactionRow(
                                rs.getLong("chat_id"),
                                rs.getLong("message_id"),
                                rs.getString("reaction"),
                                rs.getInt("total_count"),
                                rs.getBoolean("is_chosen"),
                                rs.getString("recent_sender_ids"),
                                rs.getLong("updated_at")));
                    }
                    return out;
                }
            }
        });
    }
}
