/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Hot-reloadable composite X.509 TrustManager registry combining the shipped JVM system
 * truststore with administrator-uploaded Enterprise Root / Intermediate CA certificates.
 *
 * <p>Uploaded PEM/CRT bundles are strictly validated to reject any private-key material
 * before persisting or loading into memory.</p>
 */
public final class DynamicTrustManagerRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DynamicTrustManagerRegistry.class);
    private static final Pattern PRIVATE_KEY_MARKER = Pattern.compile(
            "-----BEGIN\\s+[A-Z0-9\\s]*PRIVATE\\s+KEY-----", Pattern.CASE_INSENSITIVE);
    private static final long MAX_PEM_BYTES = 2L * 1024L * 1024L;

    public record TrustedCertInfo(
            String id,
            String fileName,
            String subject,
            String issuer,
            String serialHex,
            String notBefore,
            String notAfter,
            String sha256Fingerprint,
            boolean caCertificate
    ) {}

    public record TrustStatus(
            String activeSource,
            boolean customActive,
            int systemTrustedIssuerCount,
            int customCertificateCount,
            List<TrustedCertInfo> customCertificates,
            long generation,
            String lastError
    ) {}

    private static final Object LOCK = new Object();
    private static final AtomicLong GENERATION = new AtomicLong(1L);
    private static volatile X509TrustManager systemTrustManager;
    private static volatile X509TrustManager customTrustManager;
    private static volatile SSLContext activeSslContext;
    private static volatile List<TrustedCertInfo> activeCertInfos = List.of();
    private static volatile String lastError = "";

    static {
        try {
            systemTrustManager = buildDefaultSystemTrustManager();
            rebuildSslContextLocked();
        } catch (Exception e) {
            logger.warn("Unable to initialize composite SSLContext at class load: {}", e.getMessage());
        }
    }

    private DynamicTrustManagerRegistry() {
    }

    public static long getGeneration() {
        return GENERATION.get();
    }

    public static SSLContext getActiveSslContext() {
        SSLContext ctx = activeSslContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("Default SSLContext unavailable", e);
        }
    }

    public static TrustStatus getStatus() {
        X509TrustManager sys = systemTrustManager;
        int sysCount = (sys != null && sys.getAcceptedIssuers() != null) ? sys.getAcceptedIssuers().length : 0;
        List<TrustedCertInfo> certs = activeCertInfos;
        boolean custom = !certs.isEmpty();
        return new TrustStatus(
                custom ? "custom" : "builtin",
                custom,
                sysCount,
                certs.size(),
                certs,
                GENERATION.get(),
                lastError
        );
    }

    /**
     * Validates raw PEM/DER certificate bytes, strictly rejecting private keys or empty bundles.
     */
    public static List<X509Certificate> parseAndValidateCertificates(String fileName, byte[] rawBytes) throws Exception {
        if (rawBytes == null || rawBytes.length == 0 || rawBytes.length > MAX_PEM_BYTES) {
            throw new IllegalArgumentException("Certificate upload must be between 1 byte and 2 MiB");
        }
        String textPreview = new String(rawBytes, StandardCharsets.ISO_8859_1);
        if (PRIVATE_KEY_MARKER.matcher(textPreview).find()) {
            String msg = "Rejected certificate upload '" + fileName + "': file contains a PRIVATE KEY block. Upload public CA certificates (.pem/.crt) only.";
            lastError = msg;
            throw new SecurityException(msg);
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> parsed = cf.generateCertificates(new ByteArrayInputStream(rawBytes));
        if (parsed == null || parsed.isEmpty()) {
            throw new IllegalArgumentException("No valid X.509 certificates found in " + fileName);
        }
        List<X509Certificate> x509List = new ArrayList<>();
        for (Certificate cert : parsed) {
            if (!(cert instanceof X509Certificate x509)) {
                throw new IllegalArgumentException("Unsupported non-X.509 certificate in " + fileName);
            }
            x509List.add(x509);
        }
        return x509List;
    }

    /**
     * Reloads all {@code .pem}, {@code .crt}, and {@code .cer} files from {@code certStorageDir}
     * and atomically updates the process-wide composite {@link SSLContext}.
     */
    public static TrustStatus reloadFromDirectory(Path certStorageDir) throws Exception {
        synchronized (LOCK) {
            try {
                if (systemTrustManager == null) {
                    systemTrustManager = buildDefaultSystemTrustManager();
                }
                if (certStorageDir == null || !Files.isDirectory(certStorageDir)) {
                    customTrustManager = null;
                    activeCertInfos = List.of();
                    lastError = "";
                    rebuildSslContextLocked();
                    return getStatus();
                }

                List<Path> files;
                try (Stream<Path> s = Files.list(certStorageDir)) {
                    files = s.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return !n.startsWith(".") && (n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer"));
                            })
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                }

                if (files.isEmpty()) {
                    customTrustManager = null;
                    activeCertInfos = List.of();
                    lastError = "";
                    rebuildSslContextLocked();
                    return getStatus();
                }

                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                Map<String, TrustedCertInfo> infoByFp = new LinkedHashMap<>();

                int idx = 0;
                for (Path file : files) {
                    byte[] bytes = Files.readAllBytes(file);
                    List<X509Certificate> certs = parseAndValidateCertificates(file.getFileName().toString(), bytes);
                    for (X509Certificate cert : certs) {
                        String fp = sha256Hex(cert.getEncoded());
                        String alias = "oscar-custom-ca-" + (++idx) + "-" + fp.substring(0, 12);
                        ks.setCertificateEntry(alias, cert);
                        infoByFp.putIfAbsent(fp, new TrustedCertInfo(
                                fp.substring(0, 16),
                                file.getFileName().toString(),
                                cert.getSubjectX500Principal().getName(),
                                cert.getIssuerX500Principal().getName(),
                                cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT),
                                cert.getNotBefore().toInstant().toString(),
                                cert.getNotAfter().toInstant().toString(),
                                fp,
                                cert.getBasicConstraints() >= 0
                        ));
                    }
                }

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                customTrustManager = extractX509TrustManager(tmf.getTrustManagers());
                activeCertInfos = Collections.unmodifiableList(new ArrayList<>(infoByFp.values()));
                lastError = "";
                rebuildSslContextLocked();
                logger.info("Loaded {} custom trusted X.509 certificate(s) into composite SSLContext", activeCertInfos.size());
                return getStatus();
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw e;
            }
        }
    }

    public static TrustStatus resetToSystemDefault() throws Exception {
        synchronized (LOCK) {
            customTrustManager = null;
            activeCertInfos = List.of();
            lastError = "";
            rebuildSslContextLocked();
            logger.info("Reset composite SSLContext to JVM system default truststore");
            return getStatus();
        }
    }

    private static void rebuildSslContextLocked() throws Exception {
        final X509TrustManager sys = systemTrustManager;
        final X509TrustManager cust = customTrustManager;

        X509TrustManager composite = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (cust != null) {
                    try {
                        cust.checkClientTrusted(chain, authType);
                        return;
                    } catch (CertificateException ignored) {
                    }
                }
                sys.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (cust != null) {
                    try {
                        cust.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ignored) {
                    }
                }
                sys.checkServerTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] s = sys.getAcceptedIssuers();
                if (cust == null) {
                    return s;
                }
                X509Certificate[] c = cust.getAcceptedIssuers();
                X509Certificate[] merged = new X509Certificate[s.length + c.length];
                System.arraycopy(s, 0, merged, 0, s.length);
                System.arraycopy(c, 0, merged, s.length, c.length);
                return merged;
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{composite}, new SecureRandom());
        activeSslContext = ctx;
        SSLContext.setDefault(ctx);
        GENERATION.incrementAndGet();
    }

    private static X509TrustManager buildDefaultSystemTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return extractX509TrustManager(tmf.getTrustManagers());
    }

    private static X509TrustManager extractX509TrustManager(TrustManager[] managers) {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
