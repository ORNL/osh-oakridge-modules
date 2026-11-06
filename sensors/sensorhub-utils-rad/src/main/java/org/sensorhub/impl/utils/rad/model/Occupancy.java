package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.data.DataArrayImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class Occupancy {

    private double samplingTime;
    private int occupancyCount;
    private double startTime;
    private double endTime;
    private double neutronBackground;
    private boolean hasGammaAlarm;
    private boolean hasNeutronAlarm;
    private int maxGammaCount;
    private int maxNeutronCount;
    private List<String> adjudicatedIds = new ArrayList<>();
    private List<String> videoPaths = new ArrayList<>();
    private List<String> webIdObsIds = new ArrayList<>();

    public int getOccupancyCount() {
        return occupancyCount;
    }

    public double getStartTime() {
        return startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public double getNeutronBackground() {
        return neutronBackground;
    }

    public boolean hasGammaAlarm() {
        return hasGammaAlarm;
    }

    public boolean hasNeutronAlarm() {
        return hasNeutronAlarm;
    }

    public int getMaxGammaCount() {
        return maxGammaCount;
    }

    public int getMaxNeutronCount() {
        return maxNeutronCount;
    }

    public List<String> getAdjudicatedIds() {
        return adjudicatedIds;
    }

    public List<String> getVideoPaths() {
        return videoPaths;
    }

    public List<String> getWebIdObsIds() {
        return webIdObsIds;
    }

    public double getSamplingTime() {
        return samplingTime;
    }

    public static class Builder {

        protected Occupancy instance;

        public Builder() {
            this.instance = new Occupancy();
        }

        public Builder samplingTime(double samplingTime) {
            instance.samplingTime = samplingTime;
            return this;
        }

        public Builder occupancyCount(int occupancyCount) {
            instance.occupancyCount = occupancyCount;
            return this;
        }

        public Builder startTime(double startTime) {
            instance.startTime = startTime;
            return this;
        }

        public Builder endTime(double endTime) {
            instance.endTime = endTime;
            return this;
        }

        public Builder neutronBackground(double neutronBackground) {
            instance.neutronBackground = neutronBackground;
            return this;
        }

        public Builder gammaAlarm(boolean hasGammaAlarm) {
            instance.hasGammaAlarm = hasGammaAlarm;
            return this;
        }

        public Builder neutronAlarm(boolean hasNeutronAlarm) {
            instance.hasNeutronAlarm = hasNeutronAlarm;
            return this;
        }

        public Builder maxGammaCount(int maxGammaCount) {
            instance.maxGammaCount = maxGammaCount;
            return this;
        }

        public Builder maxNeutronCount(int maxNeutronCount) {
            instance.maxNeutronCount = maxNeutronCount;
            return this;
        }

        public Builder adjudicatedIds(List<String> adjudicatedIds) {
            instance.adjudicatedIds = adjudicatedIds == null ? new ArrayList<>() : new ArrayList<>(adjudicatedIds);
            return this;
        }

        public Builder videoPaths(List<String> videoPaths) {
            instance.videoPaths = videoPaths == null ? new ArrayList<>() : new ArrayList<>(videoPaths);
            return this;
        }

        public Builder webIdObsIds(List<String> webIdObsIds) {
            instance.webIdObsIds = webIdObsIds == null ? new ArrayList<>() : new ArrayList<>(webIdObsIds);
            return this;
        }

        public Occupancy build() {
            return instance;
        }

    }

    private List<String> mutableAdjudicatedIds() {
        if (this.adjudicatedIds == null) {
            this.adjudicatedIds = new ArrayList<>();
        } else if (!(this.adjudicatedIds instanceof ArrayList)) {
            this.adjudicatedIds = new ArrayList<>(this.adjudicatedIds);
        }
        return this.adjudicatedIds;
    }

    public void addAdjudicatedId(String adjudicatedId) {
        mutableAdjudicatedIds().add(adjudicatedId);
    }

    public boolean hasAdjudicationCodeMarker() {
        return adjudicatedIds != null && adjudicatedIds.stream()
                .anyMatch(id -> Adjudication.parseAdjudicationCodeMarker(id).isPresent());
    }

    public OptionalInt getLatestAdjudicationCode() {
        if (adjudicatedIds == null)
            return OptionalInt.empty();
        for (int i = adjudicatedIds.size() - 1; i >= 0; i--) {
            OptionalInt parsed = Adjudication.parseAdjudicationCodeMarker(adjudicatedIds.get(i));
            if (parsed.isPresent())
                return parsed;
        }
        return OptionalInt.empty();
    }

    /**
     * Records the effective adjudication code marker ({@code "code:<n>"}) on this
     * occupancy, replacing any prior {@code "code:*"} marker so equality and
     * inequality CQL predicates stay mutually exclusive across re-adjudications.
     *
     * @return {@code true} if {@code adjudicatedIds} was modified
     */
    public boolean recordAdjudicationCode(int code) {
        if (code < 0)
            return false;
        List<String> ids = mutableAdjudicatedIds();
        String marker = Adjudication.adjudicationCodeMarker(code);
        long existingCodeMarkers = ids.stream()
                .filter(id -> Adjudication.parseAdjudicationCodeMarker(id).isPresent())
                .count();
        if (existingCodeMarkers == 1L && !ids.isEmpty() && marker.equals(ids.get(ids.size() - 1))) {
            return false;
        }
        ids.removeIf(id -> Adjudication.parseAdjudicationCodeMarker(id).isPresent());
        ids.add(marker);
        return true;
    }

    public void addVideoPath(String videoPath) {
        this.videoPaths.add(videoPath);
    }

    public void addWebIdObsId(String webIdObsId) {
        this.webIdObsIds.add(webIdObsId);
    }

    public static DataBlock fromOccupancy(Occupancy occupancy) {
        OccupancyOutput<?> output = new OccupancyOutput<>(new SensorSystem());
        DataComponent resultStructure = output.getRecordDescription();
        DataBlock dataBlock = resultStructure.createDataBlock();
        dataBlock.updateAtomCount();
        resultStructure.setData(dataBlock);

        int index = 0;

        dataBlock.setDoubleValue(index++, occupancy.getSamplingTime());
        dataBlock.setIntValue(index++, occupancy.getOccupancyCount());
        dataBlock.setDoubleValue(index++, occupancy.getStartTime());
        dataBlock.setDoubleValue(index++, occupancy.getEndTime());
        dataBlock.setDoubleValue(index++, occupancy.getNeutronBackground());
        dataBlock.setBooleanValue(index++, occupancy.hasGammaAlarm());
        dataBlock.setBooleanValue(index++, occupancy.hasNeutronAlarm());
        dataBlock.setIntValue(index++, occupancy.getMaxGammaCount());
        dataBlock.setIntValue(index++, occupancy.getMaxNeutronCount());

        int cmdIdsCount = occupancy.getAdjudicatedIds().size();
        dataBlock.setDoubleValue(index++, cmdIdsCount);

        var adjIdsArray = ((DataArrayImpl) resultStructure.getComponent("adjudicatedIds"));

        if (cmdIdsCount > 0) {
            adjIdsArray.updateSize();
            dataBlock.updateAtomCount();

            for (int i = 0; i < occupancy.getAdjudicatedIds().size(); i++) {
                dataBlock.setStringValue(index++, occupancy.getAdjudicatedIds().get(i));
            }
        }

        int filePathsCount = occupancy.getVideoPaths().size();
        dataBlock.setDoubleValue(index++, filePathsCount);

        var filePathsArray = ((DataArrayImpl) resultStructure.getComponent("videoPaths"));

        if (filePathsCount > 0) {
            filePathsArray.updateSize();
            dataBlock.updateAtomCount();

            for (int i = 0; i < occupancy.getVideoPaths().size(); i++) {
                dataBlock.setStringValue(index++, occupancy.getVideoPaths().get(i));
            }
        }

        int webIdObsIdsCount = occupancy.getWebIdObsIds().size();
        dataBlock.setDoubleValue(index++, webIdObsIdsCount);

        var webIdObsIdsArray = ((DataArrayImpl) resultStructure.getComponent("webIdObsIds"));

        if (webIdObsIdsCount > 0) {
            webIdObsIdsArray.updateSize();
            dataBlock.updateAtomCount();

            for (int i = 0; i < occupancy.getWebIdObsIds().size(); i++) {
                dataBlock.setStringValue(index++, occupancy.getWebIdObsIds().get(i));
            }
        }

        return dataBlock;
    }

    public static Occupancy toOccupancy(DataBlock dataBlock) {
        int index = 0;

        var samplingTime = dataBlock.getDoubleValue(index++);
        var occupancyCount = dataBlock.getIntValue(index++);
        var startTime = dataBlock.getDoubleValue(index++);
        var endTime = dataBlock.getDoubleValue(index++);
        var neutronBackground = dataBlock.getDoubleValue(index++);
        var gammaAlarm = dataBlock.getBooleanValue(index++);
        var neutronAlarm = dataBlock.getBooleanValue(index++);
        var maxGammaCount = dataBlock.getIntValue(index++);
        var maxNeutronCount = dataBlock.getIntValue(index++);
        var cmdIdsCount = dataBlock.getIntValue(index++);

        List<String> cmdIds = new ArrayList<>();
        for (int i = 0; i < cmdIdsCount; i++)
            cmdIds.add(dataBlock.getStringValue(index++));

        var videoPathCount = dataBlock.getIntValue(index++);

        List<String> videoPaths = new ArrayList<>();
        for (int i = 0; i < videoPathCount; i++)
            videoPaths.add(dataBlock.getStringValue(index++));

        List<String> webIdObsIds = new ArrayList<>();
        try {
            var webIdObsIdsCount = dataBlock.getIntValue(index++);
            for (int i = 0; i < webIdObsIdsCount; i++)
                webIdObsIds.add(dataBlock.getStringValue(index++));
        } catch (Exception ignored) {
            // backwards compatibility: old observations may not have webIdObsIds fields
        }

        return new Builder()
                .samplingTime(samplingTime)
                .occupancyCount(occupancyCount)
                .startTime(startTime)
                .endTime(endTime)
                .neutronBackground(neutronBackground)
                .maxGammaCount(maxGammaCount)
                .maxNeutronCount(maxNeutronCount)
                .gammaAlarm(gammaAlarm)
                .neutronAlarm(neutronAlarm)
                .adjudicatedIds(cmdIds)
                .videoPaths(videoPaths)
                .webIdObsIds(webIdObsIds)
                .build();
    }
}
