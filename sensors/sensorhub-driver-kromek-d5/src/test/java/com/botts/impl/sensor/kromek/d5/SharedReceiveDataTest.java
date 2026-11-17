package com.botts.impl.sensor.kromek.d5;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;

import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_FRAMING_FRAME_BYTE;

public class SharedReceiveDataTest {

    /** A closed peer used to make the framing loop spin forever on read() == -1. */
    @Test(timeout = 5_000, expected = EOFException.class)
    public void closedStreamBeforeAnyFrameFailsInsteadOfSpinning() throws Exception {
        Shared.receiveData(new ByteArrayInputStream(new byte[0]));
    }

    @Test(timeout = 5_000, expected = EOFException.class)
    public void streamClosedMidMessageFails() throws Exception {
        // frame byte, then a length of 0x0010 but only two payload bytes before the peer closes
        byte[] truncated = {KROMEK_SERIAL_FRAMING_FRAME_BYTE, 0x10, 0x00, 0x01, 0x02};
        Shared.receiveData(new ByteArrayInputStream(truncated));
    }
}
