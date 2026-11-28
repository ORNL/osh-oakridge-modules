package com.botts.impl.service.oscar.stats;

import com.botts.impl.service.oscar.OSCARSystem;
import com.google.common.collect.Range;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.ICancellableDataStore;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.utils.Async;
import org.vast.data.TextEncodingImpl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class StatisticsOutput extends AbstractSensorOutput<OSCARSystem> {

    public static final String NAME = "siteStatistics";
    private static final long DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L;
    private static final Instant LEGACY_STATS_EPOCH_SECONDS_MIN =
            Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant LEGACY_STATS_EPOCH_SECONDS_MAX =
            Instant.parse("2100-01-01T00:00:00Z");

    public static String gammaNeutronAlarmCQL = "gammaAlarm = true AND neutronAlarm = true";
    public static String gammaAlarmCQL = "gammaAlarm = true AND neutronAlarm = false";
    public static String neutronAlarmCQL = "gammaAlarm = false AND neutronAlarm = true";
    public static String faultCQL = "alarmState = 'Fault - Neutron High' OR alarmState = 'Fault - Gamma Low' OR alarmState = 'Fault - Gamma High' OR alarmState = 'Fault - Extended Occupancy'";
    public static String gammaFaultCQL = "alarmState = 'Fault - Gamma Low' OR alarmState = 'Fault - Gamma High'";
    public static String gammaHighFaultCQL = "alarmState = 'Fault - Gamma High'";
    public static String gammaLowFaultCQL = "alarmState = 'Fault - Gamma Low'";
    public static String neutronFaultCQL = "alarmState = 'Fault - Neutron High'";
    public static String extendedOccupancyFaultCQL = "alarmState = 'Fault - Extended Occupancy'";
    public static String tamperCQL = "tamperStatus = true";

    // RS350 alarm CQL filters (AlarmOutput uses alarmCategoryCode field)
    public static String rs350GammaAlarmCQL = "alarmCategoryCode = 'Gamma'";
    public static String rs350NeutronAlarmCQL = "alarmCategoryCode = 'Neutron'";

    private static final String DEF_ALARM_CATEGORY = RADHelper.getRadUri("AlarmCategoryCode");
    private static final String[] STATS_OBSERVED_PROPERTIES = {
            RADHelper.DEF_OCCUPANCY,
            RADHelper.DEF_GAMMA,
            RADHelper.DEF_NEUTRON,
            RADHelper.DEF_ALARM,
            RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT,
            RADHelper.DEF_TAMPER,
            DEF_ALARM_CATEGORY
    };

    private static final int PROP_OCCUPANCY = 1 << 0;
    private static final int PROP_GAMMA = 1 << 1;
    private static final int PROP_NEUTRON = 1 << 2;
    private static final int PROP_ALARM = 1 << 3;
    private static final int PROP_EXTENDED_OCCUPANCY_FAULT = 1 << 4;
    private static final int PROP_TAMPER = 1 << 5;
    private static final int PROP_ALARM_CATEGORY = 1 << 6;

    private final DataComponent recordDescription;
    private final DataEncoding recommendedEncoding;
    private final int samplingPeriod;

    private final Object lifecycleMonitor = new Object();
    private final Object workMonitor = new Object();
    private final ReentrantLock publicationLock = new ReentrantLock(true);
    private final Set<StatisticsWork> activeWork = new HashSet<>();
    private final ThreadLocal<Boolean> operationScope =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<AtomicReference<ResolvedDataStreams>> publicationStreamSnapshot =
            new ThreadLocal<>();
    private final AtomicReference<RuntimeException> unsafeStopFailure = new AtomicReference<>();
    private volatile boolean stopRequested;
    private long lifecycleGeneration;
    private ScheduledExecutorService service;
    private final IObsSystemDatabase database;

    private record LastStatisticObservationData(Instant timestamp, Statistics total) {

    }

    private record ResolvedDataStreams(
            Set<BigId> occupancyStreams,
            Set<BigId> gammaFaultStreams,
            Set<BigId> neutronFaultStreams,
            Set<BigId> extendedOccupancyFaultStreams,
            Set<BigId> tamperStreams,
            Set<BigId> rawAlarmCategoryStreams) {

    }

    public StatisticsOutput(OSCARSystem parentSensor, IObsSystemDatabase database, int samplingPeriod) {
        super(NAME, parentSensor);
        this.database = database;
        RADHelper fac = new RADHelper();
        recordDescription = fac.createSiteStatistics();
        recommendedEncoding = new TextEncodingImpl();
        this.samplingPeriod = samplingPeriod;
    }

    public void start() {
        synchronized (lifecycleMonitor) {
            if (service != null && !service.isTerminated())
                return;
            RuntimeException priorFailure = unsafeStopFailure.get();
            if (priorFailure != null)
                throw new IllegalStateException(
                        "Statistics output cannot restart after unsafe datastore cancellation", priorFailure);
            synchronized (workMonitor) {
                if (!activeWork.isEmpty())
                    throw new IllegalStateException(
                            "Statistics output still has active database work");
                stopRequested = false;
            }
            service = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "OSCAR-statistics");
                thread.setDaemon(true);
                return thread;
            });
            service.scheduleWithFixedDelay(this::publishLatestStatistics, 0L,
                    samplingPeriod, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        stop(DEFAULT_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void stop(long timeout, TimeUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        if (timeout <= 0)
            throw new IllegalArgumentException("Statistics stop timeout must be positive");

        long budgetNanos = Math.max(1L, unit.toNanos(timeout));
        long deadline = System.nanoTime() + budgetNanos;
        requestStop();
        awaitStop(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /**
     * Interrupts the scheduler and asynchronously cancels every scoped datastore operation.
     * Driver cancellation is never run on the module-registry thread because it may itself block.
     */
    public void requestStop() {
        synchronized (lifecycleMonitor) {
            synchronized (workMonitor) {
                stopRequested = true;
                lifecycleGeneration++;
                requestActiveWorkCancellationLocked();
            }
            if (service != null)
                service.shutdownNow();
        }
    }

    /**
     * Marks every currently active operation as cancellation-pending while {@link #workMonitor}
     * is still held. This closes the finish-versus-cancel race: a worker cannot remove itself
     * between discovery and marking, so a successful await also proves its cancellation helper
     * completed.
     */
    void requestActiveWorkCancellationLocked() {
        if (!Thread.holdsLock(workMonitor))
            throw new IllegalStateException("Statistics work monitor must be held");
        new java.util.ArrayList<>(activeWork)
                .forEach(StatisticsWork::requestCancellation);
    }

    /**
     * Waits for scheduler termination, database-scope closure, and cancellation completion.
     * A successful return therefore guarantees that no statistics worker can publish later.
     */
    public void awaitStop(long timeout, TimeUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        if (timeout <= 0)
            throw new IllegalArgumentException("Statistics stop timeout must be positive");
        long budgetNanos = Math.max(1L, unit.toNanos(timeout));
        long deadline = System.nanoTime() + budgetNanos;
        ScheduledExecutorService stopping;
        synchronized (lifecycleMonitor) {
            stopping = service;
        }
        try {
            while (true) {
                boolean schedulerTerminated = stopping == null || stopping.isTerminated();
                boolean workTerminated;
                synchronized (workMonitor) {
                    workTerminated = activeWork.isEmpty();
                }
                if (schedulerTerminated && workTerminated)
                    break;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    throw new IllegalStateException(
                            "Statistics output worker did not terminate within the stop deadline");
                long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50));
                if (!schedulerTerminated) {
                    stopping.awaitTermination(slice, TimeUnit.NANOSECONDS);
                } else {
                    synchronized (workMonitor) {
                        if (!activeWork.isEmpty())
                            TimeUnit.NANOSECONDS.timedWait(workMonitor, slice);
                    }
                }
            }
        } catch (InterruptedException error) {
            if (stopping != null)
                stopping.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for statistics output to stop", error);
        }

        RuntimeException unsafeFailure = unsafeStopFailure.get();
        if (unsafeFailure != null)
            throw new IllegalStateException(
                    "Statistics output stopped, but datastore cancellation or scope cleanup failed",
                    unsafeFailure);
        synchronized (lifecycleMonitor) {
            if (service == stopping)
                service = null;
        }
    }

    public final void publishLatestStatistics() {
        runSerializedPublication(() -> runCancellableStatistics(() -> {
            publishLatestStatisticsWithinScope();
            return null;
        }));
    }

    /**
     * Publishes a complete site-statistics record and returns the ID assigned to that
     * exact publication.  The baseline lookup, publication, and fresh-ID check are
     * serialized with scheduled publications so a caller can never be handed an older
     * observation merely because one already exists in the datastore.
     */
    public final BigId publishLatestStatisticsAndGetObservationId() {
        return runSerializedPublication(() -> runCancellableStatistics(() -> {
            BigId previousObservationId = getLatestObservationIdWithinScope();
            publishLatestStatisticsWithinScope();
            return waitForLatestObservationIdWithinScope(previousObservationId);
        }));
    }

    /**
     * Full publications are intentionally single-filed.  Scoped/custom counts do not
     * use this lock and remain independently cancellable.  Acquiring the lock before
     * opening the datastore operation prevents a queued publication from advertising
     * database work that has not started and preserves truthful shutdown accounting.
     */
    private <T> T runSerializedPublication(Supplier<T> publication) {
        long requestedGeneration;
        synchronized (workMonitor) {
            if (stopRequested)
                throw new CancellationException("Statistics output is stopping");
            requestedGeneration = lifecycleGeneration;
        }

        boolean locked = false;
        try {
            publicationLock.lockInterruptibly();
            locked = true;
            synchronized (workMonitor) {
                if (stopRequested || lifecycleGeneration != requestedGeneration)
                    throw new CancellationException(
                            "Statistics publication belongs to a stopped lifecycle");
            }
            return publication.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            CancellationException cancelled =
                    new CancellationException("Interrupted while waiting to publish statistics");
            cancelled.initCause(error);
            throw cancelled;
        } finally {
            if (locked)
                publicationLock.unlock();
        }
    }

    protected void publishLatestStatisticsWithinScope() {
        getLogger().info("Starting stats counting...");
        long calculationStarted = System.currentTimeMillis();

        DataBlock dataBlock = latestRecord == null ? recordDescription.createDataBlock() : latestRecord.renew();
        Instant now = normalizePublicationTime(Instant.now());

        // Get total counts from all observations
        LastStatisticObservationData lastStatisticObservationData = getLastObservation();
        if (lastStatisticObservationData != null) {
            // A statistics observation is keyed by its sampling time. Preserve ordering even
            // when two empty/fast publications occur within the SWE field's millisecond resolution.
            now = publicationTimeAfter(now, lastStatisticObservationData.timestamp());
        }

        int i = 0;
        dataBlock.setTimeStamp(i++, now);

        publicationStreamSnapshot.set(new AtomicReference<>());
        try {
            if (lastStatisticObservationData != null) {
                Instant lastTimestamp = lastStatisticObservationData.timestamp();
                Statistics previousTotal = lastStatisticObservationData.total();
                Statistics incrementalStats = getStatsAfter(lastTimestamp, now, Set.of());

                Statistics newTotal = previousTotal.add(incrementalStats);

                i = populateDataBlockWithStats(dataBlock, i, newTotal);
            } else {
                getLogger().debug("No previous stats observation found, calculating from epoch to {}", now);
                i = populateDataBlock(dataBlock, i, Instant.EPOCH, now);
            }

            // last 30 days
            Instant monthStart = now.minus(30, ChronoUnit.DAYS);
            i = populateDataBlock(dataBlock, i, monthStart, now);

            // last 7 days
            Instant weekStart = now.minus(7, ChronoUnit.DAYS);
            i = populateDataBlock(dataBlock, i, weekStart, now);

            // last 24 hours
            Instant dayStart = now.minus(24, ChronoUnit.HOURS);
            populateDataBlock(dataBlock, i, dayStart, now);
        } finally {
            publicationStreamSnapshot.remove();
        }

        latestRecord = dataBlock;
        latestRecordTime = now.toEpochMilli();
        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
        getLogger().info("Finished stats counting in {}s",
                (System.currentTimeMillis() - calculationStarted) / 1000);
    }

    private BigId getLatestObservationIdWithinScope() {
        List<BigId> keys;
        try (var keyStream = database.getObservationStore().selectKeys(new ObsFilter.Builder()
                .withLatestResult()
                .withSystems().withUniqueIDs(parentSensor.getUniqueIdentifier())
                .done()
                .withDataStreams().withOutputNames(NAME)
                .done()
                .withLimit(1)
                .build())) {
            keys = keyStream.toList();
        }
        return keys.size() == 1 ? keys.get(0) : null;
    }

    private BigId waitForLatestObservationIdWithinScope(BigId previousObservationId) {
        try {
            final List<BigId> result = new ArrayList<>();

            Async.waitForCondition(() -> {
                BigId latestObservationId = getLatestObservationIdWithinScope();
                if (latestObservationId != null
                        && (previousObservationId == null
                        || !previousObservationId.equals(latestObservationId))) {
                    result.clear();
                    result.add(latestObservationId);
                    return true;
                }
                return false;
            }, 5000);
            return result.isEmpty() ? null : result.get(0);
        } catch (TimeoutException e) {
            getLogger().error("Could not find latest observation ID");
            return null;
        }
    }

    /**
     * Populates the data block with statistics for the given time range.
     */
    public int populateDataBlock(DataBlock dataBlock, int i, Instant start, Instant end) {
        return populateDataBlock(dataBlock, i, start, end, Set.of());
    }

    public int populateDataBlock(DataBlock dataBlock, int i, Instant start, Instant end, Collection<String> laneUIDs) {
        return runCancellableStatistics(() -> {
            Statistics stats = getStats(start, end, laneUIDs);
            return populateDataBlockWithStats(dataBlock, i, stats);
        });
    }

    private int populateDataBlockWithStats(DataBlock dataBlock, int i, Statistics stats) {
        dataBlock.setLongValue(i++, stats.getNumOccupancies());
        dataBlock.setLongValue(i++, stats.getNumGammaAlarms());
        dataBlock.setLongValue(i++, stats.getNumNeutronAlarms());
        dataBlock.setLongValue(i++, stats.getNumGammaNeutronAlarms());
        dataBlock.setLongValue(i++, stats.getNumFaults());
        dataBlock.setLongValue(i++, stats.getNumGammaFaults());
        dataBlock.setLongValue(i++, stats.getNumNeutronFaults());
        dataBlock.setLongValue(i++, stats.getNumTampers());
        return i;
    }

    /**
     * Gets statistics by fetching from database
     */
    protected Statistics getStats(Instant start, Instant end) {
        return getStats(start, end, Set.of());
    }

    protected Statistics getStats(Instant start, Instant end, Collection<String> laneUIDs) {
        return getStats(timeRange(start, end, false), laneUIDs);
    }

    /** Counts only observations strictly newer than the previous publication cutoff. */
    protected Statistics getStatsAfter(Instant exclusiveStart, Instant inclusiveEnd,
                                       Collection<String> laneUIDs) {
        return getStats(timeRange(exclusiveStart, inclusiveEnd, true), laneUIDs);
    }

    private Statistics getStats(Range<Instant> timeRange, Collection<String> laneUIDs) {
        ResolvedDataStreams streams = getOrResolveDataStreams(laneUIDs);
        long numOccupancies = countObservationsOnStreams(database, null, timeRange, streams.occupancyStreams());
        long numGammaAlarms = countObservationsOnStreams(database, gammaAlarmCQL, timeRange, streams.occupancyStreams());
        long numNeutronAlarms = countObservationsOnStreams(database, neutronAlarmCQL, timeRange, streams.occupancyStreams());
        long numGammaNeutronAlarms = countObservationsOnStreams(database, gammaNeutronAlarmCQL, timeRange, streams.occupancyStreams());
        long numGammaFaults = countObservationsOnStreams(database, gammaFaultCQL, timeRange, streams.gammaFaultStreams());
        long numNeutronFaults = countObservationsOnStreams(database, neutronFaultCQL, timeRange, streams.neutronFaultStreams());
        long numExtendedOccupancyFaults = countObservationsOnStreams(
                database, extendedOccupancyFaultCQL, timeRange, streams.extendedOccupancyFaultStreams());
        long numTampers = countObservationsOnStreams(database, tamperCQL, timeRange, streams.tamperStreams());
        // Mathematical equivalence proof for numFaults (eliminating a duplicate full-site faultCQL
        // scan over high-volume gammaCounts/neutronCounts telemetry):
        //   1. faultCQL is the 4-way disjunction of gammaFaultCQL ('Fault - Gamma Low' OR
        //      'Fault - Gamma High'), neutronFaultCQL ('Fault - Neutron High'), and
        //      extendedOccupancyFaultCQL ('Fault - Extended Occupancy') on the single scalar SWE
        //      Category field `alarmState`. Because a single scalar value cannot simultaneously
        //      equal two distinct literals, the three predicates are pairwise mutually exclusive
        //      on every observation across all detector types (Rapiscan, Aspect, RS-350, D3S, D5,
        //      and lane OccupancyStatusOutput).
        //   2. Gamma faults are emitted exclusively by GammaOutput (DEF_GAMMA + DEF_ALARM),
        //      neutron faults exclusively by NeutronOutput (DEF_NEUTRON + DEF_ALARM), and
        //      extended occupancy faults exclusively by OccupancyStatusOutput
        //      (DEF_EXTENDED_OCCUPANCY_FAULT, whose allowed alarmState values are strictly
        //      'Clear' and 'Fault - Extended Occupancy'). RS-350 (AlarmOutput/BackgroundOutput/
        //      ForegroundOutput/StatusOutput), N42Output, OccupancyExtended, Kromek D3S, and
        //      Kromek D5 carry no `alarmState` field and contribute 0 to all three predicates.
        //   3. All three CQL predicates map to exact oscar_archive_schema summary counters in
        //      JsonCqlEvaluator.summaryCounterSpec(...), and scoping extendedOccupancyFaultCQL to
        //      DEF_EXTENDED_OCCUPANCY_FAULT queries only low-volume occupancyStatus streams
        //      instead of re-scanning 5 Hz RAW_TELEMETRY streams.
        long numFaults = numGammaFaults + numNeutronFaults + numExtendedOccupancyFaults + numTampers;

        // Standalone RS350 alarm counts from raw AlarmOutput only; exclude OccupancyExtended
        // datastreams (which also carry DEF_ALARM_CATEGORY) so they are never double-counted.
        Set<BigId> rawAlarmStreamIds = streams.rawAlarmCategoryStreams();
        long numRS350GammaAlarms = countObservationsOnStreams(database, rs350GammaAlarmCQL, timeRange, rawAlarmStreamIds);
        long numRS350NeutronAlarms = countObservationsOnStreams(database, rs350NeutronAlarmCQL, timeRange, rawAlarmStreamIds);
        numGammaAlarms += numRS350GammaAlarms;
        numNeutronAlarms += numRS350NeutronAlarms;
        numOccupancies += numRS350GammaAlarms + numRS350NeutronAlarms;

        return new Statistics.Builder()
                .numOccupancies(numOccupancies)
                .numGammaAlarms(numGammaAlarms)
                .numNeutronAlarms(numNeutronAlarms)
                .numGammaNeutronAlarms(numGammaNeutronAlarms)
                .numFaults(numFaults)
                .numGammaFaults(numGammaFaults)
                .numNeutronFaults(numNeutronFaults)
                .numTampers(numTampers)
                .build();
    }

    private ResolvedDataStreams getOrResolveDataStreams(Collection<String> laneUIDs) {
        if (laneUIDs == null || laneUIDs.isEmpty()) {
            AtomicReference<ResolvedDataStreams> holder = publicationStreamSnapshot.get();
            if (holder != null) {
                ResolvedDataStreams cached = holder.get();
                if (cached == null) {
                    cached = resolveDataStreams(database, Set.of());
                    holder.set(cached);
                }
                return cached;
            }
        }
        return resolveDataStreams(database, laneUIDs);
    }

    private ResolvedDataStreams resolveDataStreams(
            IObsSystemDatabase database,
            Collection<String> laneUIDs) {
        var dsFilterBuilder = new DataStreamFilter.Builder()
                .withObservedProperties(STATS_OBSERVED_PROPERTIES);

        if (laneUIDs != null && !laneUIDs.isEmpty()) {
            dsFilterBuilder.withSystems()
                    .withUniqueIDs(laneUIDs.toArray(String[]::new))
                    .includeMembers(true)
                    .done();
        }

        Set<BigId> occupancyStreams = new HashSet<>();
        Set<BigId> gammaFaultStreams = new HashSet<>();
        Set<BigId> neutronFaultStreams = new HashSet<>();
        Set<BigId> extendedOccupancyFaultStreams = new HashSet<>();
        Set<BigId> tamperStreams = new HashSet<>();
        Set<BigId> rawAlarmCategoryStreams = new HashSet<>();

        try (var entries = database.getDataStreamStore().selectEntries(dsFilterBuilder.build())) {
            entries.forEach(entry -> {
                BigId id = entry.getKey() != null ? entry.getKey().getInternalID() : null;
                DataComponent struct = entry.getValue() != null ? entry.getValue().getRecordStructure() : null;
                if (id == null || struct == null) {
                    return;
                }
                int propMask = collectObservedPropertyMask(struct);
                if ((propMask & PROP_OCCUPANCY) != 0) {
                    occupancyStreams.add(id);
                }
                if ((propMask & (PROP_GAMMA | PROP_ALARM)) != 0) {
                    gammaFaultStreams.add(id);
                }
                if ((propMask & (PROP_NEUTRON | PROP_ALARM)) != 0) {
                    neutronFaultStreams.add(id);
                }
                if ((propMask & PROP_EXTENDED_OCCUPANCY_FAULT) != 0) {
                    extendedOccupancyFaultStreams.add(id);
                }
                if ((propMask & PROP_TAMPER) != 0) {
                    tamperStreams.add(id);
                }
                if ((propMask & PROP_ALARM_CATEGORY) != 0 && !isOccupancyDataStream(struct)) {
                    rawAlarmCategoryStreams.add(id);
                }
            });
        }

        return new ResolvedDataStreams(
                Set.copyOf(occupancyStreams),
                Set.copyOf(gammaFaultStreams),
                Set.copyOf(neutronFaultStreams),
                Set.copyOf(extendedOccupancyFaultStreams),
                Set.copyOf(tamperStreams),
                Set.copyOf(rawAlarmCategoryStreams));
    }

    private static int collectObservedPropertyMask(DataComponent comp) {
        if (comp == null) {
            return 0;
        }
        int mask = 0;
        String def = comp.getDefinition();
        if (def != null) {
            if (RADHelper.DEF_OCCUPANCY.equals(def)) {
                mask |= PROP_OCCUPANCY;
            } else if (RADHelper.DEF_GAMMA.equals(def)) {
                mask |= PROP_GAMMA;
            } else if (RADHelper.DEF_NEUTRON.equals(def)) {
                mask |= PROP_NEUTRON;
            } else if (RADHelper.DEF_ALARM.equals(def)) {
                mask |= PROP_ALARM;
            } else if (RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT.equals(def)) {
                mask |= PROP_EXTENDED_OCCUPANCY_FAULT;
            } else if (RADHelper.DEF_TAMPER.equals(def)) {
                mask |= PROP_TAMPER;
            } else if (DEF_ALARM_CATEGORY.equals(def)) {
                mask |= PROP_ALARM_CATEGORY;
            }
        }
        int count = comp.getComponentCount();
        for (int i = 0; i < count; i++) {
            mask |= collectObservedPropertyMask(comp.getComponent(i));
        }
        return mask;
    }

    private static boolean isOccupancyDataStream(DataComponent struct) {
        if (struct == null) {
            return false;
        }
        if (RADHelper.DEF_OCCUPANCY.equals(struct.getDefinition())) {
            return true;
        }
        try {
            return struct.getComponent("occupancyCount") != null
                    && struct.getComponent("gammaAlarm") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private long countObservationsOnStreams(
            IObsSystemDatabase database,
            String cqlValue,
            Range<Instant> timeRange,
            Set<BigId> streamIds) {
        if (streamIds == null || streamIds.isEmpty()) {
            return 0L;
        }
        var obsBuilder = new ObsFilter.Builder().withDataStreams(streamIds);
        if (timeRange != null) {
            obsBuilder.withPhenomenonTime().withRange(timeRange).done();
        }
        if (cqlValue != null && !cqlValue.isBlank()) {
            obsBuilder.withCQLFilter(cqlValue);
        }
        return database.getObservationStore().countMatchingEntries(obsBuilder.build());
    }

    /**
     * get last observation
     */
    private LastStatisticObservationData getLastObservation() {
        List<org.sensorhub.api.data.IObsData> observations;
        try (var query = database.getObservationStore().select(new ObsFilter.Builder()
                .withSystems().withUniqueIDs(parentSensor.getUniqueIdentifier()).done()
                .withDataStreams(new DataStreamFilter.Builder()
                        .withOutputNames(NAME)
                        .build())
                .withLatestResult()
                .withLimit(1)
                .build())) {
            observations = query.toList();
        }

        if (observations.isEmpty())
            return null;

        var result = observations.get(0).getResult();

        Instant timestamp = decodeStoredStatisticsTimestamp(result);

        Statistics allTime = getLastObservationCount(result, 0);

        return new LastStatisticObservationData(timestamp, allTime);
    }

    static Range<Instant> timeRange(Instant begin, Instant end, boolean beginExclusive) {
        if (begin == null || end == null)
            return null;
        return beginExclusive ? Range.openClosed(begin, end) : Range.closed(begin, end);
    }

    /**
     * Before OSCAR 3.11.0, statistics wrote epoch seconds with {@code setLongValue}
     * into an INSTANT field. That produced an apparent January 1970 timestamp, while
     * {@code getLongValue} still exposed the intended epoch-second number. Restrict
     * compatibility decoding to the bounded lifetime of plausible OSCAR records so a
     * genuinely modern INSTANT is never reinterpreted by magnitude alone.
     */
    static Instant decodeStoredStatisticsTimestamp(DataBlock result) {
        Instant directTimestamp = result.getTimeStamp(0);
        if (directTimestamp == null)
            return null;

        long legacyEpochSeconds = result.getLongValue(0);
        if (directTimestamp.isBefore(LEGACY_STATS_EPOCH_SECONDS_MIN)
                && legacyEpochSeconds >= LEGACY_STATS_EPOCH_SECONDS_MIN.getEpochSecond()
                && legacyEpochSeconds < LEGACY_STATS_EPOCH_SECONDS_MAX.getEpochSecond()) {
            return Instant.ofEpochSecond(legacyEpochSeconds);
        }
        return directTimestamp;
    }

    /**
     * The persisted SWE Time field is backed by epoch milliseconds. PostgreSQL and
     * the cold archive can retain finer precision, but using an exact millisecond
     * here keeps the result value and observation key identical across every tier.
     */
    static Instant normalizePublicationTime(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.MILLIS);
    }

    /** Returns an exact-millisecond timestamp strictly newer than the prior publication. */
    static Instant publicationTimeAfter(Instant candidate, Instant priorPublication) {
        Instant normalizedCandidate = normalizePublicationTime(candidate);
        if (priorPublication == null || normalizedCandidate.isAfter(priorPublication))
            return normalizedCandidate;
        return normalizePublicationTime(priorPublication).plus(1, ChronoUnit.MILLIS);
    }

    private <T> T runCancellableStatistics(Supplier<T> operation) {
        if (Boolean.TRUE.equals(operationScope.get()))
            return operation.get();

        StatisticsWork work;
        synchronized (workMonitor) {
            if (stopRequested)
                throw new CancellationException("Statistics output is stopping");
            work = new StatisticsWork();
            activeWork.add(work);
        }

        ICancellableDataStore.CancellableOperation datastoreOperation = null;
        boolean operationAttached = false;
        boolean scopeClosed = true;
        Throwable primaryFailure = null;
        Throwable closeFailure = null;
        try {
            try {
                datastoreOperation = beginCancellableDataStoreOperation();
            } catch (RuntimeException | Error error) {
                latchUnsafeStopFailure(asRuntimeFailure(error,
                        "Datastore operation-scope creation failed"));
                throw error;
            }
            work.attachOperation(datastoreOperation);
            operationAttached = true;
            operationScope.set(Boolean.TRUE);
            if (stopRequested) {
                work.requestCancellation();
                throw new CancellationException("Statistics output is stopping");
            }
            return operation.get();
        } catch (RuntimeException | Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            operationScope.remove();
            if (!operationAttached)
                work.attachOperation(null);
            if (datastoreOperation != null) {
                try {
                    datastoreOperation.close();
                } catch (RuntimeException | Error error) {
                    scopeClosed = false;
                    closeFailure = error;
                    latchUnsafeStopFailure(asRuntimeFailure(error,
                            "Datastore operation-scope cleanup failed"));
                }
            }
            work.workerFinished(scopeClosed);
            if (closeFailure != null) {
                if (primaryFailure != null)
                    primaryFailure.addSuppressed(closeFailure);
                else if (closeFailure instanceof RuntimeException runtime)
                    throw runtime;
                else
                    throw (Error) closeFailure;
            }
        }
    }

    protected ICancellableDataStore.CancellableOperation beginCancellableDataStoreOperation() {
        var observationStore = database.getObservationStore();
        return observationStore instanceof ICancellableDataStore cancellable
                ? cancellable.beginCancellableOperation() : null;
    }

    private void latchUnsafeStopFailure(RuntimeException failure) {
        unsafeStopFailure.compareAndSet(null, failure);
    }

    private static RuntimeException asRuntimeFailure(Throwable failure, String message) {
        return failure instanceof RuntimeException runtime
                ? runtime : new IllegalStateException(message, failure);
    }

    private final class StatisticsWork {
        private ICancellableDataStore.CancellableOperation operation;
        private boolean operationAttached;
        private boolean cancellationRequested;
        private boolean cancellationComplete = true;
        private boolean cancellationStarted;
        private boolean workerComplete;
        private boolean scopeClosed;

        void attachOperation(ICancellableDataStore.CancellableOperation attachedOperation) {
            synchronized (workMonitor) {
                if (operationAttached)
                    return;
                operation = attachedOperation;
                operationAttached = true;
                if (cancellationRequested && operation != null)
                    startCancellationLocked();
                else if (cancellationRequested)
                    cancellationComplete = true;
                removeIfCompleteLocked();
            }
        }

        void requestCancellation() {
            synchronized (workMonitor) {
                cancellationRequested = true;
                if (operationAttached) {
                    if (operation != null)
                        startCancellationLocked();
                    else
                        cancellationComplete = true;
                } else {
                    cancellationComplete = false;
                }
                removeIfCompleteLocked();
            }
        }

        private void startCancellationLocked() {
            if (cancellationStarted)
                return;
            cancellationStarted = true;
            cancellationComplete = false;
            Thread cancellationThread = new Thread(() -> {
                try {
                    operation.cancel();
                } catch (Throwable error) {
                    latchUnsafeStopFailure(asRuntimeFailure(error,
                            "Datastore cancellation failed"));
                } finally {
                    synchronized (workMonitor) {
                        cancellationComplete = true;
                        removeIfCompleteLocked();
                    }
                }
            }, "OSCAR-statistics-cancel");
            cancellationThread.setDaemon(true);
            cancellationThread.start();
        }

        void workerFinished(boolean closedCleanly) {
            synchronized (workMonitor) {
                workerComplete = true;
                scopeClosed = closedCleanly;
                removeIfCompleteLocked();
            }
        }

        private void removeIfCompleteLocked() {
            if (workerComplete && scopeClosed
                    && (!cancellationRequested || cancellationComplete)) {
                activeWork.remove(this);
                workMonitor.notifyAll();
            }
        }
    }

    private Statistics getLastObservationCount(DataBlock dataBlock, int index) {
        return new Statistics.Builder()
                .numOccupancies(dataBlock.getLongValue(index + 1))
                .numGammaAlarms(dataBlock.getLongValue(index + 2))
                .numNeutronAlarms(dataBlock.getLongValue(index + 3))
                .numGammaNeutronAlarms(dataBlock.getLongValue(index + 4))
                .numFaults(dataBlock.getLongValue(index + 5))
                .numGammaFaults(dataBlock.getLongValue(index + 6))
                .numNeutronFaults(dataBlock.getLongValue(index + 7))
                .numTampers(dataBlock.getLongValue(index + 8))
                .build();
    }

    @Override
    public DataComponent getRecordDescription() {
        return recordDescription;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return recommendedEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {
        return samplingPeriod * 60;
    }
}
