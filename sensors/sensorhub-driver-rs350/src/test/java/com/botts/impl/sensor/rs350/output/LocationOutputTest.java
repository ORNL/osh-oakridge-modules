package com.botts.impl.sensor.rs350.output;

import com.botts.impl.sensor.rs350.RS350Outputs;
import com.botts.impl.sensor.rs350.RS350Sensor;
import com.botts.impl.sensor.rs350.messages.RS350ForegroundMeasurement;
import com.botts.impl.sensor.rs350.messages.RS350Message;
import net.opengis.swe.v20.DataBlock;
import org.junit.Test;
import org.sensorhub.api.data.DataEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LocationOutputTest {

    @Test
    public void defaultsDisabledForPortalIsolationAndPublishesMillisecondEventsWhenEnabled() {
        assertFalse("RS350Outputs.enableLocationOutput must default to false for fixed portals",
                new RS350Outputs().enableLocationOutput);

        RS350Sensor sensor = new RS350Sensor() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:sensor:rsi:rs350:test-01";
            }
        };
        LocationOutput output = new LocationOutput(sensor);
        output.init();

        List<DataEvent> published = new ArrayList<>();
        output.registerListener(event -> {
            if (event instanceof DataEvent de) {
                published.add(de);
            }
        });

        // Missing / (0.0, 0.0) GPS coordinates are ignored
        RS350Message zeroMsg = mock(RS350Message.class);
        when(zeroMsg.getRs350ForegroundMeasurement()).thenReturn(new RS350ForegroundMeasurement(
                "Foreground", 1_764_000_123_456L, 1.0, List.of(), List.of(), 100.0, 1.0, 0.05, 0.0, 0.0, 0.0));
        output.onNewMessage(zeroMsg);
        assertEquals(0, published.size());
        assertNull(output.getLatestRecord());

        // Valid GPS fix updates latestRecord, preserves millisecond event time, and writes sub-second double timestamp
        long fixTimeMs = 1_764_000_123_456L;
        RS350Message validMsg = mock(RS350Message.class);
        when(validMsg.getRs350ForegroundMeasurement()).thenReturn(new RS350ForegroundMeasurement(
                "Foreground", fixTimeMs, 1.0, List.of(), List.of(), 120.0, 2.0, 0.08, 35.931125, -84.310875, 268.4));
        output.onNewMessage(validMsg);

        assertEquals(1, published.size());
        assertEquals(fixTimeMs, published.get(0).getTimeStamp());
        assertEquals(fixTimeMs, output.getLatestRecordTime());

        DataBlock rec = output.getLatestRecord();
        assertNotNull(rec);
        assertEquals(fixTimeMs / 1000.0, rec.getDoubleValue(0), 1e-6);
        assertEquals(35.931125, rec.getDoubleValue(1), 1e-6);
        assertEquals(-84.310875, rec.getDoubleValue(2), 1e-6);
        assertEquals(268.4, rec.getDoubleValue(3), 1e-6);
        assertFalse("getRecordDescription() must remain unbound to live datablocks",
                output.getRecordDescription().hasData());

        // Subsequent fix renews latestRecord cleanly without schema deep-cloning
        long nextTimeMs = fixTimeMs + 1_000L;
        RS350Message secondMsg = mock(RS350Message.class);
        when(secondMsg.getRs350ForegroundMeasurement()).thenReturn(new RS350ForegroundMeasurement(
                "Foreground", nextTimeMs, 1.0, List.of(), List.of(), 125.0, 2.0, 0.09, 35.931200, -84.310900, 269.0));
        output.onNewMessage(secondMsg);

        assertEquals(2, published.size());
        assertEquals(nextTimeMs, output.getLatestRecordTime());
        assertEquals(35.931200, output.getLatestRecord().getDoubleValue(1), 1e-6);
    }
}
