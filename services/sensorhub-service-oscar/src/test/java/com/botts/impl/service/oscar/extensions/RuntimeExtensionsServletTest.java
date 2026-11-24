/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

import com.botts.impl.service.oscar.OSCARServiceConfig;
import com.botts.impl.service.oscar.eml.EmlPluginHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RuntimeExtensionsServletTest {

    private Path tempRoot;

    @After
    public void cleanup() throws Exception {
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    public void enforcesRbacServesPmTilesRangesPersistsAcrossUpgradeAndPublishesPolicyPacks() throws Exception {
        tempRoot = Files.createTempDirectory("oscar-ext-servlet-test-");
        Path extDir = tempRoot.resolve("extensions");
        Path mapsArchiveDir = tempRoot.resolve("archive-maps");
        Path emlDir = tempRoot.resolve("eml-plugin");

        ISensorHub hub = buildMockHub(Map.of(
                "admin", List.of("admin"),
                "operator1", List.of("operator")
        ));
        var logger = LoggerFactory.getLogger(RuntimeExtensionsServletTest.class);
        OSCARServiceConfig cfg = new OSCARServiceConfig();
        EmlPluginHandler emlHandler = new EmlPluginHandler(hub, logger, cfg, emlDir);
        AtomicInteger republishCalls = new AtomicInteger();

        RuntimeExtensionsManager manager = new RuntimeExtensionsManager(
                hub, cfg, emlHandler, logger, extDir, mapsArchiveDir, republishCalls::incrementAndGet);
        RuntimeExtensionsServlet servlet = new RuntimeExtensionsServlet(hub, manager, logger);

        // 1. Operator is forbidden from admin /status and /maps/upload, but allowed on /maps/active
        MockResp opStatus = invoke(servlet, "GET", "/status", "operator1", Map.of(), null, Map.of());
        assertEquals(403, opStatus.status);

        MockResp opActiveInitial = invoke(servlet, "GET", "/maps/active", "operator1", Map.of(), null, Map.of());
        assertEquals(200, opActiveInitial.status);
        JsonObject activeInitJson = JsonParser.parseString(opActiveInitial.bodyString()).getAsJsonObject();
        assertFalse(activeInitJson.get("active").getAsBoolean());

        // 2. Admin uploads a valid PMTiles v3 archive
        byte[] pmTilesPayload = buildSyntheticPmTilesV3((byte) 1, 2, 14, -85.5, 35.0, -83.0, 36.8);
        MockResp uploadMap = invoke(
                servlet,
                "POST",
                "/maps/upload",
                "admin",
                Map.of("filename", "east-tn-region.pmtiles", "displayName", "East Tennessee Offline Vector"),
                pmTilesPayload,
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, uploadMap.status);
        JsonObject statusAfterMap = JsonParser.parseString(uploadMap.bodyString()).getAsJsonObject();
        assertTrue(statusAfterMap.getAsJsonObject("maps").get("customActive").getAsBoolean());

        // 3. Operator can read /maps/active and issue RFC 9110 Range requests (HTTP 206) against /maps/tiles/...
        MockResp opActiveCustom = invoke(servlet, "GET", "/maps/active", "operator1", Map.of(), null, Map.of());
        assertEquals(200, opActiveCustom.status);
        JsonObject activeMapJson = JsonParser.parseString(opActiveCustom.bodyString()).getAsJsonObject();
        assertTrue(activeMapJson.get("active").getAsBoolean());
        assertEquals("mvt", activeMapJson.get("tileType").getAsString());
        assertEquals(14, activeMapJson.get("maxZoom").getAsInt());

        MockResp rangeResp = invoke(
                servlet,
                "GET",
                "/maps/tiles/east-tn-region.pmtiles",
                "operator1",
                Map.of(),
                null,
                Map.of("Range", "bytes=0-126")
        );
        assertEquals(206, rangeResp.status);
        assertEquals(127, rangeResp.body.toByteArray().length);
        assertEquals("bytes 0-126/" + pmTilesPayload.length, rangeResp.headers.get("Content-Range"));

        // 4. Admin uploads an Agency Policy Pack and verifies live republish callback + effective codes
        String policyJson = """
                {
                  "packId": "port-customs-v2",
                  "version": "2.0.0",
                  "agencyName": "Port Secondary Inspection",
                  "codes": [
                    { "code": 201, "label": "Cleared Medical Radiopharmaceutical" },
                    { "code": 202, "label": "Cleared Ceramic Tile NORM" }
                  ],
                  "operatorChecklist": ["Verify manifest match"]
                }
                """;
        MockResp policyResp = invoke(
                servlet,
                "POST",
                "/policy-packs/upload",
                "admin",
                Map.of("filename", "port-customs-v2.json"),
                policyJson.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, policyResp.status);
        assertEquals(1, republishCalls.get());
        assertEquals(2, manager.getEffectiveCustomAdjudicationCodes().size());

        // 5. Simulate an OSCAR version upgrade: instantiate a brand-new RuntimeExtensionsManager on the same state dirs
        RuntimeExtensionsManager upgradedManager = new RuntimeExtensionsManager(
                hub, cfg, emlHandler, logger, extDir, mapsArchiveDir, republishCalls::incrementAndGet);
        upgradedManager.restoreAllOnStartup();
        assertEquals(2, upgradedManager.getEffectiveCustomAdjudicationCodes().size());
        assertEquals(Boolean.TRUE, upgradedManager.getOfflineMapManager().getPublicActiveMapInfo("/sensorhub/oscar/extensions").get("active"));

        // 6. Starter template downloads succeed + OCR/Spectroscopy non-null Instant loadedAt serialize cleanly on JDK 21
        MockResp ocrTpl = invoke(servlet, "GET", "/templates/ocr-pack.zip", "admin", Map.of(), null, Map.of());
        assertEquals(200, ocrTpl.status);
        assertTrue(ocrTpl.body.toByteArray().length > 100);

        String ocrRulesJson = "{\"ruleSetId\":\"jdk21-instant-check\",\"minLength\":3,\"maxLength\":10}";
        MockResp ocrUpload = invoke(
                servlet,
                "POST",
                "/ocr/upload",
                "admin",
                Map.of("filename", "rules.json"),
                ocrRulesJson.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, ocrUpload.status);
        JsonObject ocrUpBody = JsonParser.parseString(ocrUpload.bodyString()).getAsJsonObject();
        assertTrue(ocrUpBody.getAsJsonObject("ocr").get("loadedAt").getAsString().contains("T"));

        String specJson = "{\"schemaVersion\":1,\"libraryId\":\"test-lib\",\"isotopes\":[{\"name\":\"Cs-137\",\"category\":\"Industrial\",\"energiesKeV\":[661.7]}]}";
        MockResp specUpload = invoke(
                servlet,
                "POST",
                "/spectroscopy/upload",
                "admin",
                Map.of("filename", "isotopes.json"),
                specJson.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, specUpload.status);
        JsonObject specUpBody = JsonParser.parseString(specUpload.bodyString()).getAsJsonObject();
        assertTrue(specUpBody.getAsJsonObject("spectroscopy").get("loadedAt").getAsString().contains("T"));

        invoke(servlet, "POST", "/ocr/action", "admin", Map.of(), "{\"action\":\"reset\"}".getBytes(StandardCharsets.UTF_8), Map.of("X-Requested-With", "OSCAR-Viewer"));
        invoke(servlet, "POST", "/spectroscopy/action", "admin", Map.of(), "{\"action\":\"reset\"}".getBytes(StandardCharsets.UTF_8), Map.of("X-Requested-With", "OSCAR-Viewer"));

        // 7. Reset returns cleanly to shipped defaults
        MockResp resetMap = invoke(
                servlet,
                "POST",
                "/maps/action",
                "admin",
                Map.of(),
                "{\"action\":\"reset\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "OSCAR-Viewer")
        );
        assertEquals(200, resetMap.status);
        assertEquals(Boolean.FALSE, manager.getOfflineMapManager().getPublicActiveMapInfo("/sensorhub/oscar/extensions").get("active"));
    }

    static byte[] buildSyntheticPmTilesV3(
            byte tileType,
            int minZoom,
            int maxZoom,
            double minLon,
            double minLat,
            double maxLon,
            double maxLat
    ) {
        byte[] out = new byte[256];
        ByteBuffer le = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = "PMTiles".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, out, 0, magic.length);
        out[7] = 3; // PMTiles v3
        le.putLong(8, 127L); // rootDirOffset
        le.putLong(16, 32L); // rootDirBytes
        le.putLong(24, 159L); // jsonMetadataOffset
        le.putLong(32, 2L); // jsonMetadataBytes
        le.putLong(40, 161L); // leafDirsOffset
        le.putLong(48, 0L); // leafDirsBytes
        le.putLong(56, 161L); // tileDataOffset
        le.putLong(64, 64L); // tileDataBytes
        le.putLong(72, 42L); // addressedTilesCount
        le.putLong(80, 42L); // tileEntriesCount
        le.putLong(88, 42L); // tileContentsCount
        out[96] = 1; // clustered
        out[97] = 2; // internalCompression = gzip
        out[98] = 2; // tileCompression = gzip
        out[99] = tileType;
        out[100] = (byte) minZoom;
        out[101] = (byte) maxZoom;
        le.putInt(102, (int) Math.round(minLon * 1e7));
        le.putInt(106, (int) Math.round(minLat * 1e7));
        le.putInt(110, (int) Math.round(maxLon * 1e7));
        le.putInt(114, (int) Math.round(maxLat * 1e7));
        out[118] = (byte) ((minZoom + maxZoom) / 2);
        le.putInt(119, (int) Math.round(((minLon + maxLon) / 2.0) * 1e7));
        le.putInt(123, (int) Math.round(((minLat + maxLat) / 2.0) * 1e7));
        return out;
    }

    private static ISensorHub buildMockHub(Map<String, Collection<String>> userRoles) {
        ISecurityManager sm = (ISecurityManager) Proxy.newProxyInstance(
                ISecurityManager.class.getClassLoader(),
                new Class<?>[]{ISecurityManager.class},
                (proxy, method, args) -> {
                    if ("getUserInfo".equals(method.getName()) && args != null && args.length == 1) {
                        String u = (String) args[0];
                        Collection<String> roles = userRoles.get(u);
                        if (roles == null) {
                            return null;
                        }
                        return (IUserInfo) Proxy.newProxyInstance(
                                IUserInfo.class.getClassLoader(),
                                new Class<?>[]{IUserInfo.class},
                                (up, um, ua) -> switch (um.getName()) {
                                    case "getId" -> u;
                                    case "getRoles" -> roles;
                                    default -> null;
                                }
                        );
                    }
                    return null;
                }
        );
        return (ISensorHub) Proxy.newProxyInstance(
                ISensorHub.class.getClassLoader(),
                new Class<?>[]{ISensorHub.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSecurityManager" -> sm;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    case "toString" -> "MockSensorHub";
                    default -> null;
                }
        );
    }

    private static final class MockResp {
        int status = 200;
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final StringWriter writerBuf = new StringWriter();

        String bodyString() {
            return body.size() > 0
                    ? body.toString(StandardCharsets.UTF_8)
                    : writerBuf.toString();
        }
    }

    private static MockResp invoke(
            RuntimeExtensionsServlet servlet,
            String httpMethod,
            String pathInfo,
            String username,
            Map<String, String> params,
            byte[] requestBytes,
            Map<String, String> reqHeaders
    ) throws Exception {
        byte[] payload = requestBytes == null ? new byte[0] : requestBytes;
        ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        ServletInputStream sis = new ServletInputStream() {
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) {}
            @Override public int read() { return bais.read(); }
            @Override public int read(byte[] b, int off, int len) { return bais.read(b, off, len); }
        };

        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> httpMethod;
                    case "getPathInfo" -> pathInfo;
                    case "getContextPath" -> "/sensorhub";
                    case "getServletPath" -> "/oscar/extensions";
                    case "getRemoteUser" -> username;
                    case "getUserPrincipal" -> username == null ? null : (Principal) () -> username;
                    case "getParameter" -> params.get((String) args[0]);
                    case "getHeader" -> {
                        String k = (String) args[0];
                        for (var e : reqHeaders.entrySet()) {
                            if (e.getKey().equalsIgnoreCase(k)) {
                                yield e.getValue();
                            }
                        }
                        yield null;
                    }
                    case "getInputStream" -> sis;
                    default -> null;
                }
        );

        MockResp out = new MockResp();
        ServletOutputStream sos = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener writeListener) {}
            @Override public void write(int b) { out.body.write(b); }
            @Override public void write(byte[] b, int off, int len) { out.body.write(b, off, len); }
        };
        PrintWriter pw = new PrintWriter(out.writerBuf);

        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> out.status = (Integer) args[0];
                        case "sendError" -> out.status = (Integer) args[0];
                        case "setHeader" -> out.headers.put((String) args[0], (String) args[1]);
                        case "setContentType" -> out.headers.put("Content-Type", (String) args[0]);
                        case "setContentLength" -> out.headers.put("Content-Length", String.valueOf(args[0]));
                        case "getOutputStream" -> { return sos; }
                        case "getWriter" -> { return pw; }
                    }
                    return null;
                }
        );

        if ("GET".equals(httpMethod)) {
            servlet.doGet(req, resp);
        } else if ("POST".equals(httpMethod)) {
            servlet.doPost(req, resp);
        } else if ("HEAD".equals(httpMethod)) {
            servlet.doHead(req, resp);
        }
        return out;
    }
}
