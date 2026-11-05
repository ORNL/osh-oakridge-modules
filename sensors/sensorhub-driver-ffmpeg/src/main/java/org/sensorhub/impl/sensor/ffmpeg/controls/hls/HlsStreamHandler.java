package org.sensorhub.impl.sensor.ffmpeg.controls.hls;

import com.botts.api.service.bucket.IBucketService;
import com.botts.api.service.bucket.IBucketStore;
import com.botts.impl.service.bucket.handler.DefaultObjectHandler;
import com.botts.impl.service.bucket.util.RequestContext;
import com.botts.impl.service.bucket.util.ServiceErrors;
import net.opengis.swe.v20.Category;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.impl.sensor.ffmpeg.controls.HLSControl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class HlsStreamHandler extends DefaultObjectHandler {

    public static final String VIEWER_ID_HEADER = "X-OSCAR-HLS-Viewer";
    public static final String VIEWER_STATE_HEADER = "X-OSCAR-HLS-Viewer-State";
    public static final String VIEWER_STATE_CLOSING = "closing";
    public static final String VIEWER_STATE_HEARTBEAT = "heartbeat";

    private static final Pattern HLS_PATTERN = Pattern.compile(".*\\.(m3u8|ts)$");
    static final long STREAM_TIMEOUT_MS = 30000;
    static final long VIEWER_LEASE_TTL_MS = 8_000L;
    private static final int MAX_VIEWERS_PER_STREAM = 64;
    private static final int MAX_VIEWER_ID_LENGTH = 128;
    private static final long CLEANUP_INTERVAL_MS = 1000;
    private static final String M3U8_SUFFIX = ".m3u8";
    private static final String TS_SUFFIX = ".ts";

    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<String, Long>> viewerLeasesByStream = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<String, Long>> closedViewersByStream = new ConcurrentHashMap<>();
    private final String streamBucketName;

    private record ActiveStream(HLSControl<?> control, long lastAccessMillis) {}

    public HlsStreamHandler(IBucketService service) {
        this(service, HLSControl.VIDEO_BUCKET);
    }

    public HlsStreamHandler(IBucketService service, String streamBucketName) {
        super(service.getBucketStore());
        this.streamBucketName = Objects.requireNonNull(streamBucketName, "streamBucketName");
        if (!(service.getThreadPool() instanceof ScheduledExecutorService scheduler)) {
            throw new IllegalStateException("Bucket service must be running with a scheduled executor");
        }

        // Use the bucket service's lifecycle-owned executor. BucketService shuts this executor
        // down when the service stops, which also cancels this periodic task and releases its
        // reference to the handler. A process-wide executor would retain stopped services.
        scheduler.scheduleAtFixedRate(this::cleanupInactiveStreams,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void doGet(RequestContext ctx) throws IOException, SecurityException {
        var bucketName = ctx.getBucketName();
        var objectKey = Paths.get(ctx.getObjectKey()).toString();
        checkGetPermission(ctx, bucketName);

        long now = System.currentTimeMillis();
        String viewerId = sanitizeViewerId(extractHeader(ctx, VIEWER_ID_HEADER));
        String viewerState = extractHeader(ctx, VIEWER_STATE_HEADER);
        String trimmedViewerState = viewerState != null ? viewerState.trim() : "";
        if (viewerId != null && VIEWER_STATE_CLOSING.equalsIgnoreCase(trimmedViewerState)) {
            releaseViewerLease(bucketName, objectKey, viewerId, now);
            ctx.getResponse().setHeader("Cache-Control", "no-store");
            ctx.getResponse().setStatus(204);
            return;
        }
        if (viewerId != null && VIEWER_STATE_HEARTBEAT.equalsIgnoreCase(trimmedViewerState)) {
            if (objectKey.endsWith(M3U8_SUFFIX)) {
                refreshManifestActivity(bucketName, objectKey, now);
            }
            recordViewerLease(bucketName, objectKey, viewerId, now);
            ctx.getResponse().setHeader("Cache-Control", "no-store");
            ctx.getResponse().setStatus(204);
            return;
        }

        boolean manifestRequest = objectKey.endsWith(M3U8_SUFFIX);
        if (manifestRequest) {
            // A client polling while FFmpeg waits for its first keyframe is active even though
            // there is intentionally no placeholder manifest to serve yet.
            refreshManifestActivity(bucketName, objectKey, now);
        }
        if (viewerId != null) {
            recordViewerLease(bucketName, objectKey, viewerId, now);
        }

        boolean objectExists = bucketStore.objectExists(bucketName, objectKey);

        if (!objectExists)
            throw ServiceErrors.notFound(objectKey + " in bucket " + bucketName);

        if (manifestRequest) {
            try {
                if (bucketStore.getObjectSize(bucketName, objectKey) == 0) {
                    throw ServiceErrors.notFound(objectKey + " is not ready");
                }
            } catch (DataStoreException e) {
                throw ServiceErrors.internalError(IBucketStore.FAILED_GET_OBJECT + bucketName);
            }
        }

        String mimeType;
        try {
            mimeType = bucketStore.getObjectMimeType(bucketName, objectKey);
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.UNABLE_DETERMINE_MIME_TYPE + objectKey);
        }

        if (mimeType == null || mimeType.isBlank())
            throw ServiceErrors.internalError(IBucketStore.UNABLE_DETERMINE_MIME_TYPE + objectKey);

        try {
            if (manifestRequest) {
                ctx.getResponse().setHeader("Cache-Control", "no-store");
            }
            ctx.getResponse().setContentType(mimeType);
            try (InputStream objectData = bucketStore.getObject(bucketName, objectKey)) {
                objectData.transferTo(ctx.getResponse().getOutputStream());
            }
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.FAILED_GET_OBJECT + bucketName);
        }
    }

    private static String extractHeader(RequestContext ctx, String headerName) {
        if (ctx == null || headerName == null) {
            return null;
        }
        if (ctx.getRequest() != null) {
            String direct = ctx.getRequest().getHeader(headerName);
            if (direct != null) {
                return direct;
            }
        }
        Map<String, String> headers = ctx.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String sanitizeViewerId(String rawViewerId) {
        if (rawViewerId == null) {
            return null;
        }
        String trimmed = rawViewerId.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_VIEWER_ID_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private String resolveStreamKey(String bucketName, String objectKey) {
        if (!streamBucketName.equals(bucketName) || objectKey == null || objectKey.isEmpty()) {
            return null;
        }
        if (objectKey.endsWith(M3U8_SUFFIX)) {
            return objectKey;
        }
        if (objectKey.endsWith(TS_SUFFIX)) {
            Path parent = Paths.get(objectKey).getParent();
            if (parent != null) {
                String candidate = parent.resolve("live.m3u8").toString();
                if (activeStreams.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    void recordViewerLease(String bucketName, String objectKey, String viewerId, long now) {
        String streamKey = resolveStreamKey(bucketName, objectKey);
        if (streamKey == null || viewerId == null || !activeStreams.containsKey(streamKey)) {
            return;
        }
        ConcurrentHashMap<String, Long> closedViewers = closedViewersByStream.get(streamKey);
        if (closedViewers != null) {
            closedViewers.entrySet().removeIf(entry -> now - entry.getValue() >= VIEWER_LEASE_TTL_MS);
            if (closedViewers.containsKey(viewerId)) {
                return;
            }
        }
        ConcurrentHashMap<String, Long> leases = viewerLeasesByStream.computeIfAbsent(
                streamKey, ignored -> new ConcurrentHashMap<>());
        leases.entrySet().removeIf(entry -> now - entry.getValue() >= VIEWER_LEASE_TTL_MS);
        if (leases.containsKey(viewerId) || leases.size() < MAX_VIEWERS_PER_STREAM) {
            leases.put(viewerId, now);
        }
    }

    void releaseViewerLease(String bucketName, String objectKey, String viewerId, long now) {
        String streamKey = resolveStreamKey(bucketName, objectKey);
        if (streamKey == null || viewerId == null) {
            return;
        }
        ConcurrentHashMap<String, Long> closedViewers = closedViewersByStream.computeIfAbsent(
                streamKey, ignored -> new ConcurrentHashMap<>());
        closedViewers.entrySet().removeIf(entry -> now - entry.getValue() >= VIEWER_LEASE_TTL_MS);
        if (closedViewers.size() < MAX_VIEWERS_PER_STREAM * 2) {
            closedViewers.put(viewerId, now);
        }
        ConcurrentHashMap<String, Long> leases = viewerLeasesByStream.get(streamKey);
        if (leases != null) {
            leases.remove(viewerId);
        }
    }

    public boolean hasActiveViewers(String streamName) {
        return hasActiveViewers(streamName, System.currentTimeMillis());
    }

    public boolean hasActiveViewers(String streamName, long now) {
        if (streamName == null || streamName.isBlank() || !activeStreams.containsKey(streamName)) {
            return false;
        }
        ConcurrentHashMap<String, Long> leases = viewerLeasesByStream.get(streamName);
        if (leases == null || leases.isEmpty()) {
            return false;
        }
        leases.entrySet().removeIf(entry -> now - entry.getValue() >= VIEWER_LEASE_TTL_MS);
        return !leases.isEmpty();
    }

    protected void checkGetPermission(RequestContext ctx, String bucketName) {
        var sec = ctx.getSecurityHandler();
        sec.checkPermission(sec.getBucketPermission(bucketName).get);
    }

    boolean refreshManifestActivity(String bucketName, String objectKey, long accessTime) {
        if (!streamBucketName.equals(bucketName) || !objectKey.endsWith(M3U8_SUFFIX)) {
            return false;
        }

        return activeStreams.computeIfPresent(objectKey,
                (key, stream) -> new ActiveStream(stream.control(), accessTime)) != null;
    }

    // Called by ffmpeg hlscontrols
    public void addControl(String streamName, HLSControl<?> control) {
        // Start the timeout at stream creation. A stream that is never requested must not run
        // forever, while authorized manifest polling (including pre-manifest 404s) refreshes it.
        viewerLeasesByStream.remove(streamName);
        closedViewersByStream.remove(streamName);
        activeStreams.put(streamName, new ActiveStream(control, System.currentTimeMillis()));
    }

    public void removeControl(String stream, HLSControl<?> control) {
        ActiveStream updated = activeStreams.computeIfPresent(stream,
                (key, activeStream) -> activeStream.control() == control ? null : activeStream);
        if (updated == null) {
            viewerLeasesByStream.remove(stream);
            closedViewersByStream.remove(stream);
        }
    }

    private void stopStream(HLSControl<?> control) {
        if (control != null) {
            control.prepareForceStop();
            var command = control.getCommandDescription().clone();
            command.renewDataBlock();
            ((Category)command.getComponent(0)).setValue(HLSControl.CMD_END_STREAM);
            control.submitCommand(new CommandData.Builder().withParams(command.getData()).withCommandStream(BigId.NONE).build());
        }
    }

    void cleanupInactiveStreams(long now) {
        for (var entry : activeStreams.entrySet()) {
            if (now - entry.getValue().lastAccessMillis() >= STREAM_TIMEOUT_MS
                    && activeStreams.remove(entry.getKey(), entry.getValue())) {
                viewerLeasesByStream.remove(entry.getKey());
                closedViewersByStream.remove(entry.getKey());
                // Conditional removal ensures a manifest request racing this cleanup pass can
                // atomically replace the activity record without having its stream stopped.
                stopStream(entry.getValue().control());
            }
        }
    }

    private void cleanupInactiveStreams() {
        cleanupInactiveStreams(System.currentTimeMillis());
    }

    boolean isActiveStream(String streamName) {
        return activeStreams.containsKey(streamName);
    }

    long getLastAccessMillis(String streamName) {
        ActiveStream stream = activeStreams.get(streamName);
        return stream != null ? stream.lastAccessMillis() : -1L;
    }

    @Override
    public void doPost(RequestContext ctx) throws IOException, SecurityException {
        throw ServiceErrors.unsupportedOperation("POST not supported on video streams");
        //super.doPost(ctx);
    }

    @Override
    public void doPut(RequestContext ctx) throws IOException, SecurityException {
        throw ServiceErrors.unsupportedOperation("PUT not supported on video streams");
        //super.doPut(ctx);
    }

    @Override
    public void doDelete(RequestContext ctx) throws IOException, SecurityException {
        throw ServiceErrors.unsupportedOperation("DELETE not supported on video streams");
        //super.doDelete(ctx);
    }

    @Override
    public String getObjectPattern() {
        return HLS_PATTERN.pattern();
    }

}
