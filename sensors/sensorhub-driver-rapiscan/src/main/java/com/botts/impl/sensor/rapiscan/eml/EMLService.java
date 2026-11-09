package com.botts.impl.sensor.rapiscan.eml;

import com.botts.impl.sensor.rapiscan.RapiscanSensor;
import com.botts.impl.sensor.rapiscan.RepetitiveWarningFilter;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.EmlLaneParameters;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeRegistry;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class EMLService implements IEventListener, AutoCloseable {

    private static final Object ERNIE_INITIALIZATION_LOCK = new Object();

    private final RapiscanSensor parentSensor;
    private final EmlRuntimeRegistry runtimeRegistry;
    private final Consumer<EmlRuntimeStatus> statusListener;
    private final List<String> scanDataList;
    private String latestGammaBackground;
    private String latestNeutronBackground;
    private volatile EmlLaneParameters laneParameters;

    public EMLService(RapiscanSensor parentSensor) {
        this.parentSensor = parentSensor;
        this.runtimeRegistry = EmlRuntimeRegistry.forHub(parentSensor.getParentHub());
        this.scanDataList = new ArrayList<>();
        this.laneParameters = initializeErnie(() -> buildLaneParameters(
                parentSensor.getConfiguration().setupGammaConfig.intervals,
                parentSensor.getConfiguration().setupGammaConfig.occupancyHoldin));

        this.statusListener = status -> {
            if (status.loaded()) {
                parentSensor.getLogger().info("EML runtime activated on lane {} ({} {})",
                        parentSensor.getConfiguration().name,
                        status.fileName(),
                        status.collimatedModelId());
            }
        };
        this.runtimeRegistry.addStatusListener(this.statusListener);

        if (!runtimeRegistry.isLoaded()) {
            parentSensor.getLogger().info(
                    "EML enabled on lane {}; awaiting EML plugin upload in OSCAR Service",
                    parentSensor.getConfiguration().name);
        }
    }

    private EmlLaneParameters buildLaneParameters(int intervals, int holdin) {
        var cfg = parentSensor.getConfiguration();
        boolean collimated = cfg.emlConfig == null || cfg.emlConfig.isCollimated;
        double width = cfg.emlConfig != null ? cfg.emlConfig.laneWidth : 4.82;
        return new EmlLaneParameters(cfg.name, cfg.laneID, collimated, width, intervals, holdin);
    }

    public void updateErnieSetup(int intervals, int holdin) {
        this.laneParameters = initializeErnie(() -> buildLaneParameters(intervals, holdin));
    }

    public EmlLaneParameters getLaneParameters() {
        return this.laneParameters;
    }

    static <T> T initializeErnie(Supplier<T> initializer) {
        synchronized (ERNIE_INITIALIZATION_LOCK) {
            return initializer.get();
        }
    }

    public synchronized void setLatestGammaBackground(String[] scanData) {
        this.latestGammaBackground = String.join(",", scanData);
    }

    public synchronized void setLatestNeutronBackground(String[] scanData) {
        this.latestNeutronBackground = String.join(",", scanData);
    }

    public synchronized void addScanDataLine(String[] scanData) {
        this.scanDataList.add(String.join(",", scanData));
    }

    public Optional<EmlEvaluationResult> processCurrentOccupancy() {
        List<String> snapshot;
        EmlLaneParameters params = this.laneParameters;

        synchronized (this) {
            if (this.scanDataList.isEmpty()) {
                return Optional.empty();
            }
            snapshot = new ArrayList<>(this.scanDataList.size() + 2);
            if (this.latestNeutronBackground != null)
                snapshot.add(this.latestNeutronBackground);
            if (this.latestGammaBackground != null)
                snapshot.add(this.latestGammaBackground);
            snapshot.addAll(this.scanDataList);
            this.scanDataList.clear();
        }

        if (!runtimeRegistry.isLoaded()) {
            return Optional.empty();
        }

        try {
            RepetitiveWarningFilter.install();
            Stream<String> stream = snapshot.stream();
            return Optional.ofNullable(runtimeRegistry.evaluate(params, stream));
        } catch (Exception e) {
            parentSensor.getLogger().warn("EML occupancy evaluation failed on lane {}: {}",
                    parentSensor.getConfiguration().name, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public synchronized void clearOccupancyList() {
        this.scanDataList.clear();
    }

    @Override
    public void handleEvent(Event e) {
        if (e instanceof DataEvent dataEvent) {
            int intervals = dataEvent.getSource().getLatestRecord().getIntValue(3);
            int holdin = dataEvent.getSource().getLatestRecord().getIntValue(4);
            updateErnieSetup(intervals, holdin);
        }
    }

    @Override
    public void close() {
        runtimeRegistry.removeStatusListener(statusListener);
        clearOccupancyList();
    }
}
