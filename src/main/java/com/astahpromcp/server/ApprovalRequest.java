package com.astahpromcp.server;

// The connection an approval is asked for, as the user is shown it.
record ApprovalRequest(
    String remoteAddress,
    String remoteHost,
    int remotePort,
    int localPort,
    String userAgent
) {
    
    String clientAddress() {
        return remoteAddress + ":" + remotePort;
    }
}
