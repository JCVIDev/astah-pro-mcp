package com.astahpromcp.server;

import com.astahpromcp.config.McpServerConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

// Decides whether a connection to one port may be made
@Slf4j
final class ConnectionApproval {
    
    private final ApprovalPrompt prompt;
    
    // The current time in milliseconds
    private final LongSupplier clock;
    
    private final long gracePeriodMillis;
    private final long waitTimeoutMillis;
    
    // Serializes the prompts of this port, so that at most one is shown at a time.
    private final ReentrantLock promptLock = new ReentrantLock();
    
    // The last explicit user approval (guarded by graceLock)
    private final Object graceLock = new Object();
    private long lastApprovalAtMillis;
    private String lastApprovedUserAgent;
    
    // For production
    ConnectionApproval(ApprovalPrompt prompt) {
        this(prompt,
            System::currentTimeMillis,
            McpServerConfig.APPROVAL_GRACE_PERIOD_MS,
            TimeUnit.SECONDS.toMillis(McpServerConfig.APPROVAL_DIALOG_WAIT_TIMEOUT_SECONDS));
    }
    
    // For testing
    ConnectionApproval(ApprovalPrompt prompt, LongSupplier clock, long gracePeriodMillis, long waitTimeoutMillis) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gracePeriodMillis = gracePeriodMillis;
        this.waitTimeoutMillis = waitTimeoutMillis;
    }
    
    // Whether the connection may proceed. A failure to ask, a wait that runs out and an interrupt all refuse it.
    boolean approve(ApprovalRequest request) {
        if (isWithinGracePeriod(request)) {
            log.info("Auto-approved MCP connection from {} to port {} within the approval grace period (User-Agent: {}).",
                    request.clientAddress(), request.localPort(), request.userAgent());
            return true;
        }
        
        // The wait is bounded so that a prompt left unanswered cannot park every worker thread of this port, and interruptible so that a server shutdown does not have to wait for the user.
        boolean acquired;
        try {
            acquired = promptLock.tryLock(waitTimeoutMillis, TimeUnit.MILLISECONDS);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the approval dialog of another connection on port {}; rejecting {}",
                    request.localPort(), request.clientAddress());
            return false;
        }
        
        if (!acquired) {
            log.warn("Gave up waiting for the approval dialog of another connection on port {} after {} ms; rejecting {}",
                    request.localPort(), waitTimeoutMillis, request.clientAddress());
            return false;
        }
        
        try {
            // The prompt this request waited for may have just approved the same client, so check again before asking
            if (isWithinGracePeriod(request)) {
                log.info("Auto-approved MCP connection from {} to port {} after waiting, within the approval grace period (User-Agent: {}).",
                        request.clientAddress(), request.localPort(), request.userAgent());
                return true;
            }
            
            if (prompt.ask(request)) {
                recordApproval(request);
                return true;
            }
            return false;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            promptLock.unlock();
        }
    }
    
    private boolean isWithinGracePeriod(ApprovalRequest request) {
        synchronized (graceLock) {
            return lastApprovedUserAgent != null
                    && lastApprovedUserAgent.equals(request.userAgent())
                    && clock.getAsLong() - lastApprovalAtMillis <= gracePeriodMillis;
        }
    }
    
    private void recordApproval(ApprovalRequest request) {
        synchronized (graceLock) {
            lastApprovalAtMillis = clock.getAsLong();
            lastApprovedUserAgent = request.userAgent();
        }
    }
}
