package com.digitrooms.tgclient;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.ReactionRow;
import com.digitrooms.tgclient.storage.Storage;
import com.digitrooms.tgclient.storage.WriteQueue;
import com.digitrooms.tgclient.storage.sqlite.SqliteStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    @TempDir
    Path tempDir;

    private AppConfig.Storage cfg;
    private Storage storage;

    @BeforeEach
    void setUp() {
        cfg = new AppConfig.Storage();
        cfg.sqlitePath = tempDir.resolve("test.db").toString();
        cfg.writeBatchSize = 100;
        cfg.writeFlushIntervalMs = 20;
        storage = new SqliteStorage(cfg);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private static MessageRow message(long chatId, long messageId, String text) {
        return new MessageRow(chatId, messageId, 42, "user", 1_700_000_000_000L, 0, 0,
                "text", text, null, false, false);
    }

    @Test
    void upsertIsIdempotent() {
        storage.messages().upsertBatch(List.of(message(1, 10, "hello")));
        storage.messages().upsertBatch(List.of(message(1, 10, "hello edited")));

        assertEquals(1, storage.messages().count());
        assertEquals("hello edited", storage.messages().findByChat(1, 0, 10).getFirst().text());
    }

    @Test
    void findByChatPaginatesNewestFirst() {
        storage.messages().upsertBatch(List.of(
                message(1, 10, "a"), message(1, 11, "b"), message(1, 12, "c"), message(2, 13, "other")));

        List<MessageRow> page = storage.messages().findByChat(1, 0, 2);
        assertEquals(List.of(12L, 11L), page.stream().map(MessageRow::messageId).toList());

        List<MessageRow> next = storage.messages().findByChat(1, 11, 2);
        assertEquals(List.of(10L), next.stream().map(MessageRow::messageId).toList());
    }

    @Test
    void searchFindsIndexedTextAndIgnoresDeleted() {
        List<MessageRow> rows = List.of(message(1, 10, "release notes for the client"), message(1, 11, "lunch"));
        storage.messages().upsertBatch(rows);
        storage.search().index(rows);

        assertEquals(1, storage.search().query("release notes", null, 10).size());
        assertEquals(0, storage.search().query("release notes", 999L, 10).size());

        storage.messages().markDeleted(1, List.of(10L));
        assertTrue(storage.search().query("release notes", null, 10).isEmpty());
    }

    @Test
    void searchToleratesFts5Operators() {
        List<MessageRow> rows = List.of(message(1, 10, "price is 5 OR 6"));
        storage.messages().upsertBatch(rows);
        storage.search().index(rows);

        // Raw FTS5 syntax in user input must not blow up the query.
        assertEquals(1, storage.search().query("price is 5 OR 6", null, 10).size());
    }

    @Test
    void writeQueueBatchesAndFlushes() {
        try (WriteQueue queue = new WriteQueue(storage, cfg)) {
            for (int i = 0; i < 5_000; i++) {
                queue.submitMessage(message(7, 1000 + i, "msg " + i));
            }
            queue.flush();
            assertEquals(5_000, storage.messages().count());
            assertEquals(5_000, queue.writtenCount());
        }
    }

    @Test
    void deleteRemovesTheRowAndItsIndexEntry() {
        // The temporary-id case: an outgoing message is stored under a provisional id and
        // must disappear completely once the server confirms it under the real one.
        List<MessageRow> temp = List.of(message(1, 999, "in flight"));
        storage.messages().upsertBatch(temp);
        storage.search().index(temp);

        storage.messages().delete(1, List.of(999L));
        storage.search().removeFromIndex(1, List.of(999L));

        assertEquals(0, storage.messages().count());
        assertTrue(storage.search().query("in flight", null, 10).isEmpty());
    }

    @Test
    void mediaRowsCanBeClearedForATemporaryMessageId() {
        // Attachments are first recorded under TDLib's temporary message id and must be
        // re-recorded under the real one, or they reference a message that no longer exists.
        storage.media().upsertBatch(List.of(
                new MediaRow(1, 111, "file-a", 7, "photo", "image/jpeg", 100, null, 0)));
        storage.media().upsertBatch(List.of(
                new MediaRow(1, 222, "file-a", 7, "photo", "image/jpeg", 100, null, 0)));

        storage.media().deleteByMessage(1, 111);

        assertTrue(storage.media().findByMessage(1, 111).isEmpty());
        assertEquals(1, storage.media().findByMessage(1, 222).size());
    }

    @Test
    void reactionsAreReplacedAsAWholeSet() {
        // Telegram sends the complete set, so a bucket missing from an update has been
        // removed — an upsert alone would leave the old row behind.
        storage.reactions().replace(1, 10, List.of(
                new ReactionRow(1, 10, "👍", 3, true, "42,43", 1000),
                new ReactionRow(1, 10, "🔥", 1, false, null, 1000)));
        assertEquals(2, storage.reactions().findByMessages(1, List.of(10L)).size());

        storage.reactions().replace(1, 10, List.of(
                new ReactionRow(1, 10, "👍", 5, false, "42", 2000)));

        List<ReactionRow> after = storage.reactions().findByMessages(1, List.of(10L));
        assertEquals(1, after.size());
        assertEquals("👍", after.getFirst().reaction());
        assertEquals(5, after.getFirst().totalCount());
        assertTrue(!after.getFirst().chosen());

        storage.reactions().replace(1, 10, List.of());
        assertTrue(storage.reactions().findByMessages(1, List.of(10L)).isEmpty());
    }

    @Test
    void syncStateRemembersTheBackfillCursor() {
        assertEquals(0, storage.syncState().oldestSynced(5));
        storage.syncState().setOldestSynced(5, 900, false);
        assertEquals(900, storage.syncState().oldestSynced(5));
        assertTrue(!storage.syncState().isBackfillDone(5));

        storage.syncState().setOldestSynced(5, 800, true);
        assertTrue(storage.syncState().isBackfillDone(5));
    }
}
