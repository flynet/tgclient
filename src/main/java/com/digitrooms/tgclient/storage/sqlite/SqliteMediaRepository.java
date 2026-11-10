package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * Only metadata and the path live in the database — the bytes stay on the filesystem.
 * Blobs inside SQLite are the usual reason projects like this never manage to migrate.
 */
final class SqliteMediaRepository implements Storage.MediaRepository {

    private static final String UPSERT = """
            INSERT INTO media (chat_id, message_id, file_unique_id, file_id, kind, mime_type,
                               size, local_path, downloaded_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (chat_id, message_id, file_unique_id) DO UPDATE SET
                file_id = excluded.file_id,
                kind = excluded.kind,
                mime_type = excluded.mime_type,
                size = excluded.size,
                local_path = excluded.local_path,
                downloaded_at = excluded.downloaded_at
            """;

    private final SqliteStorage storage;

    SqliteMediaRepository(SqliteStorage storage) {
        this.storage = storage;
    }

    @Override
    public void upsertBatch(List<MediaRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (MediaRow r : rows) {
                    ps.setLong(1, r.chatId());
                    ps.setLong(2, r.messageId());
                    ps.setString(3, r.fileUniqueId());
                    ps.setLong(4, r.fileId());
                    SqliteStorage.setNullableString(ps, 5, r.kind());
                    SqliteStorage.setNullableString(ps, 6, r.mimeType());
                    ps.setLong(7, r.size());
                    SqliteStorage.setNullableString(ps, 8, r.localPath());
                    ps.setLong(9, r.downloadedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<MediaRow> findByMessages(long chatId, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM media WHERE chat_id = ? AND message_id IN ("
                + "?,".repeat(messageIds.size() - 1) + "?)";
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, chatId);
                for (int i = 0; i < messageIds.size(); i++) {
                    ps.setLong(i + 2, messageIds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return SqliteStorage.readMedia(rs);
                }
            }
        });
    }

    @Override
    public void deleteByMessage(long chatId, long messageId) {
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM media WHERE chat_id = ? AND message_id = ?")) {
                ps.setLong(1, chatId);
                ps.setLong(2, messageId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<MediaRow> findByMessage(long chatId, long messageId) {
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM media WHERE chat_id = ? AND message_id = ?")) {
                ps.setLong(1, chatId);
                ps.setLong(2, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return SqliteStorage.readMedia(rs);
                }
            }
        });
    }
}
