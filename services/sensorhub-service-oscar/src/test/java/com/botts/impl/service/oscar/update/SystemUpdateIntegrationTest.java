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
        byte[] sampleZip = buildValidReleaseZip("4.0.1");
        String zipSha256 = sha256Hex(sampleZip);
        AtomicBoolean sawAuthHeader = new AtomicBoolean(false);

        mockGitHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockGitHub.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/ORNL/oscar";

        mockGitHub.createContext("/ORNL/oscar/releases/latest", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            ex.getResponseHeaders().add("Location", baseUrl + "/releases/tag/v4.0.1");
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
                        <id>tag:github.com,2008:Repository/1/v4.0.1</id>
                        <updated>2026-11-28T16:30:00Z</updated>
                        <link rel="alternate" type="text/html" href="%s/releases/tag/v4.0.1"/>
                        <title>Release v4.0.1</title>
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
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.1/oscar-4.0.1.zip.sha256", ex -> {
            if (ex.getRequestHeaders().containsKey("Authorization")) {
                sawAuthHeader.set(true);
            }
            byte[] b = (zipSha256 + " *oscar-4.0.1.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.1/oscar-4.0.1.zip", ex -> {
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
        JsonObject info = checker.checkLatestRelease("4.0.0", true);

        assertFalse("Zero-login checker must never send an Authorization header", sawAuthHeader.get());
        assertFalse(info.get("requiresGitHubLogin").getAsBoolean());
        assertEquals("4.0.1", info.get("latestVersion").getAsString());
        assertEquals("4.0.0", info.get("currentVersion").getAsString());
        assertTrue(info.get("updateAvailable").getAsBoolean());
        assertTrue(info.get("inPlaceCompatible").getAsBoolean());
        assertEquals(zipSha256, info.get("expectedSha256").getAsString());
        assertEquals(sampleZip.length, info.get("downloadBytes").getAsLong());
        assertTrue(info.get("notesSummary").getAsString().contains("Zero-login GUI system updater & Rapiscan edge proxy."));

        // Verify up-to-date comparison when already running 4.0.1
        JsonObject same = checker.checkLatestRelease("4.0.1", false);
        assertTrue(same.get("fromCache").getAsBoolean());
        assertFalse(same.get("updateAvailable").getAsBoolean());
        assertTrue(same.get("upToDate").getAsBoolean());
    }

    @Test
    public void queuesGitHubUpgradeStagesOfflineZipAndEnforcesAdminAndSha256() throws Exception {
        tempDir = Files.createTempDirectory("oscar-system-update-test");
        System.setProperty(SystemUpdateHostBridge.PROP_CURRENT_VERSION, "4.0.0");

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

        byte[] validZip = buildValidReleaseZip("4.0.1");
        String validSha = sha256Hex(validZip);

        mockGitHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockGitHub.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/ORNL/oscar";
        mockGitHub.createContext("/ORNL/oscar/releases/latest", ex -> {
            ex.getResponseHeaders().add("Location", baseUrl + "/releases/tag/v4.0.1");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases.atom", ex -> {
            byte[] b = "<feed><entry><link href=\"/releases/tag/v4.0.1\"/><title>v4.0.1</title></entry></feed>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.1/oscar-4.0.1.zip.sha256", ex -> {
            byte[] b = (validSha + " *oscar-4.0.1.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.1/oscar-4.0.1.zip", ex -> {
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
        assertEquals("4.0.0", checkJson.get("currentVersion").getAsString());
        assertEquals("4.0.1", checkJson.getAsJsonObject("latestRelease").get("latestVersion").getAsString());

        // 4. Admin POST /apply writes atomic request.json with SHA-256 and backup flag
        MockResponse applyResp = invokeServlet(
                servlet, "POST", "/apply", "admin", true, "XMLHttpRequest",
                ("{\"targetVersion\":\"4.0.1\",\"createBackupFirst\":true}").getBytes(StandardCharsets.UTF_8));
        assertEquals(200, applyResp.status);
        Path reqPath = tempDir.resolve("request.json");
        assertTrue(Files.isRegularFile(reqPath));
        JsonObject writtenReq = JsonParser.parseString(Files.readString(reqPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("upgrade-github", writtenReq.get("action").getAsString());
        assertEquals("4.0.1", writtenReq.get("targetVersion").getAsString());
        assertEquals(validSha, writtenReq.get("expectedSha256").getAsString());
        assertTrue(writtenReq.get("createBackupFirst").getAsBoolean());
        Files.deleteIfExists(reqPath);

        // 5. Reject pre-4.0.0 version (both GitHub target and uploaded pre-4.0 zip)
        try {
            bridge.queueGitHubUpgrade("3.12.2", "", false, "admin");
            fail("Expected pre-4.0.0 upgrade to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("4.0.0"));
        }
        byte[] pre40Zip = buildValidReleaseZip("3.12.2");
        try {
            bridge.stageUploadedBundleAndMaybeApply(
                    new ByteArrayInputStream(pre40Zip),
                    "oscar-3.12.2.zip",
                    "",
                    false,
                    false,
                    "admin");
            fail("Expected pre-4.0.0 bundle upload to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("4.0.0"));
        }

        // 6. Reject tampered SHA-256 on offline bundle upload
        try {
            bridge.stageUploadedBundleAndMaybeApply(
                    new ByteArrayInputStream(validZip),
                    "oscar-4.0.1.zip",
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
                    "oscar-4.0.1.zip",
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
                "oscar-4.0.1.zip",
                validSha,
                false,
                true,
                "admin");
        assertNotNull(stagedResult);
        assertTrue(Files.isRegularFile(tempDir.resolve("staged/oscar-4.0.1.zip")));
        JsonObject stagedReq = JsonParser.parseString(Files.readString(reqPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("upgrade-staged", stagedReq.get("action").getAsString());
        assertEquals("oscar-4.0.1.zip", stagedReq.get("bundleFileName").getAsString());
        assertEquals("4.0.1", stagedReq.get("targetVersion").getAsString());
        assertEquals(validSha, stagedReq.get("expectedSha256").getAsString());
    }

    @Test
    public void defaultsToStableLatestAndAllowsExplicitDevPrereleaseChannelOptIn() throws Exception {
        // SemVer 2.0.0 numeric prerelease ordering checks
        assertTrue(SystemUpdateReleaseChecker.compareSemVer("4.0.0-dev.2", "4.0.0-dev.1") > 0);
        assertTrue(SystemUpdateReleaseChecker.compareSemVer("4.0.0-dev.10", "4.0.0-dev.2") > 0);
        assertTrue(SystemUpdateReleaseChecker.compareSemVer("4.0.0-rc.1", "4.0.0-dev.10") > 0);
        assertTrue(SystemUpdateReleaseChecker.compareSemVer("4.0.0", "4.0.0-rc.1") > 0);
        assertTrue(SystemUpdateReleaseChecker.compareSemVer("4.1.0-dev.1", "4.0.0") > 0);
        assertTrue(SystemUpdateReleaseChecker.isInPlaceCompatible40Plus("4.0.0-dev.1"));
        assertFalse(SystemUpdateReleaseChecker.isInPlaceCompatible40Plus("3.12.2"));

        tempDir = Files.createTempDirectory("oscar-system-update-channel-test");
        System.setProperty(SystemUpdateHostBridge.PROP_CURRENT_VERSION, "4.0.0-dev.1");

        Files.writeString(tempDir.resolve("status.json"), """
                {
                  "agentActive": true,
                  "state": "IDLE",
                  "stage": "IDLE",
                  "progressPercent": 0,
                  "availableDiskGib": 64.0
                }
                """, StandardCharsets.UTF_8);

        byte[] devZip = buildValidReleaseZip("4.0.0-dev.2");
        String devSha = sha256Hex(devZip);
        byte[] prodZip = buildValidReleaseZip("3.12.2");
        String prodSha = sha256Hex(prodZip);

        mockGitHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockGitHub.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port + "/ORNL/oscar";

        // Production Latest remains 3.12.2 while Dev Pre-release 4.0.0-dev.2 is published in releases.atom
        mockGitHub.createContext("/ORNL/oscar/releases/latest", ex -> {
            ex.getResponseHeaders().add("Location", baseUrl + "/releases/tag/v3.12.2");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases.atom", ex -> {
            String atom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry>
                        <id>tag:github.com,2008:Repository/1/v4.0.0-dev.2</id>
                        <updated>2026-11-29T01:00:00Z</updated>
                        <link rel="alternate" type="text/html" href="%s/releases/tag/v4.0.0-dev.2"/>
                        <title>Release v4.0.0-dev.2 (Dev Pre-release)</title>
                        <content type="html">&lt;p&gt;OSCAR 4.0.0 Dev Pre-release build 2.&lt;/p&gt;</content>
                      </entry>
                      <entry>
                        <id>tag:github.com,2008:Repository/1/v3.12.2</id>
                        <updated>2026-11-28T01:00:00Z</updated>
                        <link rel="alternate" type="text/html" href="%s/releases/tag/v3.12.2"/>
                        <title>Release v3.12.2</title>
                        <content type="html">&lt;p&gt;Production release 3.12.2.&lt;/p&gt;</content>
                      </entry>
                    </feed>
                    """.formatted(baseUrl, baseUrl);
            byte[] b = atom.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/atom+xml;charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.0-dev.2/oscar-4.0.0-dev.2.zip.sha256", ex -> {
            byte[] b = (devSha + " *oscar-4.0.0-dev.2.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v4.0.0-dev.2/oscar-4.0.0-dev.2.zip", ex -> {
            ex.getResponseHeaders().add("Content-Length", Integer.toString(devZip.length));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.12.2/oscar-3.12.2.zip.sha256", ex -> {
            byte[] b = (prodSha + " *oscar-3.12.2.zip\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mockGitHub.createContext("/ORNL/oscar/releases/download/v3.12.2/oscar-3.12.2.zip", ex -> {
            ex.getResponseHeaders().add("Content-Length", Integer.toString(prodZip.length));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        mockGitHub.start();

        var logger = LoggerFactory.getLogger(SystemUpdateIntegrationTest.class);
        SystemUpdateHostBridge bridge = new SystemUpdateHostBridge(tempDir, logger);
        SystemUpdateReleaseChecker checker = new SystemUpdateReleaseChecker(baseUrl, logger);
        SystemUpdateServlet servlet = new SystemUpdateServlet(null, bridge, checker, logger);

        // 1. Default channel MUST be stable (Production Latest = 3.12.2, NOT 4.0.0-dev.2)
        assertEquals("stable", bridge.getReleaseChannel());
        MockResponse stableCheck = invokeServlet(
                servlet, "POST", "/check", "admin", true, "XMLHttpRequest",
                "{\"force\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, stableCheck.status);
        JsonObject stableBody = JsonParser.parseString(stableCheck.bodyString()).getAsJsonObject();
        assertEquals("stable", stableBody.get("releaseChannel").getAsString());
        JsonObject stableRel = stableBody.getAsJsonObject("latestRelease");
        assertEquals("3.12.2", stableRel.get("latestVersion").getAsString());
        assertEquals("stable", stableRel.get("channel").getAsString());
        assertFalse(stableRel.get("isPrerelease").getAsBoolean());
        assertFalse(stableRel.get("updateAvailable").getAsBoolean());

        // 2. Reject invalid channel names cleanly (400 Bad Request)
        MockResponse invalidChan = invokeServlet(
                servlet, "POST", "/channel", "admin", true, "XMLHttpRequest",
                "{\"channel\":\"nightly\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, invalidChan.status);

        // 3. Explicit opt-in to dev channel persists to channel.json and discovers 4.0.0-dev.2
        MockResponse setDev = invokeServlet(
                servlet, "POST", "/channel", "admin", true, "XMLHttpRequest",
                "{\"channel\":\"dev\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, setDev.status);
        assertEquals("dev", bridge.getReleaseChannel());
        assertTrue(Files.isRegularFile(tempDir.resolve("channel.json")));

        MockResponse devCheck = invokeServlet(
                servlet, "POST", "/check", "admin", true, "XMLHttpRequest",
                "{\"force\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, devCheck.status);
        JsonObject devBody = JsonParser.parseString(devCheck.bodyString()).getAsJsonObject();
        assertEquals("dev", devBody.get("releaseChannel").getAsString());
        assertTrue(devBody.get("linuxUpgradeCommand").getAsString().contains("--channel dev"));
        JsonObject devRel = devBody.getAsJsonObject("latestRelease");
        assertEquals("4.0.0-dev.2", devRel.get("latestVersion").getAsString());
        assertEquals("dev", devRel.get("channel").getAsString());
        assertTrue(devRel.get("isPrerelease").getAsBoolean());
        assertTrue(devRel.get("updateAvailable").getAsBoolean());
        assertTrue(devRel.get("inPlaceCompatible").getAsBoolean());
        assertEquals(devSha, devRel.get("expectedSha256").getAsString());

        // 4. Switching back to stable restores Production Latest behavior cleanly
        MockResponse setStable = invokeServlet(
                servlet, "POST", "/channel", "admin", true, "XMLHttpRequest",
                "{\"channel\":\"stable\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, setStable.status);
        assertEquals("stable", bridge.getReleaseChannel());
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
