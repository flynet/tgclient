package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.storage.ChatRow;
import com.digitrooms.tgclient.storage.Storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

final class SqliteChatRepository implements Storage.ChatRepository {

    private static final String UPSERT = """
            INSERT INTO chats (chat_id, type, title, username, updated_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (chat_id) DO UPDATE SET
                type = excluded.type,
                title = excluded.title,
                username = excluded.username,
                updated_at = excluded.updated_at
            """;

    private final SqliteStorage storage;

    SqliteChatRepository(SqliteStorage storage) {
        this.storage = storage;
    }

    @Override
    public void upsertBatch(List<ChatRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        storage.inWriteTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (ChatRow r : rows) {
                    ps.setLong(1, r.chatId());
                    SqliteStorage.setNullableString(ps, 2, r.type());
                    SqliteStorage.setNullableString(ps, 3, r.title());
                    SqliteStorage.setNullableString(ps, 4, r.username());
                    ps.setLong(5, r.updatedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<ChatRow> list(int limit) {
        return storage.inReadConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM chats ORDER BY updated_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    return SqliteStorage.readChats(rs);
                }
            }
        });
    }
}
