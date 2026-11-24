package com.botts.impl.system.lane.helpers.occupancy;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Starts a lane's RS-350 / Kromek D5 occupancy process after its detector comes up late.
 *
 * <p>{@code SensorSystem} starts a lane's auto-start members once, right after the lane itself
 * starts, and waits at most 10 s for each to initialize. The occupancy process is initialized
 * only when its detector reaches STARTED, so a detector that is offline during that pass leaves
 * the process un-started ("Cannot start subsystem ... - Occupancy"). When the detector comes up
 * later, recovered by {@code RpmRecoverySupervisor} or restarted by an operator, the lane
 * initializes the process again, which also resets one that was already running to INITIALIZED,
 * but nothing starts it. The lane then records no occupancies for that detector, and
 * application readiness stays withdrawn because a member is not started.
 *
 * <p>During the lane's own auto-start pass, the pass starts the process itself, so this helper
 * acts only once that pass is over.
 */
public final class OccupancyProcessStarter {

    private OccupancyProcessStarter() {
    }

    /**
     * Dispatches {@code process.start()} on {@code executor} when the process is INITIALIZED,
     * set to auto-start, and its lane is running with the auto-start pass over.
     *
     * @return whether a start was dispatched
     */
    public static boolean startIfStranded(IModule<?> process, boolean laneStarted,
                                          boolean autoStartDispatchComplete, Executor executor,
                                          Logger logger) {
        if (process == null || executor == null || !laneStarted || !autoStartDispatchComplete)
            return false;
        if (process.getConfiguration() == null || !process.getConfiguration().autoStart)
            return false;
        if (process.getCurrentState() != ModuleState.INITIALIZED)
            return false;

        try {
            executor.execute(() -> {
                // Something else (an operator, a lane restart) may have started or stopped it.
                if (process.getCurrentState() != ModuleState.INITIALIZED)
                    return;
                try {
                    logger.info("Starting occupancy process {} after its detector came up", process.getName());
                    process.start();
                } catch (SensorHubException | RuntimeException e) {
                    logger.error("Cannot start occupancy process {} after its detector came up", process.getName(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // The lane is shutting down its executor; its own restart will start the process.
            return false;
        }
    }
}
