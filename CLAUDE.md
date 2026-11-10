# tgclient — orientation for agents

An automated Telegram **client** (userbot) on TDLight Java + Maven. It logs in as a user
account, so it sees private chats and private groups — things the Bot API cannot reach —
persists messages locally, and sends text/photo/video/document itself.

Java 21, Maven, no Spring: the object graph is assembled by hand in `App.main`, which is
the best place to see startup and shutdown order.

## Where things live

| Path | Responsibility |
|---|---|
| `App.java` | entry point, wiring, graceful shutdown |
| `config/AppConfig.java` | YAML + env config, validation |
| `telegram/` | TDLib client, login, `MessageSender`, `FileService`, `ChatService` (backfill), `MessageContents` (TdApi → flat fields) |
| `core/UpdateBus.java` | single ordered dispatch thread + background pool |
| `core/RateLimiter.java` | outgoing throttle and FLOOD_WAIT penalties |
| `handlers/` | `PersistHandler`, `MediaHandler` — consumers of the update stream |
| `storage/` | ports (interfaces) + `WriteQueue`; `storage/sqlite/` is the only adapter today |
| `api/HttpServer.java` | Javalin routes |
| `docs/`, `openapi.yaml` | user-facing documentation and the API contract |

## Invariants — breaking these causes real bugs

- **Storage is behind ports.** Nothing above `storage/` may import a JDBC or SQLite type.
  The PostgreSQL migration depends on this. SQL lives in the adapter.
- **Schema stays portable.** Only `BIGINT` / `TEXT` / `BOOLEAN`, epoch-millisecond
  timestamps, application-assigned keys, upserts via `ON CONFLICT`. No `INSERT OR REPLACE`,
  no `rowid`, no `AUTOINCREMENT`. Engine-specific search lives behind `Storage.MessageSearch`.
- **Update handling is single-threaded and must stay cheap.** Handlers run on the ordered
  dispatcher; an edit must not overtake its insert. Anything slow goes to
  `UpdateBus.background()`.
- **Handlers never write to the database directly.** They enqueue into `WriteQueue`, which
  batches into transactions. Committing per message means an fsync per message on SQLite.
- **Backpressure, never data loss.** A full write queue blocks the producer.
- **Outgoing messages have temporary ids.** TDLib returns a provisional `messageId` and
  replaces it via `UpdateMessageSendSucceeded`. `MessageSender` therefore uses
  `sendMessage(request, true)` to wait for confirmation, and `PersistHandler` purges the
  temporary row. Changing either reintroduces duplicate rows and ids that match nothing.
- **Media bytes stay on the filesystem.** The database stores paths and metadata only.
- **Secrets never enter the repo.** `config.yaml`, `td-data/` and `data/` are gitignored;
  `td-data/session` is equivalent to the account password.

## Build and test

```bash
mvn clean package     # target/tgclient.jar, fat jar with the TDLib native library
mvn test              # no Telegram account needed
```

Tests cover storage, FTS (including raw FTS5 operators in user input), the write queue,
the rate limiter, and QR rendering — the last one guards the fact that `tdlight-java` calls
zxing without depending on it.

Verifying against a live account requires credentials from my.telegram.org; see
`docs/SETUP.md`. When calling the local API from a shell behind a corporate proxy, pass
`--noproxy '*'` or localhost requests are intercepted.

## Dependency notes

TDLight is **not on Maven Central** — `pom.xml` declares the `mvn.mchv.eu` repository. The
native library is chosen by an OS-activated profile (`natives-windows` / `natives-linux`);
a mismatch fails at startup with `UnsatisfiedLinkError`.

## Not built yet

`RuleHandler` — declarative rules for auto-replies, forwarding and webhooks — and the
PostgreSQL adapter (`storage/postgres/`, whose migration SQL already exists).
