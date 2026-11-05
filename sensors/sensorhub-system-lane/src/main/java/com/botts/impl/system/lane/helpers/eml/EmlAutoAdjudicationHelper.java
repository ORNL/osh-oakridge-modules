package com.botts.impl.system.lane.helpers.eml;

import com.botts.impl.system.lane.AdjudicationControl;
import com.botts.impl.system.lane.LaneSystem;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.impl.system.CommandStreamTransactionHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.PendingEmlAutoAdjudicationRegistry;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens for persisted Rapiscan Occupancy observations that were pre-marked
 * by EML as Non-Threat releases and submits a durable automated Adjudication
 * command on the enclosing lane's adjudicationControl stream.
 */
public final class EmlAutoAdjudicationHelper implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(EmlAutoAdjudicationHelper.class);
    private static final AtomicLong CORRELATION_IDS = new AtomicLong(1_000_000L);

    private final LaneSystem laneSystem;
    private final ISensorHub hub;
    private final IDataProducerModule<?> occupancyProducer;
    private final IObsStore occupancyObsStore;
    private final IdEncoder obsIdEncoder;
    private final ExecutorService worker;
    private volatile Flow.Subscription subscription;
    private volatile boolean stopped = false;

    public EmlAutoAdjudicationHelper(
            LaneSystem laneSystem,
            IDataProducerModule<?> occupancyProducer,
            IObsStore occupancyObsStore) {
        this.laneSystem = laneSystem;
        this.hub = laneSystem.getParentHub();
        this.occupancyProducer = occupancyProducer;
        this.occupancyObsStore = occupancyObsStore;
        this.obsIdEncoder = hub.getIdEncoders().getObsIdEncoder();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eml-auto-adj-" + laneSystem.getConfiguration().name);
            t.setDaemon(true);
            return t;
        });
        subscribe();
    }

    private void subscribe() {
        String producerUid = occupancyProducer.getUniqueIdentifier();
        if (producerUid == null || producerUid.isBlank())
            return;

        hub.getEventBus().newSubscription(ObsEvent.class)
                .withTopicID(EventUtils.getDataStreamDataTopicID(producerUid, OccupancyOutput.NAME))
                .subscribe(this::onOccupancyEvent)
                .thenAccept(sub -> {
                    if (stopped) {
                        sub.cancel();
                        return;
                    }
                    this.subscription = sub;
                    sub.request(Long.MAX_VALUE);
                });
    }

    private void onOccupancyEvent(ObsEvent event) {
        if (stopped || event == null || event.getObservations() == null)
            return;

        String producerUid = occupancyProducer.getUniqueIdentifier();
        for (IObsData obs : event.getObservations()) {
            if (obs == null || obs.getResult() == null)
                continue;

            DataComponent struct = resolveRecordStructure(obs);
            Occupancy occupancy = OccupancyExtended.readOccupancy(struct, obs.getResult());
            EmlEvaluationResult eval = PendingEmlAutoAdjudicationRegistry.claim(
                    producerUid, occupancy.getSamplingTime(), occupancy.getOccupancyCount());
            if (eval == null)
                continue;

            if (!stopped) {
                worker.execute(() -> submitAutoAdjudication(producerUid, obs, occupancy, eval));
            }
        }
    }

    private void submitAutoAdjudication(
            String producerUid,
            IObsData obs,
            Occupancy occupancy,
            EmlEvaluationResult eval) {
        try {
            BigId obsKey = resolvePersistedObsId(producerUid, obs, occupancy);
            if (obsKey == null) {
                logger.warn("Could not locate persisted occupancy ID for EML Non-Threat auto-adjudication on {}",
                        laneSystem.getUniqueIdentifier());
                return;
            }

            String encodedObsId = obsIdEncoder.encodeID(obsKey);
            Adjudication adjudication = new Adjudication.Builder()
                    .feedback(eval.toAdjudicationFeedback())
                    .adjudicationCode(Adjudication.EML_NON_THREAT_CODE)
                    .isotopes(List.of())
                    .secondaryInspectionStatus(Adjudication.SecondaryInspectionStatus.NONE)
                    .filePaths(List.of())
                    .occupancyObsId(encodedObsId)
                    .vehicleId("")
                    .build();

            var db = hub.getDatabaseRegistry().getFederatedDatabase();
            var sysTxnHandler = new SystemDatabaseTransactionHandler(hub.getEventBus(), db);
            CommandStreamTransactionHandler cmdTxn = sysTxnHandler.getCommandStreamHandler(
                    laneSystem.getUniqueIdentifier(), AdjudicationControl.NAME);
            if (cmdTxn == null) {
                logger.warn("Adjudication command stream handler unavailable on {}", laneSystem.getUniqueIdentifier());
                return;
            }

            var cmd = new CommandData.Builder()
                    .withSender(Adjudication.EML_AUTO_ADJUDICATOR_USERNAME)
                    .withCommandStream(cmdTxn.getCommandStreamKey().getInternalID())
                    .withParams(Adjudication.fromAdjudication(adjudication))
                    .build();

            ICommandStatus status = cmdTxn.submitCommand(
                    CORRELATION_IDS.incrementAndGet(),
                    cmd,
                    10,
                    TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);

            if (status.getStatusCode() != ICommandStatus.CommandStatusCode.COMPLETED) {
                logger.warn("EML Non-Threat auto-adjudication returned {} on {}: {}",
                        status.getStatusCode(), laneSystem.getUniqueIdentifier(), status.getMessage());
            } else {
                logger.info("Auto-adjudicated occupancy {} on {} as EML Non-Threat (releaseProb={})",
                        encodedObsId, laneSystem.getConfiguration().name, eval.releaseProbability());
            }
        } catch (Exception e) {
            if (!stopped) {
                logger.error("Failed to persist EML Non-Threat auto-adjudication on {}",
                        laneSystem.getUniqueIdentifier(), e);
            }
        }
    }

    private BigId resolvePersistedObsId(String producerUid, IObsData eventObs, Occupancy targetOccupancy) {
        ObsFilter filter = new ObsFilter.Builder()
                .withSystems()
                    .withUniqueIDs(producerUid)
                    .done()
                .withDataStreams()
                    .withOutputNames(OccupancyOutput.NAME)
                    .done()
                .withPhenomenonTime()
                    .withSingleValue(eventObs.getPhenomenonTime())
                    .done()
                .build();

        for (int attempt = 0; attempt < 3; attempt++) {
            var entries = occupancyObsStore.selectEntries(filter).toList();
            if (!entries.isEmpty()) {
                for (var entry : entries) {
                    DataComponent struct = resolveRecordStructure(entry.getValue());
                    Occupancy candidate = OccupancyExtended.readOccupancy(struct, entry.getValue().getResult());
                    if (candidate.getOccupancyCount() == targetOccupancy.getOccupancyCount()) {
                        return entry.getKey();
                    }
                }
                return entries.get(0).getKey();
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private DataComponent resolveRecordStructure(IObsData obs) {
        try {
            var ds = occupancyObsStore.getDataStreams().get(new DataStreamKey(obs.getDataStreamID()));
            return ds != null ? ds.getRecordStructure() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void stop() {
        stopped = true;
        Flow.Subscription sub = subscription;
        subscription = null;
        if (sub != null)
            sub.cancel();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS))
                worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
