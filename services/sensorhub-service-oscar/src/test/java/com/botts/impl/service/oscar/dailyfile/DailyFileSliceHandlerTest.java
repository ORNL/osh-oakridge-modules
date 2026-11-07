package com.botts.impl.service.oscar.dailyfile;

import com.botts.impl.service.bucket.BucketService;
import com.botts.impl.service.bucket.BucketServiceConfig;
import com.botts.impl.service.oscar.purge.DatabasePurger;
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

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.Assert.*;

public class DailyFileSliceHandlerTest {
    private static final String USER = "dailyfile-viewer";
    private static final String PASSWORD = "test-password";
    private static final DateTimeFormatter LEGACY_TIME =
            DateTimeFormatter.ofPattern("HH-mm-ss.SSS", Locale.ROOT);
    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ISensorHub hub;
    private BucketService bucketService;
    private Path dailyFilesDir;
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
        // Deliberately omit dailyfiles from initialBuckets to verify ensureBucketRegistered.
        bucketConfig.initialBuckets = List.of();
        bucketConfig.security.requireAuth = true;
        bucketConfig.security.enableAccessControl = false;
        bucketService = (BucketService) registry.loadModule(bucketConfig);
        assertTrue(bucketService.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

        bucketService.ensureBucketRegistered(DatabasePurger.DAILY_FILES_BUCKET);
        bucketService.registerObjectHandler(new DailyFileSliceHandler(
                bucketService.getBucketStore()));
        dailyFilesDir = bucketRoot.resolve(DatabasePurger.DAILY_FILES_BUCKET);
        endpoint = httpServer.getPublicEndpointUrl(bucketConfig.endPoint);
    }

    @After
    public void tearDown() {
        if (hub != null)
            hub.stop();
    }

    @Test
    public void requiresAuthenticationRejectsMutationsAndPreservesRawCsvDownloads() throws Exception {
        String laneUid = "urn:osh:system:lane:lane-auth";
        LocalDate day = LocalDate.of(2026, 10, 26);
        String csvName = writePublishedJournalDay(laneUid, day, ZoneOffset.UTC, List.of(
                new RawRecord(Instant.parse("2026-10-26T01:31:15.100Z"), "GS,0012,0014,0011,0013"),
                new RawRecord(Instant.parse("2026-10-26T01:31:16.100Z"), "GX,0011,0013,0010,0012")));

        HttpClient client = HttpClient.newHttpClient();
        String slicePath = sliceQuery(laneUid, null,
                "2026-10-26T01:31:15Z", "2026-10-26T01:31:17Z", null);

        HttpResponse<String> unauth = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + slicePath))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauth.statusCode());

        assertEquals(405, sendAuth(client, "POST", slicePath).statusCode());
        assertEquals(405, sendAuth(client, "PUT", slicePath).statusCode());
        assertEquals(405, sendAuth(client, "DELETE", slicePath).statusCode());

        HttpResponse<String> slice = sendAuth(client, "GET", slicePath);
        assertEquals(200, slice.statusCode());
        assertEquals("nosniff", slice.headers().firstValue("X-Content-Type-Options").orElse(""));
        DailyFileSliceHandler.SliceResponse parsed =
                GSON.fromJson(slice.body(), DailyFileSliceHandler.SliceResponse.class);
        assertFalse(parsed.truncated());
        assertEquals(2, parsed.items().size());
        assertEquals("GS,0012,0014,0011,0013", parsed.items().get(0).message());
        assertEquals("2026-10-26T01:31:15.100Z", parsed.items().get(0).timestamp());

        // Direct full-day CSV download must still be served unmodified by DefaultObjectHandler.
        HttpResponse<String> rawCsv = sendAuth(client, "GET", "/dailyfiles/" + csvName);
        assertEquals(200, rawCsv.statusCode());
        assertTrue(rawCsv.body().contains("GS,0012,0014,0011,0013,01-31-15.100,01-31-15.100"));
    }

    @Test
    public void binarySearchesLargeJournalCsvWithExactRapiscanAndAspectParity() throws Exception {
        String laneUid = "urn:osh:system:lane:lane-binary";
        LocalDate day = LocalDate.of(2026, 10, 26);
        Instant base = Instant.parse("2026-10-26T00:00:00.000Z");

        List<RawRecord> records = new ArrayList<>(6_000);
        for (int i = 0; i < 6_000; i++) {
            Instant ts = base.plusMillis(i * 1_000L + 125L);
            String line;
            if (i == 4_500) {
                // Include a drifted portal hardware clock string inside rawLine to verify only
                // server receipt columns at the end of the line are parsed.
                line = "GS,1999-01-01T00:00:00Z,0012,0014,0011,0013";
            } else if (i == 4_501) {
                line = "1,AspectPortal,\"alarm,with,commas\",0450,0003";
            } else if (i == 4_502) {
                line = "GX,0011,0013,0010,0012";
            } else {
                line = String.format(Locale.ROOT,
                        "GB,%04d,0010,0012,0011,0009,padding-segment-check", i);
            }
            records.add(new RawRecord(ts, line));
        }
        String csvName = writePublishedJournalDay(laneUid, day, ZoneOffset.UTC, records);
        assertTrue("Fixture must exceed the 128 KiB binary-search threshold",
                Files.size(dailyFilesDir.resolve(csvName)) > 250 * 1024);

        HttpClient client = HttpClient.newHttpClient();
        Instant queryStart = base.plusMillis(4_500L * 1_000L + 125L);
        Instant queryEnd = base.plusMillis(4_502L * 1_000L + 125L);
        HttpResponse<String> response = sendAuth(client, "GET",
                sliceQuery(laneUid, null, queryStart.toString(), queryEnd.toString(), null));
        assertEquals(200, response.statusCode());

        DailyFileSliceHandler.SliceResponse parsed =
                GSON.fromJson(response.body(), DailyFileSliceHandler.SliceResponse.class);
        assertFalse(parsed.truncated());
        assertEquals(3, parsed.items().size());
        assertEquals("GS,1999-01-01T00:00:00Z,0012,0014,0011,0013", parsed.items().get(0).message());
        assertEquals(queryStart.toString(), parsed.items().get(0).timestamp());
        assertEquals("1,AspectPortal,\"alarm,with,commas\",0450,0003", parsed.items().get(1).message());
        assertEquals("GX,0011,0013,0010,0012", parsed.items().get(2).message());
        assertEquals(queryEnd.toString(), parsed.items().get(2).timestamp());
    }

    @Test
    public void appliesFiveMillisecondRoundingGuardAndMergesMidnightStraddle() throws Exception {
        String laneUid = "urn:osh:system:lane:lane-straddle";
        writePublishedJournalDay(laneUid, LocalDate.of(2026, 10, 25), ZoneOffset.UTC, List.of(
                new RawRecord(Instant.parse("2026-10-25T23:59:58.990Z"), "GB,before-window"),
                new RawRecord(Instant.parse("2026-10-25T23:59:59.002Z"), "GS,day1-start"),
                new RawRecord(Instant.parse("2026-10-25T23:59:59.500Z"), "GA,day1-peak")));
        writePublishedJournalDay(laneUid, LocalDate.of(2026, 10, 26), ZoneOffset.UTC, List.of(
                new RawRecord(Instant.parse("2026-10-26T00:00:00.250Z"), "GA,day2-cont"),
                new RawRecord(Instant.parse("2026-10-26T00:00:01.998Z"), "GX,day2-end"),
                new RawRecord(Instant.parse("2026-10-26T00:00:02.010Z"), "GB,after-window")));

        HttpClient client = HttpClient.newHttpClient();
        // Simulate +/-4 ms IEEE-754 epoch-second rounding inward on both startTime and endTime.
        HttpResponse<String> response = sendAuth(client, "GET", sliceQuery(
                laneUid, null,
                "2026-10-25T23:59:59.006Z",
                "2026-10-26T00:00:01.994Z",
                null));
        assertEquals(200, response.statusCode());

        DailyFileSliceHandler.SliceResponse parsed =
                GSON.fromJson(response.body(), DailyFileSliceHandler.SliceResponse.class);
        assertEquals(List.of("GS,day1-start", "GA,day1-peak", "GA,day2-cont", "GX,day2-end"),
                parsed.items().stream().map(DailyFileSliceHandler.SliceItem::message).toList());
    }

    @Test
    public void disambiguatesTwentyFiveHourDstFallBackDayInAmericaNewYork() throws Exception {
        String laneUid = "urn:osh:system:lane:lane-dst";
        ZoneId nyZone = ZoneId.of("America/New_York");
        // 2026-11-01 in America/New_York is a 25-hour day: [2026-11-01T04:00:00Z, 2026-11-02T05:00:00Z).
        LocalDate dstDay = LocalDate.of(2026, 11, 1);

        Instant startOfDayUtc430 = Instant.parse("2026-11-01T04:30:00.500Z"); // 00:30 EDT
        Instant firstLocal130Edt = Instant.parse("2026-11-01T05:30:00.250Z"); // 01:30 EDT
        Instant secondLocal130Est = Instant.parse("2026-11-01T06:30:00.250Z"); // 01:30 EST
        Instant endOfDayUtc430 = Instant.parse("2026-11-02T04:30:00.500Z");   // 23:30 EST

        writePublishedJournalDay(laneUid, dstDay, nyZone, List.of(
                new RawRecord(startOfDayUtc430, "GS,ny-0030-edt"),
                new RawRecord(firstLocal130Edt, "GS,ny-0130-edt"),
                new RawRecord(secondLocal130Est, "GS,ny-0130-est"),
                new RawRecord(endOfDayUtc430, "GS,ny-2330-est")));

        HttpClient client = HttpClient.newHttpClient();
        assertSingleSliceMatch(client, laneUid, startOfDayUtc430, "GS,ny-0030-edt");
        assertSingleSliceMatch(client, laneUid, firstLocal130Edt, "GS,ny-0130-edt");
        assertSingleSliceMatch(client, laneUid, secondLocal130Est, "GS,ny-0130-est");
        assertSingleSliceMatch(client, laneUid, endOfDayUtc430, "GS,ny-2330-est");
    }

    @Test
    public void supportsLegacyCsvFallbackBoundedTruncationAndInputValidation() throws Exception {
        Path legacyCsv = dailyFilesDir.resolve("LegacyLane_2026-10-20.csv");
        Files.writeString(legacyCsv,
                "message,timestamp\n"
                        + "\"GS,0010,\"\"quoted\"\"\",2026-10-20T10:00:01.123Z\n"
                        + "\"GA,0050,0060\",2026-10-20T10:00:02.123Z\n"
                        + "\"GX,0010,0011\",2026-10-20T10:00:03.123Z\n",
                StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> legacyResp = sendAuth(client, "GET", sliceQuery(
                null, "LegacyLane",
                "2026-10-20T10:00:00Z", "2026-10-20T10:00:05Z", 2));
        assertEquals(200, legacyResp.statusCode());
        DailyFileSliceHandler.SliceResponse legacyParsed =
                GSON.fromJson(legacyResp.body(), DailyFileSliceHandler.SliceResponse.class);
        assertTrue(legacyParsed.truncated());
        assertEquals(2, legacyParsed.items().size());
        assertEquals("GS,0010,\"quoted\"", legacyParsed.items().get(0).message());
        assertEquals("2026-10-20T10:00:01.123Z", legacyParsed.items().get(0).timestamp());

        // Missing day returns 200 with an empty items array.
        HttpResponse<String> missingResp = sendAuth(client, "GET", sliceQuery(
                "urn:osh:system:lane:missing", null,
                "2026-10-19T00:00:00Z", "2026-10-19T00:01:00Z", null));
        assertEquals(200, missingResp.statusCode());
        DailyFileSliceHandler.SliceResponse missingParsed =
                GSON.fromJson(missingResp.body(), DailyFileSliceHandler.SliceResponse.class);
        assertFalse(missingParsed.truncated());
        assertTrue(missingParsed.items().isEmpty());

        // Oversized payload is cleanly truncated below MAX_JSON_RESPONSE_BYTES.
        String heavyLane = "urn:osh:system:lane:heavy";
        String bigPayload = "GA," + "X".repeat(2_048);
        List<RawRecord> heavyRecords = new ArrayList<>();
        Instant heavyStart = Instant.parse("2026-10-21T08:00:00Z");
        for (int i = 0; i < 400; i++)
            heavyRecords.add(new RawRecord(heavyStart.plusMillis(i * 100L), bigPayload));
        writePublishedJournalDay(heavyLane, LocalDate.of(2026, 10, 21), ZoneOffset.UTC, heavyRecords);

        HttpResponse<byte[]> heavyResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + sliceQuery(heavyLane, null,
                        "2026-10-21T08:00:00Z", "2026-10-21T08:01:00Z", 1000)))
                .header("Authorization", basicAuthorization())
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, heavyResp.statusCode());
        assertTrue(heavyResp.body().length <= DailyFileSliceHandler.MAX_JSON_RESPONSE_BYTES);
        DailyFileSliceHandler.SliceResponse heavyParsed = GSON.fromJson(
                new String(heavyResp.body(), StandardCharsets.UTF_8),
                DailyFileSliceHandler.SliceResponse.class);
        assertTrue(heavyParsed.truncated());
        assertTrue(heavyParsed.items().size() > 100 && heavyParsed.items().size() < 400);

        // Tampered CSV size mismatching manifest must be rejected.
        String tamperedLane = "urn:osh:system:lane:tampered";
        String tamperedCsv = writePublishedJournalDay(tamperedLane, LocalDate.of(2026, 10, 22),
                ZoneOffset.UTC, List.of(new RawRecord(Instant.parse("2026-10-22T01:00:00Z"), "GS,1")));
        Files.writeString(dailyFilesDir.resolve(tamperedCsv), "extra", StandardOpenOption.APPEND);
        assertEquals(400, sendAuth(client, "GET", sliceQuery(tamperedLane, null,
                "2026-10-22T00:59:00Z", "2026-10-22T01:01:00Z", null)).statusCode());

        // Validation errors return 400.
        assertEquals(400, sendAuth(client, "GET",
                "/dailyfiles/dailyfile-slice?startTime=2026-10-26T01:00:00Z&endTime=2026-10-26T01:01:00Z").statusCode());
        assertEquals(400, sendAuth(client, "GET", sliceQuery(heavyLane, null,
                "2026-10-26T02:00:00Z", "2026-10-26T01:00:00Z", null)).statusCode());
        assertEquals(400, sendAuth(client, "GET", sliceQuery(heavyLane, null,
                "2026-10-25T00:00:00Z", "2026-10-26T00:00:01Z", null)).statusCode());
        assertEquals(400, sendAuth(client, "GET", sliceQuery(heavyLane, null,
                "2026-10-26T01:00:00Z", "2026-10-26T01:01:00Z", 1001)).statusCode());
    }

    private void assertSingleSliceMatch(HttpClient client, String laneUid,
                                        Instant target, String expectedMessage) throws Exception {
        HttpResponse<String> resp = sendAuth(client, "GET", sliceQuery(
                laneUid, null, target.toString(), target.toString(), null));
        assertEquals(200, resp.statusCode());
        DailyFileSliceHandler.SliceResponse parsed =
                GSON.fromJson(resp.body(), DailyFileSliceHandler.SliceResponse.class);
        assertEquals(1, parsed.items().size());
        assertEquals(expectedMessage, parsed.items().get(0).message());
        assertEquals(target.toString(), parsed.items().get(0).timestamp());
    }

    private String writePublishedJournalDay(String laneUid, LocalDate day, ZoneId zoneId,
                                            List<RawRecord> records) throws Exception {
        String csvName = DailyFileSliceHandler.publicationFileId(laneUid) + "_" + day + ".csv";
        Path csvPath = dailyFilesDir.resolve(csvName);
        StringBuilder sb = new StringBuilder();
        for (RawRecord rec : records) {
            String localTime = LEGACY_TIME.withZone(zoneId).format(rec.timestamp);
            String utcTime = LEGACY_TIME.withZone(ZoneOffset.UTC).format(rec.timestamp);
            sb.append(rec.rawLine).append(',').append(localTime).append(',').append(utcTime).append("\r\n");
        }
        byte[] csvBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(csvPath, csvBytes);

        Instant windowStart = day.atStartOfDay(zoneId).toInstant();
        Instant windowEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant();
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(csvBytes));
        String manifestJson = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"format\": \"OSCAR_LEGACY_DAILY_CSV\",\n"
                + "  \"laneId\": \"" + laneUid + "\",\n"
                + "  \"zoneId\": \"" + zoneId.getId() + "\",\n"
                + "  \"windowStartInclusive\": \"" + windowStart + "\",\n"
                + "  \"windowEndExclusive\": \"" + windowEnd + "\",\n"
                + "  \"recordCount\": " + records.size() + ",\n"
                + "  \"byteCount\": " + csvBytes.length + ",\n"
                + "  \"checksumAlgorithm\": \"SHA-256\",\n"
                + "  \"sha256\": \"" + sha256 + "\",\n"
                + "  \"dataFile\": \"" + csvName + "\",\n"
                + "  \"publishedAt\": \"" + windowEnd.plusSeconds(1) + "\"\n"
                + "}\n";
        Files.writeString(dailyFilesDir.resolve(csvName + ".manifest.v1.json"),
                manifestJson, StandardCharsets.UTF_8);
        return csvName;
    }

    private static String sliceQuery(String laneUid, String laneName,
                                     String startTime, String endTime, Integer limit) {
        StringBuilder q = new StringBuilder("/dailyfiles/dailyfile-slice?startTime=")
                .append(enc(startTime))
                .append("&endTime=")
                .append(enc(endTime));
        if (laneUid != null)
            q.append("&laneUid=").append(enc(laneUid));
        if (laneName != null)
            q.append("&laneName=").append(enc(laneName));
        if (limit != null)
            q.append("&limit=").append(limit);
        return q.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> sendAuth(HttpClient client, String method, String path)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Authorization", basicAuthorization());
        switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody());
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.noBody());
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

    private record RawRecord(Instant timestamp, String rawLine) {}
}
