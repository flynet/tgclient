-- Reactions arrive as a complete set per message, so a row here is one bucket
-- ("👍 x3") and the set is replaced wholesale whenever Telegram sends an update.

CREATE TABLE IF NOT EXISTS message_reactions (
    chat_id           BIGINT  NOT NULL,
    message_id        BIGINT  NOT NULL,
    reaction          TEXT    NOT NULL,
    total_count       BIGINT  NOT NULL DEFAULT 0,
    is_chosen         BOOLEAN NOT NULL DEFAULT 0,
    recent_sender_ids TEXT,
    updated_at        BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, message_id, reaction)
);

CREATE INDEX IF NOT EXISTS idx_reactions_message ON message_reactions (chat_id, message_id);
