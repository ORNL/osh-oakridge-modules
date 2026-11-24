/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.domain;

import com.botts.impl.process.ocr.runtime.OcrRuntimeRegistry;

import java.util.Optional;

/**
 * Heuristics for identifying plausible license-plate tokens while rejecting common shipping
 * container stencils, weight markings, and camera on-screen display (OSD) overlays.
 *
 * <p>Delegates dynamically to {@link OcrRuntimeRegistry#getActivePlateRules()}, which defaults to
 * {@link PlateRuleSet#DEFAULT} and switches atomically when an administrator activates a custom
 * OCR Model &amp; Rule Pack.</p>
 */
public final class PlateHeuristics {

    private PlateHeuristics() {
    }

    public static Optional<String> normalizePlate(String rawText) {
        return OcrRuntimeRegistry.getActivePlateRules().normalizePlate(rawText);
    }
}
