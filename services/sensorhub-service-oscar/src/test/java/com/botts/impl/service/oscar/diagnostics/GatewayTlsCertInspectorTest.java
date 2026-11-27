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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GatewayTlsCertInspectorTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void evaluatesOkWarningCriticalAndExpiredThresholdsAccurately() {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");

        var ok = GatewayTlsCertInspector.evaluateCertificate(
                stubCert(now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(120)), "CN=oscar.site", "CN=oscar.site"),
                now);
        assertTrue(ok.available());
        assertEquals(GatewayTlsCertInspector.STATUS_OK, ok.status());
        assertEquals(Long.valueOf(120L), ok.daysRemaining());
        assertTrue(ok.selfSigned());

        var warning = GatewayTlsCertInspector.evaluateCertificate(
                stubCert(now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(45)), "CN=oscar.site", "CN=Site CA"),
                now);
        assertTrue(warning.available());
        assertEquals(GatewayTlsCertInspector.STATUS_WARNING, warning.status());
        assertEquals(Long.valueOf(45L), warning.daysRemaining());
        assertFalse(warning.selfSigned());

        var critical = GatewayTlsCertInspector.evaluateCertificate(
                stubCert(now.minus(Duration.ofDays(355)), now.plus(Duration.ofDays(7)), "CN=oscar.site", "CN=oscar.site"),
                now);
        assertEquals(GatewayTlsCertInspector.STATUS_CRITICAL, critical.status());
        assertEquals(Long.valueOf(7L), critical.daysRemaining());

        var expired = GatewayTlsCertInspector.evaluateCertificate(
                stubCert(now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(2)), "CN=oscar.site", "CN=oscar.site"),
                now);
        assertEquals(GatewayTlsCertInspector.STATUS_EXPIRED, expired.status());
        assertTrue(expired.daysRemaining() <= 0L);
    }

    @Test
    public void returnsUnavailableGracefullyWhenCertificateFileIsMissingOrInvalid() throws Exception {
        Path missing = tempFolder.getRoot().toPath().resolve("nonexistent.pem");
        var missingStatus = new GatewayTlsCertInspector(missing, Clock.systemUTC()).inspect();
        assertFalse(missingStatus.available());
        assertEquals(GatewayTlsCertInspector.STATUS_UNAVAILABLE, missingStatus.status());
        assertNull(missingStatus.daysRemaining());

        Path badPem = tempFolder.newFile("bad.pem").toPath();
        Files.writeString(badPem, "not a certificate", StandardCharsets.UTF_8);
        var invalidStatus = new GatewayTlsCertInspector(badPem, Clock.systemUTC()).inspect();
        assertFalse(invalidStatus.available());
        assertEquals(GatewayTlsCertInspector.STATUS_UNAVAILABLE, invalidStatus.status());
    }

    @Test
    public void diagnosticsServletRequiresAuthenticationAndServesTlsStatusJson() throws Exception {
        Path missing = tempFolder.getRoot().toPath().resolve("missing-tls.pem");
        var inspector = new GatewayTlsCertInspector(
                missing, Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC));
        var servlet = new SystemDiagnosticsServlet(inspector, LoggerFactory.getLogger(GatewayTlsCertInspectorTest.class));

        ResponseCapture unauth = new ResponseCapture();
        servlet.doGet(stubRequest("/tls-status", null), unauth.proxy());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, unauth.status);

        ResponseCapture okResp = new ResponseCapture();
        servlet.doGet(stubRequest("/tls-status", "operator"), okResp.proxy());
        assertEquals(HttpServletResponse.SC_OK, okResp.status);
        assertEquals("no-store", okResp.headers.get("Cache-Control"));
        assertEquals("nosniff", okResp.headers.get("X-Content-Type-Options"));

        JsonObject json = JsonParser.parseString(okResp.bodyUtf8()).getAsJsonObject();
        assertFalse(json.get("available").getAsBoolean());
        assertEquals(GatewayTlsCertInspector.STATUS_UNAVAILABLE, json.get("status").getAsString());

        ResponseCapture notFound = new ResponseCapture();
        servlet.doGet(stubRequest("/unknown", "operator"), notFound.proxy());
        assertEquals(HttpServletResponse.SC_NOT_FOUND, notFound.status);
    }

    private static X509Certificate stubCert(Instant notBefore, Instant notAfter, String subjectDn, String issuerDn) {
        return new X509Certificate() {
            @Override public void checkValidity() {}
            @Override public void checkValidity(Date date) {}
            @Override public int getVersion() { return 3; }
            @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
            @Override public Principal getIssuerDN() { return getIssuerX500Principal(); }
            @Override public Principal getSubjectDN() { return getSubjectX500Principal(); }
            @Override public X500Principal getSubjectX500Principal() { return new X500Principal(subjectDn); }
            @Override public X500Principal getIssuerX500Principal() { return new X500Principal(issuerDn); }
            @Override public Date getNotBefore() { return Date.from(notBefore); }
            @Override public Date getNotAfter() { return Date.from(notAfter); }
            @Override public byte[] getTBSCertificate() { return new byte[0]; }
            @Override public byte[] getSignature() { return new byte[0]; }
            @Override public String getSigAlgName() { return "SHA256withRSA"; }
            @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
            @Override public byte[] getSigAlgParams() { return null; }
            @Override public boolean[] getIssuerUniqueID() { return null; }
            @Override public boolean[] getSubjectUniqueID() { return null; }
            @Override public boolean[] getKeyUsage() { return null; }
            @Override public int getBasicConstraints() { return -1; }
            @Override public byte[] getEncoded() { return new byte[0]; }
            @Override public void verify(PublicKey key) {}
            @Override public void verify(PublicKey key, String sigProvider) {}
            @Override public String toString() { return subjectDn; }
            @Override public PublicKey getPublicKey() { return null; }
            @Override public boolean hasUnsupportedCriticalExtension() { return false; }
            @Override public Set<String> getCriticalExtensionOIDs() { return Set.of(); }
            @Override public Set<String> getNonCriticalExtensionOIDs() { return Set.of(); }
            @Override public byte[] getExtensionValue(String oid) { return null; }
        };
    }

    private static HttpServletRequest stubRequest(String pathInfo, String username) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> pathInfo;
                    case "getRemoteUser" -> username;
                    case "getUserPrincipal" -> username == null ? null : (Principal) () -> username;
                    default -> null;
                });
    }

    private static final class ResponseCapture {
        int status = 200;
        final Map<String, String> headers = new HashMap<>();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServletResponse proxy() {
            ServletOutputStream sos = new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener writeListener) {}
                @Override public void write(int b) { out.write(b); }
            };
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> { status = (Integer) args[0]; yield null; }
                        case "setHeader" -> { headers.put((String) args[0], (String) args[1]); yield null; }
                        case "getOutputStream" -> sos;
                        default -> null;
                    });
        }

        String bodyUtf8() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
