-- Not used yet: storage.driver is 'sqlite'. Kept in sync with the SQLite migration so the
-- move to a real server is "add the adapter + run the migrator", not a redesign.
-- When the volume demands it, messages can become a partitioned table
-- (PARTITION BY HASH (chat_id) or RANGE (date)) without touching any query.

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
    outgoing            BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (chat_id, message_id)
);

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
    chat_id                  BIGINT PRIMARY KEY,
    oldest_synced_message_id BIGINT  NOT NULL DEFAULT 0,
    backfill_done            BOOLEAN NOT NULL DEFAULT FALSE
);

-- Full text search, PostgreSQL flavour (the SQLite build uses an FTS5 virtual table).
ALTER TABLE messages ADD COLUMN IF NOT EXISTS text_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(text, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_messages_tsv ON messages USING GIN (text_tsv);
