# Setup — from an empty machine to a running client

Full cycle: prerequisites → Telegram credentials → build → configure → first login →
verification → day-to-day operation.

---

## 1. Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21+ | `java -version`. The project uses records, pattern matching for `switch` and virtual threads. |
| Maven 3.9+ | `mvn -v` |
| Outbound internet | TDLib connects to Telegram data centres directly. Note: a corporate HTTP proxy does **not** apply to TDLib (it speaks MTProto, not HTTP). |
| A Telegram account | With access to the phone number or to an already logged-in Telegram app. |

No database server is needed — storage is embedded SQLite.

**Windows note:** the QR login code is drawn with block characters. The app pins its own
stdout to UTF-8, but `cmd.exe` also needs `chcp 65001`. Windows Terminal, PowerShell 7 and
the IntelliJ run console are fine as they are.

---

## 2. Telegram application credentials

These identify the *application*, not the account. They are obtained once, by hand — there
is no API for issuing them.

1. Open <https://my.telegram.org> and sign in with your phone number.
2. Go to **API development tools**.
3. Create an application (title and short name are free-form, e.g. `tgclient`;
   platform: Desktop).
4. Copy **App api_id** (a number) and **App api_hash** (32 hex characters).

Treat `api_hash` as a secret.

---

## 3. Build

```bash
git clone git@github.com:flynet/tgclient.git && cd tgclient
mvn clean package
```

Produces `target/tgclient.jar` (~43 MB — the TDLib native library is bundled).

The TDLib native binary is platform-specific and selected by a Maven profile from the
build machine's OS (`natives-windows` / `natives-linux` in `pom.xml`). **Build on the same
OS you will run on**, or adjust the classifier — otherwise the app fails at startup with
`UnsatisfiedLinkError`.

`mvn test` runs the suite (storage, FTS search, write queue, rate limiter, QR rendering).
No Telegram account is needed for tests.

---

## 4. Configure

```bash
cp config.example.yaml config.yaml
```

Fill in at minimum:

```yaml
telegram:
  apiId: 1234567
  apiHash: "0123456789abcdef0123456789abcdef"
  loginMethod: "qr"      # console | qr | phone
```

Secrets can stay out of the file entirely — these environment variables override it:

| Variable | Overrides |
|---|---|
| `TG_API_ID` | `telegram.apiId` |
| `TG_API_HASH` | `telegram.apiHash` |
| `TG_PHONE` | `telegram.phone` |
| `TG_LOGIN_METHOD` | `telegram.loginMethod` |
| `TG_API_TOKEN` | `api.token` |

### Settings worth knowing

| Key | Default | What it does |
|---|---|---|
| `telegram.sessionDir` | `td-data/session` | TDLib session. **Equivalent to the account password** — never commit or share it. |
| `telegram.filesDir` | `td-data/files` | Where downloaded media lands. |
| `storage.sqlitePath` | `data/tgclient.db` | The database file. |
| `storage.storeRaw` | `false` | Store TDLib's full representation of each message. Heavy; enable only if you need it. |
| `storage.writeBatchSize` | `500` | Rows per transaction. Raise for higher throughput, lower for fresher data. |
| `storage.writeQueueCapacity` | `50000` | When full, update handlers block (never drop). |
| `api.token` | `""` | Empty disables authentication — acceptable only on `127.0.0.1`. |
| `media.autoDownload` | `false` | Download incoming attachments automatically. |
| `media.maxFileSize` | `52428800` | Skip larger files. `0` = no limit. |

`config.yaml`, `td-data/` and `data/` are gitignored.

---

## 5. First login

```bash
java -jar target/tgclient.jar            # or: java -jar target/tgclient.jar /path/to/config.yaml
```

Relative paths (`config.yaml`, `td-data/`, `data/`) resolve against the **working
directory**. In IntelliJ set Run Configuration → Working directory to the project root.

What happens depends on `loginMethod`:

### `qr` — no SMS code needed

The console prints a `tg://login?token=…` link and an ASCII QR code. On a phone that is
already logged in: **Settings → Devices → Link Desktop Device**, scan the code.

The token expires after ~30 seconds and a fresh QR is printed — scan the most recent one.
If the terminal cannot render it, the printed link carries the same payload and can be
turned into a QR image anywhere else.

### `console` / `phone`

Asks for the phone number (unless `telegram.phone` is set) and the login code, which
Telegram sends to your other devices or by SMS.

### Cloud password (2FA)

If the account has a cloud password, it is asked on the console **in every method,
including QR**. TDLib's authorisation state machine reaches `WaitPassword` from the QR
branch exactly as from the code branch: scanning proves you hold a logged-in device, not
that you know the password.

Lost the password? QR will not help. Reset two-step verification from an already
logged-in app: Settings → Privacy and Security → Two-Step Verification. Without a recovery
email Telegram offers a reset after a **7-day wait** — that resets the password only, the
account survives. Right after a reset, Telegram may temporarily refuse new logins.

### After the first run

Success looks like this:

```
INFO  c.d.t.telegram.TelegramClientHolder - Logged in as <name> (id=…)
INFO  c.digitrooms.tgclient.api.HttpServer - HTTP API listening on http://127.0.0.1:8080
INFO  com.digitrooms.tgclient.App - Client is running. Ctrl+C to stop.
```

The session is now in `td-data/session`; subsequent runs log in silently with no prompts.
Back that directory up as carefully as a password, and keep in mind that deleting it means
authenticating again.

---

## 6. Verify it works

The startup log only proves the client started. To prove the whole path — send → Telegram
→ update stream → database → search:

```bash
# behind a corporate proxy, curl must be told to skip it for localhost
curl --noproxy '*' http://127.0.0.1:8080/health
# {"messages":0,"status":"ok"}

curl --noproxy '*' http://127.0.0.1:8080/chats?limit=5
# your chat list, proving updates are being persisted

# send to Saved Messages: chatId = your own user id from the login line
curl --noproxy '*' -X POST http://127.0.0.1:8080/messages/text \
     -H 'Content-Type: application/json' \
     -d '{"chatId":<your user id>,"text":"hello","replyToMessageId":0}'

curl --noproxy '*' 'http://127.0.0.1:8080/search?q=hello'
# the message comes back, with the same messageId the POST returned
```

Live updates only cover what happens from now on. To pull existing history:

```bash
curl --noproxy '*' -X POST 'http://127.0.0.1:8080/chats/<chatId>/backfill?max=1000'
```

See [API.md](API.md) for the full endpoint reference.

---

## 7. Running it for real

### Securing the HTTP API

The API can read every conversation and post as you. Before it leaves `127.0.0.1`:

```yaml
api:
  host: "0.0.0.0"
  token: "<a long random string>"
```

Then every request needs `X-Api-Token` (except `/health`). There is no TLS — put it behind
a reverse proxy if it crosses a network.

### As a service (systemd)

```ini
[Unit]
Description=tgclient
After=network-online.target

[Service]
WorkingDirectory=/opt/tgclient
ExecStart=/usr/bin/java -jar /opt/tgclient/tgclient.jar config.yaml
Environment=TG_API_TOKEN=…
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Deploy `tgclient.jar`, `config.yaml` and the existing `td-data/` (to avoid logging in
again). Shutdown is graceful: the client stops accepting updates, drains the write queue
and checkpoints the database, so `SIGTERM` does not lose buffered messages.

### Backups

| Path | Contains | Losing it means |
|---|---|---|
| `td-data/session` | Telegram session | logging in again |
| `data/tgclient.db` | collected messages | re-running backfill |
| `td-data/files` | downloaded media | re-downloading |

`data/tgclient.db-wal` and `-shm` belong to the database — copy them together with it, or
stop the client first.

### Operational limits

- Telegram throttles user accounts. `RateLimiter` caps outgoing traffic and backs off on
  `FLOOD_WAIT`, but bulk messaging still risks the phone number being restricted.
- Automating a user account is governed by Telegram's ToS. This is built for your own
  account and your own chats.

---

## 8. Troubleshooting

| Symptom | Cause |
|---|---|
| `telegram.apiId / apiHash are not set` | The message names the file it actually read and the working directory — check it is the config you edited. |
| `Connection refused` to `127.0.0.1:8080` from curl | A corporate proxy intercepting localhost. Use `--noproxy '*'`. |
| QR prints as `?????` | Terminal not in UTF-8. `chcp 65001` on `cmd.exe`. |
| `QR code library is missing!` | `com.google.zxing:core` missing from the classpath — `QrLoginTest` guards this. |
| `UnsatisfiedLinkError` at startup | The `tdlight-natives` classifier does not match the OS. |
| Client runs, `/health` shows `messages: 0` | Normal on a fresh database: only new messages are stored. Run a backfill. |
| Phone logged out "Terminate session" | Somebody terminated the client's session in Telegram → delete `td-data/session` and log in again. |
