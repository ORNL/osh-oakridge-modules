/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Scene-text detection and recognition engine contract.
 */
@FunctionalInterface
public interface OcrEngine extends AutoCloseable {

    /** Detects and recognizes text boxes in {@code frame}. Never returns {@code null}. */
    List<OcrRead> recognize(BufferedImage frame);

    @Override
    default void close() {
    }
}
