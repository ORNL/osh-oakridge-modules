/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.engine;

/**
 * Unchecked wrapper for model loading and inference errors so callers can degrade gracefully.
 */
public class OcrEngineException extends RuntimeException {
    public OcrEngineException(String message) {
        super(message);
    }

    public OcrEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
