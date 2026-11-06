package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.data.DataArrayImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class Adjudication {

    /** Reserved automated disposition code for EML Non-Threat releases. */
    public static final int EML_NON_THREAT_CODE = 9999;

    /** Marker stored in Occupancy.adjudicatedIds prior to publication for zero-flash Dashboard suppression and CQL filtering. */
    public static final String EML_NON_THREAT_MARKER = "eml-non-threat";

    /** Prefix for synthetic adjudication code markers stored in Occupancy.adjudicatedIds (e.g. "code:4") for indexed CQL filtering. */
    public static final String ADJUDICATION_CODE_MARKER_PREFIX = "code:";

    /** Sender/username recorded on automated EML Non-Threat adjudication commands. */
    public static final String EML_AUTO_ADJUDICATOR_USERNAME = "EML";

    /** Prefix separating human-readable adjudication summary text from structured EML JSON metadata in feedback. */
    public static final String EML_METADATA_PREFIX = "EML_METADATA:";

    public static String adjudicationCodeMarker(int code) {
        if (code < 0)
            throw new IllegalArgumentException("Adjudication code must be non-negative: " + code);
        return ADJUDICATION_CODE_MARKER_PREFIX + code;
    }

    public static OptionalInt parseAdjudicationCodeMarker(String value) {
        if (value == null)
            return OptionalInt.empty();
        String trimmed = value.trim();
        if (!trimmed.startsWith(ADJUDICATION_CODE_MARKER_PREFIX))
            return OptionalInt.empty();
        String digits = trimmed.substring(ADJUDICATION_CODE_MARKER_PREFIX.length());
        if (digits.isEmpty())
            return OptionalInt.empty();
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i)))
                return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(digits);
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private String feedback;
    private int adjudicationCode;
    private List<String> isotopes;
    private SecondaryInspectionStatus secondaryInspectionStatus;
    private List<String> filePaths;
    private String occupancyObsId;
    private String vehicleId;


    public enum SecondaryInspectionStatus {
        NONE, REQUESTED, COMPLETED
    }

    public static class Builder {

        Adjudication instance;

        public Builder() {
            this.instance = new Adjudication();
        }

        public Builder feedback(String feedback) {
            instance.feedback = feedback;
            return this;
        }

        public Builder adjudicationCode(int code) {
            instance.adjudicationCode = code;
            return this;
        }

        public Builder isotopes(List<String> isotopes) {
            instance.isotopes = isotopes;
            return this;
        }

        public Builder filePaths(List<String> filePaths) {
            instance.filePaths = filePaths;
            return this;
        }

        public Builder occupancyObsId(String occupancyObsId) {
            instance.occupancyObsId = occupancyObsId;
            return this;
        }

        public Builder vehicleId(String vehicleId) {
            instance.vehicleId = vehicleId;
            return this;
        }

        public Builder secondaryInspectionStatus(SecondaryInspectionStatus secondaryInspectionStatus) {
            instance.secondaryInspectionStatus = secondaryInspectionStatus;
            return this;
        }

        public Adjudication build() {
            return instance;
        }
    }

    public String getFeedback() {
        return feedback;
    }

    public int getAdjudicationCode() {
        return adjudicationCode;
    }

    public List<String> getIsotopes() {
        return isotopes;
    }

    public SecondaryInspectionStatus getSecondaryInspectionStatus() {
        return secondaryInspectionStatus;
    }

    public List<String> getFilePaths() {
        return filePaths;
    }

    public String getOccupancyObsId() {
        return occupancyObsId;
    }

    public String getVehicleId() {
        return vehicleId;
    }


    public static DataBlock fromAdjudication(Adjudication adjudication) {
        RADHelper radHelper = new RADHelper();
        DataComponent resultStructure = radHelper.createAdjudicationRecord();
        DataBlock dataBlock = resultStructure.createDataBlock();
        resultStructure.setData(dataBlock);

        int index = 0;

        dataBlock.setStringValue(index++, adjudication.getFeedback());
        dataBlock.setIntValue(index++, adjudication.getAdjudicationCode());

        var isotopeCount = adjudication.getIsotopes().size();
        dataBlock.setIntValue(index++, isotopeCount);

        var isotopesArray = ((DataArrayImpl) resultStructure.getComponent("isotopes"));
        isotopesArray.updateSize();
        dataBlock.updateAtomCount();

        for (int i = 0; i < isotopeCount; i++ )
            dataBlock.setStringValue(index++, adjudication.getIsotopes().get(i));
        
        dataBlock.setStringValue(index++, adjudication.getSecondaryInspectionStatus().toString());

        var filesCount = adjudication.getFilePaths().size();
        dataBlock.setIntValue(index++, filesCount);

        var filesArray = ((DataArrayImpl) resultStructure.getComponent("filePaths"));
        filesArray.updateSize();
        dataBlock.updateAtomCount();

        for (int i = 0; i < filesCount; i++ )
            dataBlock.setStringValue(index++, adjudication.getFilePaths().get(i));
        
        dataBlock.setStringValue(index++, adjudication.getOccupancyObsId());
        dataBlock.setStringValue(index, adjudication.getVehicleId());

        return dataBlock;
    }


    public static Adjudication toAdjudication(DataBlock dataBlock) {
        int index = 0;

        var feedback = dataBlock.getStringValue(index++);
        var adjudicationCode = dataBlock.getIntValue(index++);
        var isotopeCount = dataBlock.getIntValue(index++);

        List<String> isotopes = new ArrayList<>();
        for (int i = 0; i < isotopeCount; i++)
            isotopes.add(dataBlock.getStringValue(index++));

        var secondaryInspectionStatus = SecondaryInspectionStatus.valueOf(dataBlock.getStringValue(index++));
        var filePathCount = dataBlock.getIntValue(index++);

        List<String> filePaths = new ArrayList<>();
        for (int i = 0; i < filePathCount; i++)
            filePaths.add(dataBlock.getStringValue(index++));

        var occupancyObsId = dataBlock.getStringValue(index++);
        var vehicleId = dataBlock.getStringValue(index);

        return new Builder()
                .feedback(feedback)
                .adjudicationCode(adjudicationCode)
                .isotopes(isotopes)
                .secondaryInspectionStatus(secondaryInspectionStatus)
                .filePaths(filePaths)
                .occupancyObsId(occupancyObsId)
                .vehicleId(vehicleId)
                .build();
    }
}
