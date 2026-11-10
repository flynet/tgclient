package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

final class SqliteSyncStateRepository implements Storage.SyncStateRepository {

    private final SqliteStorage storage;

    SqliteSyncStateRepository(SqliteStorage storage) {
        this.storage = storage;
    }

    @Override
    public long oldestSynced(long chatId) {
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT oldest_synced_message_id FROM sync_state WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public void setOldestSynced(long chatId, long messageId, boolean backfillDone) {
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO sync_state (chat_id, oldest_synced_message_id, backfill_done)
                    VALUES (?,?,?)
                    ON CONFLICT (chat_id) DO UPDATE SET
                        oldest_synced_message_id = excluded.oldest_synced_message_id,
                        backfill_done = excluded.backfill_done
                    """)) {
                ps.setLong(1, chatId);
                ps.setLong(2, messageId);
                ps.setBoolean(3, backfillDone);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean isBackfillDone(long chatId) {
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT backfill_done FROM sync_state WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }
}
