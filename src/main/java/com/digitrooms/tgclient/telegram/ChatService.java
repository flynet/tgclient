package com.digitrooms.tgclient.telegram;

import com.digitrooms.tgclient.handlers.PersistHandler;
import com.digitrooms.tgclient.storage.Storage;
import com.digitrooms.tgclient.storage.WriteQueue;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Chat list loading and history backfill.
 *
 * <p>Live updates only cover what happens from now on; the existing history has to be
 * pulled explicitly, page by page, with a cursor in sync_state so an interrupted
 * backfill resumes instead of starting over.
 */
public final class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int PAGE_SIZE = 100;

    private final SimpleTelegramClient client;
    private final WriteQueue writeQueue;
    private final Storage.SyncStateRepository syncState;

    public ChatService(SimpleTelegramClient client, WriteQueue writeQueue, Storage storage) {
        this.client = client;
        this.writeQueue = writeQueue;
        this.syncState = storage.syncState();
    }

    /** Makes TDLib emit UpdateNewChat for the main chat list, which PersistHandler stores. */
    public CompletableFuture<Void> loadChatList() {
        return client.loadChatListMainAsync();
    }

    /**
     * Pulls history backwards for one chat until it runs out or {@code maxMessages} is
     * reached. Safe to call repeatedly: it continues from the stored cursor.
     */
    public void backfill(long chatId, int maxMessages) {
        if (syncState.isBackfillDone(chatId)) {
            log.debug("Backfill for chat {} is already complete", chatId);
            return;
        }
        long cursor = syncState.oldestSynced(chatId);
        int fetched = 0;

        while (fetched < maxMessages) {
            TdApi.GetChatHistory request = new TdApi.GetChatHistory();
            request.chatId = chatId;
            request.fromMessageId = cursor;
            request.offset = 0;
            request.limit = PAGE_SIZE;
            request.onlyLocal = false;

            TdApi.Messages page = client.send(request).join();
            if (page.messages == null || page.messages.length == 0) {
                syncState.setOldestSynced(chatId, cursor, true);
                log.info("Backfill of chat {} finished, {} messages in this run", chatId, fetched);
                return;
            }

            for (TdApi.Message message : page.messages) {
                writeQueue.submitMessage(PersistHandler.toRow(message));
                // Old messages usually arrive with reactions already attached.
                var reactions = PersistHandler.reactionRows(message.chatId, message.id, message.interactionInfo);
                if (!reactions.isEmpty()) {
                    writeQueue.submitReactions(message.chatId, message.id, reactions);
                }
                cursor = message.id;
                fetched++;
            }
            syncState.setOldestSynced(chatId, cursor, false);
        }
        log.info("Backfill of chat {} paused at message {} after {} messages", chatId, cursor, fetched);
    }
}
