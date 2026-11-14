package com.botts.impl.service.oscar.alarms;

import com.botts.api.service.bucket.IBucketStore;
import com.botts.api.service.bucket.IObjectHandler;
import com.botts.impl.service.bucket.util.RequestContext;
import com.botts.impl.service.bucket.util.ServiceErrors;
import com.botts.impl.service.oscar.Constants;
import org.sensorhub.api.datastore.DataStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authenticated {@link IObjectHandler} serving {@code /buckets/adjudication/dashboard-read-state}.
 *
 * <p>Provides site-shared per-lane Dashboard alarm read/acknowledge state with conditional
 * {@code If-None-Match} / {@code 304 Not Modified} fast-path responses and idempotent per-lane
 * merge updates ({@code POST} / {@code PUT}).</p>
 */
public final class DashboardAlarmReadStateHandler implements IObjectHandler {
    public static final String BUCKET_NAME = Constants.ADJUDICATION_BUCKET;
    public static final String OBJECT_KEY = "dashboard-read-state";
    public static final int MAX_REQUEST_BYTES = 64 * 1024;

    private static final String BUCKET_PATH_ANCHOR = ".oscar-adjudication-path-anchor";
    private static final Pattern OBJECT_PATTERN = Pattern.compile("dashboard-read-state(?:/.*)?");

    private final DashboardAlarmReadStateStore store;

    public DashboardAlarmReadStateHandler(IBucketStore bucketStore) throws DataStoreException {
        this(bucketStore, Clock.systemUTC(), LoggerFactory.getLogger(DashboardAlarmReadStateHandler.class));
    }

    public DashboardAlarmReadStateHandler(IBucketStore bucketStore, Logger logger) throws DataStoreException {
        this(bucketStore, Clock.systemUTC(), logger);
    }

    public DashboardAlarmReadStateHandler(IBucketStore bucketStore,
                                          Clock clock,
                                          Logger logger) throws DataStoreException {
        Objects.requireNonNull(bucketStore, "bucketStore");
        if (!bucketStore.bucketExists(BUCKET_NAME)) {
            bucketStore.createBucket(BUCKET_NAME);
        }
        try {
            Path anchor = Path.of(bucketStore.getWritableResourceURI(BUCKET_NAME, BUCKET_PATH_ANCHOR))
                    .toAbsolutePath().normalize();
            Path root = anchor.getParent();
            if (root == null
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) {
                throw new DataStoreException("Adjudication bucket is not a regular directory");
            }
            this.store = new DashboardAlarmReadStateStore(root.toRealPath(), clock, logger);
        } catch (IOException | IllegalArgumentException e) {
            throw new DataStoreException("Unable to initialize dashboard alarm read-state store", e);
        }
    }

    public DashboardAlarmReadStateStore getStore() {
        return store;
    }

    @Override
    public boolean canHandleRequest(HttpServletRequest request) {
        if (request == null || request.getPathInfo() == null)
            return false;
        String pathInfo = request.getPathInfo().replace('\\', '/');
        String prefix = "/" + BUCKET_NAME + "/" + OBJECT_KEY;
        return prefix.equals(pathInfo) || pathInfo.startsWith(prefix + "/");
    }

    @Override
    public String getObjectPattern() {
        return OBJECT_PATTERN.pattern();
    }

    @Override
    public void doGet(RequestContext ctx) throws IOException, SecurityException {
        var sec = ctx.getSecurityHandler();
        var perms = sec.getBucketPermission(ctx.getBucketName());
        if (perms == null)
            throw ServiceErrors.forbidden(ctx.getBucketName());
        sec.checkPermission(perms.get);

        requireTargetResource(ctx);

        DashboardAlarmReadStateStore.CachedSnapshot snapshot = store.getSnapshot();
        HttpServletResponse resp = ctx.getResponse();
        applyStandardHeaders(resp, snapshot.etag());

        if (ifNoneMatchSatisfied(ctx.getRequest().getHeader("If-None-Match"), snapshot.etag())
                || sinceRevisionSatisfied(ctx.getRequest().getParameter("sinceRevision"), snapshot.revision())) {
            resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        writeSnapshotResponse(resp, snapshot);
    }

    @Override
    public void doPost(RequestContext ctx) throws IOException, SecurityException {
        var sec = ctx.getSecurityHandler();
        var perms = sec.getBucketPermission(ctx.getBucketName());
        if (perms == null)
            throw ServiceErrors.forbidden(ctx.getBucketName());
        sec.checkPermission(perms.create);

        requireTargetResource(ctx);
        handleMergeMutation(ctx);
    }

    @Override
    public void doPut(RequestContext ctx) throws IOException, SecurityException {
        var sec = ctx.getSecurityHandler();
        var perms = sec.getBucketPermission(ctx.getBucketName());
        if (perms == null)
            throw ServiceErrors.forbidden(ctx.getBucketName());
        sec.checkPermission(perms.put);

        requireTargetResource(ctx);
        handleMergeMutation(ctx);
    }

    @Override
    public void doDelete(RequestContext ctx) throws IOException {
        throw ServiceErrors.unsupportedOperation("Dashboard alarm read-state deletion is not supported");
    }

    private void handleMergeMutation(RequestContext ctx) throws IOException {
        String rawBody = readBoundedBodyUtf8(ctx.getRequest());
        DashboardAlarmReadStateStore.CachedSnapshot updated = store.applyJsonUpdate(rawBody);
        HttpServletResponse resp = ctx.getResponse();
        applyStandardHeaders(resp, updated.etag());
        writeSnapshotResponse(resp, updated);
    }

    private static void requireTargetResource(RequestContext ctx) throws com.botts.impl.service.bucket.util.InvalidRequestException {
        if (!BUCKET_NAME.equals(ctx.getBucketName()))
            throw ServiceErrors.notFound(ctx.getObjectKey());
        String key = ctx.getObjectKey() == null ? "" : ctx.getObjectKey().replace('\\', '/');
        if (!OBJECT_KEY.equals(key))
            throw ServiceErrors.notFound(key);
    }

    private static void applyStandardHeaders(HttpServletResponse resp, String etag) {
        resp.setHeader("ETag", etag);
        resp.setHeader("Cache-Control", "private, no-cache");
        resp.setHeader("X-Content-Type-Options", "nosniff");
    }

    private static void writeSnapshotResponse(HttpServletResponse resp,
                                              DashboardAlarmReadStateStore.CachedSnapshot snapshot)
            throws IOException {
        byte[] bytes = snapshot.jsonBytes();
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json; charset=utf-8");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
        resp.getOutputStream().flush();
    }

    static boolean sinceRevisionSatisfied(String rawSinceRevision, long currentRevision) {
        if (rawSinceRevision == null || rawSinceRevision.isBlank())
            return false;
        try {
            return Long.parseLong(rawSinceRevision.trim()) == currentRevision;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean ifNoneMatchSatisfied(String headerValue, String currentEtag) {
        if (headerValue == null || headerValue.isBlank() || currentEtag == null)
            return false;
        for (String rawToken : headerValue.split(",")) {
            String token = rawToken.trim();
            if ("*".equals(token))
                return true;
            if (token.startsWith("W/"))
                token = token.substring(2).trim();
            if (currentEtag.equals(token))
                return true;
        }
        return false;
    }

    private static String readBoundedBodyUtf8(HttpServletRequest req) throws IOException {
        long declaredLength = req.getContentLengthLong();
        if (declaredLength > MAX_REQUEST_BYTES) {
            throw ServiceErrors.badRequest(
                    "Dashboard read-state update exceeds maximum size of " + MAX_REQUEST_BYTES + " bytes");
        }
        int initialCapacity = declaredLength > 0
                ? (int) Math.min(declaredLength, 4096L) : 512;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(initialCapacity);
        byte[] chunk = new byte[4096];
        int total = 0;
        try (InputStream in = req.getInputStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw ServiceErrors.badRequest(
                            "Dashboard read-state update exceeds maximum size of " + MAX_REQUEST_BYTES + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
