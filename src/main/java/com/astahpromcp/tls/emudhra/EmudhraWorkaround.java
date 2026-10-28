package com.astahpromcp.tls.emudhra;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Temporary workaround for the eMudhra intermediate CA key compromise (CVE-2026-91403, CVE-2026-114820).
@Slf4j
public final class EmudhraWorkaround {
    
    private static final String CVES = "CVE-2026-91403, CVE-2026-114820";
    
    // Built once, for the Java that runs Astah
    private static volatile EmudhraWorkaround forRunningJava;
    
    // The trust policy for the plugin's own connections
    private final SSLContext sslContext;
    
    // Whether the running Java still trusts a root that OpenJDK removed
    private final boolean trustsRemovedRoot;
    
    private EmudhraWorkaround(SSLContext sslContext, boolean trustsRemovedRoot) {
        this.sslContext = sslContext;
        this.trustsRemovedRoot = trustsRemovedRoot;
    }
    
    // Has the client this builder builds use the trust policy
    public static void apply(HttpClient.Builder builder) {
        forRunningJava().applyTo(builder);
    }
    
    // Has the connection use the trust policy, if it is an HTTPS connection
    public static void apply(URLConnection connection) {
        forRunningJava().applyTo(connection);
    }
    
    // Logs that the workaround is applied, and whether the running Java still trusts a removed root, which is part of what tells when it can be removed
    public static void logStatus() {
        String runtime = System.getProperty("java.runtime.version") + ", " + System.getProperty("java.home");
        if (forRunningJava().trustsRemovedRoot()) {
            log.warn("eMudhra workaround is applied: the plugin's own HTTPS connections reject certificate chains that contain a key distrusted after the eMudhra CA key compromise ({}). "
                    + "This Java ({}) still trusts a root that OpenJDK removed, and Astah's other connections are not covered.",
                    CVES, runtime);
        } else {
            log.info("eMudhra workaround is applied: the plugin's own HTTPS connections reject certificate chains that contain a key distrusted after the eMudhra CA key compromise ({}). "
                    + "This Java ({}) trusts none of the roots that OpenJDK removed, which alone does not show that it rejects the compromised intermediate CAs.",
                    CVES, runtime);
        }
    }
    
    private static EmudhraWorkaround forRunningJava() {
        EmudhraWorkaround current = forRunningJava;
        if (current == null) {
            synchronized (EmudhraWorkaround.class) {
                current = forRunningJava;
                if (current == null) {
                    current = forTrustedCAs(defaultTrustedCAs(), DistrustedKeys.load());
                    forRunningJava = current;
                }
            }
        }
        return current;
    }
    
    // The workaround for a Java that trusts these CAs, which applies its trust policy whether or not any listed root is among them
    static EmudhraWorkaround forTrustedCAs(Collection<X509Certificate> trustedCAs, DistrustedKeys keys) {
        try {
            return new EmudhraWorkaround(trustPolicy(trustedCAs, keys), trustedCAs.stream().anyMatch(keys::isRoot));
            
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build the trust policy of the eMudhra workaround", e);
        }
    }
    
    boolean trustsRemovedRoot() {
        return trustsRemovedRoot;
    }
    
    SSLContext sslContext() {
        return sslContext;
    }
    
    void applyTo(HttpClient.Builder builder) {
        builder.sslContext(sslContext);
    }
    
    void applyTo(URLConnection connection) {
        if (connection instanceof HttpsURLConnection httpsConnection) {
            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
        }
    }
    
    // The CAs the running Java trusts by default, as its default trust manager sees them
    private static List<X509Certificate> defaultTrustedCAs() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509TrustManager) {
                    return List.of(x509TrustManager.getAcceptedIssuers());
                }
            }
            throw new IllegalStateException("The default trust manager factory provides no X509TrustManager");
            
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to read the CAs the running Java trusts", e);
        }
    }
    
    // The trusted CAs without the distrusted ones, plus a check that rejects every certificate carrying a distrusted key
    private static SSLContext trustPolicy(Collection<X509Certificate> trustedCAs, DistrustedKeys keys) throws GeneralSecurityException {
        Set<TrustAnchor> anchors = trustedCAs.stream()
                .filter(ca -> !keys.isDistrusted(ca))
                .map(ca -> new TrustAnchor(ca, null))
                .collect(Collectors.toSet());
        
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, new X509CertSelector());
        // The revocation setting the default trust manager uses, so that the policy never checks less than the Java default does
        parameters.setRevocationEnabled(Boolean.getBoolean("com.sun.net.ssl.checkRevocation"));
        parameters.addCertPathChecker(new DistrustedKeyChecker(keys));
        
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        factory.init(new CertPathTrustManagerParameters(parameters));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
    
    // Rejects a certificate path in which any certificate carries a distrusted key
    private static final class DistrustedKeyChecker extends PKIXCertPathChecker {
        
        private final DistrustedKeys keys;
        
        DistrustedKeyChecker(DistrustedKeys keys) {
            this.keys = keys;
        }
        
        @Override
        public void init(boolean forward) {
            // Stateless: each certificate is judged on its own key
        }
        
        @Override
        public boolean isForwardCheckingSupported() {
            return true;
        }
        
        @Override
        public Set<String> getSupportedExtensions() {
            return null;
        }
        
        @Override
        public void check(Certificate certificate, Collection<String> unresolvedCriticalExtensions) throws CertPathValidatorException {
            if (certificate instanceof X509Certificate x509Certificate && keys.isDistrusted(x509Certificate)) {
                throw new CertPathValidatorException("Rejected by the eMudhra workaround (" + CVES + "): "
                        + x509Certificate.getSubjectX500Principal() + " carries a key distrusted after the eMudhra CA key compromise");
            }
        }
    }
}
