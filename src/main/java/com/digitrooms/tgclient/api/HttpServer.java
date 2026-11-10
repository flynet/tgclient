package com.digitrooms.tgclient.api;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.Storage;
import com.digitrooms.tgclient.telegram.ChatService;
import com.digitrooms.tgclient.telegram.FileService;
import com.digitrooms.tgclient.telegram.InteractionService;
import com.digitrooms.tgclient.telegram.MessageSender;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Thin HTTP front end: sending goes to Telegram, reading comes from the database
 * (cheaper than TDLib and it does not consume rate limit).
 */
public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final Javalin app;
    private final AppConfig.Api cfg;
    private final MessageSender sender;
    private final Storage storage;
    private final ChatService chatService;
    private final FileService fileService;
    private final InteractionService interactions;

    public HttpServer(AppConfig.Api cfg, MessageSender sender, Storage storage,
                      ChatService chatService, FileService fileService,
                      InteractionService interactions) {
        this.cfg = cfg;
        this.sender = sender;
        this.storage = storage;
        this.chatService = chatService;
        this.fileService = fileService;
        this.interactions = interactions;
        this.app = Javalin.create(config -> config.showJavalinBanner = false);
        routes();
    }

    private void routes() {
        app.before(ctx -> {
            if (cfg.token.isBlank() || "/health".equals(ctx.path())) {
                return;
            }
            if (!cfg.token.equals(ctx.header("X-Api-Token"))) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "invalid token"));
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "messages", storage.messages().count())));

        app.post("/messages/text", ctx -> {
            SendTextRequest req = ctx.bodyAsClass(SendTextRequest.class);
            TdApi.Message sent = sender.sendText(req.chatId(), req.text(), req.replyToMessageId());
            ctx.json(sentResponse(sent));
        });

        app.post("/messages/album", ctx -> {
            AlbumRequest req = ctx.bodyAsClass(AlbumRequest.class);
            List<TdApi.Message> sent = sender.sendAlbum(req.chatId(), req.items(), req.replyToMessageId());
            ctx.json(Map.of(
                    "chatId", req.chatId(),
                    "messageIds", sent.stream().map(m -> m.id).toList()));
        });

        app.post("/messages/copy", ctx -> {
            ForwardRequest req = ctx.bodyAsClass(ForwardRequest.class);
            // A copy is a forward without attribution — same Telegram call, sendCopy on.
            List<TdApi.Message> copied = sender.forward(req.toChatId(), req.fromChatId(),
                    toArray(req.messageIds()), true, req.removeCaption());
            ctx.json(Map.of("toChatId", req.toChatId(), "messageIds", ids(copied)));
        });

        app.post("/messages/react", ctx -> {
            ReactionRequest req = ctx.bodyAsClass(ReactionRequest.class);
            sender.react(req.chatId(), req.messageId(), req.emoji(), req.big());
            // Telegram only streams interaction updates for opened chats, so our own
            // reaction is read back at once instead of waiting for one.
            interactions.refreshReactions(req.chatId(), req.messageId());
            ctx.json(Map.of("chatId", req.chatId(), "messageId", req.messageId(), "emoji", req.emoji()));
        });

        app.post("/messages/unreact", ctx -> {
            ReactionRequest req = ctx.bodyAsClass(ReactionRequest.class);
            sender.unreact(req.chatId(), req.messageId(), req.emoji());
            interactions.refreshReactions(req.chatId(), req.messageId());
            ctx.json(Map.of("chatId", req.chatId(), "messageId", req.messageId(), "removed", req.emoji()));
        });

        app.post("/chats/{chatId}/read", ctx -> {
            long chatId = Long.parseLong(ctx.pathParam("chatId"));
            ReadRequest req = ctx.body().isBlank() ? null : ctx.bodyAsClass(ReadRequest.class);
            long[] ids = req != null && req.messageIds() != null && !req.messageIds().isEmpty()
                    ? toArray(req.messageIds())
                    : newestMessageIds(chatId);
            if (ids.length == 0) {
                ctx.json(Map.of("chatId", chatId, "read", 0));
                return;
            }
            sender.markRead(chatId, ids);
            ctx.json(Map.of("chatId", chatId, "read", ids.length));
        });

        app.post("/messages/edit", ctx -> {
            EditRequest req = ctx.bodyAsClass(EditRequest.class);
            sender.edit(req.chatId(), req.messageId(), req.text());
            ctx.json(Map.of("chatId", req.chatId(), "messageId", req.messageId(), "edited", true));
        });

        app.post("/messages/delete", ctx -> {
            DeleteRequest req = ctx.bodyAsClass(DeleteRequest.class);
            sender.deleteMessages(req.chatId(), toArray(req.messageIds()), req.revoke());
            ctx.json(Map.of("chatId", req.chatId(), "deleted", req.messageIds().size()));
        });

        app.post("/messages/forward", ctx -> {
            ForwardRequest req = ctx.bodyAsClass(ForwardRequest.class);
            List<TdApi.Message> forwarded = sender.forward(req.toChatId(), req.fromChatId(),
                    toArray(req.messageIds()), req.sendCopy(), req.removeCaption());
            ctx.json(Map.of("toChatId", req.toChatId(), "messageIds", ids(forwarded)));
        });

        app.post("/messages/pin", ctx -> {
            PinRequest req = ctx.bodyAsClass(PinRequest.class);
            sender.pin(req.chatId(), req.messageId(), req.disableNotification(), req.onlyForSelf());
            ctx.json(Map.of("chatId", req.chatId(), "messageId", req.messageId(), "pinned", true));
        });

        app.post("/messages/unpin", ctx -> {
            PinRequest req = ctx.bodyAsClass(PinRequest.class);
            sender.unpin(req.chatId(), req.messageId());
            ctx.json(Map.of("chatId", req.chatId(), "messageId", req.messageId(), "pinned", false));
        });

        app.post("/messages/photo", ctx -> sendFile(ctx, "photo"));
        app.post("/messages/video", ctx -> sendFile(ctx, "video"));
        app.post("/messages/document", ctx -> sendFile(ctx, "document"));

        app.get("/chats", ctx -> ctx.json(storage.chats().list(intParam(ctx, "limit", 100))));

        app.get("/chats/{chatId}/messages", ctx -> {
            long chatId = Long.parseLong(ctx.pathParam("chatId"));
            long before = Long.parseLong(ctx.queryParamAsClass("before", String.class).getOrDefault("0"));
            ctx.json(withMedia(chatId, storage.messages().findByChat(chatId, before, intParam(ctx, "limit", 50))));
        });

        app.get("/media/{chatId}/{messageId}/{fileUniqueId}", this::serveMedia);

        app.post("/chats/{chatId}/backfill", ctx -> {
            long chatId = Long.parseLong(ctx.pathParam("chatId"));
            int max = intParam(ctx, "max", 1000);
            // Backfill can run for minutes; do not hold the HTTP request open for it.
            Thread.ofVirtual().name("backfill-" + chatId).start(() -> chatService.backfill(chatId, max));
            ctx.status(HttpStatus.ACCEPTED).json(Map.of("chatId", chatId, "max", max));
        });

        app.get("/search", ctx -> {
            String q = ctx.queryParam("q");
            String chat = ctx.queryParam("chatId");
            Long chatId = chat == null || chat.isBlank() ? null : Long.parseLong(chat);
            List<MessageRow> hits = storage.search().query(q, chatId, intParam(ctx, "limit", 50));
            // Hits can span chats, so attachments are joined per chat.
            ctx.json(hits.stream()
                    .collect(Collectors.groupingBy(MessageRow::chatId, LinkedHashMap::new, Collectors.toList()))
                    .entrySet().stream()
                    .flatMap(e -> withMedia(e.getKey(), e.getValue()).stream())
                    .sorted(Comparator.comparingLong(MessageDto::date).reversed())
                    .toList());
        });

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", String.valueOf(e.getMessage()))));

        // Telegram's own refusals are the caller's problem, not a client failure: a
        // disallowed reaction or a chat that forbids forwarding must not look like a 500.
        // Javalin unwraps CompletionException before dispatching, so the handler has to be
        // registered on TelegramError itself; the wrapper is covered too, just in case.
        app.exception(TelegramError.class, (e, ctx) -> reportTelegramError(e, ctx));
        app.exception(CompletionException.class, (e, ctx) -> {
            if (e.getCause() instanceof TelegramError te) {
                reportTelegramError(te, ctx);
            } else {
                log.error("{} {} failed", ctx.method(), ctx.path(), e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", String.valueOf(e.getMessage())));
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            log.error("{} {} failed", ctx.method(), ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", String.valueOf(e.getMessage())));
        });
    }

    private List<MessageDto> withMedia(long chatId, List<MessageRow> messages) {
        List<Long> ids = messages.stream().map(MessageRow::messageId).toList();
        return MessageDto.join(messages,
                storage.media().findByMessages(chatId, ids),
                storage.reactions().findByMessages(chatId, ids));
    }

    /**
     * Streams an attachment. Downloads it from Telegram on first request, then serves it
     * from disk. Uses a seekable stream so byte-range requests work — video players and
     * browsers rely on that to seek without pulling the whole file.
     */
    private void serveMedia(Context ctx) throws IOException {
        long chatId = Long.parseLong(ctx.pathParam("chatId"));
        long messageId = Long.parseLong(ctx.pathParam("messageId"));
        String fileUniqueId = ctx.pathParam("fileUniqueId");

        MediaRow row = storage.media().findByMessage(chatId, messageId).stream()
                .filter(m -> m.fileUniqueId().equals(fileUniqueId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No attachment " + fileUniqueId + " on message " + chatId + "/" + messageId));

        Path file = localCopy(row);
        if (file == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "file is not available"));
            return;
        }

        String mime = row.mimeType() == null || row.mimeType().isBlank()
                ? "application/octet-stream" : row.mimeType();
        ctx.header("Content-Disposition", "inline; filename=\"" + file.getFileName() + "\"");
        ctx.writeSeekableStream(Files.newInputStream(file), mime, Files.size(file));
    }

    /** The file on disk, downloading it once and remembering where it landed. */
    private Path localCopy(MediaRow row) {
        if (row.localPath() != null && Files.isRegularFile(Path.of(row.localPath()))) {
            return Path.of(row.localPath());
        }
        Path downloaded = fileService.fetch(row.chatId(), row.messageId(), row.fileUniqueId()).orElse(null);
        if (downloaded == null || !Files.isRegularFile(downloaded)) {
            return null;
        }
        storage.media().upsertBatch(List.of(new MediaRow(row.chatId(), row.messageId(), row.fileUniqueId(),
                row.fileId(), row.kind(), row.mimeType(), row.size(),
                downloaded.toString(), System.currentTimeMillis())));
        return downloaded;
    }

    private void sendFile(Context ctx, String kind) {
        SendFileRequest req = ctx.bodyAsClass(SendFileRequest.class);
        Path path = Path.of(req.path());
        TdApi.Message sent = switch (kind) {
            case "photo" -> sender.sendPhoto(req.chatId(), path, req.caption(), req.replyToMessageId());
            case "video" -> sender.sendVideo(req.chatId(), path, req.caption(), req.replyToMessageId());
            default -> sender.sendDocument(req.chatId(), path, req.caption(), req.replyToMessageId());
        };
        ctx.json(sentResponse(sent));
    }

    private static Map<String, Object> sentResponse(TdApi.Message sent) {
        return Map.of("chatId", sent.chatId, "messageId", sent.id, "date", sent.date * 1000L);
    }

    private static int intParam(Context ctx, String name, int fallback) {
        String raw = ctx.queryParam(name);
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
    }

    public record SendTextRequest(long chatId, String text, long replyToMessageId) {
    }

    public record SendFileRequest(long chatId, String path, String caption, long replyToMessageId) {
    }

    public record EditRequest(long chatId, long messageId, String text) {
    }

    public record DeleteRequest(long chatId, List<Long> messageIds, boolean revoke) {
    }

    public record ForwardRequest(long fromChatId, long toChatId, List<Long> messageIds,
                                 boolean sendCopy, boolean removeCaption) {
    }

    public record PinRequest(long chatId, long messageId, boolean disableNotification, boolean onlyForSelf) {
    }

    public record AlbumRequest(long chatId, List<MessageSender.AlbumItem> items, long replyToMessageId) {
    }

    public record ReactionRequest(long chatId, long messageId, String emoji, boolean big) {
    }

    public record ReadRequest(List<Long> messageIds) {
    }

    /** Falls back to the newest stored message when the caller does not name ids. */
    private long[] newestMessageIds(long chatId) {
        return storage.messages().findByChat(chatId, 0, 1).stream()
                .mapToLong(MessageRow::messageId)
                .toArray();
    }

    private static void reportTelegramError(TelegramError e, Context ctx) {
        ctx.status(telegramStatus(e.getErrorCode()))
                .json(Map.of("error", e.getErrorMessage(), "telegramCode", e.getErrorCode()));
    }

    /** Maps a Telegram error code onto the HTTP status the caller should see. */
    private static HttpStatus telegramStatus(int telegramCode) {
        return switch (telegramCode) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static List<Long> ids(List<TdApi.Message> messages) {
        return messages.stream().map(m -> m.id).toList();
    }

    private static long[] toArray(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("messageIds must not be empty");
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    public void start() {
        app.start(cfg.host, cfg.port);
        log.info("HTTP API listening on http://{}:{}{}", cfg.host, cfg.port,
                cfg.token.isBlank() ? " (no token check — bind to localhost only!)" : "");
    }

    @Override
    public void close() {
        app.stop();
    }
}
