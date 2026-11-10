package com.digitrooms.tgclient.api;

import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.ReactionRow;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A stored message as the HTTP API presents it: the row plus its attachments, each with a
 * ready-made URL so a client never has to assemble one itself.
 */
public record MessageDto(
        long chatId,
        long messageId,
        long senderId,
        String senderType,
        long date,
        long editDate,
        long replyToMessageId,
        String contentType,
        String text,
        String rawJson,
        boolean outgoing,
        boolean deleted,
        List<Attachment> media,
        List<Reaction> reactions
) {

    /**
     * One reaction bucket. {@code chosen} tells whether this account reacted;
     * {@code recentSenderIds} is what Telegram chose to disclose — a few ids at most,
     * and nothing at all in large chats.
     */
    public record Reaction(String emoji, int count, boolean chosen, List<Long> recentSenderIds) {
        static Reaction from(ReactionRow row) {
            List<Long> senders = row.recentSenderIds() == null || row.recentSenderIds().isBlank()
                    ? List.of()
                    : java.util.Arrays.stream(row.recentSenderIds().split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .map(Long::valueOf)
                    .toList();
            return new Reaction(row.reaction(), row.totalCount(), row.chosen(), senders);
        }
    }

    public record Attachment(
            String fileUniqueId,
            String kind,
            String mimeType,
            long size,
            boolean downloaded,
            String url
    ) {
        static Attachment from(MediaRow row) {
            return new Attachment(
                    row.fileUniqueId(),
                    row.kind(),
                    row.mimeType(),
                    row.size(),
                    row.localPath() != null && !row.localPath().isBlank(),
                    "/media/" + row.chatId() + "/" + row.messageId() + "/" + row.fileUniqueId());
        }
    }

    public static MessageDto of(MessageRow row, List<MediaRow> attachments, List<ReactionRow> reactions) {
        return new MessageDto(
                row.chatId(), row.messageId(), row.senderId(), row.senderType(), row.date(),
                row.editDate(), row.replyToMessageId(), row.contentType(), row.text(),
                row.rawJson(), row.outgoing(), row.deleted(),
                attachments.stream().map(Attachment::from).toList(),
                reactions.stream().map(Reaction::from).toList());
    }

    /**
     * Joins a page of messages with their attachments and reactions, each fetched in a
     * single extra query rather than one per message.
     */
    public static List<MessageDto> join(List<MessageRow> messages, List<MediaRow> media,
                                        List<ReactionRow> reactions) {
        Map<Long, List<MediaRow>> mediaByMessage = media.stream()
                .collect(Collectors.groupingBy(MediaRow::messageId));
        Map<Long, List<ReactionRow>> reactionsByMessage = reactions.stream()
                .collect(Collectors.groupingBy(ReactionRow::messageId));
        return messages.stream()
                .map(m -> of(m,
                        mediaByMessage.getOrDefault(m.messageId(), List.of()),
                        reactionsByMessage.getOrDefault(m.messageId(), List.of())))
                .toList();
    }
}
