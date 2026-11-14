/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlateHeuristicsTest {

    @Test
    void normalizesValidAlphanumericPlates() {
        assertEquals("ABC1234", PlateHeuristics.normalizePlate("ABC-1234").orElse(""));
        assertEquals("7B9421", PlateHeuristics.normalizePlate("7B9 421").orElse(""));
    }

    @Test
    void rejectsTimestampsStopWordsAndIsoContainers() {
        assertTrue(PlateHeuristics.normalizePlate("20261114").isEmpty());
        assertTrue(PlateHeuristics.normalizePlate("ABCDEFG").isEmpty());
        assertTrue(PlateHeuristics.normalizePlate("CAMERA01").isEmpty());
        assertTrue(PlateHeuristics.normalizePlate("CH01").isEmpty());
        assertTrue(PlateHeuristics.normalizePlate("CSQU3054383").isEmpty());
    }
}
