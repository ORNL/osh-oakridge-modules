/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.runtime;

import java.util.List;

/**
 * Immutable status snapshot of the active OCR runtime (built-in shipped default or custom override).
 */
public record OcrRuntimeStatus(
        String activeSource,
        boolean customActive,
        String packageKind,
        String fileName,
        String sha256,
        String engineId,
        String versionId,
        boolean fastRoiSupported,
        String plateRuleSetId,
        String plateRuleDescription,
        List<String> modelFiles,
        String loadedAt,
        String lastError
) {
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_CUSTOM = "custom";

    public static OcrRuntimeStatus builtinDefault(boolean fastRoiSupported, List<String> builtinModels, String lastError) {
        return new OcrRuntimeStatus(
                SOURCE_BUILTIN,
                false,
                "builtin-onnx-pack",
                "Bundled PP-OCRv3 + PARSeq (models/ocr)",
                "",
                "OnnxOcrEngine (PP-OCRv3 + PARSeq INT8)",
                "3.11.0",
                fastRoiSupported,
                "builtin-latin",
                "Built-In Latin & ISO-6346 Filter",
                builtinModels == null ? List.of() : List.copyOf(builtinModels),
                "",
                lastError == null ? "" : lastError
        );
    }
}
