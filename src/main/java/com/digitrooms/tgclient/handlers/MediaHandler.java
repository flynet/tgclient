package com.digitrooms.tgclient.handlers;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.WriteQueue;
import com.digitrooms.tgclient.telegram.FileService;
import com.digitrooms.tgclient.telegram.MessageContents;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Records attachments and, when enabled, downloads them. Downloading happens on the
 * background pool with a concurrency cap — it must never stall the ordered dispatcher.
 */
public final class MediaHandler implements Consumer<TdApi.Update> {

    private static final Logger log = LoggerFactory.getLogger(MediaHandler.class);

    private final WriteQueue writeQueue;
    private final FileService fileService;
    private final Executor background;
    private final AppConfig.Media cfg;
    private final Semaphore downloadSlots;

    public MediaHandler(WriteQueue writeQueue, FileService fileService, Executor background, AppConfig.Media cfg) {
        this.writeQueue = writeQueue;
        this.fileService = fileService;
        this.background = background;
        this.cfg = cfg;
        this.downloadSlots = new Semaphore(Math.max(1, cfg.workers));
    }

    @Override
    public void accept(TdApi.Update update) {
        switch (update) {
            case TdApi.UpdateNewMessage u -> record(u.message);

            // An outgoing message is first seen under a temporary id; once the server
            // confirms it, the attachments have to be re-recorded under the real id and
            // the temporary rows dropped, or they point at a message that no longer exists.
            case TdApi.UpdateMessageSendSucceeded u -> {
                writeQueue.submitMediaPurge(u.message.chatId, u.oldMessageId);
                record(u.message);
            }
            case TdApi.UpdateMessageSendFailed u ->
                    writeQueue.submitMediaPurge(u.message.chatId, u.oldMessageId);
            default -> {
            }
        }
    }

    private void record(TdApi.Message message) {
        List<MessageContents.Attachment> attachments = MessageContents.attachments(message.content);
        for (MessageContents.Attachment a : attachments) {
            writeQueue.submitMedia(new MediaRow(message.chatId, message.id, a.fileUniqueId(), a.fileId(),
                    a.kind(), a.mimeType(), a.size(), null, 0));

            // Never download our own attachments: the file is already on this machine,
            // and outgoing messages are recorded twice (temporary id, then real one).
            if (cfg.autoDownload && !message.isOutgoing && withinSizeLimit(a)) {
                scheduleDownload(message.chatId, message.id, a);
            }
        }
    }

    private boolean withinSizeLimit(MessageContents.Attachment a) {
        return cfg.maxFileSize <= 0 || a.size() <= cfg.maxFileSize;
    }

    private void scheduleDownload(long chatId, long messageId, MessageContents.Attachment a) {
        background.execute(() -> {
            try {
                downloadSlots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            fileService.download(a.fileId()).whenComplete((path, error) -> {
                downloadSlots.release();
                if (error != null) {
                    log.warn("Download of {} from {}/{} failed", a.kind(), chatId, messageId, error);
                    return;
                }
                if (path != null) {
                    writeQueue.submitMedia(new MediaRow(chatId, messageId, a.fileUniqueId(), a.fileId(),
                            a.kind(), a.mimeType(), a.size(), path, System.currentTimeMillis()));
                }
            });
        });
    }
}
