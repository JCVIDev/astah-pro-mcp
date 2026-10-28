package com.astahpromcp.server;

import com.astahpromcp.config.McpServerConfig;
import com.astahpromcp.tool.JsonSupport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Servlet in front of the MCP transport of one port: it refuses a request that may not reach this server, bounds and caches the body, bounds the rate of initialize requests and has each one approved before the transport sees it, and hands every request it lets through to the transport
@Slf4j
public class McpClientApprovalServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    
    // HttpServletResponse has no constant for it
    static final int SC_TOO_MANY_REQUESTS = 429;
    
    private final HttpServletStreamableServerTransportProvider delegate;
    
    // Decides whether an initialize may connect; one per port
    private final ConnectionApproval approval;
    
    // Bounds how many initialize requests reach the approval; one per port
    private final InitializeRateLimit initializeRateLimit;
    
    // Largest POST body this servlet caches and hands to the transport
    private final int maxRequestSizeBytes;
    
    // For production
    McpClientApprovalServlet(HttpServletStreamableServerTransportProvider delegate,
            ConnectionApproval approval,
            InitializeRateLimit initializeRateLimit) {
        this(delegate, approval, initializeRateLimit, McpServerConfig.MCP_MAX_REQUEST_SIZE_BYTES);
    }
    
    // For testing
    McpClientApprovalServlet(HttpServletStreamableServerTransportProvider delegate,
            ConnectionApproval approval,
            InitializeRateLimit initializeRateLimit,
            int maxRequestSizeBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.approval = Objects.requireNonNull(approval, "approval");
        this.initializeRateLimit = Objects.requireNonNull(initializeRateLimit, "initializeRateLimit");
        
        if (maxRequestSizeBytes <= 0) {
            throw new IllegalArgumentException("maxRequestSizeBytes must be positive");
        }
        this.maxRequestSizeBytes = maxRequestSizeBytes;
    }
    
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Whether the request may reach this server at all is settled before anything else is done for it, so that a request this servlet will never serve cannot make it buffer anything or touch a session
        List<String> hostHeaders = headerValues(req, "Host");
        List<String> originHeaders = headerValues(req, "Origin");
        Optional<LoopbackRequestPolicy.Refusal> refusal = LoopbackRequestPolicy.check(hostHeaders, originHeaders, req.getLocalPort());
        if (refusal.isPresent()) {
            log.warn("Rejected MCP request from {}:{} to port {}: {} (Host: {}, Origin: {})",
                    req.getRemoteAddr(), req.getRemotePort(), req.getLocalPort(), refusal.get().reason(), hostHeaders, originHeaders);
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, refusal.get().reason());
            return;
        }
        
        HttpServletRequest actualReq = req;
        
        // Some clients pass the session ID as a query parameter (legacy HTTP+SSE convention) instead of the Mcp-Session-Id header; normalize it so the transport can find the session
        String sessionIdParam = req.getParameter("sessionId") != null
                ? req.getParameter("sessionId")
                : req.getParameter("session_id");
        if (sessionIdParam != null && !sessionIdParam.isBlank() && req.getHeader(HttpHeaders.MCP_SESSION_ID) == null) {
            actualReq = new SessionHeaderInjectingRequestWrapper(req, sessionIdParam.trim());
        }
        
        RequestContext context = RequestContext.from(actualReq);
        
        // The JSON-RPC method decides probe handling and approval below, and the servlet input stream is single-read, so cache the POST body and parse it once here.
        // The body is read under the same limit the transport applies, because everything below runs on a body this servlet has already buffered whole.
        JsonRpcCall call = JsonRpcCall.NONE;
        if ("POST".equalsIgnoreCase(actualReq.getMethod())) {
            // An announced length above the limit is refused without reading the body at all
            long announcedLength = actualReq.getContentLengthLong();
            if (announcedLength > maxRequestSizeBytes) {
                log.warn("Rejected MCP request to port {}: Content-Length {} exceeds the limit of {} bytes",
                        context.localPort(), announcedLength, maxRequestSizeBytes);
                resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
            
            CachedBodyRequestWrapper cachedReq;
            try {
                // A length that is absent, chunked or understated is caught here instead, by the number of bytes actually read
                cachedReq = CachedBodyRequestWrapper.readBounded(actualReq, maxRequestSizeBytes);
                
            } catch (RequestBodyTooLargeException e) {
                // Only a size overrun becomes 413; an ordinary I/O failure keeps propagating as one
                log.warn("Rejected MCP request to port {}: request body exceeds the limit of {} bytes",
                        context.localPort(), maxRequestSizeBytes);
                resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
            
            call = JsonRpcCall.parse(cachedReq.getBody());
            actualReq = cachedReq;
        }
        
        // 'server/discover' is a pre-initialize probe sent by newer MCP clients. The transport rejects pre-session methods it does not know with a plain HTTP 400 instead of a JSON-RPC error envelope, so answer -32601 here; the client then falls back to the legacy initialize handshake.
        if ("server/discover".equals(call.method()) && context.sessionId().isEmpty()) {
            log.info("Received server/discover probe from {} to port {}; replying 'Method not found' to trigger the initialize fallback",
                    context.clientAddress(), context.localPort());
            respondMethodNotFound(resp, call.id());
            return;
        }
        
        // If the request is an initialize attempt, request approval from the user
        if (call.isInitialize()) {
            // Only a JSON body gets as far as the dialog. A browser sends any other body to another origin without asking it first, and the transport refuses such a body anyway, so the user is never asked to approve a connection that could not be made.
            if (!isJsonContentType(actualReq.getContentType())) {
                log.warn("Rejected MCP initialize from {} to port {}: Content-Type {} is not application/json",
                        context.clientAddress(), context.localPort(), actualReq.getContentType());
                resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
                return;
            }
            
            // Counted before the approval, so that a flood of initialize requests neither queues up behind the dialog, holding a worker thread each, nor opens a session each within the grace period
            Optional<InitializeRateLimit.Refusal> limited = initializeRateLimit.admit();
            if (limited.isPresent()) {
                log.warn("Rejected MCP initialize from {} to port {}: more initialize requests than this port lets through; the next fits in {} s (User-Agent: {})",
                        context.clientAddress(), context.localPort(), limited.get().retryAfterSeconds(), context.userAgent());
                resp.setHeader("Retry-After", String.valueOf(limited.get().retryAfterSeconds()));
                resp.sendError(SC_TOO_MANY_REQUESTS, "Too many initialize requests");
                return;
            }
            
            if (!approval.approve(context.approvalRequest())) {
                log.info("Rejected MCP connection from {} to port {}", context.clientAddress(), context.localPort());
                resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Connection not approved by user");
                return;
            }
            log.info("Approved MCP connection from {} to port {}", context.clientAddress(), context.localPort());
        }
        
        // Create a response wrapper to track the status and the issued session ID
        SessionTrackingResponseWrapper responseWrapper = new SessionTrackingResponseWrapper(resp);
        
        // Service the request
        delegate.service(actualReq, responseWrapper);
        
        // Log the session issued by the transport after a successful initialization
        if (call.isInitialize() && responseWrapper.isSuccessful()) {
            responseWrapper.issuedSessionId().ifPresent(sessionId ->
                    log.info("Registered client session: {} from {} to port {} (User-Agent: {})",
                            sessionId, context.clientAddress(), context.localPort(), context.userAgent()));
        }
        
        // Log when the MCP client closes its session
        if (context.isDeleteRequest() && responseWrapper.isSuccessful() && context.sessionId().isPresent()) {
            log.info("Connection closed by MCP client (session '{}') on port {}.", context.sessionId().get(), context.localPort());
        }
    }
    
    // Reply with a JSON-RPC 'Method not found' error envelope
    private static void respondMethodNotFound(HttpServletResponse resp, JsonNode requestId) throws IOException {
        ObjectNode envelope = JsonSupport.OBJ_MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        ObjectNode error = envelope.putObject("error");
        error.put("code", -32601);
        error.put("message", "Method not found");
        envelope.set("id", requestId == null ? NullNode.getInstance() : requestId);
        
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(JsonSupport.OBJ_MAPPER.writeValueAsString(envelope));
        resp.getWriter().flush();
    }
    
    // Every value of a header, in the order received.
    private static List<String> headerValues(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }
    
    // Whether a Content-Type header names JSON: the media type application/json, whatever parameters (such as charset) follow it
    static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return mediaType.equalsIgnoreCase("application/json");
    }
    
    // Raised only when the request body passes the size limit, so that an ordinary I/O failure is never reported as 413.
    private static final class RequestBodyTooLargeException extends Exception {
        
        private static final long serialVersionUID = 1L;
        
        RequestBodyTooLargeException(int maxBodyBytes) {
            super("Request body exceeds the maximum allowed size of " + maxBodyBytes + " bytes");
        }
    }
    
    // Minimal view of the JSON-RPC call carried in a POST body
    record JsonRpcCall(String method, JsonNode id) {
        
        static final JsonRpcCall NONE = new JsonRpcCall(null, null);
        
        static JsonRpcCall parse(String body) {
            try {
                JsonNode root = JsonSupport.OBJ_MAPPER.readTree(body);
                if (root != null && root.isObject()) {
                    JsonNode method = root.get("method");
                    return new JsonRpcCall(method != null && method.isString() ? method.asString() : null,
                            root.get("id"));
                }
            } catch (RuntimeException e) {
                // Malformed JSON; the transport reports the error to the client
            }
            return NONE;
        }
        
        boolean isInitialize() {
            return "initialize".equals(method);
        }
    }
    
    // Response wrapper that tracks the status and the session ID issued by the transport
    private static final class SessionTrackingResponseWrapper extends HttpServletResponseWrapper {
        
        private int status;
        private String issuedSessionId;
        
        SessionTrackingResponseWrapper(HttpServletResponse response) {
            super(response);
            this.status = response.getStatus();
        }
        
        @Override
        public void setStatus(int sc) {
            super.setStatus(sc);
            this.status = sc;
        }
        
        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, value);
            captureSessionId(name, value);
        }
        
        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, value);
            captureSessionId(name, value);
        }
        
        private void captureSessionId(String name, String value) {
            if (HttpHeaders.MCP_SESSION_ID.equalsIgnoreCase(name)) {
                this.issuedSessionId = value;
            }
        }
        
        public Optional<String> issuedSessionId() {
            return Optional.ofNullable(issuedSessionId);
        }
        
        @Override
        public void sendError(int sc) throws IOException {
            this.status = sc;
            super.sendError(sc);
        }
        
        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            super.sendError(sc, msg);
        }
        
        @Override
        public void sendRedirect(String location) throws IOException {
            super.sendRedirect(location);
            this.status = HttpServletResponse.SC_FOUND;
        }
        
        public int getStatus() {
            return status;
        }
        
        public boolean isSuccessful() {
            return status < HttpServletResponse.SC_BAD_REQUEST;
        }
    }
    
    static final class RequestContext {
        
        private final String method;
        private final String sessionId;
        private final String remoteAddress;
        private final int remotePort;
        private final String remoteHost;
        private final int localPort;
        private final String userAgent;
        
        private RequestContext(String method,
                String sessionId,
                String remoteAddress,
                int remotePort,
                String remoteHost,
                int localPort,
                String userAgent) {
            this.method = method;
            this.sessionId = sessionId;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.remoteHost = remoteHost;
            this.localPort = localPort;
            this.userAgent = userAgent;
        }
        
        static RequestContext from(HttpServletRequest request) {
            String method = request.getMethod();
            String sessionIdHeader = Optional.ofNullable(request.getHeader(HttpHeaders.MCP_SESSION_ID))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElse(null);
            String remoteAddress = request.getRemoteAddr();
            int remotePort = request.getRemotePort();
            String remoteHost = request.getRemoteHost();
            int localPort = request.getLocalPort();
            String userAgent = Optional.ofNullable(request.getHeader("User-Agent")).orElse("Unknown");
            return new RequestContext(method, sessionIdHeader, remoteAddress, remotePort, remoteHost, localPort, userAgent);
        }
        
        boolean isDeleteRequest() {
            return "DELETE".equalsIgnoreCase(method);
        }
        
        Optional<String> sessionId() {
            return Optional.ofNullable(sessionId);
        }
        
        String clientAddress() {
            return remoteAddress + ":" + remotePort;
        }
        
        int localPort() {
            return localPort;
        }
        
        String userAgent() {
            return userAgent;
        }
        
        // The connection as the approval of an initialize sees it
        ApprovalRequest approvalRequest() {
            return new ApprovalRequest(remoteAddress, remoteHost, remotePort, localPort, userAgent);
        }
    }
    
    // Presents the session ID from a query parameter as the Mcp-Session-Id header
    private static final class SessionHeaderInjectingRequestWrapper extends HttpServletRequestWrapper {
        
        private final String sessionId;
        
        SessionHeaderInjectingRequestWrapper(HttpServletRequest request, String sessionId) {
            super(request);
            this.sessionId = sessionId;
        }
        
        @Override
        public String getHeader(String name) {
            if (HttpHeaders.MCP_SESSION_ID.equalsIgnoreCase(name)) {
                return sessionId;
            }
            return super.getHeader(name);
        }
        
        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.MCP_SESSION_ID.equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(sessionId));
            }
            return super.getHeaders(name);
        }
        
        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            boolean hasSessionId = names.stream().anyMatch(HttpHeaders.MCP_SESSION_ID::equalsIgnoreCase);
            if (!hasSessionId) {
                names.add(HttpHeaders.MCP_SESSION_ID);
            }
            return Collections.enumeration(names);
        }
    }
    
    // Buffers the request body so it can be inspected here and still be read by the transport
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        
        // Size of one read from the client, not a limit of its own
        private static final int READ_CHUNK_BYTES = 8192;
        
        private final byte[] cachedBody;
        
        private CachedBodyRequestWrapper(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }
        
        // Cache the body while reading at most maxBodyBytes + 1 bytes, so that a body far above the limit costs
        // no more than a body just above it. The one extra byte is what proves the limit was passed.
        static CachedBodyRequestWrapper readBounded(HttpServletRequest request, int maxBodyBytes)
                throws IOException, RequestBodyTooLargeException {
            
            ServletInputStream input = request.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] chunk = new byte[READ_CHUNK_BYTES];
            int total = 0;
            
            while (true) {
                int wanted = Math.min(chunk.length, maxBodyBytes - total + 1);
                int read = input.read(chunk, 0, wanted);
                if (read == -1) {
                    break;
                }
                
                total += read;
                if (total > maxBodyBytes) {
                    throw new RequestBodyTooLargeException(maxBodyBytes);
                }
                body.write(chunk, 0, read);
            }
            
            return new CachedBodyRequestWrapper(request, body.toByteArray());
        }
        
        // MCP bodies are UTF-8. Reported here rather than delegated, so that this servlet's own parse and the transport's
        // parse of the same cached bytes cannot decode them differently.
        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }
        
        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new ServletInputStream() {
                private final ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
                
                @Override
                public int read() throws IOException {
                    return bais.read();
                }
                
                // The transport reads the body in buffers; without this the default implementation would fall back to one call per byte
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return bais.read(b, off, len);
                }
                
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }
                
                @Override
                public boolean isReady() {
                    return true;
                }
                
                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener != null) {
                        try {
                            readListener.onDataAvailable();
                            if (isFinished()) {
                                readListener.onAllDataRead();
                            }
                        } catch (IOException e) {
                            readListener.onError(e);
                        }
                    }
                }
            };
        }
        
        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        
        String getBody() {
            return new String(cachedBody, StandardCharsets.UTF_8);
        }
    }
}
