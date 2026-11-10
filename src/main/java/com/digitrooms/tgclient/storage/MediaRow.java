package com.digitrooms.tgclient.storage;

public record MediaRow(
        long chatId,
        long messageId,
        String fileUniqueId,
        int fileId,
        String kind,
        String mimeType,
        long size,
        String localPath,
        long downloadedAt
) {
}
