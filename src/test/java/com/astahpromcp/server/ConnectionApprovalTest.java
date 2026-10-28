package com.astahpromcp.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ConnectionApprovalTest {
    
    private static final long GRACE_MILLIS = 30_000;
    private static final long WAIT_MILLIS = 5_000;
    
    // The time the approval reads, moved forward by the tests instead of waiting
    private final AtomicLong now = new AtomicLong(1_000_000);
    
    private final ApprovalPrompt prompt = mock(ApprovalPrompt.class);
    
    private ConnectionApproval approval() {
        return approval(WAIT_MILLIS);
    }
    
    private ConnectionApproval approval(long waitMillis) {
        return new ConnectionApproval(prompt, now::get, GRACE_MILLIS, waitMillis);
    }
    
    private static ApprovalRequest from(String userAgent, int remotePort) {
        return new ApprovalRequest("127.0.0.1", "localhost", remotePort, 8888, userAgent);
    }
    
    @Test
    void approve_ok_approvesWhatTheUserApproves() throws Exception {
        ApprovalRequest request = from("Agent-A", 50001);
        when(prompt.ask(request)).thenReturn(true);
        
        assertTrue(approval().approve(request));
        
        verify(prompt).ask(request);
    }
    
    @Test
    void approve_ng_refusesWhatTheUserDeclinesAndStartsNoGracePeriod() throws Exception {
        when(prompt.ask(any())).thenReturn(false);
        ConnectionApproval approval = approval();
        
        assertFalse(approval.approve(from("Agent-A", 50001)));
        assertFalse(approval.approve(from("Agent-A", 50002)));
        
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    void approve_ok_approvesTheSameUserAgentWithoutAskingUntilTheGracePeriodEnds() throws Exception {
        when(prompt.ask(any())).thenReturn(true);
        ConnectionApproval approval = approval();
        assertTrue(approval.approve(from("Agent-A", 50001)));
        
        // The last moment of the grace period still counts
        now.addAndGet(GRACE_MILLIS);
        
        assertTrue(approval.approve(from("Agent-A", 50002)));
        verify(prompt, times(1)).ask(any());
    }
    
    @Test
    void approve_ng_asksAgainOnceTheGracePeriodHasEnded() throws Exception {
        when(prompt.ask(any())).thenReturn(true);
        ConnectionApproval approval = approval();
        assertTrue(approval.approve(from("Agent-A", 50001)));
        
        now.addAndGet(GRACE_MILLIS + 1);
        
        assertTrue(approval.approve(from("Agent-A", 50002)));
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    void approve_ng_asksAnotherUserAgentWithinTheGracePeriod() throws Exception {
        when(prompt.ask(any())).thenReturn(true);
        ConnectionApproval approval = approval();
        assertTrue(approval.approve(from("Agent-A", 50001)));
        
        assertTrue(approval.approve(from("Agent-B", 50002)));
        
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    void approve_ng_doesNotExtendTheGracePeriodByApprovingWithoutAsking() throws Exception {
        // The grace period starts at the explicit approval of the user, and an approval given without asking does not move it
        when(prompt.ask(any())).thenReturn(true);
        ConnectionApproval approval = approval();
        assertTrue(approval.approve(from("Agent-A", 50001)));
        
        now.addAndGet(GRACE_MILLIS - 1);
        assertTrue(approval.approve(from("Agent-A", 50002)));
        verify(prompt, times(1)).ask(any());
        
        now.addAndGet(2);
        assertTrue(approval.approve(from("Agent-A", 50003)));
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    void approve_ng_keepsTheApprovalsOfTwoPortsApart() throws Exception {
        when(prompt.ask(any())).thenReturn(true);
        ConnectionApproval direct = approval();
        ConnectionApproval programmatic = approval();
        assertTrue(direct.approve(from("Agent-A", 50001)));
        
        assertTrue(programmatic.approve(from("Agent-A", 50002)));
        
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    @Timeout(30)
    void approve_ok_approvesARequestThatWaitedForTheApprovalOfTheSameClient() throws Exception {
        // Clients such as Codex CLI open several connections at once. Once the user approves the first, the ones that waited for that dialog are approved by its grace period rather than each showing a dialog of its own.
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        when(prompt.ask(any())).thenAnswer(invocation -> {
            shown.countDown();
            assertTrue(answered.await(10, TimeUnit.SECONDS), "The dialog was never answered");
            return true;
        });
        ConnectionApproval approval = approval();
        
        Attempt first = new Attempt(approval, from("Agent-A", 50001));
        assertTrue(shown.await(5, TimeUnit.SECONDS), "The dialog was never shown");
        Attempt second = new Attempt(approval, from("Agent-A", 50002));
        second.awaitQueued();
        answered.countDown();
        
        assertTrue(first.result());
        assertTrue(second.result());
        verify(prompt, times(1)).ask(any());
    }
    
    @Test
    @Timeout(30)
    void approve_ok_asksARequestThatWaitedWhenTheUserDeclined() throws Exception {
        // A refusal starts no grace period, so the request that waited gets a dialog of its own, after the first one is closed and never on top of it
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger onScreen = new AtomicInteger();
        AtomicInteger mostOnScreen = new AtomicInteger();
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        when(prompt.ask(any())).thenAnswer(invocation -> {
            mostOnScreen.accumulateAndGet(onScreen.incrementAndGet(), Math::max);
            try {
                shown.countDown();
                assertTrue(answered.await(10, TimeUnit.SECONDS), "The dialog was never answered");
                // The user declines the first and approves the second
                return asked.incrementAndGet() > 1;
                
            } finally {
                onScreen.decrementAndGet();
            }
        });
        ConnectionApproval approval = approval();
        
        Attempt first = new Attempt(approval, from("Agent-A", 50001));
        assertTrue(shown.await(5, TimeUnit.SECONDS), "The dialog was never shown");
        Attempt second = new Attempt(approval, from("Agent-A", 50002));
        second.awaitQueued();
        answered.countDown();
        
        assertFalse(first.result());
        assertTrue(second.result());
        assertEquals(2, asked.get());
        assertEquals(1, mostOnScreen.get(), "Two dialogs were on screen at the same time");
    }
    
    @Test
    @Timeout(30)
    void approve_ng_refusesWithoutAskingWhenTheWaitRunsOut() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        when(prompt.ask(any())).thenAnswer(invocation -> {
            shown.countDown();
            assertTrue(answered.await(10, TimeUnit.SECONDS), "The dialog was never answered");
            return true;
        });
        ConnectionApproval approval = approval(200);
        
        Attempt first = new Attempt(approval, from("Agent-A", 50001));
        assertTrue(shown.await(5, TimeUnit.SECONDS), "The dialog was never shown");
        Attempt second = new Attempt(approval, from("Agent-B", 50002));
        
        assertFalse(second.result());
        answered.countDown();
        assertTrue(first.result());
        verify(prompt, times(1)).ask(any());
    }
    
    @Test
    @Timeout(30)
    void approve_ng_refusesAndKeepsTheInterruptWhenInterruptedWhileWaiting() throws Exception {
        // A server shutdown interrupts the requests that wait, and must not have to wait for the user
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        when(prompt.ask(any())).thenAnswer(invocation -> {
            shown.countDown();
            assertTrue(answered.await(10, TimeUnit.SECONDS), "The dialog was never answered");
            return true;
        });
        ConnectionApproval approval = approval();
        
        Attempt first = new Attempt(approval, from("Agent-A", 50001));
        assertTrue(shown.await(5, TimeUnit.SECONDS), "The dialog was never shown");
        Attempt second = new Attempt(approval, from("Agent-B", 50002));
        second.awaitQueued();
        second.interrupt();
        
        assertFalse(second.result());
        assertTrue(second.interruptedAfterwards(), "The interrupt must be kept for the caller");
        answered.countDown();
        assertTrue(first.result());
        verify(prompt, times(1)).ask(any());
    }
    
    @Test
    void approve_ng_refusesAndKeepsTheInterruptWhenInterruptedWhileAsking() throws Exception {
        when(prompt.ask(any())).thenThrow(new InterruptedException()).thenReturn(true);
        ConnectionApproval approval = approval();
        
        assertFalse(approval.approve(from("Agent-A", 50001)));
        assertTrue(Thread.interrupted(), "The interrupt must be kept for the caller");
        
        // The dialog is free again for the next request
        assertTrue(approval.approve(from("Agent-A", 50002)));
        verify(prompt, times(2)).ask(any());
    }
    
    @Test
    void approve_ng_freesTheDialogForTheNextRequestWhenThePromptFails() throws Exception {
        when(prompt.ask(any())).thenThrow(new IllegalStateException("no dialog")).thenReturn(true);
        ConnectionApproval approval = approval();
        
        assertThrows(IllegalStateException.class, () -> approval.approve(from("Agent-A", 50001)));
        
        assertTrue(approval.approve(from("Agent-A", 50002)));
        verify(prompt, times(2)).ask(any());
    }
    
    // One call of approve() on a thread of its own, as each initialize arrives on a worker thread of its own
    private static final class Attempt {
        
        private final Thread thread;
        private final AtomicReference<Boolean> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean interruptedAfterwards = new AtomicBoolean();
        
        Attempt(ConnectionApproval approval, ApprovalRequest request) {
            thread = new Thread(() -> {
                try {
                    result.set(approval.approve(request));
                    interruptedAfterwards.set(Thread.currentThread().isInterrupted());
                } catch (Throwable t) {
                    // Reported by the test thread; throwing here would only reach stderr
                    failure.set(t);
                }
            });
            // Daemon so that a regression which never returns fails the test instead of hanging the build
            thread.setDaemon(true);
            thread.start();
        }
        
        boolean result() throws InterruptedException {
            assertTrue(thread.join(Duration.ofSeconds(10)), "approve() never returned");
            if (failure.get() != null) {
                fail("approve() failed", failure.get());
            }
            return result.get();
        }
        
        boolean interruptedAfterwards() {
            return interruptedAfterwards.get();
        }
        
        void interrupt() {
            thread.interrupt();
        }
        
        // Wait until the call has queued up behind the dialog on screen
        void awaitQueued() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                // The wait for the dialog is bounded, so a queued thread parks in TIMED_WAITING
                Thread.State state = thread.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    return;
                }
                Thread.sleep(5);
            }
            fail("The request never queued up behind the dialog (thread state: " + thread.getState() + ")");
        }
    }
}
