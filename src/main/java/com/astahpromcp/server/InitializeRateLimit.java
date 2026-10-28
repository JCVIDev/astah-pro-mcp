package com.astahpromcp.server;

import com.astahpromcp.config.McpServerConfig;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// Bounds how many initialize requests one port lets through, counting every client together.
final class InitializeRateLimit {
    
    // Why an initialize was refused: the budget is used up, and the next initialize fits in it after this many seconds
    record Refusal(
        long retryAfterSeconds
    ) {
    }
    
    // The current time in nanoseconds, counted the way System.nanoTime() counts it
    private final LongSupplier nanoClock;
    
    // What one initialize costs, as time: the budget comes back at the rate of one initialize per interval
    private final long intervalNanos;
    
    // How far ahead of the clock the budget may be used up, which lets (burst) initialize requests arrive at once
    private final long burstAheadNanos;
    
    // The time the budget is used up to (guarded by this). Each initialize let through moves it one interval forward.
    private long usedUntilNanos;
    
    // For production
    InitializeRateLimit() {
        this(System::nanoTime, McpServerConfig.MCP_INITIALIZE_RATE_PER_MINUTE, McpServerConfig.MCP_INITIALIZE_BURST);
    }
    
    // For testing
    InitializeRateLimit(LongSupplier nanoClock, int perMinute, int burst) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (perMinute <= 0) {
            throw new IllegalArgumentException("perMinute must be positive");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("burst must be positive");
        }
        
        this.intervalNanos = TimeUnit.MINUTES.toNanos(1) / perMinute;
        this.burstAheadNanos = intervalNanos * (burst - 1);
        this.usedUntilNanos = nanoClock.getAsLong();
    }
    
    // Charges one initialize to the budget and lets it through, or returns why it may not proceed.
    synchronized Optional<Refusal> admit() {
        long now = nanoClock.getAsLong();
        
        // A budget left unused does not pile up beyond the burst: time that has already passed cannot be spent again.
        // Compared by difference, as System.nanoTime() values have to be.
        long usedUntil = usedUntilNanos - now > 0 ? usedUntilNanos : now;
        long ahead = usedUntil - now;
        if (ahead > burstAheadNanos) {
            return Optional.of(new Refusal(secondsRoundedUp(ahead - burstAheadNanos)));
        }
        
        usedUntilNanos = usedUntil + intervalNanos;
        return Optional.empty();
    }
    
    // At least one second, as Retry-After counts whole seconds and 0 would invite an immediate retry
    private static long secondsRoundedUp(long nanos) {
        long nanosPerSecond = TimeUnit.SECONDS.toNanos(1);
        return Math.max(1, (nanos + nanosPerSecond - 1) / nanosPerSecond);
    }
}
