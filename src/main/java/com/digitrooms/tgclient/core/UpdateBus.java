package com.digitrooms.tgclient.core;

import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Single ordered dispatch point for TDLib updates.
 *
 * <p>Updates are processed on one thread on purpose: Telegram's update stream is ordered
 * and the persistence layer relies on that (an edit must not overtake the insert).
 * Anything slow — downloading media, calling a webhook — is handed to {@link #background()}.
 */
public final class UpdateBus implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpdateBus.class);

    private final List<Consumer<TdApi.Update>> handlers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "update-dispatcher"));
    private final ExecutorService background;

    public UpdateBus(int backgroundWorkers) {
        this.background = Executors.newFixedThreadPool(Math.max(1, backgroundWorkers),
                r -> new Thread(r, "update-worker"));
    }

    public void subscribe(Consumer<TdApi.Update> handler) {
        handlers.add(handler);
    }

    /** Called from the TDLib callback thread; returns immediately. */
    public void publish(TdApi.Update update) {
        dispatcher.execute(() -> {
            for (Consumer<TdApi.Update> handler : handlers) {
                try {
                    handler.accept(update);
                } catch (RuntimeException e) {
                    log.error("Update handler {} failed on {}",
                            handler.getClass().getSimpleName(), update.getClass().getSimpleName(), e);
                }
            }
        });
    }

    /** Pool for work that must not block the ordered dispatch thread. */
    public ExecutorService background() {
        return background;
    }

    @Override
    public void close() {
        dispatcher.shutdown();
        background.shutdown();
        try {
            if (!dispatcher.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("Update dispatcher did not drain in time");
            }
            background.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
