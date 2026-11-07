package com.botts.impl.service.bucket.handler;

import com.botts.api.service.bucket.IBucketStore;
import com.botts.api.service.bucket.IObjectHandler;
import com.botts.impl.service.bucket.util.RequestContext;
import com.botts.impl.service.bucket.util.ServiceErrors;
import com.botts.impl.service.bucket.util.UploadPolicy;
import org.sensorhub.api.datastore.DataStoreException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

public class DefaultObjectHandler implements IObjectHandler {

    protected final IBucketStore bucketStore;
    private final MultipartHandler multipartHandler;

    public DefaultObjectHandler(IBucketStore bucketStore) {
        this(bucketStore, UploadPolicy.DEFAULT_MAX_FILE_SIZE_BYTES);
    }

    public DefaultObjectHandler(IBucketStore bucketStore, long maxFileSizeBytes) {
        this.bucketStore = bucketStore;
        this.multipartHandler = new MultipartHandler(bucketStore, maxFileSizeBytes);
    }

    @Override
    public void doGet(RequestContext ctx) throws IOException, SecurityException {
        var bucketName = ctx.getBucketName();
        var objectKey = ctx.getObjectKey();
        var sec = ctx.getSecurityHandler();
        sec.checkPermission(sec.getBucketPermission(bucketName).get);

        boolean objectExists = bucketStore.objectExists(bucketName, objectKey);
        if (!objectExists)
            throw ServiceErrors.notFound(objectKey + " in bucket " + bucketName);

        String mimeType;
        try {
            mimeType = bucketStore.getObjectMimeType(bucketName, objectKey);
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.UNABLE_DETERMINE_MIME_TYPE + objectKey);
        }

        if (mimeType == null || mimeType.isBlank())
            throw ServiceErrors.internalError(IBucketStore.UNABLE_DETERMINE_MIME_TYPE + objectKey);

        try {
            ctx.getResponse().setContentType(mimeType);
            if (isAttachmentRequested(ctx, objectKey)) {
                ctx.getResponse().setHeader("Content-Disposition",
                        "attachment; filename=\"" + safeDownloadFileName(objectKey) + "\"");
            }
            try (InputStream objectData = bucketStore.getObject(bucketName, objectKey)) {
                objectData.transferTo(ctx.getResponse().getOutputStream());
            }
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.FAILED_GET_OBJECT + bucketName);
        }
    }

    private static boolean isAttachmentRequested(RequestContext ctx, String objectKey) {
        if (objectKey != null && objectKey.toLowerCase().endsWith(".xlsx")) {
            return true;
        }
        if (ctx == null || ctx.getRequest() == null) {
            return false;
        }
        String dl = ctx.getRequest().getParameter("download");
        return "1".equals(dl) || "true".equalsIgnoreCase(dl);
    }

    private static String safeDownloadFileName(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "download";
        }
        String normalized = objectKey.replace('\\', '/');
        int slashIdx = normalized.lastIndexOf('/');
        String base = slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        String sanitized = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "download" : sanitized;
    }

    @Override
    public void doPost(RequestContext ctx) throws IOException, SecurityException {
        // /buckets/{bucketName}
        var bucketName = ctx.getBucketName();
        var sec = ctx.getSecurityHandler();
        sec.checkPermission(sec.getBucketPermission(bucketName).create);

        if (ctx.hasObjectKey())
            throw ServiceErrors.unsupportedOperation("You can only create an object from the root of a bucket");

        if (ctx.isMultipartRequest()) {
            multipartHandler.handleMultipartPost(ctx);
            return;
        }

        if (ctx.getHeaders().get("Content-Type") == null)
            throw ServiceErrors.badRequest("Content-Type header is required");
        UploadPolicy.validateMetadata(ctx.getHeaders());

        String newObjectKey;
        try (InputStream data = ctx.getRequest().getInputStream()) {
            newObjectKey = bucketStore.createObject(bucketName, data, ctx.getHeaders());
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.FAILED_CREATE_OBJECT + bucketName);
        }

        if (newObjectKey == null)
            throw ServiceErrors.internalError(IBucketStore.FAILED_CREATE_OBJECT + bucketName);

        ctx.getResponse().setStatus(HttpServletResponse.SC_CREATED);
        ctx.getResponse().setHeader("Location", ctx.getResourceURL(newObjectKey));
    }

    // /buckets/{bucketName}/{newOrExistingObjectKey}
    @Override
    public void doPut(RequestContext ctx) throws IOException, SecurityException {
        var bucketName = ctx.getBucketName();
        var objectKey = ctx.getObjectKey();
        var sec = ctx.getSecurityHandler();
        sec.checkPermission(sec.getBucketPermission(bucketName).put);

        if (ctx.isMultipartRequest()) {
            multipartHandler.handleMultipartPut(ctx);
            return;
        }

        UploadPolicy.validateUpload(objectKey, ctx.getHeaders());

        // Original raw stream upload behavior
        int successStatus = bucketStore.objectExists(bucketName, objectKey) ?
                HttpServletResponse.SC_OK : HttpServletResponse.SC_CREATED;

        try (InputStream data = ctx.getRequest().getInputStream()) {
            bucketStore.putObject(bucketName, objectKey, data, ctx.getHeaders());
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.FAILED_PUT_OBJECT + bucketName);
        }

        ctx.getResponse().setStatus(successStatus);
        ctx.getResponse().setHeader("Location", ctx.getResourceURL(objectKey));
    }

    // /buckets/{bucketName}/{objectKey}
    @Override
    public void doDelete(RequestContext ctx) throws IOException, SecurityException {
        var bucketName = ctx.getBucketName();
        var objectKey = ctx.getObjectKey();
        var sec = ctx.getSecurityHandler();
        sec.checkPermission(sec.getBucketPermission(bucketName).delete);

        try {
            bucketStore.deleteObject(bucketName, objectKey);
        } catch (DataStoreException e) {
            throw ServiceErrors.internalError(IBucketStore.FAILED_DELETE_OBJECT + bucketName);
        }
    }

    @Override
    public String getObjectPattern() {
        return Pattern.compile(".*").pattern();
    }
}
