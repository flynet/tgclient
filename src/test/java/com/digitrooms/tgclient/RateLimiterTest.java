package com.digitrooms.tgclient;

import com.digitrooms.tgclient.core.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void parsesFloodWaitMessages() {
        assertEquals(17, RateLimiter.parseFloodWaitSeconds("Too Many Requests: retry after 17"));
        assertEquals(5, RateLimiter.parseFloodWaitSeconds("Too Many Requests: retry after 5 seconds"));
        assertEquals(-1, RateLimiter.parseFloodWaitSeconds("CHAT_WRITE_FORBIDDEN"));
        assertEquals(-1, RateLimiter.parseFloodWaitSeconds(null));
    }

    @Test
    void spacesOutMessagesToTheSameChat() {
        RateLimiter limiter = new RateLimiter(100, 200);
        long start = System.currentTimeMillis();
        limiter.acquire(1);
        limiter.acquire(1);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 190, "expected at least the per-chat interval, waited " + elapsed + "ms");
    }

    @Test
    void differentChatsAreNotBlockedByEachOther() {
        RateLimiter limiter = new RateLimiter(1000, 5_000);
        long start = System.currentTimeMillis();
        limiter.acquire(1);
        limiter.acquire(2);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1_000, "per-chat gating leaked across chats, waited " + elapsed + "ms");
    }
}
