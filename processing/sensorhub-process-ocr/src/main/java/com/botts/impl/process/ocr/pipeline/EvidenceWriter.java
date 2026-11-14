/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.pipeline;

import com.botts.api.service.bucket.IBucketService;
import com.botts.api.service.bucket.IBucketStore;
import org.sensorhub.api.datastore.DataStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists padded JPEG evidence crops for recognized container/plate detections into the
 * {@code ocr} bucket, ensures dynamic SensorHub HTTP {@code GET} permission registration via
 * {@link IBucketService#ensureBucketRegistered(String)}, and enforces a bounded retention ceiling
 * so evidence crops never grow without bound.
 */
public class EvidenceWriter {

    public static final String OCR_BUCKET = "ocr";
    public static final String CROPS_PREFIX = "crops/";
    public static final int DEFAULT_MAX_RETAINED_CROPS = 2_000;

    private static final Logger logger = LoggerFactory.getLogger(EvidenceWriter.class);
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final double PADDING_FRACTION = 0.25;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int PRUNE_CHECK_INTERVAL = 25;

    private final IBucketStore bucketStore;
    private final IBucketService bucketService;
    private final int maxRetainedCrops;
    private final AtomicInteger writesSincePrune = new AtomicInteger(0);
    private final AtomicBoolean registeredInService = new AtomicBoolean(false);

    public EvidenceWriter(IBucketStore bucketStore) {
        this(bucketStore, null, DEFAULT_MAX_RETAINED_CROPS);
    }

    public EvidenceWriter(IBucketStore bucketStore, int maxRetainedCrops) {
        this(bucketStore, null, maxRetainedCrops);
    }

    public EvidenceWriter(IBucketStore bucketStore, IBucketService bucketService) {
        this(bucketStore, bucketService, DEFAULT_MAX_RETAINED_CROPS);
    }

    public EvidenceWriter(IBucketStore bucketStore, IBucketService bucketService, int maxRetainedCrops) {
        this.bucketStore = bucketStore;
        this.bucketService = bucketService;
        this.maxRetainedCrops = Math.max(10, maxRetainedCrops);
    }

    /**
     * Crops {@code box} (with 25% contextual padding) from {@code frame}, encodes it as a JPEG,
     * writes it to {@code ocr/crops/<yyyy-MM-dd>/<uuid>.jpg}, and returns the bucket-relative
     * resource URI ({@code ocr/crops/...}) or {@code ""} on any error.
     */
    public String writeCrop(BufferedImage frame, Rectangle box, Instant timestamp) {
        if (bucketStore == null || frame == null || box == null)
            return "";

        try {
            ensureBucket();
            BufferedImage cropped = extractPaddedRgbCrop(frame, box);
            if (cropped == null)
                return "";

            byte[] jpegBytes;
            try {
                jpegBytes = encodeJpeg(cropped);
            } finally {
                cropped.flush();
            }

            Instant ts = timestamp != null ? timestamp : Instant.now();
            String objectKey = CROPS_PREFIX + DATE_DIR.format(ts) + "/" + UUID.randomUUID() + ".jpg";
            bucketStore.putObject(
                    OCR_BUCKET,
                    objectKey,
                    new ByteArrayInputStream(jpegBytes),
                    Map.of("Content-Type", "image/jpeg"));

            if (writesSincePrune.incrementAndGet() >= PRUNE_CHECK_INTERVAL) {
                writesSincePrune.set(0);
                pruneOldestIfNeeded();
            }

            String rel = bucketStore.getRelativeResourceURI(OCR_BUCKET, objectKey);
            return (rel != null && !rel.isBlank()) ? rel : (OCR_BUCKET + "/" + objectKey);
        } catch (Exception e) {
            logger.debug("Could not persist OCR evidence crop: {}", e.getMessage());
            return "";
        }
    }

    public void pruneOldestIfNeeded() {
        if (bucketStore == null)
            return;
        try {
            if (!bucketStore.bucketExists(OCR_BUCKET))
                return;
            List<String> keys = new ArrayList<>();
            for (String key : bucketStore.listObjects(OCR_BUCKET)) {
                String norm = key.replace('\\', '/');
                if (norm.startsWith(CROPS_PREFIX) && norm.endsWith(".jpg")) {
                    keys.add(norm);
                }
            }
            int excess = keys.size() - maxRetainedCrops;
            if (excess <= 0)
                return;

            // Keys start with crops/yyyy-MM-dd/<uuid>.jpg so lexicographical sort orders oldest dates first
            Collections.sort(keys);
            for (int i = 0; i < excess; i++) {
                try {
                    bucketStore.deleteObject(OCR_BUCKET, keys.get(i));
                } catch (DataStoreException ignored) {
                }
            }
        } catch (Exception e) {
            logger.debug("OCR crop retention check skipped: {}", e.getMessage());
        }
    }

    private void ensureBucket() throws DataStoreException {
        if (!bucketStore.bucketExists(OCR_BUCKET)) {
            bucketStore.createBucket(OCR_BUCKET);
        }
        if (bucketService != null && registeredInService.compareAndSet(false, true)) {
            bucketService.ensureBucketRegistered(OCR_BUCKET);
        }
    }

    static BufferedImage extractPaddedRgbCrop(BufferedImage frame, Rectangle box) {
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        if (fw <= 1 || fh <= 1 || box.width <= 0 || box.height <= 0)
            return null;

        int padX = Math.max(8, (int) Math.round(box.width * PADDING_FRACTION));
        int padY = Math.max(8, (int) Math.round(box.height * PADDING_FRACTION));

        int x = Math.max(0, box.x - padX);
        int y = Math.max(0, box.y - padY);
        int x2 = Math.min(fw, box.x + box.width + padX);
        int y2 = Math.min(fh, box.y + box.height + padY);
        int cw = x2 - x;
        int ch = y2 - y;
        if (cw <= 1 || ch <= 1)
            return null;

        BufferedImage rgb = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(frame, 0, 0, cw, ch, x, y, x2, y2, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage rgb) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext())
            throw new IOException("No JPEG ImageIO writer available");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(16_384);
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
