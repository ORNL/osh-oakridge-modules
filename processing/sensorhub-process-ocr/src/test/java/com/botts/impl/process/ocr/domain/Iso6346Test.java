/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Iso6346Test {

    @Test
    void computesAndValidatesStandardCheckDigit() {
        assertEquals(3, Iso6346.computeCheckDigit("CSQU305438"));
        assertTrue(Iso6346.isValid("CSQU3054383"));
        assertFalse(Iso6346.isValid("CSQU3054384"));
    }

    @Test
    void coercesAmbiguousLettersAndDigitsByPosition() {
        Optional<Iso6346.Match> match = Iso6346.extractBest("CS0U 3O5438 3");
        assertTrue(match.isPresent());
        assertEquals("CSQU3054383", match.get().compact());
        assertEquals("CSQU 305438 3", match.get().formatted());
        assertTrue(match.get().checksumValid());
        assertFalse(match.get().checkDigitRepaired());
    }

    @Test
    void stripsBurnedInTimestampsBeforeContainerExtraction() {
        Optional<Iso6346.Match> match = Iso6346.extractBest("2026-11-14 00:25:12 CSQU 305438 3 CAM01");
        assertTrue(match.isPresent());
        assertEquals("CSQU3054383", match.get().compact());
        assertTrue(match.get().checksumValid());
    }

    @Test
    void repairsMissingBoxedCheckDigitWhenTenCharPrefixReadCleanly() {
        Optional<Iso6346.Match> match = Iso6346.extractBest("CSQU 305438");
        assertTrue(match.isPresent());
        assertEquals("CSQU3054383", match.get().compact());
        assertTrue(match.get().checksumValid());
        assertTrue(match.get().checkDigitRepaired());
    }

    @Test
    void rejectsDigitCoercedCategoryWhenCheckDigitDoesNotValidate() {
        // '0' at position 3 would coerce to 'U', and check digit '9' is wrong (expected '4'):
        // must be rejected so random alphanumeric strings do not masquerade as containers.
        Optional<Iso6346.Match> match = Iso6346.extractBest("ABC01234569");
        assertTrue(match.isEmpty());
    }
}
