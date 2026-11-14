/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

import java.awt.Rectangle;

/**
 * Single detected text line within a video frame.
 */
public record OcrRead(String text, double confidence, Rectangle boundingBox) {
}
