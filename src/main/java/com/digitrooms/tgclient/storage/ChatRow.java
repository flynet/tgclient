package com.digitrooms.tgclient.storage;

public record ChatRow(
        long chatId,
        String type,
        String title,
        String username,
        long updatedAt
) {
}
