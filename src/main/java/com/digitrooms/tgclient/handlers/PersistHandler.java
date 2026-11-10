package com.digitrooms.tgclient.handlers;

import com.digitrooms.tgclient.storage.ChatRow;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.ReactionRow;
import com.digitrooms.tgclient.storage.WriteQueue;
import com.digitrooms.tgclient.telegram.MessageContents;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Turns the update stream into rows. Runs on the ordered dispatch thread and only ever
 * enqueues — the actual writing happens on the WriteQueue's writer thread.
 */
public final class PersistHandler implements Consumer<TdApi.Update> {

    private static final Logger log = LoggerFactory.getLogger(PersistHandler.class);

    private final WriteQueue writeQueue;
    private final SimpleTelegramClient client;
    private final Executor background;

    public PersistHandler(WriteQueue writeQueue, SimpleTelegramClient client, Executor background) {
        this.writeQueue = writeQueue;
        this.client = client;
        this.background = background;
    }

    @Override
    public void accept(TdApi.Update update) {
        switch (update) {
            case TdApi.UpdateNewMessage u -> {
                writeQueue.submitMessage(toRow(u.message));
                submitReactions(u.message.chatId, u.message.id, u.message.interactionInfo);
            }

            // An outgoing message first appears with a temporary id, then the server
            // confirms it under its real id. Without dropping the temporary row the same
            // message is stored twice.
            case TdApi.UpdateMessageSendSucceeded u -> {
                writeQueue.submitPurge(u.message.chatId, u.oldMessageId);
                writeQueue.submitMessage(toRow(u.message));
            }
            case TdApi.UpdateMessageSendFailed u -> writeQueue.submitPurge(u.message.chatId, u.oldMessageId);

            // An edit arrives as content-only; TDLib does not resend the whole message,
            // so the current version is re-read off the round trip and upserted.
            case TdApi.UpdateMessageContent u -> refresh(u.chatId, u.messageId);
            case TdApi.UpdateMessageEdited u -> refresh(u.chatId, u.messageId);

            case TdApi.UpdateDeleteMessages u -> {
                if (u.isPermanent) {
                    for (long messageId : u.messageIds) {
                        writeQueue.submitDelete(u.chatId, messageId);
                    }
                }
            }
            // Reactions, view counts and forward counts all arrive here, as a complete
            // replacement set rather than a delta.
            case TdApi.UpdateMessageInteractionInfo u ->
                    submitReactions(u.chatId, u.messageId, u.interactionInfo);

            case TdApi.UpdateNewChat u -> writeQueue.submitChat(toRow(u.chat));
            case TdApi.UpdateChatTitle u ->
                    writeQueue.submitChat(new ChatRow(u.chatId, null, u.title, null, System.currentTimeMillis()));
            default -> {
            }
        }
    }

    private void refresh(long chatId, long messageId) {
        background.execute(() -> {
            TdApi.GetMessage request = new TdApi.GetMessage();
            request.chatId = chatId;
            request.messageId = messageId;
            client.send(request).whenComplete((message, error) -> {
                if (error != null) {
                    log.debug("Could not re-read edited message {}/{}", chatId, messageId, error);
                } else {
                    writeQueue.submitMessage(toRow(message));
                }
            });
        });
    }

    private void submitReactions(long chatId, long messageId, TdApi.MessageInteractionInfo info) {
        List<ReactionRow> rows = reactionRows(chatId, messageId, info);
        if (rows.isEmpty() && info == null) {
            return; // nothing known either way — do not wipe what is already stored
        }
        // An empty list from a real update is meaningful: the last reaction was removed.
        writeQueue.submitReactions(chatId, messageId, rows);
    }

    /** Reaction rows of a message; also used by the history backfill. */
    public static List<ReactionRow> reactionRows(long chatId, long messageId,
                                                 TdApi.MessageInteractionInfo info) {
        long now = System.currentTimeMillis();
        return MessageContents.reactions(info).stream()
                .map(r -> new ReactionRow(chatId, messageId, r.reaction(), r.totalCount(),
                        r.chosen(), r.recentSenderIds(), now))
                .toList();
    }

    public static MessageRow toRow(TdApi.Message message) {
        return new MessageRow(
                message.chatId,
                message.id,
                MessageContents.senderId(message.senderId),
                MessageContents.senderType(message.senderId),
                message.date * 1000L,
                message.editDate * 1000L,
                MessageContents.replyToMessageId(message),
                MessageContents.contentType(message.content),
                MessageContents.text(message.content),
                String.valueOf(message.content),
                message.isOutgoing,
                false);
    }

    private static ChatRow toRow(TdApi.Chat chat) {
        String type = chat.type == null ? null
                : chat.type.getClass().getSimpleName().replace("ChatType", "").toLowerCase();
        return new ChatRow(chat.id, type, chat.title, null, System.currentTimeMillis());
    }
}
