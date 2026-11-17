package com.botts.impl.system.lane.helpers;

import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.module.AbstractModule;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RpmRecoverySupervisorTest {

    /** Stand-in for an RPM driver whose bounded connection loop can fail at init or start. */
    static class FakeRpm extends AbstractModule<ModuleConfig> {
        volatile boolean deviceUp = false;
        int initCalls = 0;
        int startCalls = 0;
        Boolean recoveringDuringStart = null;
        RpmRecoverySupervisor observer;

        FakeRpm() {
            var config = new ModuleConfig();
            config.id = "rpm-1";
            config.name = "Lane01 - RPM";
            config.autoStart = true;
            setConfiguration(config);
        }

        @Override
        protected void doInit() throws SensorHubException {
            initCalls++;
            if (!deviceUp)
                throw new SensorHubException("Maximum number of connection attempts reached");
        }

        @Override
        protected void doStart() throws SensorHubException {
            startCalls++;
            if (observer != null)
                recoveringDuringStart = observer.isRecovering();
            if (!deviceUp)
                throw new SensorHubException("Maximum number of connection attempts reached");
        }

        @Override
        protected void doStop() {
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private FakeRpm rpm;
    private boolean laneRunning;
    private RpmRecoverySupervisor supervisor;

    @Before
    public void setUp() {
        rpm = new FakeRpm();
        laneRunning = true;
        supervisor = new RpmRecoverySupervisor(() -> rpm, () -> laneRunning, now::get,
                LoggerFactory.getLogger(RpmRecoverySupervisorTest.class));
    }

    /** What ORNL's RobustConnection leaves behind when a runtime reconnect runs out of attempts. */
    private void failAtRuntime() throws SensorHubException {
        rpm.deviceUp = true;
        rpm.init();
        rpm.start();
        assertEquals(ModuleState.STARTED, rpm.getCurrentState());
        rpm.deviceUp = false;
        rpm.stop();
        rpm.start();
        assertEquals(ModuleState.STOPPED, rpm.getCurrentState());
        assertNotNull(rpm.getCurrentError());
        rpm.startCalls = 0;
    }

    @Test
    public void restartsAFailedRpmOnceTheDeviceIsBack() throws Exception {
        failAtRuntime();

        supervisor.tick();
        assertEquals("first round tries straight away", 1, rpm.startCalls);
        assertEquals(ModuleState.STOPPED, rpm.getCurrentState());

        rpm.deviceUp = true;
        supervisor.tick();
        assertEquals("backoff holds the next round", 1, rpm.startCalls);

        now.addAndGet(RpmRecoverySupervisor.BACKOFF_MS[0]);
        supervisor.tick();
        assertEquals(2, rpm.startCalls);
        assertEquals(ModuleState.STARTED, rpm.getCurrentState());
        assertNull(rpm.getCurrentError());
    }

    @Test
    public void backsOffBetweenFailedRoundsUpToTheCap() throws Exception {
        failAtRuntime();
        long[] expected = RpmRecoverySupervisor.BACKOFF_MS;
        supervisor.tick();
        for (int round = 0; round < expected.length + 2; round++) {
            long delay = expected[Math.min(round, expected.length - 1)];
            int calls = rpm.startCalls;
            now.addAndGet(delay - 1);
            supervisor.tick();
            assertEquals("no attempt before the backoff elapses (round " + round + ")", calls, rpm.startCalls);
            now.addAndGet(1);
            supervisor.tick();
            assertEquals("attempt once it elapses (round " + round + ")", calls + 1, rpm.startCalls);
        }
    }

    @Test
    public void initialisesAnRpmThatNeverConnectedAtBoot() throws Exception {
        rpm.init();
        assertEquals(ModuleState.LOADED, rpm.getCurrentState());
        assertNotNull(rpm.getCurrentError());

        rpm.deviceUp = true;
        supervisor.tick();
        assertEquals(2, rpm.initCalls);
        assertEquals(ModuleState.STARTED, rpm.getCurrentState());
    }

    @Test
    public void leavesADeliberatelyStoppedRpmAlone() throws Exception {
        rpm.deviceUp = true;
        rpm.init();
        rpm.start();
        rpm.stop();
        assertEquals(ModuleState.STOPPED, rpm.getCurrentState());
        assertNull(rpm.getCurrentError());

        rpm.startCalls = 0;
        supervisor.tick();
        assertEquals(0, rpm.startCalls);
    }

    @Test
    public void doesNothingWhileTheLaneIsNotRunningOrForAManualStartRpm() throws Exception {
        failAtRuntime();
        laneRunning = false;
        supervisor.tick();
        assertEquals(0, rpm.startCalls);

        laneRunning = true;
        rpm.getConfiguration().autoStart = false;
        assertFalse(RpmRecoverySupervisor.needsRecovery(rpm));
        supervisor.tick();
        assertEquals(0, rpm.startCalls);

        rpm.getConfiguration().autoStart = true;
        assertTrue(RpmRecoverySupervisor.needsRecovery(rpm));
    }

    @Test
    public void reportsRecoveringFromTheFirstAttemptUntilTheRpmIsBack() throws Exception {
        failAtRuntime();
        rpm.observer = supervisor;
        assertFalse("a failure alone is not yet a recovery", supervisor.isRecovering());

        supervisor.tick();
        assertEquals("flagged while the attempt holds the RPM in STARTING", Boolean.TRUE, rpm.recoveringDuringStart);
        assertTrue("still recovering through the backoff", supervisor.isRecovering());

        rpm.deviceUp = true;
        now.addAndGet(RpmRecoverySupervisor.BACKOFF_MS[0]);
        supervisor.tick();
        assertEquals(ModuleState.STARTED, rpm.getCurrentState());
        assertFalse(supervisor.isRecovering());
    }

    @Test
    public void closingClearsTheRecoveringFlag() throws Exception {
        failAtRuntime();
        supervisor.tick();
        assertTrue(supervisor.isRecovering());
        supervisor.close();
        assertFalse(supervisor.isRecovering());
    }
}
