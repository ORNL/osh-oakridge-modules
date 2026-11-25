/*******************************************************************************
 * Extension of {@link Occupancy} that carries an additional raw alarm-category
 * string and optional GPS fix coordinates reported by spectroscopic/mobile RPM
 * drivers. Lives in sensorhub-utils-rad so that downstream consumers in
 * sensorhub-utils-rad and sensorhub-system-lane (WebIdAnalyzer, WebIdHelper,
 * AdjudicationControl) can read/write extended-schema occupancy datablocks
 * without taking a dependency on any specific occupancy processing module.
 ******************************************************************************/
package org.sensorhub.impl.utils.rad.model;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.Text;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.data.DataArrayImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Occupancy variant that carries the raw alarm category string reported by the
 * driver (e.g. "Gamma", "Neutron", "Gamma-Neutron", "Alpha", "Isotope", or any
 * vendor-specific value) and optional WGS84 latitude/longitude coordinates for
 * mobile detector deployments.
 *
 * <p>Schema compatibility across upgrades:
 * <ul>
 *   <li>Base {@link Occupancy}: 16 fields (no {@code alarmCategoryCode}).</li>
 *   <li>Extended v1 (legacy RS-350): 17 fields (adds {@code alarmCategoryCode},
 *       no {@code latitude}/{@code longitude}).</li>
 *   <li>Extended v2 (current RS-350 &amp; Kromek D5): 19 fields (adds
 *       {@code alarmCategoryCode}, {@code latitude}, and {@code longitude}).</li>
 * </ul>
 *
 * <p>{@link #readOccupancy(DataComponent, DataBlock)} and
 * {@link #writeOccupancy(DataComponent, Occupancy)} inspect the datastream's
 * persisted {@link DataComponent} record structure so pre-upgrade v1 streams
 * continue receiving 17-atom datablocks while v2 streams receive 19-atom
 * datablocks.
 */
public class OccupancyExtended extends Occupancy {

    public static final String ALARM_CATEGORY_FIELD_NAME = "alarmCategoryCode";
    public static final String LATITUDE_FIELD_NAME = "latitude";
    public static final String LONGITUDE_FIELD_NAME = "longitude";

    protected String alarmCategory = "";
    protected double latitude = 0.0;
    protected double longitude = 0.0;

    public String getAlarmCategory() {
        return alarmCategory;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public boolean hasLocation() {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && (latitude != 0.0 || longitude != 0.0);
    }

    public static class Builder extends Occupancy.Builder {

        public Builder() {
            instance = new OccupancyExtended();
        }

        @Override
        public OccupancyExtended build() {
            return (OccupancyExtended) instance;
        }

        /**
         * Sets the raw alarm category string. Returns {@link Builder} (which is
         * also an {@link Occupancy.Builder}) so callers can chain location and
         * base occupancy setters fluently.
         */
        public Builder alarmCategory(String alarmCategory) {
            ((OccupancyExtended) instance).alarmCategory = alarmCategory == null ? "" : alarmCategory;
            return this;
        }

        public Builder location(double latitude, double longitude) {
            ((OccupancyExtended) instance).latitude = Double.isFinite(latitude) ? latitude : 0.0;
            ((OccupancyExtended) instance).longitude = Double.isFinite(longitude) ? longitude : 0.0;
            return this;
        }
    }

    /**
     * Build the legacy v1 extended DataRecord (base {@link OccupancyOutput}
     * fields plus {@code alarmCategoryCode}, without {@code latitude} or
     * {@code longitude}). Used when serializing observations back into a
     * pre-upgrade v1 extended datastream.
     */
    public static DataRecord createLegacyV1ExtendedRecordStructure() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        OccupancyOutput base = new OccupancyOutput(new SensorSystem());
        DataRecord baseRecord = (DataRecord) base.getRecordDescription();

        RADHelper radHelper = new RADHelper();
        DataRecord extended = radHelper.createRecord()
                .name(baseRecord.getName())
                .label(baseRecord.getLabel())
                .updatable(true)
                .definition(baseRecord.getDefinition())
                .description(baseRecord.getDescription())
                .build();

        for (int i = 0; i < baseRecord.getComponentCount(); i++) {
            DataComponent child = baseRecord.getComponent(i).copy();
            extended.addComponent(child.getName(), child);
        }

        Text alarmCategoryCode = radHelper.createText()
                .name(ALARM_CATEGORY_FIELD_NAME)
                .label("Alarm Category Code")
                .definition(RADHelper.getRadUri("AlarmCategoryCode"))
                .description("Raw alarm category string reported by the RPM driver")
                .build();
        extended.addComponent(ALARM_CATEGORY_FIELD_NAME, alarmCategoryCode);

        return extended;
    }

    /**
     * Build the v2 extended DataRecord matching base {@link OccupancyOutput}
     * plus {@code alarmCategoryCode}, {@code latitude}, and {@code longitude}.
     */
    public static DataRecord createExtendedRecordStructure() {
        DataRecord extended = createLegacyV1ExtendedRecordStructure();
        RADHelper radHelper = new RADHelper();

        Quantity latField = radHelper.createQuantity()
                .name(LATITUDE_FIELD_NAME)
                .label("Latitude")
                .definition(RADHelper.getRadUri("AlarmLatitude"))
                .description("Latitude of detector at occupancy alarm time (0 if unavailable)")
                .uom("deg")
                .build();
        extended.addComponent(LATITUDE_FIELD_NAME, latField);

        Quantity lonField = radHelper.createQuantity()
                .name(LONGITUDE_FIELD_NAME)
                .label("Longitude")
                .definition(RADHelper.getRadUri("AlarmLongitude"))
                .description("Longitude of detector at occupancy alarm time (0 if unavailable)")
                .uom("deg")
                .build();
        extended.addComponent(LONGITUDE_FIELD_NAME, lonField);

        return extended;
    }

    private static DataComponent safeGetComponent(DataComponent recordStructure, String fieldName) {
        if (recordStructure == null || fieldName == null) {
            return null;
        }
        try {
            return recordStructure.getComponent(fieldName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true if the supplied datastream record description carries the
     * extended {@code alarmCategoryCode} field (either v1 17-field or v2 19-field).
     */
    public static boolean isExtendedSchema(DataComponent recordStructure) {
        return safeGetComponent(recordStructure, ALARM_CATEGORY_FIELD_NAME) != null;
    }

    /**
     * Returns true if the supplied datastream record description carries the v2
     * {@code latitude} and {@code longitude} fields.
     */
    public static boolean hasLocationFields(DataComponent recordStructure) {
        return safeGetComponent(recordStructure, LATITUDE_FIELD_NAME) != null
                && safeGetComponent(recordStructure, LONGITUDE_FIELD_NAME) != null;
    }

    /**
     * Schema-aware reader: returns an {@link OccupancyExtended} when the record
     * has the extended {@code alarmCategoryCode} field (handling both 17-field
     * v1 and 19-field v2 schemas cleanly), otherwise a plain {@link Occupancy}.
     */
    public static Occupancy readOccupancy(DataComponent recordStructure, DataBlock dataBlock) {
        if (isExtendedSchema(recordStructure)) {
            return toOccupancy(recordStructure, dataBlock);
        }
        return Occupancy.toOccupancy(dataBlock);
    }

    /**
     * Schema-aware writer: serializes to a datablock whose atom layout matches
     * the supplied record structure (16-field base, 17-field legacy v1 extended,
     * or 19-field v2 extended).
     */
    public static DataBlock writeOccupancy(DataComponent recordStructure, Occupancy occupancy) {
        if (isExtendedSchema(recordStructure)) {
            OccupancyExtended extended;
            if (occupancy instanceof OccupancyExtended ext) {
                extended = ext;
            } else {
                extended = wrapAsExtended(occupancy, "");
            }
            return fromOccupancy(extended, hasLocationFields(recordStructure));
        }
        return Occupancy.fromOccupancy(occupancy);
    }

    /**
     * Wrap a plain {@link Occupancy} as an {@link OccupancyExtended} with the
     * given alarm category, copying all base fields (and preserving coordinates
     * if {@code occupancy} is already an {@link OccupancyExtended}).
     */
    public static OccupancyExtended wrapAsExtended(Occupancy occupancy, String alarmCategory) {
        OccupancyExtended.Builder builder = new OccupancyExtended.Builder()
                .alarmCategory(alarmCategory);
        if (occupancy instanceof OccupancyExtended ext) {
            builder.location(ext.getLatitude(), ext.getLongitude());
        }
        return (OccupancyExtended) builder
                .samplingTime(occupancy.getSamplingTime())
                .occupancyCount(occupancy.getOccupancyCount())
                .startTime(occupancy.getStartTime())
                .endTime(occupancy.getEndTime())
                .neutronBackground(occupancy.getNeutronBackground())
                .gammaAlarm(occupancy.hasGammaAlarm())
                .neutronAlarm(occupancy.hasNeutronAlarm())
                .maxGammaCount(occupancy.getMaxGammaCount())
                .maxNeutronCount(occupancy.getMaxNeutronCount())
                .adjudicatedIds(occupancy.getAdjudicatedIds())
                .videoPaths(occupancy.getVideoPaths())
                .webIdObsIds(occupancy.getWebIdObsIds())
                .build();
    }

    /**
     * Serialize an {@link OccupancyExtended} to a v2 (19-field) DataBlock
     * matching {@link #createExtendedRecordStructure()}.
     */
    public static DataBlock fromOccupancy(OccupancyExtended occupancy) {
        return fromOccupancy(occupancy, true);
    }

    /**
     * Serialize an {@link OccupancyExtended} to either the v2 19-field schema
     * ({@code includeLocation == true}) or the legacy v1 17-field schema
     * ({@code includeLocation == false}).
     */
    public static DataBlock fromOccupancy(OccupancyExtended occupancy, boolean includeLocation) {
        DataComponent resultStructure = includeLocation
                ? createExtendedRecordStructure()
                : createLegacyV1ExtendedRecordStructure();
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

        int adjCount = occupancy.getAdjudicatedIds().size();
        dataBlock.setDoubleValue(index++, adjCount);
        DataArrayImpl adjArr = (DataArrayImpl) resultStructure.getComponent("adjudicatedIds");
        if (adjCount > 0) {
            adjArr.updateSize();
            dataBlock.updateAtomCount();
            for (int i = 0; i < adjCount; i++) {
                dataBlock.setStringValue(index++, occupancy.getAdjudicatedIds().get(i));
            }
        }

        int videoCount = occupancy.getVideoPaths().size();
        dataBlock.setDoubleValue(index++, videoCount);
        DataArrayImpl videoArr = (DataArrayImpl) resultStructure.getComponent("videoPaths");
        if (videoCount > 0) {
            videoArr.updateSize();
            dataBlock.updateAtomCount();
            for (int i = 0; i < videoCount; i++) {
                dataBlock.setStringValue(index++, occupancy.getVideoPaths().get(i));
            }
        }

        int webIdObsIdsCount = occupancy.getWebIdObsIds().size();
        dataBlock.setDoubleValue(index++, webIdObsIdsCount);
        DataArrayImpl webIdObsIdsArray = (DataArrayImpl) resultStructure.getComponent("webIdObsIds");
        if (webIdObsIdsCount > 0) {
            webIdObsIdsArray.updateSize();
            dataBlock.updateAtomCount();
            for (int i = 0; i < webIdObsIdsCount; i++) {
                dataBlock.setStringValue(index++, occupancy.getWebIdObsIds().get(i));
            }
        }

        String alarmCat = occupancy.getAlarmCategory();
        dataBlock.setStringValue(index++, alarmCat == null ? "" : alarmCat);

        if (includeLocation) {
            dataBlock.setDoubleValue(index++, occupancy.getLatitude());
            dataBlock.setDoubleValue(index, occupancy.getLongitude());
        }

        return dataBlock;
    }

    /**
     * Deserialize an {@link OccupancyExtended} from a DataBlock produced by
     * either v1 (17-field) or v2 (19-field) extended occupancy schemas.
     */
    public static OccupancyExtended toOccupancy(DataBlock dataBlock) {
        return toOccupancy(null, dataBlock);
    }

    /**
     * Schema-aware extended deserializer. Avoids reading beyond {@code atomCount}
     * on legacy 17-field datablocks even when dynamic arrays contain entries.
     */
    public static OccupancyExtended toOccupancy(DataComponent recordStructure, DataBlock dataBlock) {
        int index = 0;
        int atomCount = dataBlock.getAtomCount();

        var samplingTime = dataBlock.getDoubleValue(index++);
        var occupancyCount = dataBlock.getIntValue(index++);
        var startTime = dataBlock.getDoubleValue(index++);
        var endTime = dataBlock.getDoubleValue(index++);
        var neutronBackground = dataBlock.getDoubleValue(index++);
        var gammaAlarm = dataBlock.getBooleanValue(index++);
        var neutronAlarm = dataBlock.getBooleanValue(index++);
        var maxGammaCount = dataBlock.getIntValue(index++);
        var maxNeutronCount = dataBlock.getIntValue(index++);

        int adjCount = dataBlock.getIntValue(index++);
        List<String> adjIds = new ArrayList<>(Math.max(0, adjCount));
        for (int i = 0; i < adjCount && index < atomCount; i++) {
            adjIds.add(dataBlock.getStringValue(index++));
        }

        int videoCount = dataBlock.getIntValue(index++);
        List<String> videoPaths = new ArrayList<>(Math.max(0, videoCount));
        for (int i = 0; i < videoCount && index < atomCount; i++) {
            videoPaths.add(dataBlock.getStringValue(index++));
        }

        List<String> webIdObsIds = new ArrayList<>();
        String alarmCat = "";
        double lat = 0.0;
        double lon = 0.0;

        if (index < atomCount) {
            int webIdObsIdsCount = dataBlock.getIntValue(index++);
            for (int i = 0; i < webIdObsIdsCount && index < atomCount; i++) {
                webIdObsIds.add(dataBlock.getStringValue(index++));
            }
            if (index < atomCount) {
                String rawCat = dataBlock.getStringValue(index++);
                alarmCat = rawCat == null ? "" : rawCat;
            }
            boolean expectLocation = recordStructure == null || hasLocationFields(recordStructure);
            if (expectLocation && index + 1 < atomCount) {
                lat = dataBlock.getDoubleValue(index++);
                lon = dataBlock.getDoubleValue(index);
            }
        }

        return (OccupancyExtended) new OccupancyExtended.Builder()
                .alarmCategory(alarmCat)
                .location(lat, lon)
                .samplingTime(samplingTime)
                .occupancyCount(occupancyCount)
                .startTime(startTime)
                .endTime(endTime)
                .neutronBackground(neutronBackground)
                .maxGammaCount(maxGammaCount)
                .maxNeutronCount(maxNeutronCount)
                .gammaAlarm(gammaAlarm)
                .neutronAlarm(neutronAlarm)
                .adjudicatedIds(adjIds)
                .videoPaths(videoPaths)
                .webIdObsIds(webIdObsIds)
                .build();
    }
}
