package com.digitrooms.tgclient.storage;

import java.util.List;

/**
 * The storage port. Everything above this package talks only to these interfaces,
 * so swapping SQLite for PostgreSQL is a new implementation of {@link Storage}
 * plus one line in the config — no changes to handlers, sender or the HTTP layer.
 */
public interface Storage extends AutoCloseable {

    MessageRepository messages();

    ChatRepository chats();

    MediaRepository media();

    SyncStateRepository syncState();

    ReactionRepository reactions();

    MessageSearch search();

    @Override
    void close();

    interface MessageRepository {
        /** Insert or update a batch inside a single transaction. */
        void upsertBatch(List<MessageRow> rows);

        /** Newest first; {@code beforeMessageId <= 0} means "from the newest". */
        List<MessageRow> findByChat(long chatId, long beforeMessageId, int limit);

        void markDeleted(long chatId, List<Long> messageIds);

        /**
         * Hard delete. Used for rows that never existed server-side — TDLib assigns a
         * temporary id while a message is in flight and replaces it once the server
         * confirms, leaving the temporary row behind.
         */
        void delete(long chatId, List<Long> messageIds);

        long count();
    }

    interface ChatRepository {
        void upsertBatch(List<ChatRow> rows);

        List<ChatRow> list(int limit);
    }

    interface MediaRepository {
        void upsertBatch(List<MediaRow> rows);

        List<MediaRow> findByMessage(long chatId, long messageId);

        /** Batch lookup for a page of messages — avoids a query per message. */
        List<MediaRow> findByMessages(long chatId, List<Long> messageIds);

        /** Drops every attachment of a message — used to clear rows left under a temporary id. */
        void deleteByMessage(long chatId, long messageId);
    }

    interface ReactionRepository {
        /**
         * Replaces the whole set for a message. Telegram sends reactions as a complete
         * list, so a bucket that vanishes from it has been removed — an upsert alone
         * would leave stale rows behind.
         */
        void replace(long chatId, long messageId, List<ReactionRow> reactions);

        List<ReactionRow> findByMessages(long chatId, List<Long> messageIds);
    }

    interface SyncStateRepository {
        /** Oldest message id already backfilled for a chat, 0 if none. */
        long oldestSynced(long chatId);

        void setOldestSynced(long chatId, long messageId, boolean backfillDone);

        boolean isBackfillDone(long chatId);
    }

    /**
     * The one place where the engines genuinely differ: SQLite uses FTS5,
     * PostgreSQL will use tsvector + GIN.
     */
    interface MessageSearch {
        void index(List<MessageRow> rows);

        void removeFromIndex(long chatId, List<Long> messageIds);

        List<MessageRow> query(String text, Long chatId, int limit);
    }
}
