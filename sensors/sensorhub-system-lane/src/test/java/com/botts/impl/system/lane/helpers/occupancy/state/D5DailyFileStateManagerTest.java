package com.botts.impl.system.lane.helpers.occupancy.state;

import com.botts.impl.process.d5.occupancy.DailyFileStruct;
import net.opengis.swe.v20.DataComponent;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The lane routes D5OccupancyProcessModule through {@link Rs350StateManager}, so the D5
 * process's dailyFile record must carry the fields that manager parses. Before this
 * contract was enforced the D5 record had only isAlarming, and every dailyFile event threw
 * "Unknown item 'isOccupied'" on the event bus.
 */
public class D5DailyFileStateManagerTest {

    private static DataComponent sample(boolean occupied, double startEpochSeconds, boolean alarming) {
        DataComponent record = DailyFileStruct.getRecordDescription();
        record.renewDataBlock();
        record.getComponent(0).getData().setDoubleValue(System.currentTimeMillis() / 1000.0);
        record.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().setBooleanValue(occupied);
        record.getComponent(DailyFileStruct.OCCUPANCY_START_TIME_NAME).getData().setDoubleValue(startEpochSeconds);
        record.getComponent(DailyFileStruct.ALARM_NAME).getData().setBooleanValue(alarming);
        return record;
    }

    @Test
    public void d5DailyFileIsParsedByRs350StateManager() {
        Rs350StateManager manager = new Rs350StateManager();
        Instant t0 = Instant.parse("2026-11-17T10:00:00Z");

        assertTrue("idle D5 sample must be a recognised state",
                manager.updateDailyFile(sample(false, 0.0, false), t0));
        assertFalse(manager.hasActiveOccupancy());
        assertNull(manager.getOccupancyStartTimeHint());

        double episodeStart = t0.plusSeconds(5).toEpochMilli() / 1000.0;
        assertTrue(manager.updateDailyFile(sample(true, episodeStart, true), t0.plusSeconds(6)));
        assertTrue(manager.hasActiveOccupancy());
        assertEquals(t0.plusSeconds(5), manager.getOccupancyStartTimeHint());

        assertTrue(manager.updateDailyFile(sample(false, 0.0, false), t0.plusSeconds(20)));
        assertFalse(manager.hasActiveOccupancy());
    }
}
