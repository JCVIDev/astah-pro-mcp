package com.astahpromcp.server;

import com.astahpromcp.config.McpServerConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// Decides from the Host and Origin headers alone whether a request may reach this server at all
final class LoopbackRequestPolicy {
    
    // Why a request was refused, with the reason sent back to the client
    enum Refusal {
        HOST_NOT_ALLOWED("Host not allowed"),
        ORIGIN_NOT_ALLOWED("Origin not allowed");
        
        private final String reason;
        
        Refusal(String reason) {
            this.reason = reason;
        }
        
        String reason() {
            return reason;
        }
    }
    
    // Host names a client may call this server by: the address it listens on, and the name that resolves to that address
    static final Set<String> HOST_NAMES = Set.of(McpServerConfig.HOST.toLowerCase(Locale.ROOT), "localhost");
    
    // The port a Host header without one stands for
    private static final int DEFAULT_HTTP_PORT = 80;
    
    private LoopbackRequestPolicy() {
    }
    
    // The reason to refuse a request, or empty when it may proceed.
    static Optional<Refusal> check(List<String> hostHeaders, List<String> originHeaders, int localPort) {
        if (hostHeaders.size() != 1 || !namesThisServer(hostHeaders.get(0), localPort)) {
            return Optional.of(Refusal.HOST_NOT_ALLOWED);
        }
        if (!originHeaders.isEmpty()) {
            return Optional.of(Refusal.ORIGIN_NOT_ALLOWED);
        }
        return Optional.empty();
    }
    
    // Whether a Host header value names this server
    static boolean namesThisServer(String hostHeader, int localPort) {
        String value = hostHeader.trim();
        String host;
        String port;
        
        if (value.startsWith("[")) {
            // An IPv6 literal, written in brackets so that its colons are not taken for the port separator
            int close = value.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = value.substring(1, close);
            String rest = value.substring(close + 1);
            if (rest.isEmpty()) {
                port = null;
            } else if (rest.startsWith(":")) {
                port = rest.substring(1);
            } else {
                return false;
            }
            
        } else {
            int colon = value.lastIndexOf(':');
            host = colon < 0 ? value : value.substring(0, colon);
            port = colon < 0 ? null : value.substring(colon + 1);
        }
        
        return HOST_NAMES.contains(host.toLowerCase(Locale.ROOT)) && portOf(port) == localPort;
    }
    
    // The port a Host header gives, or -1 when it is not a port number
    private static int portOf(String port) {
        if (port == null) {
            return DEFAULT_HTTP_PORT;
        }
        if (port.isEmpty() || port.length() > 5 || !port.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        return Integer.parseInt(port);
    }
}
