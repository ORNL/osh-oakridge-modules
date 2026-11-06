/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.service.oscar;

import com.botts.impl.system.lane.AdjudicationControl;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous, idempotent one-time startup migration that ensures historical
 * adjudicated {@link Occupancy} observations carry a {@code "code:<n>"} marker
 * in {@code adjudicatedIds} so server-side PostgreSQL GIN and DuckDB Parquet
 * CQL filters on {@code adjudicationCode} match upgraded deployments immediately.
 */
public final class AdjudicationCodeMarkerBackfill {
    public static final String MARKER_PATH_PROPERTY = "oscar.adjudication.backfill.marker";
    static final String DEFAULT_MARKER_FILE_NAME = ".adjudication-code-markers-v1.done";

    private final IObsSystemDatabase database;
    private final IdEncoder obsIdEncoder;
    private final Path markerPath;
    private final Logger logger;

    private volatile boolean stopRequested;
    private volatile Thread workerThread;

    public AdjudicationCodeMarkerBackfill(IObsSystemDatabase database, IdEncoder obsIdEncoder, Logger logger) {
        this(database, obsIdEncoder, resolveDefaultMarkerPath(), logger);
    }

    public AdjudicationCodeMarkerBackfill(
            IObsSystemDatabase database,
            IdEncoder obsIdEncoder,
            Path markerPath,
            Logger logger) {
        this.database = database;
        this.obsIdEncoder = obsIdEncoder;
        this.markerPath = Objects.requireNonNull(markerPath, "markerPath").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    static Path resolveDefaultMarkerPath() {
        String override = System.getProperty(MARKER_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(DEFAULT_MARKER_FILE_NAME).toAbsolutePath().normalize();
    }

    public synchronized void start() {
        if (workerThread != null || database == null || obsIdEncoder == null)
            return;
        if (Files.isRegularFile(markerPath)) {
            logger.debug("Adjudication code marker backfill already completed ({})", markerPath);
            return;
        }
        stopRequested = false;
        Thread thread = new Thread(this::runBackfillSafely, "oscar-adj-code-backfill");
        thread.setDaemon(true);
        workerThread = thread;
        thread.start();
    }

    public void requestStop() {
        stopRequested = true;
        Thread thread = workerThread;
        if (thread != null)
            thread.interrupt();
    }

    public void awaitStop(long timeout, TimeUnit unit) {
        Thread thread = workerThread;
        if (thread == null)
            return;
        long millis = Math.max(1L, unit.toMillis(timeout));
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!thread.isAlive())
                workerThread = null;
        }
    }

    int runSynchronouslyForTest() {
        return performBackfill();
    }

    private void runBackfillSafely() {
        try {
            performBackfill();
        } catch (Throwable error) {
            if (!stopRequested) {
                logger.warn("Adjudication code marker backfill did not finish cleanly (will retry on next startup)", error);
            }
        } finally {
            workerThread = null;
        }
    }

    private record TimestampedCode(Instant reportTime, String occupancyObsId, int code) {}

    private int performBackfill() {
        if (stopRequested || Files.isRegularFile(markerPath))
            return 0;

        IObsStore obsStore = database.getObservationStore();
        var statusStore = database.getCommandStatusStore();
        if (obsStore == null || statusStore == null)
            return 0;

        List<TimestampedCode> candidates = new ArrayList<>();
        CommandStatusFilter statusFilter = new CommandStatusFilter.Builder()
                .withStatus(ICommandStatus.CommandStatusCode.COMPLETED)
                .withCommands(new CommandFilter.Builder()
                        .withCommandStreams()
                            .withTaskableProperties(RADHelper.DEF_ADJUDICATION)
                            .done()
                        .build())
                .build();

        try (var stream = statusStore.select(statusFilter)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                if (stopRequested)
                    return 0;
                ICommandStatus status = it.next();
                AdjudicationControl.extractCompletedResultSummary(status).ifPresent(summary -> candidates.add(
                        new TimestampedCode(
                                status.getReportTime() != null ? status.getReportTime() : Instant.EPOCH,
                                summary.occupancyObsId(),
                                summary.adjudicationCode())));
            }
        }

        candidates.sort(Comparator.comparing(TimestampedCode::reportTime));
        Map<String, Integer> latestCodeByEncodedObsId = new LinkedHashMap<>();
        for (TimestampedCode candidate : candidates) {
            latestCodeByEncodedObsId.put(candidate.occupancyObsId(), candidate.code());
        }

        int updatedOccupancies = 0;
        for (var entry : latestCodeByEncodedObsId.entrySet()) {
            if (stopRequested)
                return updatedOccupancies;
            BigId obsId;
            try {
                obsId = obsIdEncoder.decodeID(entry.getKey());
            } catch (Exception ignored) {
                continue;
            }
            if (obsId == null)
                continue;
            if (applyMarkerIfMissing(obsStore, obsId, entry.getValue(), false))
                updatedOccupancies++;
        }

        if (stopRequested)
            return updatedOccupancies;

        ObsFilter emlFilter = new ObsFilter.Builder()
                .withDataStreams(new DataStreamFilter.Builder()
                        .withObservedProperties(RADHelper.DEF_OCCUPANCY)
                        .build())
                .withCQLFilter("adjudicatedIds='" + Adjudication.EML_NON_THREAT_MARKER + "'")
                .build();
        List<BigId> emlObsIds = new ArrayList<>();
        try (var stream = obsStore.selectKeys(emlFilter)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                if (stopRequested)
                    return updatedOccupancies;
                emlObsIds.add(it.next());
            }
        } catch (Exception e) {
            logger.debug("Skipping EML marker scan during adjudication code backfill: {}", e.getMessage());
        }

        for (BigId obsId : emlObsIds) {
            if (stopRequested)
                return updatedOccupancies;
            if (applyMarkerIfMissing(obsStore, obsId, Adjudication.EML_NON_THREAT_CODE, true))
                updatedOccupancies++;
        }

        if (!stopRequested) {
            writeCompletionMarker(updatedOccupancies);
            logger.info("Backfilled adjudication code markers onto {} historical occupancies", updatedOccupancies);
        }
        return updatedOccupancies;
    }

    private boolean applyMarkerIfMissing(IObsStore obsStore, BigId obsId, int code, boolean requireEmlMarker) {
        try {
            IObsData obs = obsStore.get(obsId);
            if (obs == null || obs.getResult() == null)
                return false;
            var ds = obsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
            if (ds == null)
                return false;
            DataComponent recordStructure = ds.getRecordStructure();
            if (recordStructure == null || !Objects.equals(RADHelper.getRadUri("Occupancy"), recordStructure.getDefinition()))
                return false;

            Occupancy occupancy = OccupancyExtended.readOccupancy(recordStructure, obs.getResult());
            if (requireEmlMarker && !occupancy.getAdjudicatedIds().contains(Adjudication.EML_NON_THREAT_MARKER))
                return false;
            // Never overwrite an occupancy that already received a live "code:<n>" marker.
            if (occupancy.hasAdjudicationCodeMarker())
                return false;
            if (!occupancy.recordAdjudicationCode(code))
                return false;

            DataBlock newResult = OccupancyExtended.writeOccupancy(recordStructure, occupancy);
            obs.getResult().setUnderlyingObject(newResult.getUnderlyingObject());
            obs.getResult().updateAtomCount();
            obsStore.put(obsId, obs);
            return true;
        } catch (Exception e) {
            logger.debug("Unable to backfill adjudication code marker onto observation {}: {}", obsId, e.getMessage());
            return false;
        }
    }

    private void writeCompletionMarker(int updatedOccupancies) {
        try {
            Path parent = markerPath.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Path tmp = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
            String content = "version=1\nupdatedOccupancies=" + updatedOccupancies
                    + "\ncompletedAt=" + Instant.now() + "\n";
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, markerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, markerPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Unable to write adjudication code backfill marker {}: {}", markerPath, e.getMessage());
        }
    }
}
