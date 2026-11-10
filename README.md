# tgclient — automated Telegram client (userbot)

A real Telegram **client**, not a Bot API bot: it logs in as a user account, so it sees
private conversations, private and public groups and channels, and it can send text,
photos, videos and documents itself.

Built on [TDLight Java](https://github.com/tdlight-team/tdlight-java), a wrapper around
Telegram's official TDLib.

## Documentation

| Document | Contents |
|---|---|
| **[docs/SETUP.md](docs/SETUP.md)** | Full cycle: prerequisites → Telegram credentials → build → config → login → verification → running as a service → troubleshooting |
| **[docs/API.md](docs/API.md)** | HTTP endpoint reference with schemas and examples |
| **[openapi.yaml](openapi.yaml)** | Machine-readable OpenAPI 3.0 spec |
| This file | Architecture and design decisions |

> Automating a user account is governed by Telegram's ToS. Mass messaging or scraping
> other people's chats gets the phone number banned. This client is built for your own
> account and your own chats, with outbound throttling and FLOOD_WAIT handling.

## Layout

```
telegram/   TDLib client, login, sending, file downloads, history backfill
core/       UpdateBus (ordered dispatch), RateLimiter
handlers/   PersistHandler (updates -> rows), MediaHandler
storage/    ports: MessageRepository, ChatRepository, MediaRepository,
            SyncStateRepository, MessageSearch  +  WriteQueue
  sqlite/   the current adapter
api/        Javalin HTTP front end
```

Nothing above `storage/` knows which database is underneath. Today it is embedded SQLite
in WAL mode; moving to PostgreSQL means adding `storage/postgres/*` (the migration SQL is
already in `src/main/resources/db/migration/postgres`) and flipping `storage.driver`.

## Quick start

```bash
cp config.example.yaml config.yaml    # fill in apiId / apiHash from https://my.telegram.org
mvn clean package
java -jar target/tgclient.jar config.yaml
```

The first run authenticates — by QR code, phone number or console prompt, depending on
`telegram.loginMethod`. After that the session in `td-data/session` keeps the client
logged in; treat that directory like a password.

Note that QR login removes the *code* step, not the *password* step: a cloud (2FA)
password is still asked on the console, because scanning proves you hold a logged-in
device, not that you know the password.

Step-by-step, including 2FA, verification and deployment: **[docs/SETUP.md](docs/SETUP.md)**.

## HTTP API

The client's own control API — unrelated to Telegram's API. All endpoints except
`/health` require `X-Api-Token` when `api.token` is set.

| Method | Path | Purpose |
|---|---|---|
| GET  | `/health` | liveness + stored message count |
| POST | `/messages/text` | send text |
| POST | `/messages/photo` · `/video` · `/document` | send a file from the client's filesystem |
| POST | `/messages/album` | send 2–10 files as one grouped message |
| POST | `/messages/edit` · `/delete` · `/forward` · `/copy` · `/pin` · `/unpin` | operate on existing messages |
| POST | `/messages/react` · `/unreact` | emoji reactions |
| POST | `/chats/{chatId}/read` | clear the unread badge |
| GET  | `/chats` | known chats |
| GET  | `/chats/{chatId}/messages` | stored messages with attachment metadata, paginated |
| GET  | `/media/{chatId}/{messageId}/{fileUniqueId}` | download an attachment's bytes (Range-capable) |
| POST | `/chats/{chatId}/backfill` | pull history in the background |
| GET  | `/search` | full-text search |

```bash
curl --noproxy '*' -X POST localhost:8080/messages/text \
     -H 'Content-Type: application/json' \
     -d '{"chatId":<your user id>,"text":"hello from tgclient","replyToMessageId":0}'
```

Full reference with request/response schemas: **[docs/API.md](docs/API.md)**.
Machine-readable spec for tooling and agents: **[openapi.yaml](openapi.yaml)**.

## Design notes worth knowing

- **Ordered dispatch.** Updates are handled on a single thread, because an edit must not
  overtake the insert it edits. Downloads and webhooks run on a background pool.
- **Batched writes.** Handlers enqueue into a bounded `WriteQueue`; one writer thread
  commits up to `writeBatchSize` rows per transaction. On SQLite, committing per message
  means an fsync per message.
- **Backpressure, not data loss.** A full queue blocks the producer and logs a warning.
- **Idempotent.** `(chat_id, message_id)` is the primary key and everything is an upsert,
  so reconnects and repeated backfills never duplicate rows.
- **Portable schema.** Only `BIGINT`/`TEXT`/`BOOLEAN`, epoch-millisecond timestamps,
  application-assigned keys, upserts via `ON CONFLICT` — the common subset of SQLite and
  PostgreSQL. Full-text search is the one engine-specific part and lives behind
  `Storage.MessageSearch` (FTS5 today, `tsvector` + GIN later).
- **Media on the filesystem.** The database stores paths and metadata only.

## Tests

```bash
mvn test
```

Covers the SQLite repositories, FTS search (including raw FTS5 operators in user input),
the write queue under 5 000 rows, and the rate limiter's FLOOD_WAIT parsing.
