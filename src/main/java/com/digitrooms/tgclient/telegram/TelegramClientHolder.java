package com.digitrooms.tgclient.telegram;

import com.digitrooms.tgclient.config.AppConfig;
import com.digitrooms.tgclient.core.UpdateBus;
import it.tdlight.Init;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the TDLib client: native init, session directory, login and the bridge from
 * TDLib's callback thread into the {@link UpdateBus}.
 *
 * <p>The session directory is the account: anyone holding a copy is logged in as the user.
 * It must stay out of git and off shared drives.
 */
public final class TelegramClientHolder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientHolder.class);

    private final SimpleTelegramClientFactory factory;
    private final SimpleTelegramClient client;

    public TelegramClientHolder(AppConfig cfg, UpdateBus bus) {
        try {
            Init.init();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Failed to load the TDLib native library. The tdlight-natives classifier in pom.xml "
                            + "must match this platform.", t);
        }

        TDLibSettings settings = TDLibSettings.create(new APIToken(cfg.telegram.apiId, cfg.telegram.apiHash));
        settings.setUseTestDatacenter(cfg.telegram.useTestDatacenter);
        settings.setDatabaseDirectoryPath(ensureDir(cfg.telegram.sessionDir));
        settings.setDownloadedFilesDirectoryPath(ensureDir(cfg.telegram.filesDir));
        settings.setDeviceModel(cfg.telegram.deviceModel);
        settings.setApplicationVersion(cfg.telegram.applicationVersion);
        // A userbot that cannot see history is useless, and the local databases also
        // let TDLib recover the update stream after downtime.
        settings.setMessageDatabaseEnabled(true);
        settings.setChatInfoDatabaseEnabled(true);
        settings.setFileDatabaseEnabled(true);

        this.factory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder builder = factory.builder(settings);
        builder.addUpdatesHandler(bus::publish);
        builder.addDefaultExceptionHandler(e -> log.error("TDLib request failed", e));
        builder.addUpdateExceptionHandler(e -> log.error("TDLib update handling failed", e));

        AuthenticationSupplier<?> auth = authenticationSupplier(cfg);
        log.info("Starting TDLib, session directory: {}, login method: {}",
                settings.getDatabaseDirectoryPath(), cfg.telegram.loginMethod);
        this.client = builder.build(auth);
    }

    /**
     * Only the first run authenticates — afterwards the session directory keeps us logged in.
     *
     * <p>Whatever is chosen here, a cloud (2FA) password is still asked on the console:
     * TDLib's authorisation state machine reaches WaitPassword from the QR branch exactly
     * as it does from the SMS-code branch.
     */
    private static AuthenticationSupplier<?> authenticationSupplier(AppConfig cfg) {
        return switch (cfg.telegram.loginMethod) {
            // TDLib emits AuthorizationStateWaitOtherDeviceConfirmation with a tg://login
            // link; the default console interaction prints the link and an ASCII QR code
            // (rendered by zxing, which this project adds explicitly — tdlight-java does not).
            case "qr" -> AuthenticationSupplier.qrCode();
            case "phone" -> AuthenticationSupplier.user(cfg.telegram.phone);
            default -> AuthenticationSupplier.consoleLogin();
        };
    }

    private static Path ensureDir(String path) {
        Path dir = Path.of(path).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory " + dir, e);
        }
        return dir;
    }

    public SimpleTelegramClient client() {
        return client;
    }

    /** Blocks until the account is authorised, i.e. until getMe() answers. */
    public TdApi.User awaitReady() {
        CompletableFuture<TdApi.User> me = client.getMeAsync();
        TdApi.User user = me.join();
        log.info("Logged in as {} {} (id={}, @{})",
                user.firstName, user.lastName, user.id, user.usernames != null
                        && user.usernames.activeUsernames.length > 0
                        ? user.usernames.activeUsernames[0] : "-");
        return user;
    }

    @Override
    public void close() {
        try {
            client.closeAndWait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Error while closing the Telegram client", e);
        }
        factory.close();
    }
}
