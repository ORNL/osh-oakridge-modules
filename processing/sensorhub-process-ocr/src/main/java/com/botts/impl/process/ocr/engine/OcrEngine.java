/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * Scene-text detection and recognition engine contract and pluggable SPI.
 *
 * <p>Custom engine implementations packaged as a {@code .jar}, {@code .zip}, {@code .tar.gz},
 * {@code .tgz}, or {@code .tar} archive can register via:</p>
 * <ol>
 *   <li>{@code META-INF/services/com.botts.impl.process.ocr.engine.OcrEngine}</li>
 *   <li>{@code META-INF/MANIFEST.MF} attribute {@code Oscar-Ocr-Engine-Class}</li>
 *   <li>{@code META-INF/oscar-ocr-engine.properties} or {@code oscar-ocr-engine.properties}
 *       with {@code class=com.example.CustomOcrEngine}</li>
 * </ol>
 */
@FunctionalInterface
public interface OcrEngine extends AutoCloseable {

    String MANIFEST_CLASS_ATTRIBUTE = "Oscar-Ocr-Engine-Class";
    String DESCRIPTOR_FILENAME = "oscar-ocr-engine.properties";

    int DEFAULT_DET_MAX_SIDE = 960;
    int HIGH_RES_DET_MAX_SIDE = 1600;

    /**
     * Optional one-time initialization hook invoked when an uploaded {@link OcrEngine} plugin
     * archive is unpacked and validated. Throwing an exception aborts activation fail-closed.
     */
    default void initialize(Path extractedRoot) throws Exception {
    }

    /** Human-readable engine or model identifier reported in runtime status. */
    default String getEngineId() {
        return getClass().getSimpleName();
    }

    /** Semantic version reported in runtime status. */
    default String getVersionId() {
        return "1.0.0";
    }

    /** Detects and recognizes text boxes in {@code frame} at {@link #DEFAULT_DET_MAX_SIDE}. Never returns {@code null}. */
    List<OcrRead> recognize(BufferedImage frame);

    /**
     * Detects and recognizes text boxes in {@code frame} resizing the longest side to at most
     * {@code detMaxSide} pixels prior to DBNet inference. Implementations that do not support
     * multi-scale detection fall back to {@link #recognize(BufferedImage)}.
     */
    default List<OcrRead> recognize(BufferedImage frame, int detMaxSide) {
        return recognize(frame);
    }

    /** Returns {@code true} when fast oriented container/plate ROI + PARSeq models are loaded. */
    default boolean supportsFastRoiPass() {
        return false;
    }

    /**
     * Executes the fast ({@code ~25-40 ms} CPU) oriented container/plate ROI + deskew + PARSeq pass
     * without running full-frame DBNet. Falls back to {@link #recognize(BufferedImage)} when unsupported.
     */
    default List<OcrRead> recognizeFastRoi(BufferedImage frame) {
        return recognize(frame);
    }

    @Override
    default void close() {
    }
}
