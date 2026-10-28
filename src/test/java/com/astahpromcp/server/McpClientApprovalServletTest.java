package com.astahpromcp.server;

import com.astahpromcp.config.McpServerConfig;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class McpClientApprovalServletTest {
    
    private static final int PORT = 8888;
    private static final String THIS_SERVER = "127.0.0.1:" + PORT;
    
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}";
    
    @Mock
    private HttpServletStreamableServerTransportProvider delegate;
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private HttpServletResponse response;
    
    // The dialog, the only part of the approval replaced here
    @Mock
    private ApprovalPrompt prompt;
    
    private McpClientApprovalServlet servlet;
    
    @BeforeEach
    void setUp() {
        servlet = new McpClientApprovalServlet(delegate, new ConnectionApproval(prompt), new InitializeRateLimit());
        
        // Every request a client sends names this server in its Host header and arrives on its port. Lenient, because the tests that never call service() do not read them, and the tests about another Host stub their own.
        lenient().when(request.getHeaders("Host")).thenAnswer(invocation -> headers(THIS_SERVER));
        lenient().when(request.getLocalPort()).thenReturn(PORT);
    }
    
    // A fresh enumeration on every call, as a container hands out
    private static Enumeration<String> headers(String... values) {
        return Collections.enumeration(List.of(values));
    }
    
    // Stub the request body; POST bodies are read through getInputStream by the servlet
    private void stubBody(String body) throws Exception {
        stubBody(request, body);
    }
    
    private void stubBody(HttpServletRequest target, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        when(target.getInputStream()).thenReturn(new ServletInputStream() {
            private final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            
            @Override
            public int read() {
                return bais.read();
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
            }
        });
    }
    
    @Test
    void service_ng_rejectsRequestFromAPageOnAnotherLoopbackPort() throws Exception {
        // A page served from this machine is another origin whatever its port, and no supported client sends Origin at all
        when(request.getHeaders("Origin")).thenAnswer(invocation -> headers("http://127.0.0.1:3000"));
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsRequestFromAnIpv6LoopbackOrigin() throws Exception {
        when(request.getHeaders("Origin")).thenAnswer(invocation -> headers("http://[::1]:3000"));
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsRequestWhenOriginNotPermitted() throws Exception {
        when(request.getHeaders("Origin")).thenAnswer(invocation -> headers("http://evil.example.com"));
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsRequestWhenOriginHeaderMalformed() throws Exception {
        when(request.getHeaders("Origin")).thenAnswer(invocation -> headers("%%%invalid-uri%%% "));
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsRequestWhoseHostIsNotThisServer() throws Exception {
        // A page reached through DNS rebinding sends its own host name
        when(request.getHeaders("Host")).thenAnswer(invocation -> headers("evil.example:" + PORT));
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
        verify(request, never()).getInputStream();
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsRequestWithoutAHost() throws Exception {
        // An HTTP/1.0 request may leave the header out
        when(request.getHeaders("Host")).thenAnswer(invocation -> headers());
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsDisallowedOriginWithoutCachingTheBody() throws Exception {
        // Host and Origin are settled first, so a request this servlet will never serve cannot make it buffer anything
        when(request.getHeaders("Origin")).thenAnswer(invocation -> headers("http://evil.example.com"));
        
        servletLimitedTo(1024).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        verify(request, never()).getInputStream();
        verify(request, never()).getContentLengthLong();
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ok_passesThroughDeleteRequestWithoutApproval() throws Exception {
        when(request.getHeader("Mcp-Session-Id")).thenReturn("session-123");
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        
        servlet.service(request, response);
        
        verify(delegate).service(eq(request), ArgumentMatchers.any(HttpServletResponse.class));
        verify(response, never()).sendError(anyInt(), anyString());
    }
    
    @Test
    void service_ok_passesThroughSessionRequestWithoutApproval() throws Exception {
        when(request.getHeader("Mcp-Session-Id")).thenReturn("session-123");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        
        servlet.service(request, response);
        
        verify(delegate).service(ArgumentMatchers.any(HttpServletRequest.class),
                ArgumentMatchers.any(HttpServletResponse.class));
        verify(response, never()).sendError(anyInt(), anyString());
    }
    
    @Test
    void service_ok_answersServerDiscoverProbeWithMethodNotFound() throws Exception {
        // 'server/discover' must be answered with a JSON-RPC -32601 envelope (without opening the approval dialog) so the client falls back to the legacy initialize handshake.
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\",\"params\":{}}");
        StringWriter out = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(out));
        
        servlet.service(request, response);
        
        verify(delegate, never()).service(any(), any());
        verify(response, never()).sendError(anyInt(), anyString());
        assertTrue(out.toString().contains("-32601"));
        assertTrue(out.toString().contains("\"id\":7"));
    }
    
    @Test
    void service_ok_toolsCallMentioningProbeMethodIsNotIntercepted() throws Exception {
        // A request whose arguments merely contain the string "server/discover" must reach the transport untouched; only the JSON-RPC method field triggers the probe reply.
        when(request.getHeader("Mcp-Session-Id")).thenReturn("session-123");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"set_name\",\"arguments\":{\"name\":\"server/discover\"}}}");
        
        servlet.service(request, response);
        
        verify(delegate).service(ArgumentMatchers.any(HttpServletRequest.class),
                ArgumentMatchers.any(HttpServletResponse.class));
        verify(response, never()).sendError(anyInt(), anyString());
    }
    
    @Test
    void service_ng_rejectsDeclinedInitializeWithServiceUnavailableNotForbidden() throws Exception {
        // When the user declines, the rejection must be 503, not 401/403, so MCP clients
        // do not mistake it for an OAuth authentication challenge. The dialog is stubbed
        // so the outcome is deterministic and no real dialog is shown.
        when(prompt.ask(any())).thenReturn(false);
        
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody(INITIALIZE);
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Connection not approved by user");
        verify(response, never()).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
        verify(response, never()).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ok_passesThroughApprovedInitialize() throws Exception {
        // When the user approves, the request is delegated to the transport and not rejected.
        when(prompt.ask(any())).thenReturn(true);
        
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody(INITIALIZE);
        
        servlet.service(request, response);
        
        verify(delegate).service(ArgumentMatchers.any(HttpServletRequest.class),
                ArgumentMatchers.any(HttpServletResponse.class));
        verify(response, never()).sendError(anyInt(), anyString());
    }
    
    @Test
    void service_ok_asksAboutTheConnectionTheRequestCameFrom() throws Exception {
        // The approval is asked about the client and the port of the request, as the dialog shows them
        when(prompt.ask(any())).thenReturn(true);
        
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(51325);
        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getHeader("User-Agent")).thenReturn("Test-Agent");
        stubBody(INITIALIZE);
        
        servlet.service(request, response);
        
        verify(prompt).ask(new ApprovalRequest("127.0.0.1", "localhost", 51325, PORT, "Test-Agent"));
    }
    
    @Test
    void service_ng_rejectsNonJsonInitializeWithoutAskingTheUser() throws Exception {
        // A browser sends a text/plain body to another origin without asking it first, and the transport would refuse the body anyway, so it must not make the dialog appear
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("text/plain;charset=UTF-8");
        stubBody(INITIALIZE);
        
        servlet.service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
        verify(prompt, never()).ask(any());
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void isJsonContentType_ok_acceptsApplicationJsonWhateverItsParameters() {
        for (String value : List.of("application/json", "Application/JSON", "application/json; charset=utf-8", " application/json ;charset=UTF-8")) {
            assertTrue(McpClientApprovalServlet.isJsonContentType(value), value);
        }
    }
    
    @Test
    void isJsonContentType_ng_refusesEveryOtherMediaType() {
        assertFalse(McpClientApprovalServlet.isJsonContentType(null));
        
        // The first three are the ones a browser sends to another origin without asking it first
        for (String value : List.of("text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "application/json-patch+json", "")) {
            assertFalse(McpClientApprovalServlet.isJsonContentType(value), value);
        }
    }
    
    @Test
    @Timeout(30)
    void service_ok_parallelInitializeWaitsForTheDialogOnScreen() throws Exception {
        // Clients such as Codex CLI open several connections at once and give up on the first refusal, so an initialize arriving while the dialog is on screen must wait for its turn, not be rejected.
        // The dialogs must not overlap either: for a single servlet, that is for a single port, at most one may be on screen at a time.
        // The two clients differ, so that the second one gets a dialog of its own rather than the grace period of the first (ConnectionApprovalTest covers that case).
        AtomicInteger inDialog = new AtomicInteger();
        AtomicInteger maxInDialog = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch dialogShown = new CountDownLatch(1);
        CountDownLatch releaseDialog = new CountDownLatch(1);
        doAnswer(invocation -> {
            maxInDialog.accumulateAndGet(inDialog.incrementAndGet(), Math::max);
            try {
                dialogShown.countDown();
                assertTrue(releaseDialog.await(10, TimeUnit.SECONDS), "Dialog was never released");
                return true;
                
            } finally {
                inDialog.decrementAndGet();
            }
        }).when(prompt).ask(any());
        HttpServletResponse secondResponse = mock(HttpServletResponse.class);
        
        Thread first = startService(servlet, newInitializeRequest(51325, "Agent-A"), mock(HttpServletResponse.class), failure);
        assertTrue(dialogShown.await(5, TimeUnit.SECONDS), "Dialog was never shown");
        Thread second = startService(servlet, newInitializeRequest(51336, "Agent-B"), secondResponse, failure);
        awaitQueuedOnDialog(second);
        
        // Neither request may reach the transport while the dialog of the first one is still on screen
        verify(delegate, never()).service(any(), any());
        
        releaseDialog.countDown();
        assertTrue(first.join(Duration.ofSeconds(10)), "The first initialize never finished");
        assertTrue(second.join(Duration.ofSeconds(10)), "The second initialize never finished");
        
        if (failure.get() != null) {
            fail("An initialize request failed", failure.get());
        }
        assertEquals(1, maxInDialog.get(), "Two approval dialogs were on screen at the same time");
        verify(prompt, times(2)).ask(any());
        verify(secondResponse, never()).sendError(anyInt(), anyString());
        verify(delegate, times(2)).service(any(), any());
    }
    
    // Build a fully stubbed initialize POST coming from the given client port and User-Agent
    private HttpServletRequest newInitializeRequest(int remotePort, String userAgent) throws Exception {
        return newInitializeRequest(remotePort, userAgent, "application/json");
    }
    
    private HttpServletRequest newInitializeRequest(int remotePort, String userAgent, String contentType) throws Exception {
        HttpServletRequest initializeRequest = mock(HttpServletRequest.class);
        when(initializeRequest.getHeaders("Host")).thenAnswer(invocation -> headers(THIS_SERVER));
        when(initializeRequest.getLocalPort()).thenReturn(PORT);
        when(initializeRequest.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(initializeRequest.getHeader("User-Agent")).thenReturn(userAgent);
        when(initializeRequest.getMethod()).thenReturn("POST");
        when(initializeRequest.getContentType()).thenReturn(contentType);
        when(initializeRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(initializeRequest.getRemotePort()).thenReturn(remotePort);
        when(initializeRequest.getRemoteHost()).thenReturn("localhost");
        stubBody(initializeRequest, INITIALIZE);
        return initializeRequest;
    }
    
    private Thread startService(McpClientApprovalServlet target,
            HttpServletRequest req,
            HttpServletResponse resp,
            AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                target.service(req, resp);
            } catch (Throwable t) {
                // Reported by the test thread; throwing here would only reach stderr
                failure.compareAndSet(null, t);
            }
        });
        // Daemon so that a regression which never releases the dialog lock fails the test instead of hanging the build
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
    
    private static final String SMALL_POST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    
    private static final String JAPANESE_POST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"set_name\",\"arguments\":{\"name\":\"クラス図\"}}}";
    
    // A servlet whose limit is the given number of bytes, so the boundary can be exercised without a 16 MiB body
    private McpClientApprovalServlet servletLimitedTo(int maxRequestSizeBytes) {
        return new McpClientApprovalServlet(delegate, new ConnectionApproval(prompt), new InitializeRateLimit(), maxRequestSizeBytes);
    }
    
    // A servlet that lets one initialize through and refuses the next until the clock, which only the test moves, reaches the next minute
    private McpClientApprovalServlet servletLettingOneInitializeThroughPerMinute() {
        return new McpClientApprovalServlet(delegate, new ConnectionApproval(prompt), new InitializeRateLimit(() -> 0L, 1, 1));
    }
    
    @Test
    void service_ng_refusesInitializeOverTheRateLimitWithoutAskingTheUser() throws Exception {
        when(prompt.ask(any())).thenReturn(true);
        McpClientApprovalServlet limited = servletLettingOneInitializeThroughPerMinute();
        limited.service(newInitializeRequest(51325, "Agent-A"), mock(HttpServletResponse.class));
        HttpServletResponse refused = mock(HttpServletResponse.class);
        
        // Another User-Agent and another client port, as a client changes them freely: the budget is the port's, not the client's
        limited.service(newInitializeRequest(51336, "Agent-B"), refused);
        
        verify(refused).setHeader("Retry-After", "60");
        verify(refused).sendError(McpClientApprovalServlet.SC_TOO_MANY_REQUESTS, "Too many initialize requests");
        verify(prompt, times(1)).ask(any());
        verify(delegate, times(1)).service(any(), any());
    }
    
    @Test
    void service_ng_countsAnInitializeTheUserDeclined() throws Exception {
        // Otherwise a client could keep the dialog on screen by sending initialize after initialize
        when(prompt.ask(any())).thenReturn(false);
        McpClientApprovalServlet limited = servletLettingOneInitializeThroughPerMinute();
        HttpServletResponse declined = mock(HttpServletResponse.class);
        HttpServletResponse refused = mock(HttpServletResponse.class);
        
        limited.service(newInitializeRequest(51325, "Agent-A"), declined);
        limited.service(newInitializeRequest(51336, "Agent-A"), refused);
        
        verify(declined).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Connection not approved by user");
        verify(refused).sendError(McpClientApprovalServlet.SC_TOO_MANY_REQUESTS, "Too many initialize requests");
        verify(prompt, times(1)).ask(any());
    }
    
    @Test
    void service_ok_letsEveryOtherRequestThroughOnceTheInitializeBudgetIsUsedUp() throws Exception {
        // Only a new session is bounded; the sessions already open keep working
        when(prompt.ask(any())).thenReturn(true);
        McpClientApprovalServlet limited = servletLettingOneInitializeThroughPerMinute();
        limited.service(newInitializeRequest(51325, "Agent-A"), mock(HttpServletResponse.class));
        stubSessionPost();
        stubBody(SMALL_POST);
        
        limited.service(request, response);
        
        verify(response, never()).sendError(anyInt(), anyString());
        verify(delegate, times(2)).service(any(), any());
    }
    
    @Test
    void service_ok_aNonJsonInitializeDoesNotUseTheBudget() throws Exception {
        // It is refused for its Content-Type before the budget is looked at, so it cannot crowd out a real client
        when(prompt.ask(any())).thenReturn(true);
        McpClientApprovalServlet limited = servletLettingOneInitializeThroughPerMinute();
        HttpServletRequest plainText = newInitializeRequest(51325, "Agent-A", "text/plain;charset=UTF-8");
        HttpServletResponse unsupported = mock(HttpServletResponse.class);
        HttpServletResponse served = mock(HttpServletResponse.class);
        
        limited.service(plainText, unsupported);
        limited.service(newInitializeRequest(51336, "Agent-A"), served);
        
        verify(unsupported).sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
        verify(served, never()).sendError(anyInt(), anyString());
        verify(delegate, times(1)).service(any(), any());
    }
    
    // Stub what every POST below shares: an established session, no Origin header, no declared Content-Length
    private void stubSessionPost() {
        when(request.getHeader("Mcp-Session-Id")).thenReturn("session-123");
        when(request.getMethod()).thenReturn("POST");
    }
    
    // Body stub that reports how many bytes the servlet actually pulled from the client
    private AtomicInteger stubCountingBody(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AtomicInteger delivered = new AtomicInteger();
        when(request.getInputStream()).thenReturn(new ServletInputStream() {
            private final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            
            @Override
            public int read() {
                int value = bais.read();
                if (value != -1) {
                    delivered.incrementAndGet();
                }
                return value;
            }
            
            @Override
            public int read(byte[] b, int off, int len) {
                int count = bais.read(b, off, len);
                if (count > 0) {
                    delivered.addAndGet(count);
                }
                return count;
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
            }
        });
        return delivered;
    }
    
    // The request the servlet handed to the transport
    private HttpServletRequest captureDelegatedRequest() throws Exception {
        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(delegate).service(captor.capture(), ArgumentMatchers.any(HttpServletResponse.class));
        return captor.getValue();
    }
    
    private static String readBodyOf(HttpServletRequest delegated) throws Exception {
        return new String(delegated.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    
    @Test
    void service_ok_acceptsBodyExactlyAtTheLimit() throws Exception {
        int exactly = SMALL_POST.getBytes(StandardCharsets.UTF_8).length;
        stubSessionPost();
        stubBody(SMALL_POST);
        
        servletLimitedTo(exactly).service(request, response);
        
        verify(response, never()).sendError(anyInt());
        assertEquals(SMALL_POST, readBodyOf(captureDelegatedRequest()),
                "A body at the limit must reach the transport unchanged");
    }
    
    @Test
    void service_ok_acceptsBodyBelowTheLimit() throws Exception {
        stubSessionPost();
        stubBody(SMALL_POST);
        
        servletLimitedTo(SMALL_POST.getBytes(StandardCharsets.UTF_8).length + 1024).service(request, response);
        
        verify(response, never()).sendError(anyInt());
        assertEquals(SMALL_POST, readBodyOf(captureDelegatedRequest()));
    }
    
    @Test
    void service_ng_rejectsBodyOneByteOverTheLimit() throws Exception {
        int oneByteShortOfTheBody = SMALL_POST.getBytes(StandardCharsets.UTF_8).length - 1;
        stubSessionPost();
        stubBody(SMALL_POST);
        
        servletLimitedTo(oneByteShortOfTheBody).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsOnContentLengthWithoutReadingTheBody() throws Exception {
        // An announced length above the limit must be refused before a single byte is pulled from the client
        stubSessionPost();
        when(request.getContentLengthLong()).thenReturn(64L * 1024 * 1024);
        
        servletLimitedTo(1024).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(request, never()).getInputStream();
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsOversizedBodyWhenLengthIsUnknown() throws Exception {
        // A chunked request reports -1; only the bytes actually read can catch it
        stubSessionPost();
        when(request.getContentLengthLong()).thenReturn(-1L);
        AtomicInteger delivered = stubCountingBody("x".repeat(40_000));
        
        servletLimitedTo(1024).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(delegate, never()).service(any(), any());
        assertTrue(delivered.get() <= 1025,
                "The servlet must stop at the limit plus the byte that proves it was passed, but read " + delivered.get());
    }
    
    @Test
    void service_ng_rejectsOversizedBodyWhenContentLengthUnderstatesIt() throws Exception {
        // A length small enough to clear the early check must not exempt the body from the real count
        stubSessionPost();
        when(request.getContentLengthLong()).thenReturn(10L);
        AtomicInteger delivered = stubCountingBody("x".repeat(40_000));
        
        servletLimitedTo(1024).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(delegate, never()).service(any(), any());
        assertTrue(delivered.get() <= 1025, "Read " + delivered.get() + " bytes despite the limit of 1024");
    }
    
    @Test
    void service_ng_rejectsOversizedInitializeBeforeAskingTheUser() throws Exception {
        // Size is settled before the approval dialog, so an oversized body cannot make the dialog appear
        stubSessionPost();
        stubBody(INITIALIZE);
        
        servletLimitedTo(16).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(prompt, never()).ask(any());
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_rejectsOversizedServerDiscoverBeforeAnsweringIt() throws Exception {
        // The probe reply is this servlet's own, so it too must come after the size check
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        stubBody("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\",\"params\":{}}");
        
        servletLimitedTo(16).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(response, never()).getWriter();
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ng_limitsJapaneseBodyByUtf8BytesNotCharacters() throws Exception {
        int utf8Bytes = JAPANESE_POST.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(utf8Bytes > JAPANESE_POST.length(),
                "The fixture must be one whose byte count and character count differ");
        
        stubSessionPost();
        stubBody(JAPANESE_POST);
        
        // One byte short of the UTF-8 length, but still well above the character count
        servletLimitedTo(utf8Bytes - 1).service(request, response);
        
        verify(response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(delegate, never()).service(any(), any());
    }
    
    @Test
    void service_ok_passesJapaneseBodyToTheTransportUnchanged() throws Exception {
        stubSessionPost();
        stubBody(JAPANESE_POST);
        
        servletLimitedTo(McpServerConfig.MCP_MAX_REQUEST_SIZE_BYTES).service(request, response);
        
        assertEquals(JAPANESE_POST, readBodyOf(captureDelegatedRequest()));
    }
    
    @Test
    void service_ok_keepsLineBreaksInsideTheBody() throws Exception {
        // SDK 2.0.0 read the body with readLine() and dropped the line breaks; 2.0.1 keeps the bytes. A script
        // argument carrying escaped line breaks must survive this servlet's cache and reach the transport intact.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"run_mcp_tool_script\","
                + "\"arguments\":{\"source\":\"// first\\nconst a = 1;\\n\"}}}\n";
        stubSessionPost();
        stubBody(body);
        
        servletLimitedTo(McpServerConfig.MCP_MAX_REQUEST_SIZE_BYTES).service(request, response);
        
        assertEquals(body, readBodyOf(captureDelegatedRequest()),
                "The body handed to the transport must be byte-identical, line breaks included");
    }
    
    @Test
    void service_ok_reportsUtf8ToTheTransportWhateverTheClientDeclared() throws Exception {
        // This servlet parses the cached bytes as UTF-8, and the transport decodes the same bytes with whatever
        // getCharacterEncoding() reports. Reporting UTF-8 is what keeps the two parses identical.
        stubSessionPost();
        stubBody(SMALL_POST);
        
        servletLimitedTo(McpServerConfig.MCP_MAX_REQUEST_SIZE_BYTES).service(request, response);
        
        assertEquals("UTF-8", captureDelegatedRequest().getCharacterEncoding());
    }
    
    @Test
    void servlet_ng_rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> servletLimitedTo(0));
    }
    
    // Wait until the thread has queued up behind the approval dialog
    private void awaitQueuedOnDialog(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            // The dialog lock is acquired with a timeout, so a queued thread parks in TIMED_WAITING
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        fail("The second initialize never queued up behind the approval dialog (thread state: " + thread.getState() + ")");
    }
}
