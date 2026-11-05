package org.sensorhub.impl.sensor.ffmpeg;

import org.junit.Assume;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.mpegts.MpegTsProcessor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MediaMtxSidecarManagerTest {

    @Test
    public void normalizesDoubleSlashesSpecialPasswordsAndCanonicalKeys() {
        Optional<MediaMtxSidecarManager.NormalizedRtspTarget> withDoubleSlash =
                MediaMtxSidecarManager.parseAndNormalizeRtspUrl(
                        "rtsp://admin:p@ss:word@Camera.Example.Local:554//h264Preview_01_sub?transport=tcp");
        assertTrue(withDoubleSlash.isPresent());
        assertEquals(
                "rtsp://admin:p%40ss:word@Camera.Example.Local:554/h264Preview_01_sub?transport=tcp",
                withDoubleSlash.get().sourceUrl());

        Optional<MediaMtxSidecarManager.NormalizedRtspTarget> implicitPortTripleSlash =
                MediaMtxSidecarManager.parseAndNormalizeRtspUrl(
                        "rtsp://admin:p@ss:word@camera.example.local///h264Preview_01_sub?transport=tcp");
        assertTrue(implicitPortTripleSlash.isPresent());
        assertEquals(
                "Equivalent camera URLs differing only in default :554, host case, or extra leading slashes must share one canonical key",
                withDoubleSlash.get().canonicalKey(),
                implicitPortTripleSlash.get().canonicalKey());

        Optional<MediaMtxSidecarManager.NormalizedRtspTarget> rtspsTarget =
                MediaMtxSidecarManager.parseAndNormalizeRtspUrl("rtsps://camera.local//secureStream");
        assertTrue(rtspsTarget.isPresent());
        assertEquals(322, rtspsTarget.get().port());
        assertEquals("rtsps://camera.local/secureStream", rtspsTarget.get().sourceUrl());

        assertFalse(MediaMtxSidecarManager.parseAndNormalizeRtspUrl("/tmp/sample.ts").isPresent());
        assertFalse(MediaMtxSidecarManager.parseAndNormalizeRtspUrl("http://camera.local/video").isPresent());
    }

    @Test
    public void redactsCredentialsFromUrlsAndLogMessages() {
        String redacted = MediaMtxSidecarManager.redactCredentials(
                "Unable to open rtsp://operator:TopSecretValue!@10.20.30.40:554/h264Preview_01_main");
        assertFalse(redacted.contains("TopSecretValue"));
        assertFalse(redacted.contains("operator"));
        assertTrue(redacted.contains("rtsp://***@10.20.30.40:554/h264Preview_01_main"));
    }

    @Test
    public void fallsBackDirectlyWhenMediaMtxBinaryIsAbsent() throws Exception {
        try (MediaMtxSidecarManager manager = new MediaMtxSidecarManager(
                Paths.get("/nonexistent/oscar-test-mediamtx"), 0, 0, false)) {
            String resolved = manager.acquireStream(
                    "lease-direct",
                    "rtsp://user:secret@camera.example:554//h264Preview_01_sub",
                    2_000L);
            assertEquals("rtsp://user:secret@camera.example:554/h264Preview_01_sub", resolved);
            assertFalse(manager.isProcessAlive());
        }
    }

    @Test
    public void supervisesDeduplicatesRejectsOfflineAndHealsRealMediaMtxProcess() throws Exception {
        Path mediaMtxBin = findMediaMtxBinary();
        Assume.assumeTrue("mediamtx binary not configured for integration test", mediaMtxBin != null);
        Assume.assumeTrue("ffmpeg CLI not available for upstream RTSP publisher fixture", isFfmpegAvailable());

        int upstreamRtspPort = allocateFreeLoopbackPort();
        Path tempDir = Files.createTempDirectory("mediamtx-upstream-test-");
        Process upstreamServer = null;
        Process publisher = null;

        try {
            Path upstreamConfig = tempDir.resolve("upstream.yml");
            Files.writeString(upstreamConfig,
                    "logLevel: warn\n"
                            + "rtsp: yes\n"
                            + "rtspTransports: [tcp]\n"
                            + "rtspAddress: 127.0.0.1:" + upstreamRtspPort + "\n"
                            + "rtmp: no\n"
                            + "hls: no\n"
                            + "webrtc: no\n"
                            + "srt: no\n"
                            + "paths:\n"
                            + "  all_others:\n",
                    StandardCharsets.UTF_8);

            upstreamServer = new ProcessBuilder(
                    mediaMtxBin.toAbsolutePath().toString(),
                    upstreamConfig.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            publisher = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-re", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15",
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                    "-pix_fmt", "yuv420p", "-g", "15",
                    "-f", "rtsp", "-rtsp_transport", "tcp",
                    "rtsp://127.0.0.1:" + upstreamRtspPort + "/h264Preview_01_sub")
                    .redirectErrorStream(true)
                    .start();

            try (MediaMtxSidecarManager manager = new MediaMtxSidecarManager(mediaMtxBin, 0, 0, false)) {
                int closedPort = allocateFreeLoopbackPort();
                try {
                    manager.acquireStream(
                            "offline-lease",
                            "rtsp://admin:SecretPass123@127.0.0.1:" + closedPort + "//h264Preview_01_sub",
                            1_200L);
                    fail("Expected offline RTSP source to fail readiness check");
                } catch (SensorHubException expected) {
                    assertFalse("Exception message must redact RTSP credentials",
                            expected.getMessage().contains("SecretPass123"));
                } finally {
                    manager.releaseStream("offline-lease");
                }
                assertEquals(0, manager.getActivePathCount());

                String upstreamUrlDoubleSlash = "rtsp://127.0.0.1:" + upstreamRtspPort + "//h264Preview_01_sub";
                String upstreamUrlSingleSlash = "rtsp://127.0.0.1:" + upstreamRtspPort + "/h264Preview_01_sub";

                String localUrl1 = manager.acquireStream("lease-1", upstreamUrlDoubleSlash, 6_000L);
                String localUrl2 = manager.acquireStream("lease-2", upstreamUrlSingleSlash, 6_000L);

                assertEquals("Equivalent camera URLs must share one MediaMTX loopback path", localUrl1, localUrl2);
                assertEquals(1, manager.getActivePathCount());
                assertEquals(2, manager.getActiveHolderCountForUrl(upstreamUrlSingleSlash));

                MpegTsProcessor processor = new MpegTsProcessor(localUrl1, 0, false, true);
                processor.setTimeout(4_000_000L);
                try {
                    assertTrue("MpegTsProcessor must open the proxied loopback RTSP stream", processor.openStream());
                    processor.queryEmbeddedStreams();
                    assertTrue(processor.hasVideoStream());
                    int[] dims = processor.getVideoStreamFrameDimensions();
                    assertEquals(320, dims[0]);
                    assertEquals(240, dims[1]);
                } finally {
                    processor.closeStream();
                }

                // Force-kill the child MediaMTX process and verify acquireStream heals it transparently.
                Process rawChild = extractChildProcess(manager);
                assertNotNull(rawChild);
                rawChild.destroyForcibly();
                rawChild.waitFor(3, TimeUnit.SECONDS);

                String healedUrl = manager.acquireStream("lease-1", upstreamUrlSingleSlash, 6_000L);
                assertTrue(healedUrl.startsWith("rtsp://oscar-rtsp:"));
                assertTrue(manager.isProcessAlive());
                assertEquals(1, manager.getActivePathCount());

                manager.releaseStream("lease-1");
                assertEquals(1, manager.getActivePathCount());
                assertEquals(1, manager.getActiveHolderCountForUrl(upstreamUrlSingleSlash));

                manager.releaseStream("lease-2");
                assertEquals(0, manager.getActivePathCount());
                assertEquals(0, manager.getActiveHolderCountForUrl(upstreamUrlSingleSlash));
            }
        } finally {
            if (publisher != null) {
                publisher.destroyForcibly();
                publisher.waitFor(3, TimeUnit.SECONDS);
            }
            if (upstreamServer != null) {
                upstreamServer.destroyForcibly();
                upstreamServer.waitFor(3, TimeUnit.SECONDS);
            }
            try (var walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    private static Process extractChildProcess(MediaMtxSidecarManager manager) throws Exception {
        Field field = MediaMtxSidecarManager.class.getDeclaredField("process");
        field.setAccessible(true);
        return (Process) field.get(manager);
    }

    private static Path findMediaMtxBinary() {
        for (String candidate : new String[]{
                System.getProperty(MediaMtxSidecarManager.PROP_BIN),
                System.getenv(MediaMtxSidecarManager.ENV_BIN),
                "/usr/local/bin/mediamtx"
        }) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Paths.get(candidate.trim());
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    private static boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int allocateFreeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
