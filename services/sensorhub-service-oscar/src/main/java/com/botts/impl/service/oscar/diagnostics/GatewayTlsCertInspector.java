/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.service.oscar.diagnostics;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inspects the public HTTPS gateway X.509 certificate mounted read-only at
 * {@code /run/secrets/tls_certificate.pem} (never reads private key material)
 * so operators receive proactive renewal alerts before a multi-year self-signed
 * or site-issued certificate expires.
 */
public final class GatewayTlsCertInspector {

    public static final Path DEFAULT_CERT_PATH = Path.of("/run/secrets/tls_certificate.pem");
    public static final String CERT_PATH_SYSPROP = "oscar.gateway.tls.cert.path";
    public static final String CERT_PATH_ENV = "OSCAR_GATEWAY_TLS_CERT_FILE";

    public static final long WARNING_DAYS_THRESHOLD = 60L;
    public static final long CRITICAL_DAYS_THRESHOLD = 14L;

    public static final String STATUS_OK = "OK";
    public static final String STATUS_WARNING = "WARNING";
    public static final String STATUS_CRITICAL = "CRITICAL";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private static final long MAX_CERT_BYTES = 128L * 1024L;

    public record GatewayTlsStatus(
            boolean available,
            String status,
            Long daysRemaining,
            String notBefore,
            String notAfter,
            String subject,
            String issuer,
            boolean selfSigned
    ) {
        public static GatewayTlsStatus unavailable() {
            return new GatewayTlsStatus(false, STATUS_UNAVAILABLE, null, null, null, null, null, false);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("available", available);
            map.put("status", status);
            if (daysRemaining != null) {
                map.put("daysRemaining", daysRemaining);
            }
            if (notBefore != null) {
                map.put("notBefore", notBefore);
            }
            if (notAfter != null) {
                map.put("notAfter", notAfter);
            }
            if (subject != null) {
                map.put("subject", subject);
            }
            if (issuer != null) {
                map.put("issuer", issuer);
            }
            if (available) {
                map.put("selfSigned", selfSigned);
            }
            return map;
        }
    }

    private final Path certPath;
    private final Clock clock;

    public GatewayTlsCertInspector() {
        this(resolveConfiguredCertPath(), Clock.systemUTC());
    }

    public GatewayTlsCertInspector(Path certPath, Clock clock) {
        this.certPath = certPath;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static Path resolveConfiguredCertPath() {
        String sys = System.getProperty(CERT_PATH_SYSPROP);
        if (sys != null && !sys.isBlank()) {
            return Path.of(sys.trim());
        }
        String env = System.getenv(CERT_PATH_ENV);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return DEFAULT_CERT_PATH;
    }

    public GatewayTlsStatus inspect() {
        if (certPath == null) {
            return GatewayTlsStatus.unavailable();
        }
        try {
            if (!Files.isRegularFile(certPath, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(certPath)) {
                return GatewayTlsStatus.unavailable();
            }
            long size = Files.size(certPath);
            if (size <= 0L || size > MAX_CERT_BYTES) {
                return GatewayTlsStatus.unavailable();
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (InputStream in = Files.newInputStream(certPath)) {
                var parsed = factory.generateCertificate(in);
                if (!(parsed instanceof X509Certificate x509)) {
                    return GatewayTlsStatus.unavailable();
                }
                cert = x509;
            }
            return evaluateCertificate(cert, clock.instant());
        } catch (Exception ignored) {
            return GatewayTlsStatus.unavailable();
        }
    }

    public static GatewayTlsStatus evaluateCertificate(X509Certificate cert, Instant now) {
        if (cert == null || cert.getNotAfter() == null) {
            return GatewayTlsStatus.unavailable();
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        Instant notBefore = cert.getNotBefore() != null ? cert.getNotBefore().toInstant() : null;
        Instant notAfter = cert.getNotAfter().toInstant();

        long secondsRemaining = Duration.between(effectiveNow, notAfter).getSeconds();
        long daysRemaining = secondsRemaining <= 0L
                ? Math.floorDiv(secondsRemaining, 86_400L)
                : secondsRemaining / 86_400L;

        String status;
        if (secondsRemaining <= 0L) {
            status = STATUS_EXPIRED;
        } else if (daysRemaining < CRITICAL_DAYS_THRESHOLD) {
            status = STATUS_CRITICAL;
        } else if (daysRemaining < WARNING_DAYS_THRESHOLD) {
            status = STATUS_WARNING;
        } else {
            status = STATUS_OK;
        }

        String subject = cert.getSubjectX500Principal() != null ? cert.getSubjectX500Principal().getName() : "";
        String issuer = cert.getIssuerX500Principal() != null ? cert.getIssuerX500Principal().getName() : "";
        boolean selfSigned = !subject.isEmpty() && subject.equals(issuer);

        return new GatewayTlsStatus(
                true,
                status,
                daysRemaining,
                notBefore != null ? notBefore.toString() : null,
                notAfter.toString(),
                subject,
                issuer,
                selfSigned);
    }
}
