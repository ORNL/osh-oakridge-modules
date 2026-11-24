/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SystemUpdateIntegrationTest {

    private HttpServer mockGitHub;
    private Path tempDir;

    @After
    public void tearDown() throws Exception {
        if (mockGitHub != null) {
            mockGitHub.stop(0);
            mockGitHub = null;
        }
        System.clearProperty(SystemUpdateHostBridge.PROP_CURRENT_VERSION);
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void discoversLatestReleaseAnonymouslyWithoutGitHubLoginOrApiRateLimit() throws Exception {
        byte[] sampleZip = buildValidReleaseZip("3.11.9");
        String zipSha256 = sha256Hex(sampleZip);
        AtomicBoolean sawAuthHeader = new AtomicBoolean(false);

        mockGitHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockGitHub.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/ORNL/oscar";

        mockGitHub.createContext("/ORNL/oscar/releases/latest", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            ex.getResponseHeaders().add("Location", baseUrl + "/releases/tag/v3.11.9");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases.atom", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            String atom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry>
                        <id>tag:github.com,2008:Repository/1/v3.11.9</id>
                        <updated>2026-11-23T03:30:00Z</updated>
                        <link rel="alternate" type="text/html" href="%s/releases/tag/v3.11.9"/>
                        <title>Release v3.11.9 (Amber)</title>
                        <content type="html">&lt;p&gt;Zero-login GUI system updater &amp;amp; Rapiscan edge proxy.&lt;/p&gt;</content>
                      </entry>
                    </feed>
                    """.formatted(baseUrl);
            byte[] b = atom.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/atom+xml;charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.11.9/oscar-3.11.9.zip.sha256", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            byte[] b = (zipSha256 + " *oscar-3.11.9.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.11.9/oscar-3.11.9.zip", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            ex.getResponseHeaders().add("Content-Length", Integer.toString(sampleZip.length));
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, sampleZip.length);
                ex.getResponseBody().write(sampleZip);
            }
            ex.close();
        });
        mockGitHub.start();

        SystemUpdateReleaseChecker checker = new SystemUpdateReleaseChecker(
                baseUrl, LoggerFactory.getLogger(SystemUpdateIntegrationTest.class));
        JsonObject info = checker.checkLatestRelease("3.11.8", true);

        assertFalse("Zero-login checker must never send an Authorization header", sawAuthHeader.get());
        assertFalse(info.get("requiresGitHubLogin").getAsBoolean());
        assertEquals("3.11.9", info.get("latestVersion").getAsString());
        assertEquals("3.11.8", info.get("currentVersion").getAsString());
        assertTrue(info.get("updateAvailable").getAsBoolean());
        assertTrue(info.get("inPlaceCompatible").getAsBoolean());
        assertEquals(zipSha256, info.get("expectedSha256").getAsString());
        assertEquals(sampleZip.length, info.get("downloadBytes").getAsLong());
        assertTrue(info.get("notesSummary").getAsString().contains("Zero-login GUI system updater & Rapiscan edge proxy."));

        // Verify up-to-date comparison when already running 3.11.9
        JsonObject same = checker.checkLatestRelease("3.11.9", false);
        assertTrue(same.get("fromCache").getAsBoolean());
        assertFalse(same.get("updateAvailable").getAsBoolean());
        assertTrue(same.get("upToDate").getAsBoolean());
    }

    @Test
    public void queuesGitHubUpgradeStagesOfflineZipAndEnforcesAdminAndSha256() throws Exception {
        tempDir = Files.createTempDirectory("oscar-system-update-test");
        System.setProperty(SystemUpdateHostBridge.PROP_CURRENT_VERSION, "3.11.8");

        Files.writeString(tempDir.resolve("status.json"), """
                {
                  "agentActive": true,
                  "state": "IDLE",
                  "stage": "IDLE",
                  "progressPercent": 0,
                  "availableDiskGib": 48.5,
                  "backupPossible": true
                }
                """, StandardCharsets.UTF_8);

        byte[] validZip = buildValidReleaseZip("3.11.9");
        String validSha = sha256Hex(validZip);

        mockGitHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockGitHub.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/ORNL/oscar";
        mockGitHub.createContext("/ORNL/oscar/releases/latest", ex -> {
            ex.getResponseHeaders().add("Location", baseUrl + "/releases/tag/v3.11.9");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases.atom", ex -> {
            byte[] b = "<feed><entry><link href=\"/releases/tag/v3.11.9\"/><title>v3.11.9</title></entry></feed>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.11.9/oscar-3.11.9.zip.sha256", ex -> {
            byte[] b = (validSha + " *oscar-3.11.9.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.11.9/oscar-3.11.9.zip", ex -> {
            ex.getResponseHeaders().add("Content-Length", Integer.toString(validZip.length));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        mockGitHub.start();

        var logger = LoggerFactory.getLogger(SystemUpdateIntegrationTest.class);
        SystemUpdateHostBridge bridge = new SystemUpdateHostBridge(tempDir, logger);
        SystemUpdateReleaseChecker checker = new SystemUpdateReleaseChecker(baseUrl, logger);
        SystemUpdateServlet servlet = new SystemUpdateServlet(null, bridge, checker, logger);

        // 1. Non-admin operator must receive 403 Forbidden
        MockResponse nonAdminResp = invokeServlet(
                servlet, "GET", "/status", "operator1", false, "XMLHttpRequest", new byte[0]);
        assertEquals(403, nonAdminResp.status);

        // 2. Missing CSRF header on POST must receive 403 Forbidden
        MockResponse noCsrfResp = invokeServlet(
                servlet, "POST", "/check", "admin", true, null, "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, noCsrfResp.status);

        // 3. Admin POST /check succeeds with zero GitHub credentials
        MockResponse checkResp = invokeServlet(
                servlet, "POST", "/check", "admin", true, "XMLHttpRequest",
                "{\"force\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, checkResp.status);
        JsonObject checkJson = JsonParser.parseString(checkResp.bodyString()).getAsJsonObject();
        assertEquals("3.11.8", checkJson.get("currentVersion").getAsString());
        assertEquals("3.11.9", checkJson.getAsJsonObject("latestRelease").get("latestVersion").getAsString());

        // 4. Admin POST /apply writes atomic request.json with SHA-256 and backup flag
        MockResponse applyResp = invokeServlet(
                servlet, "POST", "/apply", "admin", true, "XMLHttpRequest",
                ("{\"targetVersion\":\"3.11.9\",\"createBackupFirst\":true}").getBytes(StandardCharsets.UTF_8));
        assertEquals(200, applyResp.status);
        Path reqPath = tempDir.resolve("request.json");
        assertTrue(Files.isRegularFile(reqPath));
        JsonObject writtenReq = JsonParser.parseString(Files.readString(reqPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("upgrade-github", writtenReq.get("action").getAsString());
        assertEquals("3.11.9", writtenReq.get("targetVersion").getAsString());
        assertEquals(validSha, writtenReq.get("expectedSha256").getAsString());
        assertTrue(writtenReq.get("createBackupFirst").getAsBoolean());
        Files.deleteIfExists(reqPath);

        // 5. Reject pre-3.11.0 version
        try {
            bridge.queueGitHubUpgrade("3.10.2", "", false, "admin");
            fail("Expected pre-3.11.0 upgrade to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("3.11.0"));
        }

        // 6. Reject tampered SHA-256 on offline bundle upload
        try {
            bridge.stageUploadedBundleAndMaybeApply(
                    new ByteArrayInputStream(validZip),
                    "oscar-3.11.9.zip",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    false,
                    false,
                    "admin");
            fail("Expected tampered SHA-256 upload to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SHA-256 checksum mismatch"));
        }

        // 7. Reject zip-slip entry in uploaded archive
        byte[] zipSlipBytes = buildZipSlipArchive();
        try {
            bridge.stageUploadedBundleAndMaybeApply(
                    new ByteArrayInputStream(zipSlipBytes),
                    "oscar-3.11.9.zip",
                    "",
                    false,
                    false,
                    "admin");
            fail("Expected zip-slip archive to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsafe path"));
        }

        // 8. Accept valid offline .zip upload and queue upgrade-staged
        JsonObject stagedResult = bridge.stageUploadedBundleAndMaybeApply(
                new ByteArrayInputStream(validZip),
                "oscar-3.11.9.zip",
                validSha,
                false,
                true,
                "admin");
        assertNotNull(stagedResult);
        assertTrue(Files.isRegularFile(tempDir.resolve("staged/oscar-3.11.9.zip")));
        JsonObject stagedReq = JsonParser.parseString(Files.readString(reqPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("upgrade-staged", stagedReq.get("action").getAsString());
        assertEquals("oscar-3.11.9.zip", stagedReq.get("bundleFileName").getAsString());
        assertEquals("3.11.9", stagedReq.get("targetVersion").getAsString());
        assertEquals(validSha, stagedReq.get("expectedSha256").getAsString());
    }

    private static byte[] buildValidReleaseZip(String version) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            String root = "oscar-" + version + "/";
            putZipText(zos, root + ".env.example", "OSCAR_VERSION=" + version + "\nOSCAR_BACKUP_MODE=disabled\n");
            putZipText(zos, root + "compose.yaml", "services:\n  oscar:\n    image: oscar:" + version + "\n");
            putZipText(zos, root + "oscar-setup.sh", "#!/usr/bin/env bash\necho ok\n");
            // Pad above 1 KiB minimum archive check
            putZipText(zos, root + "README.txt", "OSCAR release bundle ".repeat(80));
        }
        return baos.toByteArray();
    }

    private static byte[] buildZipSlipArchive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            putZipText(zos, "oscar-3.11.9/../../etc/passwd", "bad".repeat(500));
        }
        return baos.toByteArray();
    }

    private static void putZipText(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry e = new ZipEntry(entryName);
        zos.putNextEntry(e);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class MockResponse {
        int status = 200;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        String bodyString() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static MockResponse invokeServlet(
            SystemUpdateServlet servlet,
            String method,
            String pathInfo,
            String remoteUser,
            boolean isAdminRole,
            String xRequestedWith,
            byte[] bodyBytes) throws Exception {
        MockResponse mockResp = new MockResponse();
        ByteArrayInputStream bais = new ByteArrayInputStream(bodyBytes);
        ServletInputStream sis = new ServletInputStream() {
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

            @Override
            public int read() {
                return bais.read();
            }
        };
        ServletOutputStream sos = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) {
                mockResp.out.write(b);
            }
        };

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo" -> pathInfo;
                    case "getRemoteUser" -> remoteUser;
                    case "isUserInRole" -> isAdminRole && "admin".equals(args[0]);
                    case "getHeader" -> "X-Requested-With".equalsIgnoreCase((String) args[0]) ? xRequestedWith : null;
                    case "getParameter" -> null;
                    case "getInputStream" -> sis;
                    default -> null;
                });

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "setStatus" -> mockResp.status = (Integer) args[0];
                        case "getOutputStream" -> {
                            return sos;
                        }
                        default -> {
                        }
                    }
                    return null;
                });

        if ("GET".equalsIgnoreCase(method)) {
            servlet.doGet(req, resp);
        } else {
            servlet.doPost(req, resp);
        }
        return mockResp;
    }
}
