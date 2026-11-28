package com.botts.impl.service.oscar.retention;

import com.botts.api.service.bucket.IBucketStore;
import com.botts.impl.service.oscar.media.MediaManifest;
import com.botts.impl.service.oscar.media.MediaSetStore;
import org.sensorhub.api.datastore.DataStoreException;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class StoragePressureRetention {
    private static final long DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L;
    private static final Duration OCR_AGE_SWEEP_INTERVAL = Duration.ofHours(1);
    private static final int MAX_OCR_AGE_DELETES_PER_CYCLE = 1_000;
    private static final int CRITICAL_USAGE_PERCENT = 92;

    public enum PressureTier {
        NORMAL,
        EARLY_SECONDARY_PRUNE,
        PRESSURE,
        CRITICAL
    }

    public record PressureStatus(
            Instant checkedAt,
            int usagePercent,
            PressureTier tier,
            long cumulativeSecondaryPrunedBytes,
            long cumulativeDeletedBytes,
            int cumulativeSecondarySetsPruned,
            int cumulativeDeletedObjects) {}

    private final StoragePressureRetentionConfig config;
    private final Logger log;
    private final Path storagePath;
    private final MediaSetStore mediaStore;
    private final OcrCropRetention ocrCropRetention;
    private final Object lifecycleMonitor = new Object();
    private final Object cleanupMonitor = new Object();
    private ScheduledExecutorService scheduler;
    private Instant lastOcrAgeSweep;
    private long cumulativeSecondaryPrunedBytes;
    private long cumulativeDeletedBytes;
    private int cumulativeSecondarySetsPruned;
    private int cumulativeDeletedObjects;
    private volatile PressureStatus latestStatus = new PressureStatus(
            null, 0, PressureTier.NORMAL, 0L, 0L, 0, 0);

    public StoragePressureRetention(IBucketStore bucketStore, StoragePressureRetentionConfig config, Logger log) {
        this(bucketStore, createMediaStore(bucketStore), config, id -> false, log);
    }

    public StoragePressureRetention(IBucketStore bucketStore, MediaSetStore mediaStore,
                                    StoragePressureRetentionConfig config, Logger log) {
        this(bucketStore, mediaStore, config, id -> false, log);
    }

    public StoragePressureRetention(IBucketStore bucketStore, MediaSetStore mediaStore,
                                    StoragePressureRetentionConfig config,
                                    Predicate<String> operatorAdjudicatedPredicate,
                                    Logger log) {
        if (config.triggerUsagePercent <= config.targetUsagePercent || config.triggerUsagePercent > 100
                || config.targetUsagePercent < 0)
            throw new IllegalArgumentException("Storage trigger must be greater than target and no more than 100 percent");
        if (config.earlySecondaryPruneUsagePercent < 0 || config.earlySecondaryPruneUsagePercent > 100)
            throw new IllegalArgumentException("Early secondary-frame prune usage must be between 0 and 100 percent");
        if (config.checkPeriodMinutes <= 0 || config.minimumObjectAgeMinutes < 0
                || config.maximumMediaSetsPerCycle <= 0)
            throw new IllegalArgumentException("Storage retention intervals must be valid");
        if (config.ocrCropRetentionDays < 0)
            throw new IllegalArgumentException("OCR crop retention days must be non-negative");
        if (config.storagePath == null || config.storagePath.isBlank())
            throw new IllegalArgumentException("Storage path is required");

        this.config = config;
        this.log = log;
        this.mediaStore = mediaStore;
        this.storagePath = Path.of(config.storagePath).toAbsolutePath().normalize();
        this.ocrCropRetention = new OcrCropRetention(
                this.storagePath, this.mediaStore, operatorAdjudicatedPredicate, log);
    }

    public void start() {
        synchronized (lifecycleMonitor) {
            if (scheduler != null && !scheduler.isTerminated())
                return;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "OSCAR-storage-pressure-retention");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::runSafely, 0,
                    config.checkPeriodMinutes, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        stop(DEFAULT_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void stop(long timeout, TimeUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        if (timeout <= 0)
            throw new IllegalArgumentException("Storage-pressure stop timeout must be positive");

        long budgetNanos = Math.max(1L, unit.toNanos(timeout));
        long deadline = System.nanoTime() + budgetNanos;
        requestStop();
        awaitStop(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /** Signals the scheduled cleanup immediately without waiting for its worker. */
    public void requestStop() {
        synchronized (lifecycleMonitor) {
            ScheduledExecutorService stopping = scheduler;
            if (stopping == null)
                return;
            stopping.shutdownNow();
        }
    }

    /** Waits truthfully for a stop that has already been requested. */
    public void awaitStop(long timeout, TimeUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        if (timeout <= 0)
            throw new IllegalArgumentException("Storage-pressure stop timeout must be positive");
        ScheduledExecutorService stopping;
        synchronized (lifecycleMonitor) {
            stopping = scheduler;
        }
        if (stopping == null)
            return;
        boolean terminated;
        try {
            terminated = stopping.awaitTermination(timeout, unit);
        } catch (InterruptedException error) {
            stopping.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for storage-pressure retention to stop", error);
        }
        if (!terminated)
            throw new IllegalStateException(
                    "Storage-pressure retention worker did not terminate within the stop deadline");
        synchronized (lifecycleMonitor) {
            if (scheduler == stopping)
                scheduler = null;
        }
    }

    public PressureStatus latestStatus() {
        return latestStatus;
    }

    void cleanupIfNeeded() throws IOException, DataStoreException {
        synchronized (cleanupMonitor) {
            Files.createDirectories(storagePath);
            FileStore fileStore = Files.getFileStore(storagePath);
            cleanupIfNeededWithinLock(readUsage(fileStore), fileStore);
        }
    }

    void cleanupIfNeeded(long totalBytes, long usableBytes) throws IOException, DataStoreException {
        synchronized (cleanupMonitor) {
            Files.createDirectories(storagePath);
            long safeTotal = Math.max(0L, totalBytes);
            long safeUsable = Math.max(0L, Math.min(safeTotal, usableBytes));
            long used = Math.max(0L, safeTotal - safeUsable);
            int percent = safeTotal == 0L ? 0 : (int) Math.ceil((used * 100.0) / safeTotal);
            cleanupIfNeededWithinLock(new StorageUsage(safeTotal, used, percent), null);
        }
    }

    int pruneExpiredOcrCrops(Instant now) throws IOException {
        synchronized (cleanupMonitor) {
            lastOcrAgeSweep = now;
            return ocrCropRetention.pruneExpiredDatePartitions(
                    now, config.ocrCropRetentionDays, MAX_OCR_AGE_DELETES_PER_CYCLE).deletedFiles();
        }
    }

    private int effectiveEarlySecondaryPrunePercent() {
        int configured = config.earlySecondaryPruneUsagePercent;
        return (configured > 0 && configured < config.triggerUsagePercent) ? configured : 0;
    }

    private void cleanupIfNeededWithinLock(StorageUsage usage, FileStore fileStore) throws IOException, DataStoreException {
        Instant now = Instant.now();
        maybePruneExpiredOcrCrops(now);

        int effectiveEarly = effectiveEarlySecondaryPrunePercent();
        if (usage.percentUsed() < config.triggerUsagePercent) {
            if (effectiveEarly > 0 && usage.percentUsed() >= effectiveEarly) {
                int earlyTarget = Math.max(0, effectiveEarly - 5);
                long earlyBytesToFree = Math.max(0L, usage.usedBytes() - usage.targetBytes(earlyTarget));
                log.warn("Storage usage is {}% (early pressure >= {}%); pruning oldest unheld secondary frames " +
                                "while preserving primary frame-3.jpg ({} bytes targeted)",
                        usage.percentUsed(), effectiveEarly, earlyBytesToFree);
                Instant cutoff = now.minus(Duration.ofMinutes(config.minimumObjectAgeMinutes));
                long cycleTail = mediaStore.pressureQueueTail();
                long freedBytes = 0L;
                int prunedSets = 0;
                int inspected = 0;
                while (!Thread.currentThread().isInterrupted()
                        && inspected < config.maximumMediaSetsPerCycle && freedBytes < earlyBytesToFree) {
                    Optional<MediaSetStore.PressureQueueEntry> next = mediaStore.inspectPressureHead();
                    if (next.isEmpty() || next.get().sequence() >= cycleTail)
                        break;
                    MediaSetStore.PressureQueueEntry queueEntry = next.get();
                    inspected++;
                    if (queueEntry.job().isEmpty() || !queueEntry.currentMembership()) {
                        mediaStore.completePressureEntry(queueEntry);
                        continue;
                    }
                    MediaSetStore.Identity identity = queueEntry.job().get().identity();
                    Optional<MediaManifest> found = mediaStore.findManifest(identity);
                    if (found.isEmpty()) {
                        mediaStore.completePressureEntry(queueEntry);
                        continue;
                    }
                    MediaManifest manifest = found.get();
                    boolean protectedByHold = manifest.isHeld()
                            || manifest.lifecycleState() == MediaManifest.LifecycleState.HELD;
                    boolean protectedByRollback = manifest.source().state()
                            == MediaManifest.SourceState.PRESERVED_FOR_ROLLBACK
                            && now.isBefore(manifest.source().rollbackDeleteAfter());
                    boolean tooNew = manifest.createdAt().isAfter(cutoff);
                    if (!protectedByHold && !protectedByRollback && !tooNew
                            && manifest.lifecycleState() != MediaManifest.LifecycleState.DELETING
                            && manifest.hasSecondaryFramesRemaining()) {
                        long pruned = mediaStore.pruneSecondaryFrames(new MediaSetStore.ManifestEntry(
                                mediaStore.manifestKey(identity), manifest));
                        if (pruned > 0L) {
                            freedBytes += pruned;
                            prunedSets++;
                            cumulativeSecondaryPrunedBytes += pruned;
                            cumulativeSecondarySetsPruned++;
                            log.info("Early storage-pressure cleanup pruned secondary frames for media set {} ({} bytes; primary frame preserved)",
                                    manifest.mediaSetId(), pruned);
                        }
                    }
                    // Always defer back to the pressure queue so surviving primary frame-3.jpg remains
                    // tracked in FIFO order if full-set deletion ever becomes necessary.
                    mediaStore.deferPressureEntry(queueEntry);
                }
                int finalPct = fileStore != null ? readUsage(fileStore).percentUsed() : usage.percentUsed();
                latestStatus = new PressureStatus(
                        now, finalPct, PressureTier.EARLY_SECONDARY_PRUNE,
                        cumulativeSecondaryPrunedBytes, cumulativeDeletedBytes,
                        cumulativeSecondarySetsPruned, cumulativeDeletedObjects);
                return;
            }
            latestStatus = new PressureStatus(
                    now, usage.percentUsed(), PressureTier.NORMAL,
                    cumulativeSecondaryPrunedBytes, cumulativeDeletedBytes,
                    cumulativeSecondarySetsPruned, cumulativeDeletedObjects);
            return;
        }

        boolean critical = usage.percentUsed() >= CRITICAL_USAGE_PERCENT;
        long bytesToFree = Math.max(0, usage.usedBytes() - usage.targetBytes(config.targetUsagePercent));
        log.warn("Storage usage is {}%; deleting eligible data until usage reaches {}% ({} bytes required)",
                usage.percentUsed(), config.targetUsagePercent, bytesToFree);

        Instant cutoff = now.minus(Duration.ofMinutes(config.minimumObjectAgeMinutes));
        // Freeze the tail so a held or not-yet-old entry deferred to the tail is inspected no more
        // than once in this cycle. Both work and memory therefore remain bounded regardless of the
        // number of retained sets on disk.
        long cycleTail = mediaStore.pressureQueueTail();
        long freedBytes = 0;
        int deletedObjects = 0;
        int prunedSecondarySets = 0;
        int inspected = 0;
        while (!Thread.currentThread().isInterrupted()
                && inspected < config.maximumMediaSetsPerCycle && freedBytes < bytesToFree) {
            Optional<MediaSetStore.PressureQueueEntry> next = mediaStore.inspectPressureHead();
            if (next.isEmpty() || next.get().sequence() >= cycleTail)
                break;
            MediaSetStore.PressureQueueEntry queueEntry = next.get();
            inspected++;
            if (queueEntry.job().isEmpty() || !queueEntry.currentMembership()) {
                mediaStore.completePressureEntry(queueEntry);
                continue;
            }

            MediaSetStore.Identity identity = queueEntry.job().get().identity();
            Optional<MediaManifest> found = mediaStore.findManifest(identity);
            if (found.isEmpty()) {
                mediaStore.completePressureEntry(queueEntry);
                continue;
            }
            MediaManifest manifest = found.get();
            boolean deletionAlreadyStarted = manifest.lifecycleState()
                    == MediaManifest.LifecycleState.DELETING;
            boolean protectedByHold = manifest.isHeld()
                    || manifest.lifecycleState() == MediaManifest.LifecycleState.HELD;
            boolean protectedByRollback = manifest.source().state()
                    == MediaManifest.SourceState.PRESERVED_FOR_ROLLBACK
                    && now.isBefore(manifest.source().rollbackDeleteAfter());
            boolean tooNew = manifest.createdAt().isAfter(cutoff);
            if (!deletionAlreadyStarted && (protectedByHold || protectedByRollback || tooNew)) {
                mediaStore.deferPressureEntry(queueEntry);
                continue;
            }

            if (!deletionAlreadyStarted && !critical && effectiveEarly > 0
                    && manifest.hasSecondaryFramesRemaining()) {
                long pruned = mediaStore.pruneSecondaryFrames(new MediaSetStore.ManifestEntry(
                        mediaStore.manifestKey(identity), manifest));
                if (pruned > 0L) {
                    freedBytes += pruned;
                    prunedSecondarySets++;
                    cumulativeSecondaryPrunedBytes += pruned;
                    cumulativeSecondarySetsPruned++;
                    log.info("Pressure cleanup pruned secondary frames for media set {} ({} bytes; primary frame preserved)",
                            manifest.mediaSetId(), pruned);
                    mediaStore.deferPressureEntry(queueEntry);
                    continue;
                }
            }

            long deleted = mediaStore.deleteMediaSet(new MediaSetStore.ManifestEntry(
                    mediaStore.manifestKey(identity), manifest));
            freedBytes += deleted;
            if (deleted > 0) {
                deletedObjects++;
                cumulativeDeletedBytes += deleted;
                cumulativeDeletedObjects++;
                log.info("Pressure cleanup deleted media set {} ({} bytes)",
                        manifest.mediaSetId(), deleted);
            }
            // deleteMediaSet clears membership only after the manifest is gone. Advancing the
            // direct queue is idempotent if a restart observes the now-missing queue item.
            if (deleted > 0 || mediaStore.findManifest(identity).isEmpty())
                mediaStore.completePressureEntry(queueEntry);
            else
                // A hold or rollback-grace update may race the pre-delete read. deleteMediaSet
                // rechecks the live manifest and returns zero; retain the candidate at the tail.
                mediaStore.deferPressureEntry(queueEntry);
        }

        if (!Thread.currentThread().isInterrupted() && freedBytes < bytesToFree) {
            int maxOcrDeletes = Math.max(200, config.maximumMediaSetsPerCycle * 6);
            OcrCropRetention.SweepResult cropSweep = ocrCropRetention.reclaimUnderStoragePressure(
                    cutoff, Math.max(1L, bytesToFree - freedBytes), maxOcrDeletes);
            if (cropSweep.deletedFiles() > 0) {
                freedBytes += cropSweep.freedBytes();
                deletedObjects += cropSweep.deletedFiles();
                cumulativeDeletedBytes += cropSweep.freedBytes();
                cumulativeDeletedObjects += cropSweep.deletedFiles();
                log.info("Pressure cleanup deleted {} OCR evidence crop(s) ({} bytes)",
                        cropSweep.deletedFiles(), cropSweep.freedBytes());
            }
        }

        StorageUsage finalUsage = fileStore != null ? readUsage(fileStore) : usage;
        PressureTier activeTier = (critical || finalUsage.percentUsed() >= CRITICAL_USAGE_PERCENT)
                ? PressureTier.CRITICAL : PressureTier.PRESSURE;
        latestStatus = new PressureStatus(
                now, finalUsage.percentUsed(), activeTier,
                cumulativeSecondaryPrunedBytes, cumulativeDeletedBytes,
                cumulativeSecondarySetsPruned, cumulativeDeletedObjects);
        if (finalUsage.percentUsed() > config.targetUsagePercent) {
            log.error("Storage remains at {}% after inspecting {}, pruning secondary frames on {} set(s), and deleting {} media sets "
                            + "({} bytes). Daily-file CSVs remain protected; bounded cleanup will "
                            + "resume next cycle.", finalUsage.percentUsed(), inspected,
                    prunedSecondarySets, deletedObjects, freedBytes);
        } else {
            log.info("Storage pressure cleanup completed at {}% after pruning secondary frames on {} set(s) and deleting {} objects ({} bytes)",
                    finalUsage.percentUsed(), prunedSecondarySets, deletedObjects, freedBytes);
        }
    }

    private void maybePruneExpiredOcrCrops(Instant now) {
        if (config.ocrCropRetentionDays <= 0)
            return;
        if (lastOcrAgeSweep != null && Duration.between(lastOcrAgeSweep, now).compareTo(OCR_AGE_SWEEP_INTERVAL) < 0)
            return;
        lastOcrAgeSweep = now;
        try {
            ocrCropRetention.pruneExpiredDatePartitions(
                    now, config.ocrCropRetentionDays, MAX_OCR_AGE_DELETES_PER_CYCLE);
        } catch (Exception e) {
            log.debug("OCR crop date-partition age sweep skipped: {}", e.getMessage());
        }
    }

    private void runSafely() {
        try {
            cleanupIfNeeded();
        } catch (Exception e) {
            log.error("Storage pressure cleanup failed", e);
        }
    }

    private static StorageUsage readUsage(FileStore fileStore) throws IOException {
        long total = fileStore.getTotalSpace();
        long used = Math.max(0, total - fileStore.getUsableSpace());
        int percent = total == 0 ? 0 : (int) Math.ceil((used * 100.0) / total);
        return new StorageUsage(total, used, percent);
    }

    private static MediaSetStore createMediaStore(IBucketStore bucketStore) {
        try {
            return new MediaSetStore(bucketStore);
        } catch (DataStoreException e) {
            throw new IllegalStateException("Unable to initialize retained-media storage", e);
        }
    }

    private record StorageUsage(long totalBytes, long usedBytes, int percentUsed) {
        long targetBytes(int targetPercent) {
            return (long) Math.floor(totalBytes * (targetPercent / 100.0));
        }
    }
}
