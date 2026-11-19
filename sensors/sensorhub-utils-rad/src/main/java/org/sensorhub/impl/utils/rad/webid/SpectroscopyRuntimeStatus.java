/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

/**
 * Immutable status snapshot of the secondary-inspection spectroscopy runtime.
 */
public record SpectroscopyRuntimeStatus(
        String activeSource,
        boolean customActive,
        String packageKind,
        String fileName,
        String sha256,
        String algorithmId,
        String versionId,
        String executionMode,
        int isotopeCount,
        String loadedAt,
        String lastError
) {
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_CUSTOM = "custom";

    public static SpectroscopyRuntimeStatus builtinDefault(String webIdApiRoot, String lastError) {
        String root = (webIdApiRoot == null || webIdApiRoot.isBlank())
                ? "https://full-spectrum.sandia.gov/api/v1/"
                : webIdApiRoot.trim();
        return new SpectroscopyRuntimeStatus(
                SOURCE_BUILTIN,
                false,
                "builtin-webid-n42",
                "Sandia Full Spectrum WebID + ANSI N42.42 / Cambio Parser",
                "",
                root,
                "3.11.0",
                "webid-primary",
                0,
                "",
                lastError == null ? "" : lastError
        );
    }
}
