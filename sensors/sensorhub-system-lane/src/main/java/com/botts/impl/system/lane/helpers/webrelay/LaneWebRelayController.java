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

package com.botts.impl.system.lane.helpers.webrelay;

import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.config.WebRelayOperatingMode;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.EndpointSpec;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.RelayStateSnapshot;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.impl.utils.rad.model.Adjudication;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-lane runtime controller for physical RPM pillar lights, sounder, and optional
 * auxiliary dry-contact relays via ControlByWeb WebRelay-Quad ({@code X-WR-4R3})
 * modules.
 *
 * <ul>
 *   <li><b>Fail-Safe Pass-Through on Shutdown:</b> Releases coils to {@code 0,0,0,0}
 *       on clean lane stop so native RPM hardware outputs remain functional even when
 *       OSCAR is offline.</li>
 *   <li><b>Automatic Re-Arming After Power Blips:</b> Periodic health polling detects
 *       if a WebRelay rebooted into {@code 0,0,0,0} while in {@code AUTO} or {@code MUTED}
 *       mode and automatically re-energizes {@code Relay 1 = 1, Relay 2 = 1}.</li>
 *   <li><b>Hardware-Timed Pulses:</b> Uses {@code relay3State=2&pulseTime3=<sec>} and
 *       {@code relay4State=2&pulseTime4=<sec>} so an Ethernet disconnection mid-alarm
 *       can never leave a siren or strobe stuck ON.</li>
 * </ul>
 */
public final class LaneWebRelayController implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LaneWebRelayController.class);
    private static final Duration SHUTDOWN_RELEASE_TIMEOUT = Duration.ofMillis(1200);

    public record ControllerStatusSnapshot(
            boolean enabled,
            WebRelayOperatingMode mode,
            boolean ancillaryEnabled,
            boolean manualLatchActive,
            RelayStateSnapshot primaryState,
            RelayStateSnapshot ancillaryState,
            long triggeredAlarmCount,
            long suppressedEmlCount,
            long suppressedExtendedOccupancyCount,
            String lastActionSummary,
            String lastActionAt
    ) {}

    private final LaneSystem laneSystem;
    private final ISensorHub hub;
    private final WebRelayQuadClient client;
    private final ScheduledExecutorService scheduler;
    private final Object stateLock = new Object();

    private volatile WebRelayConfig activeConfig;
    private volatile EndpointSpec primaryEndpoint;
    private volatile EndpointSpec ancillaryEndpoint;
    private volatile RelayStateSnapshot latestPrimaryState = RelayStateSnapshot.offline("Waiting for initial poll", 0L);
    private volatile RelayStateSnapshot latestAncillaryState = RelayStateSnapshot.offline("Disabled", 0L);
    private volatile boolean manualLatchGamma = false;
    private volatile boolean manualLatchNeutron = false;
    private volatile long activePulseUntilEpochMs = 0L;
    private volatile String lastActionSummary = "Initialized";
    private volatile String lastActionAt = Instant.now().toString();
    private volatile boolean closed = false;

    private final AtomicLong triggeredAlarmCount = new AtomicLong(0L);
    private final AtomicLong suppressedEmlCount = new AtomicLong(0L);
    private final AtomicLong suppressedExtendedOccupancyCount = new AtomicLong(0L);

    private ScheduledFuture<?> pollTask;
    private Flow.Subscription occupancySubscription;
    private String subscribedProducerUid;

    public LaneWebRelayController(
            LaneSystem laneSystem,
            IDataProducerModule<?> occupancyProducer,
            WebRelayConfig config
    ) {
        this(laneSystem, occupancyProducer, config, new WebRelayQuadClient());
    }

    public LaneWebRelayController(
            LaneSystem laneSystem,
            IDataProducerModule<?> occupancyProducer,
            WebRelayConfig config,
            WebRelayQuadClient client
    ) {
        this.laneSystem = Objects.requireNonNull(laneSystem, "laneSystem");
        this.hub = laneSystem.hasParentHub() ? laneSystem.getParentHub() : null;
        this.client = Objects.requireNonNull(client, "client");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            String laneName = laneSystem.getConfiguration() != null ? laneSystem.getConfiguration().name : "lane";
            Thread t = new Thread(r, "lane-webrelay-" + laneName);
            t.setDaemon(true);
            return t;
        });
        applyConfiguration(config, occupancyProducer, true);
    }

    public synchronized void reconfigure(WebRelayConfig nextConfig, IDataProducerModule<?> occupancyProducer) {
        if (closed) {
            return;
        }
        applyConfiguration(nextConfig, occupancyProducer, true);
    }

    private void applyConfiguration(
            WebRelayConfig rawConfig,
            IDataProducerModule<?> occupancyProducer,
            boolean immediateReconcile
    ) {
        WebRelayConfig cfg = rawConfig != null ? rawConfig.copy() : new WebRelayConfig();
        this.activeConfig = cfg;

        EndpointSpec nextPrimary = null;
        if (cfg.enabled && !cfg.primaryHost.isBlank()) {
            try {
                nextPrimary = new EndpointSpec(cfg.primaryHost, cfg.primaryPort, cfg.username, cfg.password);
            } catch (IllegalArgumentException ex) {
                this.latestPrimaryState = RelayStateSnapshot.offline(ex.getMessage(), 0L);
            }
        } else if (cfg.enabled) {
            this.latestPrimaryState = RelayStateSnapshot.offline("Primary WebRelay host is not configured", 0L);
        }
        this.primaryEndpoint = nextPrimary;

        EndpointSpec nextAncillary = null;
        if (cfg.enabled && cfg.ancillaryEnabled && !cfg.ancillaryHost.isBlank()) {
            try {
                nextAncillary = new EndpointSpec(cfg.ancillaryHost, cfg.ancillaryPort, cfg.username, cfg.password);
            } catch (IllegalArgumentException ex) {
                this.latestAncillaryState = RelayStateSnapshot.offline(ex.getMessage(), 0L);
            }
        } else if (!cfg.ancillaryEnabled) {
            this.latestAncillaryState = RelayStateSnapshot.offline("Disabled", 0L);
        } else {
            this.latestAncillaryState = RelayStateSnapshot.offline("Ancillary WebRelay host is not configured", 0L);
        }
        this.ancillaryEndpoint = nextAncillary;

        updateOccupancySubscription(occupancyProducer);

        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (cfg.enabled) {
            int intervalSec = WebRelayConfig.clamp(
                    cfg.pollIntervalSeconds,
                    WebRelayConfig.MIN_POLL_INTERVAL_SECONDS,
                    WebRelayConfig.MAX_POLL_INTERVAL_SECONDS,
                    WebRelayConfig.DEFAULT_POLL_INTERVAL_SECONDS
            );
            long initialDelay = immediateReconcile ? 0L : intervalSec;
            pollTask = scheduler.scheduleWithFixedDelay(
                    this::safePollAndReconcile,
                    initialDelay,
                    intervalSec,
                    TimeUnit.SECONDS
            );
        }
    }

    private void updateOccupancySubscription(IDataProducerModule<?> occupancyProducer) {
        String nextUid = (occupancyProducer != null && activeConfig != null && activeConfig.enabled)
                ? occupancyProducer.getUniqueIdentifier()
                : null;
        if (Objects.equals(nextUid, subscribedProducerUid) && occupancySubscription != null) {
            return;
        }
        if (occupancySubscription != null) {
            occupancySubscription.cancel();
            occupancySubscription = null;
        }
        subscribedProducerUid = nextUid;
        if (nextUid == null || nextUid.isBlank() || hub == null || hub.getEventBus() == null) {
            return;
        }
        final IDataProducerModule<?> boundProducer = occupancyProducer;
        hub.getEventBus().newSubscription(ObsEvent.class)
                .withTopicID(EventUtils.getDataStreamDataTopicID(nextUid, OccupancyOutput.NAME))
                .subscribe(ev -> onOccupancyEvent(ev, boundProducer))
                .thenAccept(sub -> {
                    if (closed || !Objects.equals(nextUid, subscribedProducerUid)) {
                        sub.cancel();
                        return;
                    }
                    this.occupancySubscription = sub;
                    sub.request(Long.MAX_VALUE);
                });
    }

    public void onOccupancyEvent(ObsEvent event, IDataProducerModule<?> producer) {
        if (closed || event == null || event.getObservations() == null) {
            return;
        }
        WebRelayConfig cfg = this.activeConfig;
        if (cfg == null || !cfg.enabled) {
            return;
        }
        for (IObsData obs : event.getObservations()) {
            if (obs == null || obs.getResult() == null) {
                continue;
            }
            DataComponent struct = resolveRecordStructure(obs, producer);
            Occupancy occupancy = OccupancyExtended.readOccupancy(struct, obs.getResult());
            evaluateOccupancyForRelays(occupancy);
        }
    }

    public void evaluateOccupancyForRelays(Occupancy occupancy) {
        if (closed || occupancy == null) {
            return;
        }
        WebRelayConfig cfg = this.activeConfig;
        if (cfg == null || !cfg.enabled || cfg.mode != WebRelayOperatingMode.AUTO) {
            return;
        }
        boolean hasGamma = occupancy.hasGammaAlarm();
        boolean hasNeutron = occupancy.hasNeutronAlarm();
        if (!hasGamma && !hasNeutron) {
            return;
        }

        List<String> adjIds = occupancy.getAdjudicatedIds();
        boolean isEmlNonThreat = adjIds != null && (
                adjIds.contains(Adjudication.EML_NON_THREAT_MARKER)
                        || adjIds.contains("code:" + Adjudication.EML_NON_THREAT_CODE)
        );
        if (isEmlNonThreat) {
            suppressedEmlCount.incrementAndGet();
            recordAction("Suppressed lights & sound (Code 9999: EML Non-Threat)");
            return;
        }

        double spanSec = occupancy.getEndTime() - occupancy.getStartTime();
        double thresholdSec = laneSystem.getExtendedOccupancyThresholdMinutes() * 60.0;
        boolean isExtendedGamma = (adjIds != null && (
                adjIds.contains(Adjudication.EXTENDED_OCCUPANCY_GAMMA_MARKER)
                        || adjIds.contains("code:" + Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE)
        )) || (hasGamma && !hasNeutron && Double.isFinite(spanSec) && spanSec >= thresholdSec);

        if (isExtendedGamma && cfg.suppressExtendedOccupancyGamma) {
            suppressedExtendedOccupancyCount.incrementAndGet();
            recordAction("Suppressed lights & sound (Code 9998: Extended Occupancy Gamma Alarm)");
            return;
        }

        boolean driveGammaAndSound = hasGamma || (hasNeutron && cfg.soundOnNeutronAlarm);
        boolean driveNeutronLight = hasNeutron;
        int pulseSec = WebRelayConfig.clamp(
                cfg.alarmPulseSeconds,
                WebRelayConfig.MIN_ALARM_PULSE_SECONDS,
                WebRelayConfig.MAX_ALARM_PULSE_SECONDS,
                WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS
        );

        String category = (hasGamma && hasNeutron) ? "Gamma-Neutron" : (hasNeutron ? "Neutron" : "Gamma");
        if (!closed) {
            scheduler.execute(() -> executePulseInternal(
                    driveGammaAndSound,
                    driveNeutronLight,
                    pulseSec,
                    "Auto " + category + " alarm pulse (" + pulseSec + "s)",
                    true
            ));
        }
    }

    private DataComponent resolveRecordStructure(IObsData obs, IDataProducerModule<?> producer) {
        if (producer != null) {
            var occOut = producer.getOutputs().get(OccupancyOutput.NAME);
            if (occOut != null && occOut.getRecordDescription() != null) {
                return occOut.getRecordDescription();
            }
        }
        return null;
    }

    private void safePollAndReconcile() {
        if (closed) {
            return;
        }
        try {
            pollAndReconcileNow();
        } catch (Exception ex) {
            logger.debug("WebRelay background poll error on {}: {}",
                    laneSystem.getConfiguration() != null ? laneSystem.getConfiguration().name : "lane",
                    ex.getMessage());
        }
    }

    public ControllerStatusSnapshot pollAndReconcileNow() {
        synchronized (stateLock) {
            WebRelayConfig cfg = this.activeConfig;
            if (cfg == null || !cfg.enabled) {
                return getStatusSnapshot();
            }
            long nowMs = System.currentTimeMillis();
            boolean pulseInFlight = nowMs < activePulseUntilEpochMs;
            boolean latched = manualLatchGamma || manualLatchNeutron;

            EndpointSpec pri = this.primaryEndpoint;
            if (pri != null) {
                RelayStateSnapshot snap = client.readState(pri);
                if (snap.online() && !pulseInFlight) {
                    snap = reconcileEndpointStateIfNeeded(pri, snap, cfg.mode, latched);
                }
                this.latestPrimaryState = snap;
            }

            EndpointSpec anc = this.ancillaryEndpoint;
            if (cfg.ancillaryEnabled && anc != null) {
                RelayStateSnapshot snap = client.readState(anc);
                if (snap.online() && !pulseInFlight) {
                    snap = reconcileEndpointStateIfNeeded(anc, snap, cfg.mode, latched);
                }
                this.latestAncillaryState = snap;
            } else if (!cfg.ancillaryEnabled) {
                this.latestAncillaryState = RelayStateSnapshot.offline("Disabled", 0L);
            }
            return getStatusSnapshot();
        }
    }

    private RelayStateSnapshot reconcileEndpointStateIfNeeded(
            EndpointSpec endpoint,
            RelayStateSnapshot current,
            WebRelayOperatingMode mode,
            boolean latched
    ) {
        if (latched) {
            int expR3 = manualLatchGamma ? 1 : 0;
            int expR4 = manualLatchNeutron ? 1 : 0;
            if (current.relay1() != 1 || current.relay2() != 1 || current.relay3() != expR3 || current.relay4() != expR4) {
                return client.latchIndicators(endpoint, manualLatchGamma, manualLatchNeutron);
            }
            return current;
        }
        if (mode == WebRelayOperatingMode.PASS_THROUGH) {
            if (!current.isPassThrough()) {
                return client.setPassThrough(endpoint);
            }
            return current;
        }
        // AUTO or MUTED idle state: Relay 1 & Relay 2 ON (intercept armed), Relay 3 & Relay 4 OFF
        if (current.relay1() != 1 || current.relay2() != 1 || current.relay3() != 0 || current.relay4() != 0) {
            return client.setArmedIntercept(endpoint);
        }
        return current;
    }

    public ControllerStatusSnapshot pulseGammaAndSound(Integer overrideDurationSeconds) {
        int sec = resolvePulseSeconds(overrideDurationSeconds);
        return executePulseInternal(true, false, sec, "Manual Gamma Light & Sound pulse (" + sec + "s)", false);
    }

    public ControllerStatusSnapshot pulseNeutronLight(Integer overrideDurationSeconds) {
        int sec = resolvePulseSeconds(overrideDurationSeconds);
        return executePulseInternal(false, true, sec, "Manual Neutron Light pulse (" + sec + "s)", false);
    }

    public ControllerStatusSnapshot pulseAllIndicators(Integer overrideDurationSeconds) {
        int sec = resolvePulseSeconds(overrideDurationSeconds);
        return executePulseInternal(true, true, sec, "Manual Gamma + Neutron Lights & Sound pulse (" + sec + "s)", false);
    }

    private ControllerStatusSnapshot executePulseInternal(
            boolean gammaAndSound,
            boolean neutronLight,
            int pulseSeconds,
            String summary,
            boolean countAsAutoAlarm
    ) {
        synchronized (stateLock) {
            this.manualLatchGamma = false;
            this.manualLatchNeutron = false;
            this.activePulseUntilEpochMs = System.currentTimeMillis() + (pulseSeconds * 1000L) + 500L;

            EndpointSpec pri = this.primaryEndpoint;
            if (pri != null) {
                this.latestPrimaryState = client.pulseIndicators(pri, gammaAndSound, neutronLight, pulseSeconds);
            }
            EndpointSpec anc = this.ancillaryEndpoint;
            if (activeConfig != null && activeConfig.ancillaryEnabled && anc != null) {
                this.latestAncillaryState = client.pulseIndicators(anc, gammaAndSound, neutronLight, pulseSeconds);
            }
            if (countAsAutoAlarm) {
                triggeredAlarmCount.incrementAndGet();
            }
            recordAction(summary);
            return getStatusSnapshot();
        }
    }

    public ControllerStatusSnapshot latchIndicators(boolean gammaAndSoundOn, boolean neutronLightOn) {
        synchronized (stateLock) {
            this.manualLatchGamma = gammaAndSoundOn;
            this.manualLatchNeutron = neutronLightOn;
            this.activePulseUntilEpochMs = 0L;

            EndpointSpec pri = this.primaryEndpoint;
            if (pri != null) {
                this.latestPrimaryState = client.latchIndicators(pri, gammaAndSoundOn, neutronLightOn);
            }
            EndpointSpec anc = this.ancillaryEndpoint;
            if (activeConfig != null && activeConfig.ancillaryEnabled && anc != null) {
                this.latestAncillaryState = client.latchIndicators(anc, gammaAndSoundOn, neutronLightOn);
            }
            String desc = (!gammaAndSoundOn && !neutronLightOn)
                    ? "Silenced indicators (Armed intercept)"
                    : "Latched ON: " + (gammaAndSoundOn && neutronLightOn
                        ? "Gamma + Neutron + Sound"
                        : (gammaAndSoundOn ? "Gamma Light & Sound" : "Neutron Light"));
            recordAction(desc);
            return getStatusSnapshot();
        }
    }

    public ControllerStatusSnapshot silenceAndArm() {
        synchronized (stateLock) {
            this.manualLatchGamma = false;
            this.manualLatchNeutron = false;
            this.activePulseUntilEpochMs = 0L;

            EndpointSpec pri = this.primaryEndpoint;
            if (pri != null) {
                this.latestPrimaryState = client.setArmedIntercept(pri);
            }
            EndpointSpec anc = this.ancillaryEndpoint;
            if (activeConfig != null && activeConfig.ancillaryEnabled && anc != null) {
                this.latestAncillaryState = client.setArmedIntercept(anc);
            }
            recordAction("Silenced & armed OSCAR intercept (Relays 1 & 2 ON, 3 & 4 OFF)");
            return getStatusSnapshot();
        }
    }

    public ControllerStatusSnapshot releaseToPassThrough() {
        synchronized (stateLock) {
            this.manualLatchGamma = false;
            this.manualLatchNeutron = false;
            this.activePulseUntilEpochMs = 0L;

            EndpointSpec pri = this.primaryEndpoint;
            if (pri != null) {
                this.latestPrimaryState = client.setPassThrough(pri);
            }
            EndpointSpec anc = this.ancillaryEndpoint;
            if (activeConfig != null && activeConfig.ancillaryEnabled && anc != null) {
                this.latestAncillaryState = client.setPassThrough(anc);
            }
            recordAction("Released to native RPM hardware pass-through (Relays 1-4 OFF)");
            return getStatusSnapshot();
        }
    }

    public ControllerStatusSnapshot setOperatingMode(WebRelayOperatingMode newMode) {
        Objects.requireNonNull(newMode, "newMode");
        synchronized (stateLock) {
            if (laneSystem.getConfiguration() != null && laneSystem.getConfiguration().webRelayConfig != null) {
                laneSystem.getConfiguration().webRelayConfig.mode = newMode;
            }
            if (this.activeConfig != null) {
                this.activeConfig.mode = newMode;
            }
            this.manualLatchGamma = false;
            this.manualLatchNeutron = false;
            this.activePulseUntilEpochMs = 0L;
            if (newMode == WebRelayOperatingMode.PASS_THROUGH) {
                return releaseToPassThrough();
            }
            ControllerStatusSnapshot snap = silenceAndArm();
            recordAction("Set operating mode to " + newMode.name());
            return snap;
        }
    }

    private int resolvePulseSeconds(Integer overrideSec) {
        if (overrideSec != null && overrideSec > 0) {
            return WebRelayConfig.clamp(
                    overrideSec,
                    WebRelayConfig.MIN_ALARM_PULSE_SECONDS,
                    WebRelayConfig.MAX_ALARM_PULSE_SECONDS,
                    WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS
            );
        }
        WebRelayConfig cfg = this.activeConfig;
        return cfg != null
                ? WebRelayConfig.clamp(
                        cfg.alarmPulseSeconds,
                        WebRelayConfig.MIN_ALARM_PULSE_SECONDS,
                        WebRelayConfig.MAX_ALARM_PULSE_SECONDS,
                        WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS)
                : WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS;
    }

    private void recordAction(String summary) {
        this.lastActionSummary = summary;
        this.lastActionAt = Instant.now().toString();
    }

    public ControllerStatusSnapshot getStatusSnapshot() {
        WebRelayConfig cfg = this.activeConfig != null ? this.activeConfig : new WebRelayConfig();
        return new ControllerStatusSnapshot(
                cfg.enabled,
                cfg.mode != null ? cfg.mode : WebRelayOperatingMode.AUTO,
                cfg.ancillaryEnabled,
                manualLatchGamma || manualLatchNeutron,
                latestPrimaryState,
                latestAncillaryState,
                triggeredAlarmCount.get(),
                suppressedEmlCount.get(),
                suppressedExtendedOccupancyCount.get(),
                lastActionSummary,
                lastActionAt
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (occupancySubscription != null) {
            occupancySubscription.cancel();
            occupancySubscription = null;
        }
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        scheduler.shutdownNow();

        // Fail-safe release on shutdown so native RPM hardware alarm relays work while OSCAR is stopped
        EndpointSpec pri = this.primaryEndpoint;
        EndpointSpec anc = this.ancillaryEndpoint;
        WebRelayConfig cfg = this.activeConfig;
        if (cfg != null && cfg.enabled && (pri != null || (cfg.ancillaryEnabled && anc != null))) {
            try {
                WebRelayQuadClient fastShutdownClient = new WebRelayQuadClient(SHUTDOWN_RELEASE_TIMEOUT, SHUTDOWN_RELEASE_TIMEOUT);
                if (pri != null && latestPrimaryState.online()) {
                    fastShutdownClient.setPassThrough(pri);
                }
                if (cfg.ancillaryEnabled && anc != null && latestAncillaryState.online()) {
                    fastShutdownClient.setPassThrough(anc);
                }
            } catch (Exception ignored) {
                // Never block lane shutdown on unreachable hardware
            }
        }
    }
}
