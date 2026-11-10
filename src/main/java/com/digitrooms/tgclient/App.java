package com.digitrooms.tgclient;

import com.digitrooms.tgclient.api.HttpServer;
import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.core.RateLimiter;
import com.digitrooms.tgclient.core.SentMessageTracker;
import com.digitrooms.tgclient.core.UpdateBus;
import com.digitrooms.tgclient.handlers.MediaHandler;
import com.digitrooms.tgclient.handlers.PersistHandler;
import com.digitrooms.tgclient.storage.ChatRow;
import com.digitrooms.tgclient.storage.Storage;
import com.digitrooms.tgclient.storage.WriteQueue;
import com.digitrooms.tgclient.storage.sqlite.SqliteStorage;
import com.digitrooms.tgclient.telegram.ChatService;
import com.digitrooms.tgclient.telegram.FileService;
import com.digitrooms.tgclient.telegram.InteractionService;
import com.digitrooms.tgclient.telegram.MessageSender;
import com.digitrooms.tgclient.telegram.TelegramClientHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. The object graph is assembled by hand — no DI container — so the startup
 * order (and therefore the shutdown order) is visible in one place.
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        forceUtf8Stdout();

        Path configPath = Path.of(args.length > 0 ? args[0] : "config.yaml");
        AppConfig cfg = AppConfig.load(configPath);
        log.info("Configuration loaded from {}", configPath.toAbsolutePath());

        Storage storage = createStorage(cfg);
        WriteQueue writeQueue = new WriteQueue(storage, cfg.storage);
        UpdateBus bus = new UpdateBus(Math.max(2, cfg.media.workers + 1));
        TelegramClientHolder telegram = new TelegramClientHolder(cfg, bus);

        FileService fileService = new FileService(telegram.client());
        SentMessageTracker sentTracker = new SentMessageTracker();
        MessageSender sender = new MessageSender(telegram.client(), new RateLimiter(), sentTracker);
        ChatService chatService = new ChatService(telegram.client(), writeQueue, storage);
        InteractionService interactions = new InteractionService(telegram.client(), writeQueue);

        bus.subscribe(sentTracker);
        bus.subscribe(new PersistHandler(writeQueue, telegram.client(), bus.background()));
        bus.subscribe(new MediaHandler(writeQueue, fileService, bus.background(), cfg.media));

        telegram.awaitReady();
        chatService.loadChatList().join();
        if (cfg.telegram.trackInteractions) {
            interactions.trackChats(storage.chats().list(1000).stream().map(ChatRow::chatId).toList());
        }

        HttpServer http = null;
        if (cfg.api.enabled) {
            http = new HttpServer(cfg.api, sender, storage, chatService, fileService, interactions);
            http.start();
        }

        CountDownLatch stop = new CountDownLatch(1);
        HttpServer httpRef = http;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down…");
            closeQuietly(httpRef);
            closeQuietly(telegram);
            closeQuietly(bus);
            closeQuietly(writeQueue); // after the bus, so nothing is still enqueueing
            closeQuietly(storage);
            stop.countDown();
        }, "shutdown"));

        log.info("Client is running. Ctrl+C to stop.");
        stop.await();
    }

    /**
     * The QR login code is drawn with block characters. On Windows stdout defaults to the
     * console code page, which turns every block into '?' and leaves an unscannable mess,
     * so stdout is pinned to UTF-8. The terminal must also be in UTF-8 mode
     * ({@code chcp 65001} for cmd.exe; Windows Terminal is fine by default).
     */
    private static void forceUtf8Stdout() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    private static Storage createStorage(AppConfig cfg) {
        return switch (cfg.storage.driver) {
            case "sqlite" -> new SqliteStorage(cfg.storage);
            // The PostgreSQL adapter implements the same Storage interface; adding it here
            // and flipping storage.driver is the whole migration for the application code.
            case "postgres" -> throw new UnsupportedOperationException(
                    "The PostgreSQL adapter is not implemented yet — see storage/postgres in the plan");
            default -> throw new IllegalStateException("Unknown storage driver: " + cfg.storage.driver);
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Error while closing {}", closeable.getClass().getSimpleName(), e);
        }
    }
}
