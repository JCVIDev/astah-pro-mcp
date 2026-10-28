package com.astahpromcp;

import com.astahpromcp.config.LogbackConfig;
import com.astahpromcp.config.McpServerConfig;
import com.astahpromcp.server.McpServerApp;
import com.astahpromcp.server.PortAvailabilityChecker;
import com.astahpromcp.ui.TextPaneAppender;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class Activator implements BundleActivator {
    
    private static final URI PORT_CHANGE_GUIDE_URI =
            URI.create("https://github.com/takaakit/astah-pro-mcp#changing-the-port-numbers");
    
    private McpServerApp server;
    
    // A port the plugin listens on, with the environment variable that overrides it.
    private record PortSetting(
        String label,
        int port,
        String envVar
    ) {
    }
    
    private static List<PortSetting> portSettings() {
        return List.of(
                new PortSetting("direct tool calling mode",
                        McpServerConfig.PORT_FOR_DIRECT, McpServerConfig.ENV_PORT_FOR_DIRECT),
                new PortSetting("programmatic tool calling mode",
                        McpServerConfig.PORT_FOR_PROGRAMMATIC, McpServerConfig.ENV_PORT_FOR_PROGRAMMATIC));
    }
    
    @Override
    public void start(BundleContext context) throws Exception {
        // Every profile needs its own port, otherwise whichever starts second fails to bind.
        Map<Integer, PortSetting> byPort = new LinkedHashMap<>();
        for (PortSetting setting : portSettings()) {
            PortSetting clashing = byPort.putIfAbsent(setting.port(), setting);
            if (clashing != null) {
                showPluginDisabledDialog("The astah-pro-mcp plugin is disabled because the same port ("
                        + setting.port() + ") is configured for both the " + clashing.label() + " ("
                        + clashing.envVar() + ") and the " + setting.label() + " ("
                        + setting.envVar() + ").",
                        JOptionPane.ERROR_MESSAGE);
                
                throw new IllegalStateException("The same port is configured for two profiles: " + setting.port());
            }
        }
        
        // Check port availability
        List<Integer> portsInUse = PortAvailabilityChecker.findPortsInUse(
                McpServerConfig.HOST,
                portSettings().stream().map(PortSetting::port).toList());
        
        if (!portsInUse.isEmpty()) {
            String ports = portsInUse.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("\n"));
            
            showPluginDisabledDialog("The astah-pro-mcp plugin is disabled because the following port(s) are already in use:\n"
                    + ports + "\n\n"
                    + "Please change the port numbers with environment variables. For details, see:",
                    PORT_CHANGE_GUIDE_URI,
                    JOptionPane.INFORMATION_MESSAGE);
            
            throw new IllegalStateException("Port is already in use: " + portsInUse.get(0));
        }
        
        // Configure Logback
        LogbackConfig.configure(McpServerConfig.ROOT_OUTPUT_DIR, new TextPaneAppender());
        
        // Report how the ports were resolved
        McpServerConfig.portResolutionWarnings().forEach(log::warn);
        log.info("MCP ports: direct tool calling={}, programmatic tool calling={}",
                McpServerConfig.PORT_FOR_DIRECT,
                McpServerConfig.PORT_FOR_PROGRAMMATIC);
        
        log.info("Log panel shows {} and above; the log file also keeps DEBUG. Set the environment variable {} (for example to DEBUG) to change the panel level.",
                LogbackConfig.getLogPanelLevel(),
                McpServerConfig.ENV_LOG_PANEL_LEVEL);
        
        log.info("MCP-BUNDLE: Starting bundle...");
        
        // The class loader must be switched to the Jetty bundle's class loader to prevent ClassNotFoundExceptions for Jetty classes.
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();
        log.debug("Original context class loader: {}", originalClassLoader);
        
        // Set the context class loader to this bundle's class loader, which will contain Jetty
        currentThread.setContextClassLoader(Activator.class.getClassLoader());
        log.debug("Context class loader set to bundle class loader");
        
        try {
            this.server = new McpServerApp();
            this.server.start();
            log.info("MCP-BUNDLE: Bundle started successfully.");
            
        } catch (Throwable t) {
            log.error("MCP-BUNDLE: Failed to start MCP server", t);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception("Failed to start MCP server", t);
            
        } finally {
            // Restore the original class loader
            currentThread.setContextClassLoader(originalClassLoader);
            log.debug("Context class loader restored");
        }
    }
    
    // Notify the user that the plugin has been disabled
    private void showPluginDisabledDialog(String message, int messageType) {
        showPluginDisabledDialog(message, null, messageType);
    }
    
    // Same as above, with a link to the given page under the message. link may be null.
    private void showPluginDisabledDialog(String message, URI link, int messageType) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        
        SwingUtilities.invokeLater(() -> {
            // JOptionPane shows each element of an array message on its own row.
            Object content = (link == null) ? message : new Object[] { message, createLinkPane(link) };
            JOptionPane optionPane = new JOptionPane(content, messageType);
            
            Frame tempOwner = null;
            JDialog dialog = null;
            try {
                tempOwner = new Frame();
                tempOwner.setUndecorated(true);
                tempOwner.setType(Window.Type.UTILITY);
                tempOwner.setAlwaysOnTop(true);
                tempOwner.setLocationRelativeTo(null);
                tempOwner.setVisible(true);
                
                dialog = optionPane.createDialog(tempOwner, "astah-pro-mcp");
                dialog.setAlwaysOnTop(true);
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                
                dialog.setVisible(true);
                
            } finally {
                if (dialog != null) {
                    dialog.dispose();
                }
                if (tempOwner != null) {
                    tempOwner.dispose();
                }
            }
        });
    }
    
    // A link that opens the page in the browser when clicked
    private static JEditorPane createLinkPane(URI link) {
        // A URI cannot contain <, > or ", so & is the only character that needs escaping in HTML.
        String url = link.toASCIIString().replace("&", "&amp;");
        
        JEditorPane linkPane = new JEditorPane("text/html", "<a href=\"" + url + "\">" + url + "</a>");
        linkPane.setEditable(false);
        linkPane.setOpaque(false);
        linkPane.setBorder(BorderFactory.createEmptyBorder());
        
        // Use the font of the message text rather than the HTML default font.
        linkPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        Font messageFont = UIManager.getFont("OptionPane.messageFont");
        linkPane.setFont(messageFont != null ? messageFont : UIManager.getFont("Label.font"));
        
        linkPane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openInBrowser(link, linkPane);
            }
        });
        return linkPane;
    }
    
    // Opening a browser can take a while, so it runs off the event dispatch thread.
    private static void openInBrowser(URI page, Component parent) {
        CompletableFuture.runAsync(() -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                showBrowserError(parent, "Opening a browser is not supported on this platform.");
                return;
            }
            
            try {
                Desktop.getDesktop().browse(page);
            } catch (IOException | SecurityException e) {
                showBrowserError(parent, "Failed to open the page in the browser: " + e.getMessage());
            }
        });
    }
    
    private static void showBrowserError(Component parent, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parent, message, "astah-pro-mcp", JOptionPane.ERROR_MESSAGE));
    }
    
    @Override
    public void stop(BundleContext context) throws Exception {
        log.debug("MCP-BUNDLE: Stopping bundle...");
        if (this.server != null) {
            try {
                this.server.stop();
                log.info("MCP-BUNDLE: Bundle stopped successfully.");
                
            } catch (Exception e) {
                log.warn("MCP-BUNDLE: Exception occurred during shutdown: {}", e.getMessage());
                log.debug("MCP-BUNDLE: Shutdown exception details", e);
                
            } finally {
                this.server = null;
            }
        }
    }
}
