package com.digitrooms.tgclient.telegram;

import com.digitrooms.tgclient.core.RateLimiter;
import com.digitrooms.tgclient.core.SentMessageTracker;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Outgoing messages of every type. Each send goes through the {@link RateLimiter} and
 * retries once after a FLOOD_WAIT instead of surfacing the error.
 */
public final class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);

    private static final int CONFIRM_TIMEOUT_SECONDS = 60;

    private final SimpleTelegramClient client;
    private final RateLimiter rateLimiter;
    private final SentMessageTracker tracker;

    public MessageSender(SimpleTelegramClient client, RateLimiter rateLimiter, SentMessageTracker tracker) {
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.tracker = tracker;
    }

    public TdApi.Message sendText(long chatId, String text, long replyToMessageId) {
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        return send(chatId, content, replyToMessageId);
    }

    public TdApi.Message sendPhoto(long chatId, Path file, String caption, long replyToMessageId) {
        requireFile(file);
        TdApi.InputPhoto photo = new TdApi.InputPhoto();
        photo.photo = new TdApi.InputFileLocal(file.toAbsolutePath().toString());

        TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto();
        content.photo = photo;
        content.caption = caption == null ? null : new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
        return send(chatId, content, replyToMessageId);
    }

    public TdApi.Message sendVideo(long chatId, Path file, String caption, long replyToMessageId) {
        requireFile(file);
        TdApi.InputVideo video = new TdApi.InputVideo();
        video.video = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
        video.supportsStreaming = true;

        TdApi.InputMessageVideo content = new TdApi.InputMessageVideo();
        content.video = video;
        content.caption = caption == null ? null : new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
        return send(chatId, content, replyToMessageId);
    }

    public TdApi.Message sendDocument(long chatId, Path file, String caption, long replyToMessageId) {
        requireFile(file);
        TdApi.InputDocument document = new TdApi.InputDocument();
        document.document = new TdApi.InputFileLocal(file.toAbsolutePath().toString());

        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument();
        content.document = document;
        content.caption = caption == null ? null : new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
        return send(chatId, content, replyToMessageId);
    }

    /**
     * Edits a message's text, or its caption when it carries media — Telegram treats
     * those as different operations, so the current content decides which one to use.
     */
    public void edit(long chatId, long messageId, String text) {
        TdApi.GetMessage lookup = new TdApi.GetMessage();
        lookup.chatId = chatId;
        lookup.messageId = messageId;
        TdApi.Message message = client.send(lookup).join();
        TdApi.FormattedText formatted = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);

        if (message.content instanceof TdApi.MessageText) {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = formatted;

            TdApi.EditMessageText request = new TdApi.EditMessageText();
            request.chatId = chatId;
            request.messageId = messageId;
            request.inputMessageContent = content;
            withRetry(chatId, () -> client.send(request));
        } else {
            TdApi.EditMessageCaption request = new TdApi.EditMessageCaption();
            request.chatId = chatId;
            request.messageId = messageId;
            request.caption = formatted;
            withRetry(chatId, () -> client.send(request));
        }
    }

    /**
     * @param sendCopy     send without the "forwarded from" header, as if it were your own
     * @param removeCaption drop media captions; only meaningful together with sendCopy
     */
    public List<TdApi.Message> forward(long toChatId, long fromChatId, long[] messageIds,
                                      boolean sendCopy, boolean removeCaption) {
        TdApi.ForwardMessages request = new TdApi.ForwardMessages();
        request.chatId = toChatId;
        request.fromChatId = fromChatId;
        request.messageIds = messageIds;
        request.sendCopy = sendCopy;
        request.removeCaption = removeCaption;

        TdApi.Messages provisional = withRetry(toChatId, () -> client.send(request));
        // Like sendMessageAlbum, forwardMessages answers with temporary ids.
        return Arrays.stream(provisional.messages)
                .filter(Objects::nonNull)
                .map(this::confirm)
                .toList();
    }

    /**
     * @param onlyForSelf pin only in your own view; in a group without pin rights this is
     *                    the only variant that will succeed
     */
    public void pin(long chatId, long messageId, boolean disableNotification, boolean onlyForSelf) {
        TdApi.PinChatMessage request = new TdApi.PinChatMessage();
        request.chatId = chatId;
        request.messageId = messageId;
        request.disableNotification = disableNotification;
        request.onlyForSelf = onlyForSelf;
        withRetry(chatId, () -> client.send(request));
    }

    public void unpin(long chatId, long messageId) {
        TdApi.UnpinChatMessage request = new TdApi.UnpinChatMessage();
        request.chatId = chatId;
        request.messageId = messageId;
        withRetry(chatId, () -> client.send(request));
    }

    /**
     * @param forEveryone delete for all participants rather than only your own copy;
     *                    Telegram limits how long this stays possible
     */
    public void deleteMessages(long chatId, long[] messageIds, boolean forEveryone) {
        TdApi.DeleteMessages request = new TdApi.DeleteMessages();
        request.chatId = chatId;
        request.messageIds = messageIds;
        request.revoke = forEveryone;
        withRetry(chatId, () -> client.send(request));
    }

    /**
     * Sends several media files as one album (a single grouped message in the chat).
     *
     * <p>Telegram accepts 2..10 items and only photos and videos may be mixed; documents
     * and audio must form an album of their own.
     */
    public List<TdApi.Message> sendAlbum(long chatId, List<AlbumItem> items, long replyToMessageId) {
        if (items.size() < 2 || items.size() > 10) {
            throw new IllegalArgumentException("An album takes between 2 and 10 items, got " + items.size());
        }
        TdApi.InputMessageContent[] contents = items.stream()
                .map(MessageSender::albumContent)
                .toArray(TdApi.InputMessageContent[]::new);

        TdApi.SendMessageAlbum request = new TdApi.SendMessageAlbum();
        request.chatId = chatId;
        request.inputMessageContents = contents;
        if (replyToMessageId > 0) {
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.messageId = replyToMessageId;
            request.replyTo = replyTo;
        }

        TdApi.Messages provisional = withRetry(chatId, () -> client.send(request));

        // sendMessageAlbum has no "wait for confirmation" mode, so the ids it returns are
        // temporary. Swap each for the confirmed one before handing them to the caller.
        return Arrays.stream(provisional.messages)
                .filter(Objects::nonNull)
                .map(this::confirm)
                .toList();
    }

    private TdApi.Message confirm(TdApi.Message provisional) {
        try {
            return tracker.await(provisional.id).get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return provisional;
        } catch (TimeoutException | ExecutionException e) {
            log.warn("No send confirmation for message {} in chat {}, returning the provisional id",
                    provisional.id, provisional.chatId);
            return provisional;
        }
    }

    private static TdApi.InputMessageContent albumContent(AlbumItem item) {
        Path file = Path.of(item.path());
        requireFile(file);
        TdApi.InputFileLocal input = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
        TdApi.FormattedText caption = item.caption() == null
                ? null : new TdApi.FormattedText(item.caption(), new TdApi.TextEntity[0]);

        return switch (item.type() == null ? "photo" : item.type().toLowerCase()) {
            case "video" -> {
                TdApi.InputVideo video = new TdApi.InputVideo();
                video.video = input;
                video.supportsStreaming = true;
                TdApi.InputMessageVideo content = new TdApi.InputMessageVideo();
                content.video = video;
                content.caption = caption;
                yield content;
            }
            case "document" -> {
                TdApi.InputDocument document = new TdApi.InputDocument();
                document.document = input;
                TdApi.InputMessageDocument content = new TdApi.InputMessageDocument();
                content.document = document;
                content.caption = caption;
                yield content;
            }
            default -> {
                TdApi.InputPhoto photo = new TdApi.InputPhoto();
                photo.photo = input;
                TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto();
                content.photo = photo;
                content.caption = caption;
                yield content;
            }
        };
    }

    /** One element of an album: {@code type} is photo, video or document. */
    public record AlbumItem(String type, String path, String caption) {
    }

    /** Adds one of your reactions to a message. {@code big} plays the oversized animation. */
    public void react(long chatId, long messageId, String emoji, boolean big) {
        TdApi.AddMessageReaction request = new TdApi.AddMessageReaction();
        request.chatId = chatId;
        request.messageId = messageId;
        request.reactionType = new TdApi.ReactionTypeEmoji(emoji);
        request.isBig = big;
        request.updateRecentReactions = true;
        withRetry(chatId, () -> client.send(request));
    }

    public void unreact(long chatId, long messageId, String emoji) {
        TdApi.RemoveMessageReaction request = new TdApi.RemoveMessageReaction();
        request.chatId = chatId;
        request.messageId = messageId;
        request.reactionType = new TdApi.ReactionTypeEmoji(emoji);
        withRetry(chatId, () -> client.send(request));
    }

    /**
     * Marks messages as read, clearing the unread badge for the account.
     *
     * <p>{@code forceRead} is set, so they count as read even though no chat is actually
     * open — which is the point for an automated client.
     */
    public void markRead(long chatId, long[] messageIds) {
        TdApi.ViewMessages request = new TdApi.ViewMessages();
        request.chatId = chatId;
        request.messageIds = messageIds;
        request.source = new TdApi.MessageSourceChatHistory();
        request.forceRead = true;
        withRetry(chatId, () -> client.send(request));
    }

    public TdApi.Message send(long chatId, TdApi.InputMessageContent content, long replyToMessageId) {
        TdApi.SendMessage request = new TdApi.SendMessage();
        request.chatId = chatId;
        request.inputMessageContent = content;
        if (replyToMessageId > 0) {
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.messageId = replyToMessageId;
            request.replyTo = replyTo;
        }

        // Waits for the server to confirm the message. A plain send() would return
        // immediately with TDLib's temporary message id, which is replaced once the server
        // answers — the caller would be left holding an id that matches nothing.
        return withRetry(chatId, () -> client.sendMessage(request, true));
    }

    /**
     * Runs a Telegram call under the rate limiter, retrying once after a FLOOD_WAIT.
     * Every operation that touches a chat goes through here — edits and forwards count
     * against the same limits as sending.
     */
    private <T> T withRetry(long chatId, Supplier<CompletableFuture<T>> call) {
        rateLimiter.acquire(chatId);
        try {
            return call.get().join();
        } catch (CompletionException e) {
            int wait = floodWaitSeconds(e);
            if (wait < 0) {
                throw e;
            }
            rateLimiter.applyFloodWait(chatId, wait);
            rateLimiter.acquire(chatId);
            log.info("Retrying operation on chat {} after a {}s flood wait", chatId, wait);
            return call.get().join();
        }
    }

    /** Seconds to wait if this failure is a 429, otherwise -1. */
    private static int floodWaitSeconds(Throwable e) {
        Throwable cause = e instanceof CompletionException ? e.getCause() : e;
        if (cause instanceof TelegramError te && te.getErrorCode() == 429) {
            int seconds = RateLimiter.parseFloodWaitSeconds(te.getErrorMessage());
            return seconds > 0 ? seconds : 5;
        }
        return -1;
    }

    private static void requireFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found: " + file.toAbsolutePath());
        }
    }
}
