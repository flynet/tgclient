package com.digitrooms.tgclient.telegram;

import it.tdlight.jni.TdApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens TDLib message content into the few fields we store. Kept separate from the
 * persistence layer so the storage adapters never see TdApi types.
 */
public final class MessageContents {

    private MessageContents() {
    }

    /** A downloadable attachment of a message. */
    public record Attachment(String kind, String fileUniqueId, int fileId, String mimeType, long size) {
    }

    public static String contentType(TdApi.MessageContent content) {
        String name = content.getClass().getSimpleName();
        return name.startsWith("Message") ? name.substring("Message".length()).toLowerCase() : name.toLowerCase();
    }

    /** Text or caption, null when the content carries neither. */
    public static String text(TdApi.MessageContent content) {
        TdApi.FormattedText formatted = switch (content) {
            case TdApi.MessageText c -> c.text;
            case TdApi.MessagePhoto c -> c.caption;
            case TdApi.MessageVideo c -> c.caption;
            case TdApi.MessageDocument c -> c.caption;
            case TdApi.MessageAudio c -> c.caption;
            case TdApi.MessageVoiceNote c -> c.caption;
            case TdApi.MessageAnimation c -> c.caption;
            default -> null;
        };
        return formatted == null || formatted.text == null || formatted.text.isEmpty() ? null : formatted.text;
    }

    public static long senderId(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser u -> u.userId;
            case TdApi.MessageSenderChat c -> c.chatId;
            case null, default -> 0L;
        };
    }

    public static String senderType(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser ignored -> "user";
            case TdApi.MessageSenderChat ignored -> "chat";
            case null, default -> null;
        };
    }

    public static long replyToMessageId(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage r) {
            return r.messageId;
        }
        return 0L;
    }

    /** One reaction bucket, flattened. */
    public record Reaction(String reaction, int totalCount, boolean chosen, String recentSenderIds) {
    }

    /** Reaction buckets of a message; empty when nobody reacted. */
    public static List<Reaction> reactions(TdApi.MessageInteractionInfo info) {
        if (info == null || info.reactions == null || info.reactions.reactions == null) {
            return List.of();
        }
        List<Reaction> out = new ArrayList<>();
        for (TdApi.MessageReaction r : info.reactions.reactions) {
            String senders = r.recentSenderIds == null ? null
                    : java.util.Arrays.stream(r.recentSenderIds)
                    .map(MessageContents::senderId)
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            out.add(new Reaction(reactionKey(r.type), r.totalCount, r.isChosen,
                    senders == null || senders.isEmpty() ? null : senders));
        }
        return out;
    }

    /**
     * Stable key for a reaction type. Emoji are stored as-is; the other two kinds get a
     * prefix so they cannot collide with an emoji.
     */
    private static String reactionKey(TdApi.ReactionType type) {
        return switch (type) {
            case TdApi.ReactionTypeEmoji e -> e.emoji;
            case TdApi.ReactionTypeCustomEmoji e -> "custom:" + e.customEmojiId;
            case TdApi.ReactionTypePaid ignored -> "paid";
            case null, default -> "unknown";
        };
    }

    /** Files worth downloading. Photos report their largest size only. */
    public static List<Attachment> attachments(TdApi.MessageContent content) {
        List<Attachment> out = new ArrayList<>();
        switch (content) {
            case TdApi.MessagePhoto c -> {
                TdApi.PhotoSize largest = largestPhotoSize(c.photo);
                if (largest != null) {
                    out.add(of("photo", largest.photo, "image/jpeg"));
                }
            }
            case TdApi.MessageVideo c -> out.add(of("video", c.video.video, c.video.mimeType));
            case TdApi.MessageDocument c -> out.add(of("document", c.document.document, c.document.mimeType));
            case TdApi.MessageAudio c -> out.add(of("audio", c.audio.audio, c.audio.mimeType));
            case TdApi.MessageVoiceNote c -> out.add(of("voice", c.voiceNote.voice, c.voiceNote.mimeType));
            case TdApi.MessageAnimation c -> out.add(of("animation", c.animation.animation, c.animation.mimeType));
            default -> {
            }
        }
        return out;
    }

    private static Attachment of(String kind, TdApi.File file, String mimeType) {
        long size = file.size > 0 ? file.size : file.expectedSize;
        return new Attachment(kind, file.remote != null ? file.remote.uniqueId : String.valueOf(file.id),
                file.id, mimeType, size);
    }

    private static TdApi.PhotoSize largestPhotoSize(TdApi.Photo photo) {
        TdApi.PhotoSize best = null;
        for (TdApi.PhotoSize size : photo.sizes) {
            if (best == null || (long) size.width * size.height > (long) best.width * best.height) {
                best = size;
            }
        }
        return best;
    }
}
