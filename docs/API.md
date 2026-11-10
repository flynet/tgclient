# HTTP API reference

The client exposes a local HTTP API (Javalin). This is **your** API for driving the
client — it is unrelated to Telegram's own API; `telegram.apiId` / `apiHash` in the config
are Telegram application credentials and have nothing to do with `api.token` here.

- Base URL: `http://{api.host}:{api.port}`, default `http://127.0.0.1:8080`
- Content type: JSON in, JSON out (`application/json`)
- Machine-readable spec: [openapi.yaml](../openapi.yaml)

Reads are served from the local database, not from Telegram: they are fast and consume no
rate limit, but they only contain what the client has already collected (live updates
since it started, plus whatever a backfill has pulled).

## Authentication

Every endpoint except `GET /health` requires the header:

```
X-Api-Token: <api.token from config.yaml>
```

If `api.token` is empty the check is disabled entirely — acceptable only while bound to
`127.0.0.1`. A wrong or missing token returns `401 {"error":"invalid token"}`.

## Conventions

- `chatId` is Telegram's chat id: positive for private chats (it equals the user id),
  negative for groups, supergroups and channels. Your own id — from the startup log — is
  the chat id of Saved Messages.
- All timestamps are **epoch milliseconds, UTC**.
- `replyToMessageId: 0` means "not a reply" — set it to a `messageId` to reply.

## Operations on a message

| Operation | How |
|---|---|
| Reply | `replyToMessageId` on any `/messages/*` send |
| Edit | `POST /messages/edit` |
| Delete | `POST /messages/delete` |
| Forward | `POST /messages/forward` |
| Copy without attribution | `POST /messages/copy` |
| Pin / unpin | `POST /messages/pin`, `POST /messages/unpin` |
| React / remove reaction | `POST /messages/react`, `POST /messages/unreact` |
| Read reactions | the `reactions` field of any stored message |
| Mark as read | `POST /chats/{chatId}/read` |
| Send as album | `POST /messages/album` |
| Download attachment | `GET /media/{chatId}/{messageId}/{fileUniqueId}` |

All of them go through the same rate limiter as sending: edits and forwards count against
Telegram's limits too, and a `FLOOD_WAIT` is retried once automatically.

---

## `GET /health`

Liveness plus the number of stored messages. The only unauthenticated endpoint.

```bash
curl --noproxy '*' http://127.0.0.1:8080/health
```

```json
{"status": "ok", "messages": 1843}
```

---

## `POST /messages/text`

```json
{
  "chatId": 1234567890,
  "text": "hello",
  "replyToMessageId": 0
}
```

Response — the confirmed message:

```json
{"chatId": 1234567890, "messageId": 298844160, "date": 1789643958000}
```

The call waits for the server to acknowledge the message, so the returned `messageId` is
the final one and matches what is stored in the database. (TDLib assigns a temporary id
first; returning that would hand you an id that matches nothing.) Expect tens to hundreds
of milliseconds.

---

## `POST /messages/photo`, `/messages/video`, `/messages/document`

```json
{
  "chatId": 1234567890,
  "path": "C:/media/photo.jpg",
  "caption": "optional",
  "replyToMessageId": 0
}
```

Same response shape as `/messages/text`.

**`path` is a path on the machine running the client**, not an upload — the body is JSON,
not multipart. To send from elsewhere, copy the file to that machine first.

A missing file returns `400 {"error":"File not found: …"}`.

Choose the endpoint by how the recipient should see it: `/photo` and `/video` are
displayed inline and re-encoded by Telegram; `/document` preserves the file exactly.

---

## `POST /messages/album`

Sends several files as one grouped message.

```json
{
  "chatId": 1234567890,
  "items": [
    {"type": "photo", "path": "C:/media/a.jpg", "caption": "first"},
    {"type": "photo", "path": "C:/media/b.jpg", "caption": null}
  ],
  "replyToMessageId": 0
}
```

`type` is `photo`, `video` or `document`.

Telegram's rules, enforced here or by the server: **2 to 10 items**; photos and videos may
be mixed, documents may not be mixed with them. In most clients only the first caption is
displayed for the group.

```json
{"chatId": 1234567890, "messageIds": [306184192, 307232768]}
```

The ids are the confirmed ones. Telegram's album call has no built-in "wait for
confirmation" mode, so the client waits for each message to be acknowledged before
answering — which makes an album slower than a single send.

---

## `POST /messages/edit`

```json
{"chatId": 1234567890, "messageId": 303038464, "text": "corrected"}
```

Works on your own messages only. Telegram treats editing text and editing a media caption
as different operations; the client looks at the current content and picks the right one,
so one endpoint covers both. Telegram also limits how long a message stays editable.

```json
{"chatId": 1234567890, "messageId": 303038464, "edited": true}
```

---

## `POST /messages/delete`

```json
{"chatId": 1234567890, "messageIds": [303038464, 304087040], "revoke": true}
```

`revoke: true` deletes for everyone; `false` removes only your own copy. Deleting for
everyone is time-limited by Telegram and, in groups, requires the right permissions.

The stored rows are **not** removed — they are flagged `deleted: true` so history stays
intact. Filter them out client-side if you do not want them.

```json
{"chatId": 1234567890, "deleted": 2}
```

---

## `POST /messages/forward`

```json
{
  "fromChatId": -1001234567890,
  "toChatId": 1234567890,
  "messageIds": [12345],
  "sendCopy": false,
  "removeCaption": false
}
```

| Field | Effect |
|---|---|
| `sendCopy: false` | a normal forward, with the "forwarded from" header |
| `sendCopy: true` | sends a copy as if it were yours, no attribution |
| `removeCaption: true` | drops media captions; only meaningful with `sendCopy` |

Some chats forbid forwarding (content protection) — that surfaces as `403`.

```json
{"toChatId": 1234567890, "messageIds": [313524224]}
```

Like `/messages/album`, this waits for confirmation so the returned ids are the final ones.

---

## `POST /messages/copy`

Same body as `/messages/forward` minus `sendCopy` (it is implied):

```json
{"fromChatId": -1001234567890, "toChatId": 1234567890, "messageIds": [12345], "removeCaption": false}
```

The message arrives without the "forwarded from" header, as if you had written it. This is
the "copy" of Telegram's context menu, and it is the same Telegram call as a forward —
`/messages/forward` with `sendCopy: true` does exactly the same thing.

---

## `POST /messages/pin`, `POST /messages/unpin`

```json
{"chatId": -1001234567890, "messageId": 12345, "disableNotification": false, "onlyForSelf": false}
```

| Field | Effect |
|---|---|
| `disableNotification: true` | pin without notifying members |
| `onlyForSelf: true` | pin only in your own view — in a group where you lack pin rights this is the only variant that succeeds |

`unpin` takes the same body and ignores the two flags.

Pinning in a channel produces a service message in the history, which the client stores
like any other message (with an empty `text`).

---

## `POST /messages/react`, `POST /messages/unreact`

```json
{"chatId": 1234567890, "messageId": 303038464, "emoji": "👍", "big": false}
```

`big: true` plays the oversized animation for everyone in the chat. `unreact` takes the
same body and removes that reaction.

Only emoji reactions can be *sent* — not custom (premium) ones. Chats can restrict which
emoji are allowed, and a disallowed one comes back as `400` with `telegramCode: 400`.

Reading reactions is a different thing entirely: they ride along on every stored message in
the `reactions` field, custom and paid ones included.

> **Encoding.** If your shell might mangle UTF-8 — a Windows console commonly does — send
> the emoji as a JSON escape (`\\ud83d\\udc4d` for 👍). A corrupted emoji reaches Telegram
> as an unknown reaction and comes back as `400 The reaction isn't available for the
> message`, which looks exactly like a chat restriction and wastes a lot of debugging time.

---

## `POST /chats/{chatId}/read`

Marks messages as read, clearing the unread badge.

```json
{"messageIds": [303038464, 304087040]}
```

The body is optional: **with no body the newest stored message in that chat is marked
read**, which is enough to clear the badge.

```json
{"chatId": 1234567890, "read": 2}
```

Reading is forced, so messages count as read even though no chat is really open — the
point of an automated client. Bear in mind this makes the other side see the read receipt.

---

## `GET /chats`

| Query | Default | Meaning |
|---|---|---|
| `limit` | `100` | Maximum rows |

Ordered by most recently updated.

```json
[
  {
    "chatId": -1001234567890,
    "type": "supergroup",
    "title": "AI Tools Talks",
    "username": null,
    "updatedAt": 1789643696058
  }
]
```

`type` is one of `private`, `basicgroup`, `supergroup`, `secret`. Note that channels also
report `supergroup` — Telegram models them as the same type, and the client does not
currently store the flag that separates them.

---

## `GET /chats/{chatId}/messages`

| Query | Default | Meaning |
|---|---|---|
| `before` | `0` | Return messages with a smaller `messageId`; `0` starts from the newest |
| `limit` | `50` | Page size |

Newest first. Paginate by passing the last `messageId` of a page as the next `before`.

```json
[
  {
    "chatId": 1234567890,
    "messageId": 298844160,
    "senderId": 1234567890,
    "senderType": "user",
    "date": 1789643958000,
    "editDate": 0,
    "replyToMessageId": 0,
    "contentType": "text",
    "text": "hello",
    "rawJson": null,
    "outgoing": true,
    "deleted": false,
    "media": [],
    "reactions": []
  }
]
```

| Field | Notes |
|---|---|
| `senderType` | `user` or `chat` (channel posts and anonymous admins) |
| `contentType` | `text`, `photo`, `video`, `document`, `audio`, `voicenote`, `animation`, `sticker`, … |
| `text` | Message text or media caption; `null` when there is neither |
| `rawJson` | TDLib's full content representation, only when `storage.storeRaw: true` |
| `deleted` | Deleted in Telegram; the row is kept for history |
| `media` | Attachments; empty for text messages |
| `reactions` | Reaction buckets; empty when nobody reacted |

Attachments and reactions are each joined in one extra query per page, so a page of 50
messages costs three queries rather than 101.

### Attachments

```json
"media": [
  {
    "fileUniqueId": "AQADexampleFileId",
    "kind": "photo",
    "mimeType": "image/jpeg",
    "size": 66341,
    "downloaded": false,
    "url": "/media/1234567890/303038464/AQADexampleFileId"
  }
]
```

`url` is ready to use — append it to the base URL and fetch. `downloaded` only says
whether the bytes are already on the client's disk; fetching works either way.

### Reactions

```json
"reactions": [
  {"emoji": "👍", "count": 3, "chosen": true,  "recentSenderIds": [1234567890]},
  {"emoji": "🔥", "count": 1, "chosen": false, "recentSenderIds": []}
]
```

| Field | Meaning |
|---|---|
| `emoji` | the reaction; custom (premium) reactions appear as `custom:<id>`, paid ones as `paid` |
| `count` | how many accounts reacted with it |
| `chosen` | whether *your* account is one of them |
| `recentSenderIds` | the few reactors Telegram discloses — often empty, and normally empty in large chats |

Reactions are stored as a complete set per message and replaced whenever Telegram sends an
update, so a bucket that disappears has genuinely been removed. They are captured both from
live updates and from the history backfill.

Counts are what Telegram reports, which in big channels is aggregated — `recentSenderIds`
is not a way to enumerate everyone who reacted.

---

## `GET /media/{chatId}/{messageId}/{fileUniqueId}`

Downloads the actual bytes of an attachment — photos, videos, documents, audio, voice
notes, animations.

```bash
curl --noproxy '*' -H 'X-Api-Token: …' \
     -o photo.jpg \
     http://127.0.0.1:8080/media/1234567890/303038464/AQADexampleFileId
```

Take the path from the `url` field of an attachment rather than building it by hand.

**On-demand download.** If the file is not on the client's disk yet, it is fetched from
Telegram during the request and then cached — so the **first call on a large video can take
a while**, and later calls are served from disk. Set `media.autoDownload: true` in the
config to pull incoming attachments in the background instead, making the first request
fast too.

**Range requests are supported.** The response is a seekable stream, so
`Range: bytes=…` works and a video player can seek without downloading the whole file.
That also makes the URL usable directly as a `<video src>`.

| Header | Value |
|---|---|
| `Content-Type` | the attachment's MIME type, `application/octet-stream` if unknown |
| `Content-Length` | file size |
| `Content-Disposition` | `inline; filename="…"` |

| Status | When |
|---|---|
| `200` / `206` | full body / range |
| `400` | no such attachment on that message |
| `404` | the file could not be retrieved from Telegram (expired, deleted, no access) |

`fileUniqueId` is stable across restarts; TDLib's numeric file id is not, which is why the
client re-resolves it from the message on every fetch.

---

## `GET /search`

Full-text search over stored messages.

| Query | Default | Meaning |
|---|---|---|
| `q` | — | Search phrase (required) |
| `chatId` | all chats | Restrict to one chat |
| `limit` | `50` | Maximum rows |

Returns the same message objects as above, newest first, excluding deleted ones.

```bash
curl --noproxy '*' 'http://127.0.0.1:8080/search?q=release%20notes&limit=10'
```

The query is treated as a literal phrase — operators in the input are not interpreted, so
arbitrary user text is safe to pass through. Only messages carrying text or a caption are
indexed.

---

## `POST /chats/{chatId}/backfill`

Pulls history backwards for one chat. Returns `202 Accepted` immediately and continues in
the background — a full chat can take minutes.

| Query | Default | Meaning |
|---|---|---|
| `max` | `1000` | Stop after this many messages in this run |

```json
{"chatId": -1001234567890, "max": 1000}
```

Safe to call repeatedly: a cursor in `sync_state` means each call resumes where the last
one stopped instead of starting over, and once a chat is exhausted further calls do
nothing. Progress is visible through `GET /health` (the message count climbs) and in the
log.

Backfill competes with Telegram's rate limits — do not fan it out across hundreds of chats
at once.

---

## Errors

| Status | Body | When |
|---|---|---|
| `400` | `{"error": "…"}` | Bad parameters, missing file |
| `401` | `{"error": "invalid token"}` | Missing or wrong `X-Api-Token` |
| `500` | `{"error": "…"}` | Client-side failure; the full stack trace is in the client's log |

**Telegram's own refusals keep their meaning.** When Telegram rejects a request the error
code is mapped onto the HTTP status, and the body carries the original code:

```json
{"error": "The reaction isn't available for the message", "telegramCode": 400}
```

| Telegram code | HTTP | Typical cause |
|---|---|---|
| 400 | `400` | reaction not allowed here, message too old to edit, bad chat id |
| 403 | `403` | no permission to post, pin or forward |
| 404 | `404` | message or chat not found |
| 429 | `429` | flood wait — already retried once automatically before you see this |
| other | `502` | Telegram is unreachable or returned something unexpected |

So a `500` means the client broke; anything else means Telegram said no.

### Chat restrictions worth knowing

Some operations are refused by the chat rather than by permissions — reactions, for
instance, are not available in Saved Messages, and a call there returns `400` even though
the message is yours.

---

## Not exposed over HTTP

Deliberately absent, because it lives inside the process: reacting to incoming messages
(auto-replies, forwarding, webhooks). That is `UpdateBus` plus a handler in `handlers/` —
see the architecture section in the [README](../README.md).
