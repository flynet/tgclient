package com.digitrooms.tgclient.storage;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.ReactionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded queue in front of the database with a single writer thread that groups rows
 * into batches and commits them in one transaction.
 *
 * <p>This matters far more than it looks: committing per message on SQLite means an fsync
 * per message. It is also what makes the move to PostgreSQL painless — the same batching
 * is a win there, only with several writers instead of one.
 *
 * <p>When the queue is full the producer blocks rather than dropping data: falling behind
 * is recoverable, losing messages is not.
 */
public final class WriteQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteQueue.class);

    private sealed interface Task
            permits MessageTask, ChatTask, MediaTask, DeleteTask, PurgeTask, MediaPurgeTask,
                    ReactionsTask, Flush {
    }

    private record MessageTask(MessageRow row) implements Task {
    }

    private record ChatTask(ChatRow row) implements Task {
    }

    private record MediaTask(MediaRow row) implements Task {
    }

    private record DeleteTask(long chatId, long messageId) implements Task {
    }

    private record PurgeTask(long chatId, long messageId) implements Task {
    }

    private record MediaPurgeTask(long chatId, long messageId) implements Task {
    }

    private record ReactionsTask(long chatId, long messageId, List<ReactionRow> reactions) implements Task {
    }

    private record Flush(java.util.concurrent.CountDownLatch latch) implements Task {
    }

    private final BlockingQueue<Task> queue;
    private final Storage storage;
    private final int batchSize;
    private final long flushIntervalMs;
    private final Thread writer;
    private final AtomicLong written = new AtomicLong();
    private volatile boolean running = true;
    private long lastWarnAt = 0;

    public WriteQueue(Storage storage, AppConfig.Storage cfg) {
        this.storage = storage;
        this.queue = new ArrayBlockingQueue<>(cfg.writeQueueCapacity);
        this.batchSize = cfg.writeBatchSize;
        this.flushIntervalMs = cfg.writeFlushIntervalMs;
        this.writer = new Thread(this::runWriter, "db-writer");
        this.writer.setDaemon(false);
        this.writer.start();
    }

    public void submitMessage(MessageRow row) {
        put(new MessageTask(row));
    }

    public void submitChat(ChatRow row) {
        put(new ChatTask(row));
    }

    public void submitMedia(MediaRow row) {
        put(new MediaTask(row));
    }

    /** Marks a message as deleted, keeping the row for history. */
    public void submitDelete(long chatId, long messageId) {
        put(new DeleteTask(chatId, messageId));
    }

    /** Removes a row outright — for temporary ids that the server later replaced. */
    public void submitPurge(long chatId, long messageId) {
        put(new PurgeTask(chatId, messageId));
    }

    /** Removes the attachments of a message, likewise for temporary ids. */
    public void submitMediaPurge(long chatId, long messageId) {
        put(new MediaPurgeTask(chatId, messageId));
    }

    /** Replaces the whole reaction set of a message. */
    public void submitReactions(long chatId, long messageId, List<ReactionRow> reactions) {
        put(new ReactionsTask(chatId, messageId, reactions));
    }

    /** Blocks until everything queued so far has been committed. Used by tests and shutdown. */
    public void flush() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        put(new Flush(latch));
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long writtenCount() {
        return written.get();
    }

    public int pending() {
        return queue.size();
    }

    private void put(Task task) {
        if (!running) {
            throw new IllegalStateException("WriteQueue is closed");
        }
        try {
            if (!queue.offer(task)) {
                warnBackpressure();
                queue.put(task); // block; never drop
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while queueing a write", e);
        }
    }

    private void warnBackpressure() {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt > 5_000) {
            lastWarnAt = now;
            log.warn("Write queue is full ({} items) — update handlers are being throttled by the database",
                    queue.size());
        }
    }

    private void runWriter() {
        List<Task> buffer = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                Task first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                buffer.add(first);
                queue.drainTo(buffer, batchSize - 1);
                writeBatch(buffer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Failed to write a batch of {} rows, dropping it to keep the writer alive",
                        buffer.size(), e);
            } finally {
                buffer.forEach(t -> {
                    if (t instanceof Flush f) {
                        f.latch().countDown();
                    }
                });
                buffer.clear();
            }
        }
    }

    private void writeBatch(List<Task> buffer) {
        List<MessageRow> msgs = new ArrayList<>();
        List<ChatRow> chats = new ArrayList<>();
        List<MediaRow> media = new ArrayList<>();
        List<long[]> deletes = new ArrayList<>();
        List<long[]> purges = new ArrayList<>();
        List<long[]> mediaPurges = new ArrayList<>();
        List<ReactionsTask> reactionSets = new ArrayList<>();

        for (Task t : buffer) {
            switch (t) {
                case MessageTask m -> msgs.add(m.row());
                case ChatTask c -> chats.add(c.row());
                case MediaTask m -> media.add(m.row());
                case DeleteTask d -> deletes.add(new long[]{d.chatId(), d.messageId()});
                case PurgeTask p -> purges.add(new long[]{p.chatId(), p.messageId()});
                case MediaPurgeTask p -> mediaPurges.add(new long[]{p.chatId(), p.messageId()});
                case ReactionsTask r -> reactionSets.add(r);
                case Flush ignored -> {
                }
            }
        }

        if (!chats.isEmpty()) {
            storage.chats().upsertBatch(chats);
        }
        if (!msgs.isEmpty()) {
            storage.messages().upsertBatch(msgs);
            storage.search().index(msgs);
        }
        if (!media.isEmpty()) {
            storage.media().upsertBatch(media);
        }
        for (long[] d : deletes) {
            storage.messages().markDeleted(d[0], List.of(d[1]));
            storage.search().removeFromIndex(d[0], List.of(d[1]));
        }
        for (long[] p : purges) {
            storage.messages().delete(p[0], List.of(p[1]));
            storage.search().removeFromIndex(p[0], List.of(p[1]));
        }
        // After the inserts above, so a batch containing both the temporary and the real
        // attachment rows ends up with only the real ones.
        for (long[] p : mediaPurges) {
            storage.media().deleteByMessage(p[0], p[1]);
        }
        for (ReactionsTask r : reactionSets) {
            storage.reactions().replace(r.chatId(), r.messageId(), r.reactions());
        }
        written.addAndGet(msgs.size());
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        flush();
        running = false;
        writer.interrupt();
        try {
            writer.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Write queue stopped, {} messages persisted this session", written.get());
    }
}
