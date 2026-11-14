package com.botts.impl.service.oscar.diagnostics;

import com.botts.impl.service.bucket.BucketService;
import com.botts.impl.service.bucket.BucketServiceConfig;
import com.botts.impl.service.oscar.Constants;
import com.botts.impl.service.oscar.OSCARServiceConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.service.IHttpServer;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.security.BasicSecurityRealmConfig;
import org.sensorhub.impl.service.HttpServer;
import org.sensorhub.impl.service.HttpServerConfig;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

public class SupportBundleHandlerTest {
    private static final String USER = "support-operator";
    private static final String PASSWORD = "operator-test-password";
    private static final String BUNDLE_PATH =
            "/" + Constants.REPORT_BUCKET + "/" + SupportBundleHandler.OBJECT_KEY;

    private static final String SYNTHETIC_ADMIN_HASH = "$pbkdf2-sha256$600000$SuperSecretHashValue999";
    private static final String SYNTHETIC_RTSP_CRED = "cam_admin:UltraSecretCamPass42";
    private static final String SYNTHETIC_BEARER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.SecretPayloadToken999";
    private static final String SYNTHETIC_SESSION = "s3ss10n_t0k3n_abcdef1234567890";
    private static final String SYNTHETIC_DB_PASS = "PostgresSuperSecretDbPass88";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ISensorHub hub;
    private BucketService bucketService;
    private Path stateDir;
    private Path logDir;
    private Path configFile;
    private Path archiveDir;
    private Path readinessMarker;
    private String endpoint;

    @Before
    public void setUp() throws Exception {
        hub = new SensorHub();
        hub.start();
        ModuleRegistry registry = hub.getModuleRegistry();

        BasicSecurityRealmConfig realmConfig = new BasicSecurityRealmConfig();
        realmConfig.id = UUID.randomUUID().toString();
        realmConfig.autoStart = true;
        BasicSecurityRealmConfig.UserConfig user = new BasicSecurityRealmConfig.UserConfig();
        user.userID = USER;
        user.name = USER;
        user.password = PASSWORD;
        realmConfig.users.add(user);
        registry.loadModule(realmConfig).waitForState(ModuleEvent.ModuleState.STARTED, 5_000);

        HttpServerConfig httpConfig = new HttpServerConfig();
        httpConfig.id = UUID.randomUUID().toString();
        httpConfig.autoStart = true;
        httpConfig.httpPort = freePort();
        httpConfig.authMethod = HttpServerConfig.AuthMethod.BASIC;
        IHttpServer<?> httpServer = (HttpServer) registry.loadModule(httpConfig);
        assertTrue(httpServer.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

        stateDir = temporaryFolder.newFolder("oscar-state").toPath();
        logDir = Files.createDirectories(stateDir.resolve("logs"));
        archiveDir = Files.createDirectories(temporaryFolder.newFolder("oscar-archive").toPath());
        readinessMarker = stateDir.resolve("oscar-ready");
        Files.writeString(readinessMarker, "ready\n", StandardCharsets.UTF_8);

        // Seed synthetic config.json containing secrets, secret file paths, RTSP credentials, and safe keys
        configFile = stateDir.resolve("config.json");
        Files.writeString(configFile, """
                [
                  {
                    "objClass": "org.sensorhub.impl.security.session.BasicSessionConfig",
                    "cookieName": "OSCAR_SESSION",
                    "secureCookie": true
                  },
                  {
                    "objClass": "org.sensorhub.ui.AdminUIConfig",
                    "deploymentName": "OSCAR 3.11.0"
                  },
                  {
                    "objClass": "org.sensorhub.impl.security.BasicSecurityRealmConfig",
                    "users": [
                      {
                        "id": "admin",
                        "password": "%s"
                      }
                    ]
                  },
                  {
                    "objClass": "org.sensorhub.impl.database.system.SystemDriverDatabaseConfig",
                    "dbConfig": {
                      "url": "postgres:5432",
                      "password": "",
                      "passwordFile": "/run/secrets/oscar_db_password"
                    }
                  },
                  {
                    "objClass": "com.botts.impl.driver.ffmpeg.FFMPEGConfig",
                    "streamPath": "rtsp://%s@192.168.10.50:554/axis-media/media.amp?token=QuerySecret777"
                  }
                ]
                """.formatted(SYNTHETIC_ADMIN_HASH, SYNTHETIC_RTSP_CRED), StandardCharsets.UTF_8);

        // Seed primary rolling log + module log with realistic errors and embedded credentials
        StringBuilder primaryLog = new StringBuilder();
        for (int i = 1; i <= 150; i++) {
            primaryLog.append("2026-11-13 21:00:00.000 INFO  com.botts.Test [main] - Heartbeat line ")
                    .append(i).append('\n');
        }
        primaryLog.append("2026-11-13 21:05:01.120 WARN  com.botts.Camera [cam-1] - Reconnecting to rtsp://")
                .append(SYNTHETIC_RTSP_CRED).append("@192.168.10.50:554/stream1\n");
        primaryLog.append("2026-11-13 21:05:02.340 ERROR com.botts.Auth [http-1] - Failed upstream call Authorization: Bearer ")
                .append(SYNTHETIC_BEARER).append(" Cookie: OSCAR_SESSION=").append(SYNTHETIC_SESSION)
                .append(" db_password=").append(SYNTHETIC_DB_PASS)
                .append(" token=InlineTokenSecret555")
                .append(" contact=operator.jane@ornl.gov\n");
        Files.writeString(logDir.resolve("oscar.log"), primaryLog.toString(), StandardCharsets.UTF_8);

        Path laneModuleLogDir = Files.createDirectories(stateDir.resolve(".moduledata").resolve("lane-50"));
        Files.writeString(laneModuleLogDir.resolve("log.txt"),
                "2026-11-13 21:06:00.000 ERROR [lane-50] - Upstream URL https://user1:SecretUrlPass@rpm50.local/status?api_key=Key9999\n",
                StandardCharsets.UTF_8);

        Path bucketRoot = Files.createDirectories(stateDir.resolve("files"));
        BucketServiceConfig bucketConfig = new BucketServiceConfig();
        bucketConfig.id = UUID.randomUUID().toString();
        bucketConfig.autoStart = true;
        bucketConfig.fileStoreRootDir = bucketRoot.toAbsolutePath().toString();
        bucketConfig.initialBuckets = List.of(Constants.REPORT_BUCKET);
        bucketConfig.security.requireAuth = true;
        bucketConfig.security.enableAccessControl = false;
        bucketService = (BucketService) registry.loadModule(bucketConfig);
        assertTrue(bucketService.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

        OSCARServiceConfig oscarConfig = new OSCARServiceConfig();
        oscarConfig.nodeId = "site-alpha";

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-13T22:00:00Z"), ZoneOffset.UTC);
        SupportBundleHandler.DiagnosticPaths paths = new SupportBundleHandler.DiagnosticPaths(
                stateDir, logDir, configFile, archiveDir, readinessMarker);
        bucketService.ensureBucketRegistered(Constants.REPORT_BUCKET);
        bucketService.registerObjectHandler(new SupportBundleHandler(
                hub,
                oscarConfig,
                paths,
                fixedClock,
                LoggerFactory.getLogger(SupportBundleHandlerTest.class)));

        endpoint = httpServer.getPublicEndpointUrl(bucketConfig.endPoint);
    }

    @After
    public void tearDown() {
        if (hub != null)
            hub.stop();
    }

    @Test
    public void requiresAuthEnforcesReadOnlyPreservesReportFilesAndScrubsAllSecrets() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // 1. Unauthenticated requests are rejected with 401
        HttpResponse<byte[]> unauth = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + BUNDLE_PATH))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, unauth.statusCode());

        // 2. Write/delete methods on support-bundle are rejected with 405
        assertEquals(405, client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + BUNDLE_PATH))
                .header("Authorization", basicAuthorization())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(405, client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + BUNDLE_PATH))
                .header("Authorization", basicAuthorization())
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        // 3. Invalid tailLines query parameter returns 400
        assertEquals(400, client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + BUNDLE_PATH + "?tailLines=not-a-number"))
                .header("Authorization", basicAuthorization())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        // 4. Normal report uploads/downloads in /buckets/reports remain completely unaffected
        HttpResponse<String> putReport = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/reports/daily-summary.csv"))
                .header("Authorization", basicAuthorization())
                .header("Content-Type", "text/csv")
                .PUT(HttpRequest.BodyPublishers.ofString("lane,alarms\nLane50,2\n"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(putReport.statusCode() >= 200 && putReport.statusCode() < 300);

        HttpResponse<String> getReport = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/reports/daily-summary.csv"))
                .header("Authorization", basicAuthorization())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getReport.statusCode());
        assertEquals("lane,alarms\nLane50,2\n", getReport.body());

        // 5. Authenticated GET /buckets/reports/support-bundle?tailLines=100 returns valid redacted ZIP
        HttpResponse<byte[]> bundleResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + BUNDLE_PATH + "?tailLines=100"))
                .header("Authorization", basicAuthorization())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, bundleResp.statusCode());
        assertEquals("application/zip", bundleResp.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", bundleResp.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("same-origin", bundleResp.headers().firstValue("Cross-Origin-Resource-Policy").orElse(""));
        assertEquals("no-store", bundleResp.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(bundleResp.headers().firstValue("Content-Disposition").orElse("")
                .contains("oscar-backend-support-20261113T220000Z.zip"));
        assertTrue(bundleResp.body().length > 0 && bundleResp.body().length <= SupportBundleHandler.MAX_ZIP_BYTES);

        Map<String, String> entries = unzipUtf8Entries(bundleResp.body());
        assertEquals("summary.json should be the first ZIP entry",
                "summary.json", entries.keySet().iterator().next());
        assertTrue(entries.containsKey("summary.json"));
        assertTrue(entries.containsKey("redaction-report.json"));
        assertTrue(entries.containsKey("backend/modules.json"));
        assertTrue(entries.containsKey("backend/jvm-diagnostics.json"));
        assertTrue(entries.containsKey("backend/storage-summary.json"));
        assertTrue(entries.containsKey("backend/config.redacted.json"));
        assertTrue(entries.containsKey("backend/oscar.log"));
        assertTrue(entries.containsKey("backend/module-logs/lane-50.log"));

        // Verify zero secret leakage anywhere across all archive entries
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String text = entry.getValue();
            assertFalse("Admin hash leaked in " + entry.getKey(), text.contains(SYNTHETIC_ADMIN_HASH));
            assertFalse("RTSP credentials leaked in " + entry.getKey(), text.contains(SYNTHETIC_RTSP_CRED));
            assertFalse("Bearer token leaked in " + entry.getKey(), text.contains(SYNTHETIC_BEARER));
            assertFalse("Session cookie leaked in " + entry.getKey(), text.contains(SYNTHETIC_SESSION));
            assertFalse("DB password leaked in " + entry.getKey(), text.contains(SYNTHETIC_DB_PASS));
            assertFalse("Standalone token leaked in " + entry.getKey(), text.contains("InlineTokenSecret555"));
            assertFalse("Query secret leaked in " + entry.getKey(), text.contains("QuerySecret777"));
            assertFalse("Module URL password leaked in " + entry.getKey(), text.contains("SecretUrlPass"));
            assertFalse("Module API key leaked in " + entry.getKey(), text.contains("Key9999"));
            assertFalse("Unmasked email leaked in " + entry.getKey(), text.contains("operator.jane@ornl.gov"));
            assertFalse("Raw secret file path leaked in " + entry.getKey(),
                    text.contains("/run/secrets/oscar_db_password"));
        }

        // Verify safe metadata and expected structure remain intact
        String redactedConfig = entries.get("backend/config.redacted.json");
        assertTrue(redactedConfig.contains("\"OSCAR_SESSION\""));
        assertTrue(redactedConfig.contains(SupportBundleRedactor.REDACTED_SECRET_FILE));
        assertTrue(redactedConfig.contains("rtsp://[REDACTED]@192.168.10.50:554/axis-media/media.amp?token=[REDACTED]"));

        String oscarLog = entries.get("backend/oscar.log");
        long lineCount = oscarLog.lines().count();
        assertEquals("tailLines=100 should bound captured primary log lines", 100L, lineCount);
        assertTrue(oscarLog.contains("o***@ornl.gov"));

        JsonObject summary = JsonParser.parseString(entries.get("summary.json")).getAsJsonObject();
        assertEquals("backend", summary.get("bundleSource").getAsString());
        assertEquals("3.11.0", summary.get("oscarVersion").getAsString());
        assertEquals("site-alpha", summary.get("nodeId").getAsString());
        assertEquals(1, summary.getAsJsonObject("logs").get("errorLineCount").getAsInt());
        assertEquals(1, summary.getAsJsonObject("logs").get("warnLineCount").getAsInt());
        assertTrue(summary.getAsJsonObject("redaction").get("totalRedactions").getAsInt() >= 8);
    }

    private static Map<String, String> unzipUtf8Entries(byte[] zipBytes) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                map.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }
        return map;
    }

    private static String basicAuthorization() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
