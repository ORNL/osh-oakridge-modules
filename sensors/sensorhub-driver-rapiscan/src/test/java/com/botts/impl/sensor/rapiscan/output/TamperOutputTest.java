package com.botts.impl.sensor.rapiscan.output;

import com.botts.impl.sensor.rapiscan.RapiscanSensor;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TamperOutputTest {

    @Test
    public void newConnectionPublishesClearBaseline() {
        var output = new RecordingTamperOutput(new RapiscanSensor());

        output.onNewMessage(true);
        assertTrue(output.lastTamperState);

        output.onConnected();
        assertFalse(output.lastTamperState);
    }

    @Test
    public void subSecondTamperClearPreservesMonotonicMillisecondTimestamps() throws Exception {
        RapiscanSensor sensor = new RapiscanSensor() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:sensor:rapiscan:test";
            }
        };
        var output = new TamperOutput(sensor);
        output.init();

        output.onNewMessage(true);
        var alarmRecord = output.getLatestRecord();
        double alarmTs = alarmRecord.getDoubleValue(0);
        assertTrue(alarmRecord.getBooleanValue(1));

        Thread.sleep(15L);
        output.onNewMessage(false);
        var clearRecord = output.getLatestRecord();
        double clearTs = clearRecord.getDoubleValue(0);
        assertFalse(clearRecord.getBooleanValue(1));
        assertTrue("Expected millisecond-precision timestamp monotonic progression", clearTs > alarmTs);
    }

    private static class RecordingTamperOutput extends TamperOutput {
        private boolean lastTamperState;

        private RecordingTamperOutput(RapiscanSensor parentSensor) {
            super(parentSensor);
        }

        @Override
        public void onNewMessage(boolean tamperState) {
            lastTamperState = tamperState;
        }
    }
}
