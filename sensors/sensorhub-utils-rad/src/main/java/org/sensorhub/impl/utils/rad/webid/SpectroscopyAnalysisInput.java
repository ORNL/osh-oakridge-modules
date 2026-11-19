/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

/**
 * Input payload supplied to a pluggable {@link SpectroscopyIdentificationAlgorithm}.
 */
public record SpectroscopyAnalysisInput(
        byte[] foregroundBytes,
        String foregroundFileName,
        byte[] backgroundBytes,
        String backgroundFileName,
        String enrichedN42Xml,
        String requestedDrf,
        boolean synthesizeBackground
) {}
