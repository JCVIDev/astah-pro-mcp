package com.astahpromcp.tls.emudhra;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class EmudhraWorkaroundTest {
    
    private static final char[] PASSWORD = "changeit".toCharArray();
    
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    
    private static X509Certificate root;
    private static X509Certificate otherRoot;
    private static X509Certificate intermediate;
    
    private static HttpsServer server;
    private static String url;
    
    @BeforeAll
    static void startServer() throws Exception {
        KeyStore pki = KeyStore.getInstance("PKCS12");
        try (InputStream in = EmudhraWorkaroundTest.class.getResourceAsStream("test-pki.p12")) {
            pki.load(in, PASSWORD);
        }
        root = (X509Certificate) pki.getCertificate("root");
        otherRoot = (X509Certificate) pki.getCertificate("other-root");
        intermediate = (X509Certificate) pki.getCertificate("intermediate");
        
        // The server's own key only, so that it cannot present another entry of the keystore
        KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
        serverKeyStore.load(null, null);
        serverKeyStore.setKeyEntry("server", (PrivateKey) pki.getKey("server", PASSWORD), PASSWORD, pki.getCertificateChain("server"));
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(serverKeyStore, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagerFactory.getKeyManagers(), null, null);
        
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
    
    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }
    
    @Test
    void forTrustedCAs_ok_tellsWhetherAListedRootIsStillTrusted() {
        assertTrue(EmudhraWorkaround.forTrustedCAs(List.of(root, otherRoot), keys(Set.of(otherRoot), Set.of())).trustsRemovedRoot());
        assertFalse(EmudhraWorkaround.forTrustedCAs(List.of(root), keys(Set.of(otherRoot), Set.of())).trustsRemovedRoot());
    }
    
    @Test
    void applyTo_ok_appliesTheTrustPolicyEvenWhileNoListedRootIsTrusted() throws Exception {
        EmudhraWorkaround workaround = EmudhraWorkaround.forTrustedCAs(List.of(root), keys(Set.of(otherRoot), Set.of()));

        assertNotSame(SSLContext.getDefault(), workaround.sslContext());

        HttpClient.Builder builder = HttpClient.newBuilder();
        workaround.applyTo(builder);
        try (HttpClient client = builder.build()) {
            assertSame(workaround.sslContext(), client.sslContext());
        }
        
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        workaround.applyTo(connection);
        assertNotSame(HttpsURLConnection.getDefaultSSLSocketFactory(), connection.getSSLSocketFactory());
    }
    
    @Test
    void applyTo_ok_acceptsAChainWithoutADistrustedKey() throws Exception {
        // Nothing in the server's chain is listed
        EmudhraWorkaround workaround = EmudhraWorkaround.forTrustedCAs(List.of(root), keys(Set.of(otherRoot), Set.of()));
        
        assertEquals(200, getWithUrlConnection(workaround));
        assertEquals(200, getWithHttpClient(workaround));
    }
    
    @Test
    void applyTo_ng_rejectsAChainThatContainsADistrustedIntermediateKey() {
        // A listed root is still trusted, as on a Java that predates the fix
        EmudhraWorkaround workaround = EmudhraWorkaround.forTrustedCAs(List.of(root, otherRoot), keys(Set.of(otherRoot), Set.of(intermediate)));
        
        assertRejectedByTheWorkaround(assertThrows(SSLHandshakeException.class, () -> getWithUrlConnection(workaround)));
        assertRejectedByTheWorkaround(assertThrows(IOException.class, () -> getWithHttpClient(workaround)));
    }
    
    @Test
    void applyTo_ng_rejectsADistrustedIntermediateKeyEvenWhileNoListedRootIsTrusted() {
        // The server's chain leads through the listed intermediate to a root that is trusted but not listed, as a
        // cross-signature by AAA Certificate Services does for a trust store that lacks the removed emSign roots
        EmudhraWorkaround workaround = EmudhraWorkaround.forTrustedCAs(List.of(root), keys(Set.of(otherRoot), Set.of(intermediate)));
        
        assertRejectedByTheWorkaround(assertThrows(SSLHandshakeException.class, () -> getWithUrlConnection(workaround)));
        assertRejectedByTheWorkaround(assertThrows(IOException.class, () -> getWithHttpClient(workaround)));
    }
    
    @Test
    void applyTo_ng_rejectsAChainThatEndsAtADistrustedRoot() {
        // The listed root stops being a trust anchor, so the server's chain leads to nothing trusted
        EmudhraWorkaround workaround = EmudhraWorkaround.forTrustedCAs(List.of(root, otherRoot), keys(Set.of(root), Set.of()));
        
        assertThrows(SSLHandshakeException.class, () -> getWithUrlConnection(workaround));
        assertThrows(IOException.class, () -> getWithHttpClient(workaround));
    }
    
    @Test
    void logStatus_ok_buildsTheWorkaroundForTheRunningJavaWithTheBundledList() {
        // Whether a listed root is still trusted depends on the Java running the tests; either way the bundled list has to load and the trust policy has to be built
        assertDoesNotThrow(EmudhraWorkaround::logStatus);
    }
    
    private static DistrustedKeys keys(Set<X509Certificate> roots, Set<X509Certificate> intermediates) {
        return new DistrustedKeys(keyHashes(roots), keyHashes(intermediates));
    }
    
    private static Set<String> keyHashes(Set<X509Certificate> certificates) {
        return certificates.stream().map(DistrustedKeys::keyHash).collect(Collectors.toSet());
    }
    
    private static int getWithUrlConnection(EmudhraWorkaround workaround) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        workaround.applyTo(connection);
        connection.setConnectTimeout((int) TIMEOUT.toMillis());
        connection.setReadTimeout((int) TIMEOUT.toMillis());
        try {
            return connection.getResponseCode();
            
        } finally {
            connection.disconnect();
        }
    }
    
    private static int getWithHttpClient(EmudhraWorkaround workaround) throws IOException, InterruptedException {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        workaround.applyTo(builder);
        try (HttpClient client = builder.build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
    
    // The rejection has to come from the workaround's own check, not from some other failure of the handshake
    private static void assertRejectedByTheWorkaround(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("Rejected by the eMudhra workaround")) {
                return;
            }
        }
        fail("The handshake failed for another reason than the eMudhra workaround", thrown);
    }
}
