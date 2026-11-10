-- Schema kept inside the common subset of SQLite and PostgreSQL:
-- BIGINT / TEXT / BOOLEAN only, timestamps as epoch milliseconds UTC,
-- application-assigned primary keys, upserts via ON CONFLICT.

CREATE TABLE IF NOT EXISTS chats (
    chat_id     BIGINT PRIMARY KEY,
    type        TEXT,
    title       TEXT,
    username    TEXT,
    updated_at  BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    chat_id             BIGINT  NOT NULL,
    message_id          BIGINT  NOT NULL,
    sender_id           BIGINT  NOT NULL DEFAULT 0,
    sender_type         TEXT,
    date                BIGINT  NOT NULL,
    edit_date           BIGINT  NOT NULL DEFAULT 0,
    reply_to_message_id BIGINT  NOT NULL DEFAULT 0,
    content_type        TEXT    NOT NULL,
    text                TEXT,
    raw_json            TEXT,
    outgoing            BOOLEAN NOT NULL DEFAULT 0,
    is_deleted          BOOLEAN NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, message_id)
);

-- (chat_id, date) drives the chat timeline; date alone drives retention sweeps.
CREATE INDEX IF NOT EXISTS idx_messages_chat_date ON messages (chat_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_messages_date      ON messages (date);
CREATE INDEX IF NOT EXISTS idx_messages_sender    ON messages (sender_id);

CREATE TABLE IF NOT EXISTS media (
    chat_id        BIGINT NOT NULL,
    message_id     BIGINT NOT NULL,
    file_unique_id TEXT   NOT NULL,
    file_id        BIGINT NOT NULL DEFAULT 0,
    kind           TEXT,
    mime_type      TEXT,
    size           BIGINT NOT NULL DEFAULT 0,
    local_path     TEXT,
    downloaded_at  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, message_id, file_unique_id)
);

CREATE TABLE IF NOT EXISTS sync_state (
    chat_id                 BIGINT PRIMARY KEY,
    oldest_synced_message_id BIGINT  NOT NULL DEFAULT 0,
    backfill_done            BOOLEAN NOT NULL DEFAULT 0
);

-- Full text search. This is the engine-specific part, hidden behind Storage.MessageSearch.
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5 (
    text,
    chat_id    UNINDEXED,
    message_id UNINDEXED
);
