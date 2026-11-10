package com.digitrooms.tgclient.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Application configuration, loaded from a YAML file.
 * Secrets may also come from the environment so that config.yaml can stay out of git:
 * TG_API_ID, TG_API_HASH, TG_PHONE, TG_API_TOKEN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {

    public Telegram telegram = new Telegram();
    public Storage storage = new Storage();
    public Api api = new Api();
    public Media media = new Media();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Telegram {
        public int apiId;
        public String apiHash;
        /**
         * How the first login is performed:
         * <ul>
         *   <li>{@code qr} — TDLib prints a tg://login link and an ASCII QR code; confirm it
         *       from an already logged-in Telegram app. No SMS code needed.</li>
         *   <li>{@code phone} — uses {@link #phone}, the code is typed on the console.</li>
         *   <li>{@code console} — asks for the phone number on the console too.</li>
         * </ul>
         * A cloud (2FA) password, if the account has one, is asked on the console in all
         * three cases — QR confirms device ownership, not knowledge of the password.
         * Only the first run needs any of this; afterwards the session directory is enough.
         */
        public String loginMethod = "console";
        /** Phone number in international format; empty means "ask on the console". */
        public String phone = "";
        /** Directory holding the TDLib session database. Treat it like a password. */
        public String sessionDir = "td-data/session";
        public String filesDir = "td-data/files";
        public String deviceModel = "tgclient";
        public String applicationVersion = "0.1.0";
        public boolean useTestDatacenter = false;
        /**
         * Open every known chat at startup so Telegram streams reactions and view counts
         * live. Off by default: it costs extra update traffic, and reactions this account
         * makes are refreshed immediately either way.
         */
        public boolean trackInteractions = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Storage {
        /** sqlite now, postgres later. Only this line changes when we migrate. */
        public String driver = "sqlite";
        public String sqlitePath = "data/tgclient.db";
        /** JDBC url for the postgres driver, used once driver=postgres. */
        public String jdbcUrl = "";
        public String username = "";
        public String password = "";
        public int readPoolSize = 4;
        /** Bounded queue between update handlers and the writer thread. */
        public int writeQueueCapacity = 50_000;
        /** Rows per transaction. */
        public int writeBatchSize = 500;
        /** Max time the writer waits to fill a batch. */
        public long writeFlushIntervalMs = 200;
        /** Store TDLib's own representation of the message content (heavy). */
        public boolean storeRaw = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Api {
        public boolean enabled = true;
        public String host = "127.0.0.1";
        public int port = 8080;
        /** Sent by clients as "X-Api-Token". Empty disables the check (local use only). */
        public String token = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Media {
        /** Download incoming photos/videos/documents automatically. */
        public boolean autoDownload = false;
        /** Skip files larger than this (bytes). 0 = no limit. */
        public long maxFileSize = 50L * 1024 * 1024;
        /** Concurrent downloads. */
        public int workers = 2;
    }

    public static AppConfig load(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        AppConfig cfg;
        boolean found = Files.exists(file);
        if (found) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            cfg = mapper.readValue(file.toFile(), AppConfig.class);
        } else {
            cfg = new AppConfig();
        }
        cfg.applyEnvOverrides();
        // Says which file was actually read: "config.yaml" is resolved against the working
        // directory, which in an IDE is not necessarily the project root.
        cfg.validate(absolute, found);
        return cfg;
    }

    private void applyEnvOverrides() {
        String apiId = System.getenv("TG_API_ID");
        if (apiId != null && !apiId.isBlank()) {
            telegram.apiId = Integer.parseInt(apiId.trim());
        }
        String apiHash = System.getenv("TG_API_HASH");
        if (apiHash != null && !apiHash.isBlank()) {
            telegram.apiHash = apiHash.trim();
        }
        String phone = System.getenv("TG_PHONE");
        if (phone != null && !phone.isBlank()) {
            telegram.phone = phone.trim();
        }
        String loginMethod = System.getenv("TG_LOGIN_METHOD");
        if (loginMethod != null && !loginMethod.isBlank()) {
            telegram.loginMethod = loginMethod.trim().toLowerCase();
        }
        String apiToken = System.getenv("TG_API_TOKEN");
        if (apiToken != null && !apiToken.isBlank()) {
            api.token = apiToken.trim();
        }
    }

    private void validate(Path configPath, boolean configFound) {
        if (telegram.apiId == 0 || telegram.apiHash == null || telegram.apiHash.isBlank()) {
            String where = configFound
                    ? "Read " + configPath + ", but telegram.apiId / telegram.apiHash are still empty there."
                    : "No config file at " + configPath + " (the working directory is "
                    + Path.of("").toAbsolutePath() + ") - built-in defaults were used.";
            throw new IllegalStateException(where
                    + " Create api_id / api_hash at https://my.telegram.org -> API development tools"
                    + " and set them in the config or via the TG_API_ID / TG_API_HASH environment variables.");
        }
        if (!java.util.Set.of("console", "qr", "phone").contains(telegram.loginMethod)) {
            throw new IllegalStateException(
                    "telegram.loginMethod must be one of console|qr|phone, got: " + telegram.loginMethod);
        }
        if ("phone".equals(telegram.loginMethod) && (telegram.phone == null || telegram.phone.isBlank())) {
            throw new IllegalStateException("telegram.loginMethod=phone requires telegram.phone (or TG_PHONE)");
        }
        if (!"sqlite".equals(storage.driver) && !"postgres".equals(storage.driver)) {
            throw new IllegalStateException("storage.driver must be 'sqlite' or 'postgres', got: " + storage.driver);
        }
    }
}
