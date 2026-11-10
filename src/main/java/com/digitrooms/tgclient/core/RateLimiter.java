package com.digitrooms.tgclient.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outgoing throttle for a user account. Telegram's limits are not published; these
 * defaults are deliberately conservative — a userbot that trips FLOOD_WAIT repeatedly
 * gets the phone number restricted.
 *
 * <p>Also holds the FLOOD_WAIT penalty: when TDLib answers "Too Many Requests: retry
 * after N", {@link #applyFloodWait} pushes the next allowed send N seconds into the future
 * instead of failing the request.
 */
public final class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final long globalIntervalMs;
    private final long perChatIntervalMs;
    private final Map<Long, Long> nextAllowedPerChat = new ConcurrentHashMap<>();
    private final Object globalLock = new Object();
    private long nextAllowedGlobal = 0;

    public RateLimiter() {
        this(35, 1000);
    }

    /**
     * @param globalMessagesPerSecond  cap across all chats
     * @param perChatIntervalMs        minimum gap between two messages to the same chat
     */
    public RateLimiter(int globalMessagesPerSecond, long perChatIntervalMs) {
        this.globalIntervalMs = Math.max(1, 1000 / Math.max(1, globalMessagesPerSecond));
        this.perChatIntervalMs = perChatIntervalMs;
    }

    /** Blocks the caller until sending to this chat is allowed. */
    public void acquire(long chatId) {
        long waitUntil;
        synchronized (globalLock) {
            long now = System.currentTimeMillis();
            long chatGate = nextAllowedPerChat.getOrDefault(chatId, 0L);
            waitUntil = Math.max(Math.max(now, nextAllowedGlobal), chatGate);
            nextAllowedGlobal = waitUntil + globalIntervalMs;
            nextAllowedPerChat.put(chatId, waitUntil + perChatIntervalMs);
        }
        sleepUntil(waitUntil);
    }

    /** Called after a 429 from TDLib. */
    public void applyFloodWait(long chatId, int seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        synchronized (globalLock) {
            nextAllowedPerChat.merge(chatId, until, Math::max);
            if (seconds > 30) {
                // A long wait usually means the account as a whole is being throttled.
                nextAllowedGlobal = Math.max(nextAllowedGlobal, until);
            }
        }
        log.warn("FLOOD_WAIT {}s for chat {} — sending paused", seconds, chatId);
    }

    /** Extracts N from "Too Many Requests: retry after N"; -1 when it is not a flood wait. */
    public static int parseFloodWaitSeconds(String message) {
        if (message == null) {
            return -1;
        }
        int idx = message.indexOf("retry after ");
        if (idx < 0) {
            return -1;
        }
        String tail = message.substring(idx + "retry after ".length()).trim();
        StringBuilder digits = new StringBuilder();
        for (char ch : tail.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                break;
            }
        }
        return digits.isEmpty() ? -1 : Integer.parseInt(digits.toString());
    }

    private static void sleepUntil(long timestamp) {
        long delay = timestamp - System.currentTimeMillis();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
