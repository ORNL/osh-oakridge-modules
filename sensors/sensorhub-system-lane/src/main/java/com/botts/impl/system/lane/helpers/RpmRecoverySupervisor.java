package com.botts.impl.system.lane.helpers;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.slf4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps retrying a lane's RPM member after the driver has given up on it.
 *
 * <p>Lane RPM drivers connect through {@code RobustConnection} with a bounded number of attempts
 * (the lane sets 10 retries, 10 s apart). When a detector stays unreachable for longer than that,
 * a failed reconnect leaves the module {@code STOPPED} with an error, and a failed connect at boot
 * leaves it {@code LOADED} with an error. Nothing ever retried from either state, so a switch
 * reboot or a cable pull of more than about two minutes took the lane's radiation data down until
 * someone restarted the module by hand.</p>
 *
 * <p>The supervisor only acts on a member that failed: it must be auto-start and carry an error.
 * A member an operator stopped on purpose has no error and is left alone. Each recovery attempt
 * re-runs init (if the member never initialized) and start; the driver's own bounded connection
 * loop runs inside that, so attempts are effectively continuous while the device is down, spaced
 * by {@link #BACKOFF_MS} between failed rounds.</p>
 */
public final class RpmRecoverySupervisor implements AutoCloseable {

    static final long CHECK_PERIOD_MS = 15_000;
    static final long[] BACKOFF_MS = {30_000, 60_000, 120_000, 300_000};

    private final Supplier<? extends IModule<?>> rpm;
    private final BooleanSupplier laneRunning;
    private final LongSupplier clock;
    private final Logger logger;
    private ScheduledExecutorService executor;

    private int failedRounds = 0;
    private long nextAttemptAt = Long.MIN_VALUE;

    public RpmRecoverySupervisor(Supplier<? extends IModule<?>> rpm, BooleanSupplier laneRunning, Logger logger) {
        this(rpm, laneRunning, System::currentTimeMillis, logger);
    }

    RpmRecoverySupervisor(Supplier<? extends IModule<?>> rpm, BooleanSupplier laneRunning,
                          LongSupplier clock, Logger logger) {
        this.rpm = rpm;
        this.laneRunning = laneRunning;
        this.clock = clock;
        this.logger = logger;
    }

    public synchronized void start(String laneName) {
        if (executor != null)
            return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lane-rpm-recovery-" + laneName);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, CHECK_PERIOD_MS, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable e) {
            logger.error("RPM recovery check failed", e);
        }
    }

    /** One supervision pass. Package-private so tests can drive it without the scheduler. */
    void tick() {
        IModule<?> module = rpm.get();
        if (module == null || !laneRunning.getAsBoolean() || !needsRecovery(module)) {
            if (module != null && module.getCurrentState() == ModuleState.STARTED)
                failedRounds = 0;
            return;
        }
        long now = clock.getAsLong();
        if (now < nextAttemptAt)
            return;

        logger.warn("RPM {} is down after its connection retries ran out ({}); restarting it (attempt {})",
                module.getName(), errorMessage(module), failedRounds + 1);
        if (attemptRestart(module)) {
            logger.info("RPM {} recovered", module.getName());
            failedRounds = 0;
            nextAttemptAt = Long.MIN_VALUE;
        } else {
            long delay = BACKOFF_MS[Math.min(failedRounds, BACKOFF_MS.length - 1)];
            failedRounds++;
            nextAttemptAt = clock.getAsLong() + delay;
            logger.warn("RPM {} still unreachable; next attempt in {} s", module.getName(), delay / 1000);
        }
    }

    static boolean needsRecovery(IModule<?> module) {
        var config = module.getConfiguration();
        if (config == null || !config.autoStart || module.getCurrentError() == null)
            return false;
        ModuleState state = module.getCurrentState();
        return state == ModuleState.LOADED || state == ModuleState.STOPPED;
    }

    private boolean attemptRestart(IModule<?> module) {
        try {
            if (module.getCurrentState() == ModuleState.LOADED) {
                module.init();
                if (module.getCurrentState() != ModuleState.INITIALIZED)
                    return false;
            }
            module.start();
            return module.getCurrentState() == ModuleState.STARTED;
        } catch (Exception e) {
            logger.debug("RPM {} restart attempt failed", module.getName(), e);
            return false;
        }
    }

    private static String errorMessage(IModule<?> module) {
        Throwable error = module.getCurrentError();
        if (error == null)
            return "no error";
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
