package com.botts.impl.system.lane;

import com.botts.impl.system.lane.config.AxisCameraConfig;
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

    @Test
    public void defaultAxisH264EndpointSpecifies640x480And30Fps() {
        assertEquals(
                "/axis-media/media.amp?adjustablelivestream=1&resolution=640x480&videocodec=h264&videokeyframeinterval=15&fps=30",
                AxisCameraConfig.CodecEndpoint.H264.getPath());
    }
}
