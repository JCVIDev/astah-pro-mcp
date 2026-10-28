package com.astahpromcp.server;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

// Asks the user with a modal dialog on the event dispatch thread
@Slf4j
final class SwingApprovalPrompt implements ApprovalPrompt {
    
    private final BooleanSupplier headless;
    
    // For production
    SwingApprovalPrompt() {
        this(GraphicsEnvironment::isHeadless);
    }
    
    // For testing
    SwingApprovalPrompt(BooleanSupplier headless) {
        this.headless = headless;
    }
    
    @Override
    public boolean ask(ApprovalRequest request) throws InterruptedException {
        if (headless.getAsBoolean()) {
            log.warn("Headless environment detected. Rejecting connection from {} to port {}.", request.clientAddress(), request.localPort());
            return false;
        }
        
        AtomicBoolean approved = new AtomicBoolean(false);
        String message = buildDialogMessage(request);
        Object[] options = {"Connect", "Cancel"};
        try {
            runOnEdtBlocking(() -> showApprovalDialog(message, options, approved));
            
        } catch (InvocationTargetException e) {
            log.error("Failed to display approval dialog", e.getCause());
            return false;
        }
        return approved.get();
    }
    
    // Build the dialog message
    static String buildDialogMessage(ApprovalRequest request) {
        return "Connection request received from MCP client." + "\n" +
                "\n" +
                "Client address: " + request.remoteAddress() + "\n" +
                "Client host: " + request.remoteHost() + "\n" +
                "Client port: " + request.remotePort() + "\n" +
                "Server port: " + request.localPort() + "\n" +
                "User-Agent: " + request.userAgent() + "\n";
    }
    
    private static void runOnEdtBlocking(Runnable task) throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
    }
    
    private static void showApprovalDialog(String message,
            Object[] options,
            AtomicBoolean approved) {
        // Create a JOptionPane that keeps the dialog on top
        JOptionPane optionPane = new JOptionPane(message,
                JOptionPane.INFORMATION_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                null,
                options,
                options[0]);
        
        Frame tempOwner = null;
        JDialog dialog = null;
        try {
            // Simple approach: create a temporary invisible owner and set DOCUMENT_MODAL
            tempOwner = new Frame();
            tempOwner.setUndecorated(true);
            tempOwner.setType(Window.Type.UTILITY);
            tempOwner.setAlwaysOnTop(true);
            tempOwner.setLocationRelativeTo(null);
            tempOwner.setVisible(true);
            
            dialog = optionPane.createDialog(tempOwner, "MCP Connection Request");
            dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            
            dialog.setVisible(true);
            
            Object selectedValue = optionPane.getValue();
            approved.set(selectedValue != null && selectedValue.equals(options[0]));
            
        } finally {
            if (dialog != null) {
                dialog.dispose();
            }
            if (tempOwner != null) {
                tempOwner.dispose();
            }
        }
    }
}
