package com.botts.impl.system.lane;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LaneSystemStreamPathTest {

    @Test
    public void collapsesMultipleLeadingSlashesAndAppliesFallbackForBlankPaths() {
        assertEquals("/h264Preview_01_sub",
                LaneSystem.normalizeStreamPath("//h264Preview_01_sub", "/default"));
        assertEquals("/h264Preview_01_main",
                LaneSystem.normalizeStreamPath("///h264Preview_01_main", "/default"));
        assertEquals("/h264Preview_01_main",
                LaneSystem.normalizeStreamPath("h264Preview_01_main", "/default"));
        assertEquals("/axis-media/media.amp?videocodec=h264",
                LaneSystem.normalizeStreamPath("   ", "/axis-media/media.amp?videocodec=h264"));
        assertEquals("/",
                LaneSystem.normalizeStreamPath("/", "/default"));
    }
}
