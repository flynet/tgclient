package com.digitrooms.tgclient.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads files through TDLib. TDLib already stores them under
 * telegram.filesDir with de-duplication, so nothing is copied — only the resulting
 * path is recorded in the media table.
 */
public final class FileService {

    private static final int PRIORITY = 16; // 1..32, higher is sooner

    private final SimpleTelegramClient client;

    public FileService(SimpleTelegramClient client) {
        this.client = client;
    }

    /**
     * Resolves an attachment to a file on disk, downloading it from Telegram if needed.
     *
     * <p>The file id is re-read from the message rather than taken from the database:
     * TDLib's numeric file ids are only valid within a session, while {@code fileUniqueId}
     * is stable, so a stored id can go stale after a restart.
     *
     * @return the local path, or empty if the message has no such attachment
     */
    public Optional<Path> fetch(long chatId, long messageId, String fileUniqueId) {
        TdApi.GetMessage request = new TdApi.GetMessage();
        request.chatId = chatId;
        request.messageId = messageId;
        TdApi.Message message = client.send(request).join();

        return MessageContents.attachments(message.content).stream()
                .filter(a -> a.fileUniqueId().equals(fileUniqueId))
                .findFirst()
                .map(a -> download(a.fileId()).join())
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of);
    }

    /** Completes with the local path once the file is fully on disk. */
    public CompletableFuture<String> download(int fileId) {
        TdApi.DownloadFile request = new TdApi.DownloadFile();
        request.fileId = fileId;
        request.priority = PRIORITY;
        request.offset = 0;
        request.limit = 0;
        request.synchronous = true; // resolve only when the download has finished
        return client.send(request).thenApply(file ->
                file.local != null && file.local.isDownloadingCompleted ? file.local.path : null);
    }
}
