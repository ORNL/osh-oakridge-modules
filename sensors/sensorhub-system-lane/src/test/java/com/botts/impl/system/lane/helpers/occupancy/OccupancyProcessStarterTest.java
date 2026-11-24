package com.botts.impl.system.lane.helpers.occupancy;

import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.module.AbstractModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OccupancyProcessStarterTest {

    /** Stand-in for an RS-350 / D5 occupancy process. */
    static class FakeOccupancyProcess extends AbstractModule<ModuleConfig> {
        int startCalls = 0;
        boolean failStart = false;

        FakeOccupancyProcess() {
            var config = new ModuleConfig();
            config.id = "occupancy-1";
            config.name = "Walker01 - Occupancy";
            config.autoStart = true;
            setConfiguration(config);
        }

        @Override
        protected void doInit() {
        }

        @Override
        protected void doStart() throws SensorHubException {
            startCalls++;
            if (failStart)
                throw new SensorHubException("process chain failed to start");
        }

        @Override
        protected void doStop() {
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(OccupancyProcessStarterTest.class);
    private static final Executor DIRECT = Runnable::run;

    private FakeOccupancyProcess process;

    @Before
    public void setUp() {
        process = new FakeOccupancyProcess();
    }

    @Test
    public void startsAProcessInitializedAfterTheAutoStartPass() throws Exception {
        // The detector was offline during the lane's auto-start pass; it just came up and the
        // lane initialized the process.
        process.init();
        assertEquals(ModuleState.INITIALIZED, process.getCurrentState());

        assertTrue(OccupancyProcessStarter.startIfStranded(process, true, true, DIRECT, LOG));
        assertEquals(ModuleState.STARTED, process.getCurrentState());
        assertEquals(1, process.startCalls);
    }

    @Test
    public void restartsARunningProcessThatReInitResetToInitialized() throws Exception {
        process.init();
        process.start();
        assertEquals(ModuleState.STARTED, process.getCurrentState());

        // What LaneSystem does when the detector restarts while the process is running.
        process.init();
        assertEquals("init() resets a running module", ModuleState.INITIALIZED, process.getCurrentState());

        assertTrue(OccupancyProcessStarter.startIfStranded(process, true, true, DIRECT, LOG));
        assertEquals(ModuleState.STARTED, process.getCurrentState());
    }

    @Test
    public void leavesTheStartToTheLaneDuringItsAutoStartPass() throws Exception {
        process.init();
        assertFalse(OccupancyProcessStarter.startIfStranded(process, true, false, DIRECT, LOG));
        assertEquals(ModuleState.INITIALIZED, process.getCurrentState());
        assertEquals(0, process.startCalls);
    }

    @Test
    public void doesNothingWhileTheLaneIsNotStarted() throws Exception {
        process.init();
        assertFalse(OccupancyProcessStarter.startIfStranded(process, false, true, DIRECT, LOG));
        assertEquals(0, process.startCalls);
    }

    @Test
    public void respectsAutoStartOff() throws Exception {
        process.getConfiguration().autoStart = false;
        process.init();
        assertFalse(OccupancyProcessStarter.startIfStranded(process, true, true, DIRECT, LOG));
        assertEquals(0, process.startCalls);
    }

    @Test
    public void ignoresAProcessThatFailedToInitialize() {
        // Still LOADED: init() failed, e.g. no system UID.
        assertFalse(OccupancyProcessStarter.startIfStranded(process, true, true, DIRECT, LOG));
        assertEquals(0, process.startCalls);
    }

    @Test
    public void skipsTheStartWhenSomethingElseStartedTheProcessFirst() throws Exception {
        process.init();
        List<Runnable> queued = new ArrayList<>();
        assertTrue(OccupancyProcessStarter.startIfStranded(process, true, true, queued::add, LOG));

        process.start(); // e.g. an operator, before the lane's executor ran the task
        queued.forEach(Runnable::run);
        assertEquals(1, process.startCalls);
        assertEquals(ModuleState.STARTED, process.getCurrentState());
    }

    @Test
    public void aFailedStartIsLoggedNotThrown() throws Exception {
        process.failStart = true;
        process.init();
        assertTrue(OccupancyProcessStarter.startIfStranded(process, true, true, DIRECT, LOG));
        assertEquals(1, process.startCalls);
    }

    @Test
    public void aShutDownExecutorIsNotAnError() throws Exception {
        process.init();
        Executor rejecting = r -> {
            throw new RejectedExecutionException("lane executor shut down");
        };
        assertFalse(OccupancyProcessStarter.startIfStranded(process, true, true, rejecting, LOG));
        assertEquals(0, process.startCalls);
    }

    @Test
    public void toleratesMissingArguments() {
        assertFalse(OccupancyProcessStarter.startIfStranded(null, true, true, DIRECT, LOG));
        assertFalse(OccupancyProcessStarter.startIfStranded(process, true, true, null, LOG));
    }
}
