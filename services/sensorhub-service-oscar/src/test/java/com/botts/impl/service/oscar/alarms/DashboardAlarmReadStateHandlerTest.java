package com.botts.impl.service.oscar.alarms;

import com.botts.impl.service.bucket.BucketService;
import com.botts.impl.service.bucket.BucketServiceConfig;
import com.botts.impl.service.oscar.Constants;
import com.google.gson.Gson;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class DashboardAlarmReadStateHandlerTest {
    private static final String USER = "alarm-operator";
    private static final String PASSWORD = "test-password";
    private static final String READ_STATE_PATH =
            "/" + Constants.ADJUDICATION_BUCKET + "/" + DashboardAlarmReadStateHandler.OBJECT_KEY;
    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ISensorHub hub;
    private BucketService bucketService;
    private Path adjudicationDir;
    private MutableClock testClock;
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

        Path bucketRoot = temporaryFolder.newFolder("bucket-root").toPath();
        BucketServiceConfig bucketConfig = new BucketServiceConfig();
        bucketConfig.id = UUID.randomUUID().toString();
        bucketConfig.autoStart = true;
        bucketConfig.fileStoreRootDir = bucketRoot.toAbsolutePath().toString();
        bucketConfig.initialBuckets = List.of();
        bucketConfig.security.requireAuth = true;
        bucketConfig.security.enableAccessControl = false;
        bucketService = (BucketService) registry.loadModule(bucketConfig);
        assertTrue(bucketService.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

        testClock = new MutableClock(Instant.parse("2026-11-13T20:00:00Z").toEpochMilli());
        bucketService.ensureBucketRegistered(Constants.ADJUDICATION_BUCKET);
        bucketService.registerObjectHandler(new DashboardAlarmReadStateHandler(
                bucketService.getBucketStore(),
                testClock,
                LoggerFactory.getLogger(DashboardAlarmReadStateHandlerTest.class)));
        adjudicationDir = bucketRoot.resolve(Constants.ADJUDICATION_BUCKET);
        endpoint = httpServer.getPublicEndpointUrl(bucketConfig.endPoint);
    }

    @After
    public void tearDown() {
        if (hub != null)
            hub.stop();
    }

    @Test
    public void requiresAuthenticationSupportsConditionalGetAndPreservesAdjudicationUploads() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> unauthGet = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + READ_STATE_PATH))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthGet.statusCode());

        HttpResponse<String> unauthPost = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + READ_STATE_PATH))
                .POST(HttpRequest.BodyPublishers.ofString("{\"lanes\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthPost.statusCode());

        assertEquals(405, sendAuth(client, "DELETE", READ_STATE_PATH, null, null).statusCode());

        HttpResponse<String> initial = sendAuth(client, "GET", READ_STATE_PATH, null, null);
        assertEquals(200, initial.statusCode());
        assertEquals("\"rev-0\"", initial.headers().firstValue("ETag").orElse(""));
        assertEquals("nosniff", initial.headers().firstValue("X-Content-Type-Options").orElse(""));

        DashboardAlarmReadStateStore.StatePayload parsed = GSON.fromJson(
                initial.body(), DashboardAlarmReadStateStore.StatePayload.class);
        assertEquals(1, parsed.schemaVersion());
        assertEquals(0L, parsed.revision());
        assertTrue(parsed.lanes().isEmpty());

        HttpResponse<String> notModifiedEtag = sendAuth(
                client, "GET", READ_STATE_PATH, null, Map.of("If-None-Match", "\"rev-0\""));
        assertEquals(304, notModifiedEtag.statusCode());
        assertEquals("", notModifiedEtag.body());

        HttpResponse<String> notModifiedWeakEtag = sendAuth(
                client, "GET", READ_STATE_PATH, null, Map.of("If-None-Match", "W/\"rev-0\""));
        assertEquals(304, notModifiedWeakEtag.statusCode());

        HttpResponse<String> notModifiedRevParam = sendAuth(
                client, "GET", READ_STATE_PATH + "?sinceRevision=0", null, null);
        assertEquals(304, notModifiedRevParam.statusCode());

        // Normal adjudication evidence upload & download must remain unaffected.
        HttpResponse<String> putEvidence = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/adjudication/evidence-note.txt"))
                .header("Authorization", basicAuthorization())
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("operator-evidence-check"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue("Expected 2xx for evidence PUT, got " + putEvidence.statusCode(),
                putEvidence.statusCode() >= 200 && putEvidence.statusCode() < 300);

        HttpResponse<String> getEvidence = sendAuth(
                client, "GET", "/adjudication/evidence-note.txt", null, null);
        assertEquals(200, getEvidence.statusCode());
        assertEquals("operator-evidence-check", getEvidence.body());
    }

    @Test
    public void isolatesOperationalViewLanesAndClampsFutureClockSkew() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long serverNow = testClock.millis();

        // Operator on north-gate acknowledges Lane21 and Lane22; south-gate Lane31 is untouched.
        String northGateAck = """
                {
                  "lanes": {
                    "Lane21": { "acknowledgeAll": true },
                    "Lane22": { "acknowledgeAll": true, "acknowledgedThroughMs": %d }
                  }
                }
                """.formatted(serverNow + 86_400_000L);

        HttpResponse<String> resp = sendAuth(client, "POST", READ_STATE_PATH, northGateAck, null);
        assertEquals(200, resp.statusCode());
        assertEquals("\"rev-1\"", resp.headers().firstValue("ETag").orElse(""));

        DashboardAlarmReadStateStore.StatePayload state = GSON.fromJson(
                resp.body(), DashboardAlarmReadStateStore.StatePayload.class);
        assertEquals(1L, state.revision());
        assertEquals(serverNow, state.lanes().get("Lane21").acknowledgedThroughMs());
        assertEquals(
                serverNow + DashboardAlarmReadStateStore.MAX_FUTURE_CLOCK_SKEW_MS,
                state.lanes().get("Lane22").acknowledgedThroughMs());
        assertFalse("South-gate Lane31 must remain untouched", state.lanes().containsKey("Lane31"));

        // Conditional GET with old revision returns 200, while rev-1 returns 304.
        assertEquals(200, sendAuth(
                client, "GET", READ_STATE_PATH, null, Map.of("If-None-Match", "\"rev-0\"")).statusCode());
        assertEquals(304, sendAuth(
                client, "GET", READ_STATE_PATH, null, Map.of("If-None-Match", "\"rev-1\"")).statusCode());
    }

    @Test
    public void mergesReadKeysDeduplicatesNoOpsEvictsOldestAndPersistsAcrossRestart() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        String markSingle = """
                {
                  "lanes": {
                    "Lane31": {
                      "readKeys": [
                        "t:Lane31|12|2026-11-13T19:59:00Z|2026-11-13T19:59:05Z",
                        "o:obs-alpha-1"
                      ]
                    }
                  }
                }
                """;
        HttpResponse<String> first = sendAuth(client, "POST", READ_STATE_PATH, markSingle, null);
        assertEquals(200, first.statusCode());
        DashboardAlarmReadStateStore.StatePayload firstState = GSON.fromJson(
                first.body(), DashboardAlarmReadStateStore.StatePayload.class);
        assertEquals(1L, firstState.revision());
        assertEquals(2, firstState.lanes().get("Lane31").readKeys().size());

        // Repeating the identical readKeys update must not bump revision or rewrite disk.
        HttpResponse<String> duplicate = sendAuth(client, "PUT", READ_STATE_PATH, markSingle, null);
        assertEquals(200, duplicate.statusCode());
        DashboardAlarmReadStateStore.StatePayload dupState = GSON.fromJson(
                duplicate.body(), DashboardAlarmReadStateStore.StatePayload.class);
        assertEquals(1L, dupState.revision());

        // Add 500 more keys across two batches and verify bounded FIFO/MRU eviction at 500 keys.
        List<String> batch1 = new ArrayList<>();
        for (int i = 0; i < 300; i++)
            batch1.add("\"k-" + i + "\"");
        List<String> batch2 = new ArrayList<>();
        for (int i = 300; i < 510; i++)
            batch2.add("\"k-" + i + "\"");

        assertEquals(200, sendAuth(client, "POST", READ_STATE_PATH,
                "{\"lanes\":{\"Lane31\":{\"readKeys\":[" + String.join(",", batch1) + "]}}}", null).statusCode());
        HttpResponse<String> cappedResp = sendAuth(client, "POST", READ_STATE_PATH,
                "{\"lanes\":{\"Lane31\":{\"readKeys\":[" + String.join(",", batch2) + "]}}}", null);
        assertEquals(200, cappedResp.statusCode());
        DashboardAlarmReadStateStore.StatePayload capped = GSON.fromJson(
                cappedResp.body(), DashboardAlarmReadStateStore.StatePayload.class);
        List<String> laneKeys = capped.lanes().get("Lane31").readKeys();
        assertEquals(DashboardAlarmReadStateStore.MAX_READ_KEYS_PER_LANE, laneKeys.size());
        assertFalse(laneKeys.contains("o:obs-alpha-1"));
        assertFalse(laneKeys.contains("k-9"));
        assertTrue(laneKeys.contains("k-10"));
        assertTrue(laneKeys.contains("k-509"));

        // Verify abandoned .partial-* temp files are cleaned up and state reloads identically on restart.
        Path abandonedPartial = adjudicationDir.resolve(
                DashboardAlarmReadStateStore.STATE_FILE_NAME + ".partial-stale");
        Files.writeString(abandonedPartial, "{incomplete", StandardCharsets.UTF_8);

        DashboardAlarmReadStateStore reloaded = new DashboardAlarmReadStateStore(
                adjudicationDir, testClock, LoggerFactory.getLogger(DashboardAlarmReadStateHandlerTest.class));
        assertFalse("Abandoned partial temp file should be cleaned on startup",
                Files.exists(abandonedPartial));
        assertEquals(capped.revision(), reloaded.getSnapshot().revision());
        assertEquals(DashboardAlarmReadStateStore.MAX_READ_KEYS_PER_LANE,
                reloaded.getSnapshot().payload().lanes().get("Lane31").readKeys().size());

        // Advancing acknowledgeAll on Lane31 prunes pre-watermark readKeys while preserving watermark.
        testClock.advanceMillis(5_000L);
        DashboardAlarmReadStateStore.CachedSnapshot afterAckAll =
                reloaded.applyJsonUpdate("{\"lanes\":{\"Lane31\":{\"acknowledgeAll\":true}}}");
        assertEquals(testClock.millis(),
                afterAckAll.payload().lanes().get("Lane31").acknowledgedThroughMs());
        assertTrue(afterAckAll.payload().lanes().get("Lane31").readKeys().isEmpty());

        // Corrupting the persisted state file fails safe to clean rev-0 rather than crashing startup.
        Files.writeString(adjudicationDir.resolve(DashboardAlarmReadStateStore.STATE_FILE_NAME),
                "{not-valid-json", StandardCharsets.UTF_8);
        DashboardAlarmReadStateStore recovered = new DashboardAlarmReadStateStore(
                adjudicationDir, testClock, LoggerFactory.getLogger(DashboardAlarmReadStateHandlerTest.class));
        assertEquals(0L, recovered.getSnapshot().revision());
        assertTrue(recovered.getSnapshot().payload().lanes().isEmpty());
    }

    @Test
    public void rejectsMalformedAndOversizedPayloads() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH, "", null).statusCode());
        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH, "{broken", null).statusCode());
        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH, "{\"wrong\":1}", null).statusCode());
        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH,
                "{\"lanes\":{\"Lane1\":{\"acknowledgedThroughMs\":-5}}}", null).statusCode());
        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH,
                "{\"lanes\":{\"Lane1\":{\"readKeys\":[\"bad\\u001fkey\"]}}}", null).statusCode());

        String oversized = "{\"lanes\":{\"Lane1\":{\"readKeys\":[\""
                + "x".repeat(DashboardAlarmReadStateHandler.MAX_REQUEST_BYTES + 1024)
                + "\"]}}}";
        assertEquals(400, sendAuth(client, "POST", READ_STATE_PATH, oversized, null).statusCode());
    }

    private HttpResponse<String> sendAuth(HttpClient client,
                                          String method,
                                          String path,
                                          String body,
                                          Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Authorization", basicAuthorization());
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        switch (method) {
            case "POST" -> {
                builder.header("Content-Type", "application/json");
                builder.POST(body != null
                        ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());
            }
            case "PUT" -> {
                builder.header("Content-Type", "application/json");
                builder.PUT(body != null
                        ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());
            }
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
