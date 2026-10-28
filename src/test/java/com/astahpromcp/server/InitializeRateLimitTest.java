package com.astahpromcp.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class InitializeRateLimitTest {
    
    // The time the limit reads, moved forward by the tests instead of waiting
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    
    private InitializeRateLimit limit(int perMinute, int burst) {
        return new InitializeRateLimit(now::get, perMinute, burst);
    }
    
    private static int admitted(InitializeRateLimit limit, int attempts) {
        int count = 0;
        for (int i = 0; i < attempts; i++) {
            if (limit.admit().isEmpty()) {
                count++;
            }
        }
        return count;
    }
    
    @Test
    void admit_ok_letsTheBurstThroughAtOnceAndRefusesTheNext() {
        InitializeRateLimit limit = limit(32, 16);
        
        assertEquals(16, admitted(limit, 16));
        assertTrue(limit.admit().isPresent(), "The initialize after the burst must be refused");
    }
    
    @Test
    void admit_ok_theBudgetComesBackAtTheConfiguredRate() {
        InitializeRateLimit limit = limit(32, 16);
        admitted(limit, 16);
        
        // One initialize per 60/32 s
        now.addAndGet(TimeUnit.MINUTES.toNanos(1) / 32);
        
        assertEquals(1, admitted(limit, 5));
    }
    
    @Test
    void admit_ok_aMinuteOfSteadyRequestsGetsTheRatePlusTheBurst() {
        InitializeRateLimit limit = limit(32, 16);
        int count = 0;
        
        // One attempt every 100 ms for a minute, far more often than the rate
        for (int i = 0; i < 600; i++) {
            if (limit.admit().isEmpty()) {
                count++;
            }
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        }
        
        assertTrue(count >= 16 + 31 && count <= 16 + 32, "Admitted " + count);
    }
    
    @Test
    void admit_ok_anUnusedBudgetDoesNotPileUpBeyondTheBurst() {
        InitializeRateLimit limit = limit(32, 16);
        
        now.addAndGet(TimeUnit.HOURS.toNanos(1));
        
        assertEquals(16, admitted(limit, 100));
    }
    
    @Test
    void admit_ng_theRefusalTellsWhenTheNextInitializeFits() {
        InitializeRateLimit limit = limit(60, 1);
        assertTrue(limit.admit().isEmpty());
        
        assertEquals(Optional.of(new InitializeRateLimit.Refusal(1)), limit.admit());
        
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
        assertEquals(Optional.of(new InitializeRateLimit.Refusal(1)), limit.admit(),
                "A wait shorter than a second is still told as one second, never as zero");
        
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertTrue(limit.admit().isEmpty(), "The initialize must fit once the time it was told has passed");
    }
    
    @Test
    void admit_ng_theWaitIsRoundedUpToWholeSeconds() {
        // 60/32 s = 1.875 s
        InitializeRateLimit limit = limit(32, 1);
        assertTrue(limit.admit().isEmpty());
        
        assertEquals(Optional.of(new InitializeRateLimit.Refusal(2)), limit.admit());
    }
    
    @Test
    void admit_ok_keepsCountingWhenTheClockWrapsAround() {
        // System.nanoTime() may start anywhere, including just short of where a long wraps around
        now.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1));
        InitializeRateLimit limit = limit(60, 1);
        
        assertTrue(limit.admit().isEmpty());
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limit.admit().isEmpty(), "One interval later the next initialize fits");
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        assertEquals(Optional.of(new InitializeRateLimit.Refusal(1)), limit.admit(), "Half an interval later it does not");
    }
    
    @Test
    @Timeout(30)
    void admit_ok_parallelInitializeRequestsShareOneBudget() throws Exception {
        InitializeRateLimit limit = limit(32, 16);
        AtomicInteger admittedCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        
        for (int i = 0; i < 64; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (limit.admit().isEmpty()) {
                    admittedCount.incrementAndGet();
                }
            });
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }
        
        assertEquals(16, admittedCount.get());
    }
    
    @Test
    void constructor_ng_rejectsANonPositiveRateOrBurst() {
        assertThrows(IllegalArgumentException.class, () -> limit(0, 16));
        assertThrows(IllegalArgumentException.class, () -> limit(32, 0));
    }
}
