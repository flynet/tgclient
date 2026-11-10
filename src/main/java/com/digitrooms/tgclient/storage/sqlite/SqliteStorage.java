package com.digitrooms.tgclient.storage.sqlite;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.storage.ChatRow;
import com.digitrooms.tgclient.storage.MediaRow;
import com.digitrooms.tgclient.storage.MessageRow;
import com.digitrooms.tgclient.storage.Storage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded storage. SQLite cannot parallelise writes, so there is exactly one write
 * connection (owned by the writer thread of the WriteQueue) and a small Hikari pool
 * for readers. WAL mode lets the REST layer read while the writer commits.
 */
public final class SqliteStorage implements Storage {

    private static final Logger log = LoggerFactory.getLogger(SqliteStorage.class);

    private final HikariDataSource readPool;
    private final Connection writeConnection;
    private final Object writeLock = new Object();

    private final SqliteMessageRepository messages;
    private final SqliteChatRepository chats;
    private final SqliteMediaRepository media;
    private final SqliteSyncStateRepository syncState;
    private final SqliteReactionRepository reactions;
    private final SqliteMessageSearch search;

    public SqliteStorage(AppConfig.Storage cfg) {
        Path dbPath = Path.of(cfg.sqlitePath).toAbsolutePath();
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the database directory " + dbPath.getParent(), e);
        }
        String url = "jdbc:sqlite:" + dbPath;

        try {
            this.writeConnection = DriverManager.getConnection(url);
            applyPragmas(this.writeConnection);
            this.writeConnection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open " + dbPath, e);
        }

        migrate();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setMaximumPoolSize(Math.max(1, cfg.readPoolSize));
        hikari.setPoolName("sqlite-read");
        hikari.setReadOnly(false); // SQLite read-only mode also blocks WAL recovery
        hikari.setConnectionInitSql("PRAGMA busy_timeout=5000");
        this.readPool = new HikariDataSource(hikari);

        this.messages = new SqliteMessageRepository(this, cfg);
        this.chats = new SqliteChatRepository(this);
        this.media = new SqliteMediaRepository(this);
        this.syncState = new SqliteSyncStateRepository(this);
        this.reactions = new SqliteReactionRepository(this);
        this.search = new SqliteMessageSearch(this);

        log.info("SQLite storage ready at {} (WAL, {} reader connections)", dbPath, cfg.readPoolSize);
    }

    private static void applyPragmas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA temp_store=MEMORY");
        }
    }

    /**
     * Migrations are plain idempotent DDL (everything is {@code IF NOT EXISTS}) replayed
     * in order on every start, so an existing database picks up new tables without a
     * version table to maintain.
     */
    private static final List<String> MIGRATIONS = List.of(
            "db/migration/sqlite/V1__init.sql",
            "db/migration/sqlite/V2__reactions.sql");

    private void migrate() {
        inWriteTransaction(c -> {
            try (Statement st = c.createStatement()) {
                for (String migration : MIGRATIONS) {
                    for (String stmt : splitStatements(readResource(migration))) {
                        st.execute(stmt);
                    }
                }
            }
            return null;
        });
    }

    private static List<String> splitStatements(String sql) {
        // Comments go first: a ';' inside one would otherwise split a statement in half.
        String withoutComments = sql.replaceAll("(?m)^\\s*--.*$", "");
        List<String> out = new ArrayList<>();
        for (String raw : withoutComments.split(";")) {
            String stmt = raw.trim();
            if (!stmt.isEmpty()) {
                out.add(stmt);
            }
        }
        return out;
    }

    private static String readResource(String name) {
        try (InputStream in = SqliteStorage.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Migration resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + name, e);
        }
    }

    /** Runs work on the single write connection and commits it as one transaction. */
    <T> T inWriteTransaction(SqlFunction<T> work) {
        synchronized (writeLock) {
            try {
                T result = work.apply(writeConnection);
                writeConnection.commit();
                return result;
            } catch (SQLException e) {
                try {
                    writeConnection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw new IllegalStateException("Write transaction failed", e);
            }
        }
    }

    <T> T inReadConnection(SqlFunction<T> work) {
        try (Connection c = readPool.getConnection()) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Read query failed", e);
        }
    }

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    static MessageRow readMessage(ResultSet rs) throws SQLException {
        return new MessageRow(
                rs.getLong("chat_id"),
                rs.getLong("message_id"),
                rs.getLong("sender_id"),
                rs.getString("sender_type"),
                rs.getLong("date"),
                rs.getLong("edit_date"),
                rs.getLong("reply_to_message_id"),
                rs.getString("content_type"),
                rs.getString("text"),
                rs.getString("raw_json"),
                rs.getBoolean("outgoing"),
                rs.getBoolean("is_deleted"));
    }

    static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    @Override
    public MessageRepository messages() {
        return messages;
    }

    @Override
    public ChatRepository chats() {
        return chats;
    }

    @Override
    public MediaRepository media() {
        return media;
    }

    @Override
    public SyncStateRepository syncState() {
        return syncState;
    }

    @Override
    public ReactionRepository reactions() {
        return reactions;
    }

    @Override
    public MessageSearch search() {
        return search;
    }

    @Override
    public void close() {
        readPool.close();
        synchronized (writeLock) {
            try {
                try (Statement st = writeConnection.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                writeConnection.close();
            } catch (SQLException e) {
                log.warn("Error while closing the write connection", e);
            }
        }
    }

    /** Placeholder rows of the given size, e.g. "(?,?,?)". */
    static String placeholders(int count) {
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    static List<ChatRow> readChats(ResultSet rs) throws SQLException {
        List<ChatRow> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new ChatRow(
                    rs.getLong("chat_id"),
                    rs.getString("type"),
                    rs.getString("title"),
                    rs.getString("username"),
                    rs.getLong("updated_at")));
        }
        return out;
    }

    static List<MediaRow> readMedia(ResultSet rs) throws SQLException {
        List<MediaRow> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new MediaRow(
                    rs.getLong("chat_id"),
                    rs.getLong("message_id"),
                    rs.getString("file_unique_id"),
                    rs.getInt("file_id"),
                    rs.getString("kind"),
                    rs.getString("mime_type"),
                    rs.getLong("size"),
                    rs.getString("local_path"),
                    rs.getLong("downloaded_at")));
        }
        return out;
    }
}
