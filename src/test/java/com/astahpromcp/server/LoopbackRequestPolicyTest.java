package com.astahpromcp.server;

import com.astahpromcp.server.LoopbackRequestPolicy.Refusal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class LoopbackRequestPolicyTest {
    
    private static final int PORT = 8888;
    
    @Test
    void check_ok_acceptsAHostThatNamesThisServerAndNoOrigin() {
        for (String host : List.of("127.0.0.1:8888", "localhost:8888", "LOCALHOST:8888", " 127.0.0.1:8888 ")) {
            assertEquals(Optional.empty(), LoopbackRequestPolicy.check(List.of(host), List.of(), PORT), host);
        }
    }
    
    @Test
    void check_ok_takesAHostWithoutAPortForTheDefaultHttpPort() {
        assertEquals(Optional.empty(), LoopbackRequestPolicy.check(List.of("127.0.0.1"), List.of(), 80));
    }
    
    @Test
    void check_ng_refusesAHostThatDoesNotNameThisServer() {
        List<String> hosts = List.of(
                "evil.example:8888",            // a page reached through DNS rebinding sends its own host name
                "127.0.0.1.evil.example:8888",
                "localhost.:8888",
                "user@127.0.0.1:8888",
                "[::1]:8888",                   // the server listens on IPv4 loopback only
                "[::1",
                "127.0.0.1:9",                  // another port
                "127.0.0.1",                    // no port, which stands for 80
                "127.0.0.1:",
                "127.0.0.1:8888x",
                "127.0.0.1:+8888",
                "127.0.0.1:088888",
                "");
        for (String host : hosts) {
            assertEquals(Optional.of(Refusal.HOST_NOT_ALLOWED), LoopbackRequestPolicy.check(List.of(host), List.of(), PORT), host);
        }
    }
    
    @Test
    void check_ng_refusesNoHostAndMoreThanOne() {
        assertEquals(Optional.of(Refusal.HOST_NOT_ALLOWED), LoopbackRequestPolicy.check(List.of(), List.of(), PORT));
        assertEquals(Optional.of(Refusal.HOST_NOT_ALLOWED),
                LoopbackRequestPolicy.check(List.of("127.0.0.1:8888", "127.0.0.1:8888"), List.of(), PORT));
    }
    
    @Test
    void check_ng_refusesEveryOrigin() {
        // Pages on this machine, whatever their port or scheme, as well as foreign ones and the opaque origin
        List<String> origins = List.of(
                "http://127.0.0.1:44444",
                "http://[::1]:3000",
                "ftp://127.0.0.1",
                "http://localhost:6274",
                "http://evil.example",
                "null",
                "");
        for (String origin : origins) {
            assertEquals(Optional.of(Refusal.ORIGIN_NOT_ALLOWED),
                    LoopbackRequestPolicy.check(List.of("127.0.0.1:8888"), List.of(origin), PORT), origin);
        }
        
        assertEquals(Optional.of(Refusal.ORIGIN_NOT_ALLOWED),
                LoopbackRequestPolicy.check(List.of("127.0.0.1:8888"), List.of("http://127.0.0.1:3000", "http://evil.example"), PORT));
    }
    
    @Test
    void check_ng_reportsTheHostWhenBothHeadersAreWrong() {
        assertEquals(Optional.of(Refusal.HOST_NOT_ALLOWED),
                LoopbackRequestPolicy.check(List.of("evil.example:8888"), List.of("http://evil.example:8888"), PORT));
    }
}
