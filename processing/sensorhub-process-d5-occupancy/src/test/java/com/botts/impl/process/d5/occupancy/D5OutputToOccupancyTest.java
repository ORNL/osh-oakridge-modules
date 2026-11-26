package com.botts.impl.process.d5.occupancy;

import net.opengis.swe.v20.DataComponent;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class D5OutputToOccupancyTest {

    @Test
    public void edgeDetectsAlarmEpisodesTracksPeakCountsCapturesFirstGpsFixAndEnforcesCooldown() {
        D5OutputToOccupancy proc = new D5OutputToOccupancy();
        long t0 = 1_764_000_000_000L;

        // Background radiometrics before any alarm sets neutron background rate (15 counts / 3000 ms = 5.0 cps)
        proc.onRadiometricsSample(220, 15, 3_000.0);
        assertNull(proc.onRadiometricStatusSample(false, false, false, 0.0, 0.0, t0));

        DataComponent daily = proc.getOutputList().getComponent(DailyFileStruct.DAILY_FILE_NAME);
        assertFalse(daily.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().getBooleanValue());

        // Rising gamma alarm opens episode; initial (0,0) fix is ignored until first real fix arrives
        assertNull(proc.onRadiometricStatusSample(false, true, false, 0.0, 0.0, t0 + 1_000L));
        assertTrue(daily.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().getBooleanValue());
        assertEquals((t0 + 1_000L) / 1000.0,
                daily.getComponent(DailyFileStruct.OCCUPANCY_START_TIME_NAME).getData().getDoubleValue(), 1e-6);

        proc.onRadiometricsSample(1_850, 4, 1_000.0);
        proc.onRadiometricsSample(2_410, 9, 1_000.0);

        // Neutron alarm joins mid-episode and first valid GPS fix is captured
        assertNull(proc.onRadiometricStatusSample(false, true, true, 35.9315, -84.3108, t0 + 2_000L));
        // Later GPS fix during same episode does not overwrite initial alarm origin
        assertNull(proc.onRadiometricStatusSample(true, false, false, 35.9999, -84.9999, t0 + 3_000L));

        // Clear for <= 3000ms does NOT close episode yet
        assertNull(proc.onRadiometricStatusSample(false, false, false, 35.9320, -84.3110, t0 + 5_500L));
        assertTrue(daily.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().getBooleanValue());

        // Clear beyond EPISODE_CLOSE_DELAY_MS (3000ms after t0+3000ms -> t0+6100ms) closes episode & emits OccupancyExtended
        OccupancyExtended ep1 = proc.onRadiometricStatusSample(false, false, false, 35.9320, -84.3110, t0 + 6_100L);
        assertNotNull(ep1);
        assertFalse(daily.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().getBooleanValue());
        assertEquals("Gamma-Neutron", ep1.getAlarmCategory());
        assertTrue(ep1.hasGammaAlarm());
        assertTrue(ep1.hasNeutronAlarm());
        assertEquals(2_410, ep1.getMaxGammaCount());
        assertEquals(9, ep1.getMaxNeutronCount());
        assertEquals(5.0, ep1.getNeutronBackground(), 1e-6);
        assertTrue(ep1.hasLocation());
        assertEquals(35.9315, ep1.getLatitude(), 1e-6);
        assertEquals(-84.3108, ep1.getLongitude(), 1e-6);
        assertEquals(1, ep1.getOccupancyCount());
        assertEquals((t0 + 1_000L) / 1000.0, ep1.getStartTime(), 1e-6);
        assertEquals((t0 + 3_000L) / 1000.0, ep1.getEndTime(), 1e-6);

        // Cooldown: alarm within OCCUPANCY_INTERVAL_MS (10s after t0+6100ms) is suppressed
        assertNull(proc.onRadiometricStatusSample(false, false, true, 35.9330, -84.3120, t0 + 12_000L));
        assertFalse(daily.getComponent(DailyFileStruct.OCCUPIED_NAME).getData().getBooleanValue());

        // After 10s cooldown (t0 + 17_000ms), neutron-only episode opens and closes cleanly
        assertNull(proc.onRadiometricStatusSample(false, false, true, 35.9340, -84.3130, t0 + 17_000L));
        proc.onRadiometricsSample(300, 42, 1_000.0);
        OccupancyExtended ep2 = proc.onRadiometricStatusSample(false, false, false, 35.9340, -84.3130, t0 + 20_500L);
        assertNotNull(ep2);
        assertEquals("Neutron", ep2.getAlarmCategory());
        assertFalse(ep2.hasGammaAlarm());
        assertTrue(ep2.hasNeutronAlarm());
        assertEquals(42, ep2.getMaxNeutronCount());
        assertEquals(2, ep2.getOccupancyCount());
    }
}
