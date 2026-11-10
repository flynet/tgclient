package com.digitrooms.tgclient.telegram;

import com.digitrooms.tgclient.handlers.PersistHandler;
import com.digitrooms.tgclient.storage.ReactionRow;
import com.digitrooms.tgclient.storage.WriteQueue;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Keeps reaction data current.
 *
 * <p>TDLib only pushes {@code updateMessageInteractionInfo} — reactions, view counts — for
 * chats the client has explicitly opened. Two mechanisms cover that:
 * <ul>
 *   <li>after this account reacts, the message is re-read at once, so the caller sees the
 *       change on the very next request;</li>
 *   <li>optionally ({@code telegram.trackInteractions}) chats are opened at startup, which
 *       makes Telegram stream other people's reactions as they happen.</li>
 * </ul>
 */
public final class InteractionService {

    private static final Logger log = LoggerFactory.getLogger(InteractionService.class);

    private final SimpleTelegramClient client;
    private final WriteQueue writeQueue;

    public InteractionService(SimpleTelegramClient client, WriteQueue writeQueue) {
        this.client = client;
        this.writeQueue = writeQueue;
    }

    /** Re-reads one message and stores its current reaction set. */
    public void refreshReactions(long chatId, long messageId) {
        TdApi.GetMessage request = new TdApi.GetMessage();
        request.chatId = chatId;
        request.messageId = messageId;
        TdApi.Message message = client.send(request).join();

        List<ReactionRow> rows = PersistHandler.reactionRows(chatId, messageId, message.interactionInfo);
        writeQueue.submitReactions(chatId, messageId, rows);
    }

    /**
     * Subscribes to interaction updates for the given chats. Telegram then streams
     * reactions and view counts for them, at the cost of more update traffic.
     */
    public void trackChats(List<Long> chatIds) {
        int opened = 0;
        for (long chatId : chatIds) {
            try {
                client.send(new TdApi.OpenChat(chatId)).join();
                opened++;
            } catch (RuntimeException e) {
                log.debug("Could not open chat {} for interaction tracking", chatId, e);
            }
        }
        log.info("Tracking live reactions and view counts for {} of {} chats", opened, chatIds.size());
    }
}
