package com.digitrooms.tgclient.core;

import it.tdlight.jni.TdApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Resolves TDLib's temporary message ids to the ids the server finally assigned.
 *
 * <p>{@code sendMessage} has a built-in "wait for confirmation" mode, but
 * {@code sendMessageAlbum} does not — without this, sending an album would hand the caller
 * provisional ids that match nothing in the database.
 *
 * <p>The confirmation can arrive before the caller gets round to waiting for it, so
 * results are also parked in a small bounded cache.
 */
public final class SentMessageTracker implements Consumer<TdApi.Update> {

    private static final int RECENT_CAPACITY = 512;

    private final Map<Long, CompletableFuture<TdApi.Message>> pending = new ConcurrentHashMap<>();
    private final Map<Long, TdApi.Message> recent = new LinkedHashMap<>(RECENT_CAPACITY, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TdApi.Message> eldest) {
            return size() > RECENT_CAPACITY;
        }
    };

    @Override
    public void accept(TdApi.Update update) {
        switch (update) {
            case TdApi.UpdateMessageSendSucceeded u -> resolve(u.oldMessageId, u.message, null);
            case TdApi.UpdateMessageSendFailed u -> resolve(u.oldMessageId, null,
                    new IllegalStateException("Telegram rejected the message: "
                            + (u.error == null ? "unknown error" : u.error.message)));
            default -> {
            }
        }
    }

    private void resolve(long temporaryId, TdApi.Message message, Throwable failure) {
        CompletableFuture<TdApi.Message> waiting = pending.remove(temporaryId);
        if (waiting != null) {
            if (failure != null) {
                waiting.completeExceptionally(failure);
            } else {
                waiting.complete(message);
            }
            return;
        }
        if (message != null) {
            synchronized (recent) {
                recent.put(temporaryId, message);
            }
        }
    }

    /** Future completing with the confirmed message for a temporary id. */
    public CompletableFuture<TdApi.Message> await(long temporaryMessageId) {
        synchronized (recent) {
            TdApi.Message alreadyArrived = recent.remove(temporaryMessageId);
            if (alreadyArrived != null) {
                return CompletableFuture.completedFuture(alreadyArrived);
            }
        }
        return pending.computeIfAbsent(temporaryMessageId, id -> new CompletableFuture<>());
    }
}
