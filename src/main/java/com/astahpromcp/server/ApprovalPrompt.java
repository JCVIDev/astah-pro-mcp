package com.astahpromcp.server;

// Asks the user whether to accept a connection. ConnectionApproval calls it at most once at a time per port.
@FunctionalInterface
interface ApprovalPrompt {
    
    // True only when the user explicitly approved the connection.
    // False when the user declined or closed the prompt, and when the prompt could not be shown, so that a failure is never taken for an approval.
    boolean ask(ApprovalRequest request) throws InterruptedException;
}
