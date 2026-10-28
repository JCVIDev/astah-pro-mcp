package com.astahpromcp.tls.emudhra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// The public keys the eMudhra workaround distrusts, each identified by the SHA-256 of its SubjectPublicKeyInfo.
// A key rather than a certificate, so that every certificate issued for a compromised key is caught.
record DistrustedKeys(Set<String> roots, Set<String> intermediates) {
    
    // Next to this class, so that the list goes with the package when the workaround is removed
    static final String RESOURCE = "distrusted-keys.txt";
    
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    
    DistrustedKeys {
        roots = Set.copyOf(roots);
        intermediates = Set.copyOf(intermediates);
    }
    
    // The list bundled with the plugin
    static DistrustedKeys load() {
        try (InputStream in = DistrustedKeys.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + RESOURCE);
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(reader.lines().toList());
            
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
    
    // Each line is "<root|intermediate> <SHA-256 of the SubjectPublicKeyInfo as lowercase hex> <name>"; '#' starts a comment
    static DistrustedKeys parse(List<String> lines) {
        Set<String> roots = new HashSet<>();
        Set<String> intermediates = new HashSet<>();
        Set<String> seen = new HashSet<>();
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int commentStart = line.indexOf('#');
            String content = (commentStart < 0 ? line : line.substring(0, commentStart)).strip();
            if (content.isEmpty()) {
                continue;
            }
            
            String[] fields = content.split("\\s+", 3);
            if (fields.length < 2 || !SHA256_HEX.matcher(fields[1]).matches()) {
                throw new IllegalArgumentException("Line " + (i + 1) + " is not '<kind> <SHA-256 as lowercase hex> <name>': " + line);
            }
            if (!seen.add(fields[1])) {
                throw new IllegalArgumentException("Line " + (i + 1) + " lists key " + fields[1] + " again");
            }
            
            switch (fields[0]) {
                case "root" -> roots.add(fields[1]);
                case "intermediate" -> intermediates.add(fields[1]);
                default -> throw new IllegalArgumentException("Line " + (i + 1) + " has the unknown kind '" + fields[0] + "'");
            }
        }
        
        // Without a root, the plugin's own connections would go on trusting the roots that OpenJDK removed
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("The list names no root");
        }
        return new DistrustedKeys(roots, intermediates);
    }
    
    // Whether the certificate carries the key of a listed root
    boolean isRoot(X509Certificate certificate) {
        return roots.contains(keyHash(certificate));
    }
    
    // Whether the certificate carries any listed key
    boolean isDistrusted(X509Certificate certificate) {
        String key = keyHash(certificate);
        return roots.contains(key) || intermediates.contains(key);
    }
    
    // SHA-256 of the certificate's SubjectPublicKeyInfo, as lowercase hex
    static String keyHash(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
            return HexFormat.of().formatHex(digest);
            
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
