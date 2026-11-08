/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.service.oscar.reports.helpers;

import com.botts.impl.service.oscar.AdjudicationCodeConfig;
import com.botts.impl.service.oscar.OSCARServiceModule;
import com.botts.impl.system.lane.LaneSystem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.ScalarComponent;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.module.IModule;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.Adjudication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Single-pass datastore aggregation engine for OSCAR reports.
 * Replaces repeated per-metric, per-day, and per-code full-store scans with single-pass stream scans
 * while preserving exact observation, fault, tamper, EML, isotope, and adjudication counts.
 */
public final class ReportDataAggregator {

    public static final DateTimeFormatter UTC_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    public static final DateTimeFormatter UTC_DATETIME_SHORT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public static final Map<String, String> STANDARD_ISOTOPES;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        // Special Nuclear Material & Actinides
        map.put("Np", "Neptunium");
        map.put("Pu", "Plutonium");
        map.put("U-233", "Uranium233");
        map.put("U-235", "Uranium235");
        map.put("U-238", "Uranium238");
        map.put("Am", "Americium");
        map.put("Cf", "Californium");
        // Industrial
        map.put("Ba", "Barium");
        map.put("Bi", "Bismuth");
        map.put("Co-57", "Cobalt57");
        map.put("Co-60", "Cobalt60");
        map.put("Cs-134", "Cesium134");
        map.put("Cs-137", "Cesium137");
        map.put("Eu", "Europium");
        map.put("Ir", "Iridium");
        map.put("Mn", "Manganese");
        map.put("Na", "Sodium");
        map.put("Se", "Selenium");
        map.put("Sr", "Strontium");
        // Medical
        map.put("F", "Fluorine");
        map.put("Ga", "Gallium");
        map.put("I-123", "Iodine123");
        map.put("I-131", "Iodine131");
        map.put("In", "Indium");
        map.put("Pd", "Palladium");
        map.put("Tc", "Technetium");
        map.put("Xe", "Xenon");
        // NORM & Unknown
        map.put("K", "Potassium");
        map.put("Ra", "Radium");
        map.put("Th", "Thorium");
        map.put("Unk", "Unknown");
        STANDARD_ISOTOPES = Collections.unmodifiableMap(map);
    }

    public record DispositionCodeSpec(int code, String label, String group) {}

    private static final List<DispositionCodeSpec> BUILTIN_DISPOSITION_CODES = List.of(
            new DispositionCodeSpec(0, "", "Other"),
            new DispositionCodeSpec(1, "Code 1: Contraband Found", "Real Alarm"),
            new DispositionCodeSpec(2, "Code 2: Other", "Real Alarm"),
            new DispositionCodeSpec(3, "Code 3: Medical Isotope Found", "Innocent Alarm"),
            new DispositionCodeSpec(4, "Code 4: NORM Found", "Innocent Alarm"),
            new DispositionCodeSpec(5, "Code 5: Declared Shipment of Radioactive Material", "Innocent Alarm"),
            new DispositionCodeSpec(6, "Code 6: Physical Inspection Negative", "False Alarm"),
            new DispositionCodeSpec(7, "Code 7: RIID/ASP Indicates Background Only", "False Alarm"),
            new DispositionCodeSpec(8, "Code 8: Other", "False Alarm"),
            new DispositionCodeSpec(9, "Code 9: Authorized Test, Maintenance, or Training Activity", "Test/Maintenance"),
            new DispositionCodeSpec(10, "Code 10: Unauthorized Activity", "Tamper/Fault"),
            new DispositionCodeSpec(11, "Code 11: Other", "Other")
    );

    public static final class ObservationMetrics {
        public long gammaAlarm;
        public long neutronAlarm;
        public long gammaNeutronAlarm;
        public long emlSuppressed;
        public long totalOccupancies;
        public long tamper;
        public long gammaHighFault;
        public long gammaLowFault;
        public long neutronHighFault;
        public long extendedOccupancyFault;

        public long totalAlarms() {
            return gammaAlarm + neutronAlarm + gammaNeutronAlarm;
        }

        public long totalFaultsAndTamper() {
            return tamper + gammaHighFault + gammaLowFault + neutronHighFault + extendedOccupancyFault;
        }

        public double alarmOccupancyRatePct() {
            return Utils.calculateAlarmingOccRatePct(totalAlarms(), totalOccupancies);
        }

        public double emlAlarmRatePct() {
            return Utils.calcEMLAlarmRatePct(emlSuppressed, totalAlarms());
        }

        public void add(ObservationMetrics other) {
            if (other == null) {
                return;
            }
            this.gammaAlarm += other.gammaAlarm;
            this.neutronAlarm += other.neutronAlarm;
            this.gammaNeutronAlarm += other.gammaNeutronAlarm;
            this.emlSuppressed += other.emlSuppressed;
            this.totalOccupancies += other.totalOccupancies;
            this.tamper += other.tamper;
            this.gammaHighFault += other.gammaHighFault;
            this.gammaLowFault += other.gammaLowFault;
            this.neutronHighFault += other.neutronHighFault;
            this.extendedOccupancyFault += other.extendedOccupancyFault;
        }
    }

    public record AdjudicationDetailRecord(
            String laneUid,
            int sequenceNumber,
            String timeUtcIso,
            String timeUtcDisplay,
            String username,
            int adjudicationCode,
            String dispositionLabel,
            String dispositionGroup,
            List<String> isotopes,
            String secondaryInspectionStatus,
            String vehicleId,
            String occupancyObsId,
            String operatorFeedback,
            String formattedSummary,
            String emlDecision,
            String emlSourceType,
            Double emlNormProbPct,
            Double emlNonEmittingProbPct,
            Double emlThreatProbPct,
            Integer emlVehicleClass,
            Double emlVehicleLengthMeters,
            List<String> filePaths
    ) {}

    public record AdjudicationLaneSummary(
            String laneUid,
            long totalRecords,
            List<DispositionCodeSpec> dispositionSpecs,
            Map<Integer, Long> countsByCode,
            Map<String, Long> isotopeCountsBySymbol,
            List<Map<String, String>> detailRows,
            List<AdjudicationDetailRecord> structuredRecords
    ) {}

    private record OccupancyIndices(int gammaIdx, int neutronIdx, int adjCountIdx) {}

    private ReportDataAggregator() {}

    public static List<String> resolveTargetLaneUIDs(OSCARServiceModule module, String laneUIDs) {
        String[] parsed = Utils.parseLaneUIDs(laneUIDs);
        if (parsed.length > 0) {
            return List.of(parsed);
        }
        List<String> discovered = new ArrayList<>();
        if (module != null && module.getParentHub() != null && module.getParentHub().getModuleRegistry() != null) {
            for (IModule<?> mod : module.getParentHub().getModuleRegistry().getLoadedModules()) {
                if (mod instanceof LaneSystem lane && lane.getUniqueIdentifier() != null && !lane.getUniqueIdentifier().isBlank()) {
                    discovered.add(lane.getUniqueIdentifier());
                }
            }
        }
        return discovered;
    }

    /**
     * Resolves human-readable lane labels alongside UIDs while always preserving the full UID text.
     */
    public static Map<String, String> resolveLaneDisplayLabels(OSCARServiceModule module, Iterable<String> laneUIDs, boolean multiline) {
        Map<String, String> uidToName = new HashMap<>();
        if (module != null && module.getParentHub() != null && module.getParentHub().getModuleRegistry() != null) {
            for (IModule<?> mod : module.getParentHub().getModuleRegistry().getLoadedModules()) {
                if (mod instanceof LaneSystem lane && lane.getUniqueIdentifier() != null) {
                    String name = lane.getName();
                    if (name != null && !name.isBlank()) {
                        uidToName.put(lane.getUniqueIdentifier(), name.trim());
                    }
                }
            }
        }

        Map<String, String> labels = new LinkedHashMap<>();
        if (laneUIDs != null) {
            for (String uid : laneUIDs) {
                if (uid == null || uid.isBlank()) {
                    continue;
                }
                String trimmed = uid.trim();
                String friendly = uidToName.get(trimmed);
                if (friendly != null && !friendly.equalsIgnoreCase(trimmed)) {
                    labels.put(trimmed, multiline ? (friendly + "\n" + trimmed) : (friendly + " (" + trimmed + ")"));
                } else {
                    labels.put(trimmed, trimmed);
                }
            }
        }
        return labels;
    }

    public static String buildBannerScopeSummary(OSCARServiceModule module, String laneUIDs) {
        String[] scoped = Utils.parseLaneUIDs(laneUIDs);
        if (scoped.length == 0) {
            List<String> allConfigured = resolveTargetLaneUIDs(module, null);
            if (allConfigured.isEmpty()) {
                return "Scope: All Site Lanes";
            }
            return "Scope: All Configured Site Lanes (" + allConfigured.size()
                    + (allConfigured.size() == 1 ? " lane)" : " lanes)");
        }
        Map<String, String> labels = resolveLaneDisplayLabels(module, List.of(scoped), false);
        List<String> items = new ArrayList<>(labels.values());
        if (items.size() <= 4) {
            return "Lanes: " + String.join(", ", items);
        }
        List<String> head = items.subList(0, 4);
        return "Lanes (" + items.size() + " selected): " + String.join(", ", head)
                + " (+" + (items.size() - 4) + " additional lanes)";
    }

    /**
     * Single-pass site/scope metrics aggregation (used by RDS Site Report).
     */
    public static ObservationMetrics aggregateScopeMetrics(
            OSCARServiceModule module,
            String laneUIDs,
            Instant start,
            Instant end
    ) {
        ObservationMetrics metrics = new ObservationMetrics();
        IObsSystemDatabase db = getDatabase(module);
        if (db == null) {
            return metrics;
        }
        String[] scopedLanes = Utils.parseLaneUIDs(laneUIDs);

        // 1. Occupancy indexed total count + single pass over alarming occupancies only
        DataStreamFilter occDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_OCCUPANCY);
        metrics.totalOccupancies = countOccupancies(module, db, new ObsFilter.Builder()
                .withResultTimeDuring(start, end)
                .withDataStreams(occDsFilter)
                .build());

        Map<BigId, OccupancyIndices> occIndicesCache = new HashMap<>();
        try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                .withResultTimeDuring(start, end)
                .withDataStreams(occDsFilter)
                .withCQLFilter(Utils.anyOccupancyAlarmCQL)
                .build())) {
            stream.forEach(obs -> {
                if (obs == null || obs.getResult() == null) {
                    return;
                }
                OccupancyIndices idx = occIndicesCache.computeIfAbsent(
                        obs.getDataStreamID(), dsId -> resolveOccupancyIndices(db, dsId));
                DataBlock block = obs.getResult();
                boolean g = safeBoolean(block, idx.gammaIdx());
                boolean n = safeBoolean(block, idx.neutronIdx());
                if (g && n) {
                    metrics.gammaNeutronAlarm++;
                } else if (g) {
                    metrics.gammaAlarm++;
                } else if (n) {
                    metrics.neutronAlarm++;
                }
                if ((g || n) && hasEmlNonThreatMarker(block, idx)) {
                    metrics.emlSuppressed++;
                }
            });
        }

        // 2. Fallback for standalone legacy DEF_EML_ANALYSIS streams when occupancy markers are absent
        if (metrics.emlSuppressed == 0L) {
            DataStreamFilter emlDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_EML_ANALYSIS);
            metrics.emlSuppressed = db.getObservationStore().countMatchingEntries(new ObsFilter.Builder()
                    .withResultTimeDuring(start, end)
                    .withDataStreams(emlDsFilter)
                    .withCQLFilter(Utils.emlSuppressedCQL)
                    .build());
        }

        // 3. Tamper single pass with CQL predicate pushdown
        DataStreamFilter tamperDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_TAMPER);
        metrics.tamper = db.getObservationStore().countMatchingEntries(new ObsFilter.Builder()
                .withResultTimeDuring(start, end)
                .withDataStreams(tamperDsFilter)
                .withCQLFilter(Utils.tamperCQL)
                .build());

        // 4. Gamma, Neutron & Extended Occupancy faults single pass with CQL pushdown
        DataStreamFilter faultDsFilter = buildScopedDataStreamFilter(
                scopedLanes,
                RADHelper.DEF_GAMMA,
                RADHelper.DEF_NEUTRON,
                RADHelper.DEF_ALARM,
                RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT,
                RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE);
        Map<BigId, Integer> alarmIdxCache = new HashMap<>();
        try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                .withResultTimeDuring(start, end)
                .withDataStreams(faultDsFilter)
                .withCQLFilter(Utils.faultCQL)
                .build())) {
            stream.forEach(obs -> {
                if (obs == null || obs.getResult() == null) {
                    return;
                }
                int alarmIdx = alarmIdxCache.computeIfAbsent(
                        obs.getDataStreamID(), dsId -> resolveAlarmStateIndex(db, dsId));
                String state = safeString(obs.getResult(), alarmIdx);
                classifyFaultState(state, metrics);
            });
        }

        return metrics;
    }

    /**
     * Single-pass per-lane metrics aggregation across all requested lanes (used by Lane Report).
     */
    public static Map<String, ObservationMetrics> aggregateMetricsByLane(
            OSCARServiceModule module,
            List<String> targetLanes,
            Instant start,
            Instant end
    ) {
        Map<String, ObservationMetrics> perLane = new LinkedHashMap<>();
        if (targetLanes == null || targetLanes.isEmpty()) {
            return perLane;
        }
        for (String laneUid : targetLanes) {
            perLane.put(laneUid, new ObservationMetrics());
        }

        IObsSystemDatabase db = getDatabase(module);
        if (db == null) {
            return perLane;
        }

        Map<BigId, String> occStreamToLane = new HashMap<>();
        Map<BigId, OccupancyIndices> occIndices = new HashMap<>();
        Map<BigId, String> emlStreamToLane = new HashMap<>();
        Map<BigId, Integer> emlResultIdx = new HashMap<>();
        Map<BigId, String> tamperStreamToLane = new HashMap<>();
        Map<BigId, Integer> tamperStatusIdx = new HashMap<>();
        Map<BigId, String> faultStreamToLane = new HashMap<>();
        Map<BigId, Integer> faultStateIdx = new HashMap<>();

        for (String laneUid : targetLanes) {
            DataStreamFilter laneDsFilter = new DataStreamFilter.Builder()
                    .withSystems().withUniqueIDs(laneUid).includeMembers(true).done()
                    .withObservedProperties(
                            RADHelper.DEF_OCCUPANCY,
                            RADHelper.DEF_EML_ANALYSIS,
                            RADHelper.DEF_TAMPER,
                            RADHelper.DEF_GAMMA,
                            RADHelper.DEF_NEUTRON,
                            RADHelper.DEF_ALARM,
                            RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT,
                            RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE
                    )
                    .build();

            try (var entries = db.getDataStreamStore().selectEntries(laneDsFilter)) {
                entries.forEach(entry -> {
                    BigId dsId = entry.getKey().getInternalID();
                    IDataStreamInfo dsInfo = entry.getValue();
                    DataComponent struct = dsInfo != null ? dsInfo.getRecordStructure() : null;
                    if (containsDefinition(struct, RADHelper.DEF_OCCUPANCY)) {
                        occStreamToLane.put(dsId, laneUid);
                        occIndices.put(dsId, extractOccupancyIndices(struct));
                    }
                    if (containsDefinition(struct, RADHelper.DEF_EML_ANALYSIS)) {
                        emlStreamToLane.put(dsId, laneUid);
                        emlResultIdx.put(dsId, findLeadingScalarIndex(struct, c -> "result".equalsIgnoreCase(c.getName()), 0));
                    }
                    if (containsDefinition(struct, RADHelper.DEF_TAMPER)) {
                        tamperStreamToLane.put(dsId, laneUid);
                        tamperStatusIdx.put(dsId, findLeadingScalarIndex(
                                struct, c -> RADHelper.DEF_TAMPER.equalsIgnoreCase(c.getDefinition()) || "tamperStatus".equalsIgnoreCase(c.getName()), 1));
                    }
                    if (containsDefinition(struct, RADHelper.DEF_GAMMA)
                            || containsDefinition(struct, RADHelper.DEF_NEUTRON)
                            || containsDefinition(struct, RADHelper.DEF_ALARM)
                            || containsDefinition(struct, RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT)
                            || containsDefinition(struct, RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE)) {
                        faultStreamToLane.put(dsId, laneUid);
                        faultStateIdx.put(dsId, extractAlarmStateIndex(struct));
                    }
                });
            }
        }

        // 1. Occupancy indexed per-stream totals + single pass over alarming occupancies across all lanes
        if (!occStreamToLane.isEmpty()) {
            for (Map.Entry<BigId, String> occDs : occStreamToLane.entrySet()) {
                ObservationMetrics m = perLane.get(occDs.getValue());
                if (m != null) {
                    m.totalOccupancies += countOccupancies(module, db, new ObsFilter.Builder()
                            .withResultTimeDuring(start, end)
                            .withDataStreams(occDs.getKey())
                            .build());
                }
            }

            try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                    .withResultTimeDuring(start, end)
                    .withDataStreams(occStreamToLane.keySet())
                    .withCQLFilter(Utils.anyOccupancyAlarmCQL)
                    .build())) {
                stream.forEach(obs -> {
                    if (obs == null || obs.getResult() == null) {
                        return;
                    }
                    String laneUid = occStreamToLane.get(obs.getDataStreamID());
                    ObservationMetrics m = perLane.get(laneUid);
                    if (m == null) {
                        return;
                    }
                    OccupancyIndices idx = occIndices.getOrDefault(obs.getDataStreamID(), new OccupancyIndices(5, 6, 9));
                    DataBlock block = obs.getResult();
                    boolean g = safeBoolean(block, idx.gammaIdx());
                    boolean n = safeBoolean(block, idx.neutronIdx());
                    if (g && n) {
                        m.gammaNeutronAlarm++;
                    } else if (g) {
                        m.gammaAlarm++;
                    } else if (n) {
                        m.neutronAlarm++;
                    }
                    if ((g || n) && hasEmlNonThreatMarker(block, idx)) {
                        m.emlSuppressed++;
                    }
                });
            }
        }

        // 2. Legacy EML Suppressed fallback across lanes that have standalone DEF_EML_ANALYSIS streams
        if (!emlStreamToLane.isEmpty()) {
            Set<String> lanesAlreadyWithOccEml = new java.util.HashSet<>();
            for (Map.Entry<String, ObservationMetrics> e : perLane.entrySet()) {
                if (e.getValue().emlSuppressed > 0L) {
                    lanesAlreadyWithOccEml.add(e.getKey());
                }
            }
            try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                    .withResultTimeDuring(start, end)
                    .withDataStreams(emlStreamToLane.keySet())
                    .withCQLFilter(Utils.emlSuppressedCQL)
                    .build())) {
                stream.forEach(obs -> {
                    if (obs == null || obs.getResult() == null) {
                        return;
                    }
                    String laneUid = emlStreamToLane.get(obs.getDataStreamID());
                    if (lanesAlreadyWithOccEml.contains(laneUid)) {
                        return;
                    }
                    ObservationMetrics m = perLane.get(laneUid);
                    if (m != null) {
                        int rIdx = emlResultIdx.getOrDefault(obs.getDataStreamID(), 0);
                        String res = safeString(obs.getResult(), rIdx);
                        if ("RELEASE".equalsIgnoreCase(res) || "1".equals(res)) {
                            m.emlSuppressed++;
                        }
                    }
                });
            }
        }

        // 3. Tamper pass across all target lanes combined
        if (!tamperStreamToLane.isEmpty()) {
            try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                    .withResultTimeDuring(start, end)
                    .withDataStreams(tamperStreamToLane.keySet())
                    .withCQLFilter(Utils.tamperCQL)
                    .build())) {
                stream.forEach(obs -> {
                    if (obs == null || obs.getResult() == null) {
                        return;
                    }
                    String laneUid = tamperStreamToLane.get(obs.getDataStreamID());
                    ObservationMetrics m = perLane.get(laneUid);
                    if (m != null) {
                        int tIdx = tamperStatusIdx.getOrDefault(obs.getDataStreamID(), 1);
                        if (safeBoolean(obs.getResult(), tIdx)) {
                            m.tamper++;
                        }
                    }
                });
            }
        }

        // 4. Fault pass across all target lanes combined
        if (!faultStreamToLane.isEmpty()) {
            try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                    .withResultTimeDuring(start, end)
                    .withDataStreams(faultStreamToLane.keySet())
                    .withCQLFilter(Utils.faultCQL)
                    .build())) {
                stream.forEach(obs -> {
                    if (obs == null || obs.getResult() == null) {
                        return;
                    }
                    String laneUid = faultStreamToLane.get(obs.getDataStreamID());
                    ObservationMetrics m = perLane.get(laneUid);
                    if (m != null) {
                        int aIdx = faultStateIdx.getOrDefault(obs.getDataStreamID(), 1);
                        String state = safeString(obs.getResult(), aIdx);
                        classifyFaultState(state, m);
                    }
                });
            }
        }

        return perLane;
    }

    /**
     * Single-pass daily UTC calendar-day aggregation for Event Report (ALARMS, ALARMS_OCCUPANCIES, SOH).
     */
    public static Map<String, ObservationMetrics> aggregateDailyEventMetrics(
            OSCARServiceModule module,
            String laneUIDs,
            Instant start,
            Instant end,
            EventReportType eventType
    ) {
        Map<LocalDate, ObservationMetrics> byUtcDate = seedUtcDayBuckets(start, end);
        IObsSystemDatabase db = getDatabase(module);
        if (db != null) {
            String[] scopedLanes = Utils.parseLaneUIDs(laneUIDs);
            if (eventType == EventReportType.ALARMS || eventType == EventReportType.ALARMS_OCCUPANCIES) {
                boolean includeAllOccupancies = (eventType == EventReportType.ALARMS_OCCUPANCIES);
                DataStreamFilter occDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_OCCUPANCY);

                if (includeAllOccupancies && start != null && end != null) {
                    for (Map.Entry<LocalDate, ObservationMetrics> dayEntry : byUtcDate.entrySet()) {
                        LocalDate day = dayEntry.getKey();
                        Instant bucketMin = day.atStartOfDay(ZoneOffset.UTC).toInstant();
                        if (bucketMin.isBefore(start)) {
                            bucketMin = start;
                        }
                        Instant bucketMax = day.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1L);
                        if (bucketMax.isAfter(end)) {
                            bucketMax = end;
                        }
                        if (!bucketMin.isAfter(bucketMax)) {
                            dayEntry.getValue().totalOccupancies += countOccupancies(
                                    module,
                                    db,
                                    new ObsFilter.Builder()
                                            .withResultTimeDuring(bucketMin, bucketMax)
                                            .withDataStreams(occDsFilter)
                                            .build()
                            );
                        }
                    }
                    LocalDate endDay = LocalDate.ofInstant(end, ZoneOffset.UTC);
                    if (end.isAfter(start) && end.equals(endDay.atStartOfDay(ZoneOffset.UTC).toInstant())) {
                        long exactBoundaryCount = countOccupancies(
                                module,
                                db,
                                new ObsFilter.Builder()
                                        .withResultTimeDuring(end, end)
                                        .withDataStreams(occDsFilter)
                                        .build()
                        );
                        if (exactBoundaryCount > 0L) {
                            byUtcDate.computeIfAbsent(endDay, d -> new ObservationMetrics()).totalOccupancies += exactBoundaryCount;
                        }
                    }
                }

                long occEmlSuppressedTotal = 0L;
                long[] occEmlHolder = {0L};
                Map<BigId, OccupancyIndices> occIndicesCache = new HashMap<>();
                try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                        .withResultTimeDuring(start, end)
                        .withDataStreams(occDsFilter)
                        .withCQLFilter(Utils.anyOccupancyAlarmCQL)
                        .build())) {
                    stream.forEach(obs -> {
                        if (obs == null || obs.getResult() == null || obs.getResultTime() == null) {
                            return;
                        }
                        LocalDate day = LocalDate.ofInstant(obs.getResultTime(), ZoneOffset.UTC);
                        ObservationMetrics m = byUtcDate.computeIfAbsent(day, d -> new ObservationMetrics());
                        OccupancyIndices idx = occIndicesCache.computeIfAbsent(
                                obs.getDataStreamID(), dsId -> resolveOccupancyIndices(db, dsId));
                        DataBlock block = obs.getResult();
                        boolean g = safeBoolean(block, idx.gammaIdx());
                        boolean n = safeBoolean(block, idx.neutronIdx());
                        if (g && n) {
                            m.gammaNeutronAlarm++;
                        } else if (g) {
                            m.gammaAlarm++;
                        } else if (n) {
                            m.neutronAlarm++;
                        }
                        if ((g || n) && hasEmlNonThreatMarker(block, idx)) {
                            m.emlSuppressed++;
                            occEmlHolder[0]++;
                        }
                    });
                }
                occEmlSuppressedTotal = occEmlHolder[0];

                if (occEmlSuppressedTotal == 0L) {
                    DataStreamFilter emlDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_EML_ANALYSIS);
                    Map<BigId, Integer> emlIdxCache = new HashMap<>();
                    try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                            .withResultTimeDuring(start, end)
                            .withDataStreams(emlDsFilter)
                            .withCQLFilter(Utils.emlSuppressedCQL)
                            .build())) {
                        stream.forEach(obs -> {
                            if (obs == null || obs.getResult() == null || obs.getResultTime() == null) {
                                return;
                            }
                            int rIdx = emlIdxCache.computeIfAbsent(obs.getDataStreamID(), dsId -> {
                                DataComponent s = getRecordStructure(db, dsId);
                                return findLeadingScalarIndex(s, c -> "result".equalsIgnoreCase(c.getName()), 0);
                            });
                            String res = safeString(obs.getResult(), rIdx);
                            if ("RELEASE".equalsIgnoreCase(res) || "1".equals(res)) {
                                LocalDate day = LocalDate.ofInstant(obs.getResultTime(), ZoneOffset.UTC);
                                byUtcDate.computeIfAbsent(day, d -> new ObservationMetrics()).emlSuppressed++;
                            }
                        });
                    }
                }
            } else if (eventType == EventReportType.SOH) {
                DataStreamFilter faultDsFilter = buildScopedDataStreamFilter(
                        scopedLanes,
                        RADHelper.DEF_GAMMA,
                        RADHelper.DEF_NEUTRON,
                        RADHelper.DEF_ALARM,
                        RADHelper.DEF_EXTENDED_OCCUPANCY_FAULT,
                        RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE);
                Map<BigId, Integer> alarmIdxCache = new HashMap<>();
                try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                        .withResultTimeDuring(start, end)
                        .withDataStreams(faultDsFilter)
                        .withCQLFilter(Utils.faultCQL)
                        .build())) {
                    stream.forEach(obs -> {
                        if (obs == null || obs.getResult() == null || obs.getResultTime() == null) {
                            return;
                        }
                        int aIdx = alarmIdxCache.computeIfAbsent(
                                obs.getDataStreamID(), dsId -> resolveAlarmStateIndex(db, dsId));
                        String state = safeString(obs.getResult(), aIdx);
                        LocalDate day = LocalDate.ofInstant(obs.getResultTime(), ZoneOffset.UTC);
                        classifyFaultState(state, byUtcDate.computeIfAbsent(day, d -> new ObservationMetrics()));
                    });
                }

                DataStreamFilter tamperDsFilter = buildScopedDataStreamFilter(scopedLanes, RADHelper.DEF_TAMPER);
                Map<BigId, Integer> tamperIdxCache = new HashMap<>();
                try (Stream<IObsData> stream = db.getObservationStore().select(new ObsFilter.Builder()
                        .withResultTimeDuring(start, end)
                        .withDataStreams(tamperDsFilter)
                        .withCQLFilter(Utils.tamperCQL)
                        .build())) {
                    stream.forEach(obs -> {
                        if (obs == null || obs.getResult() == null || obs.getResultTime() == null) {
                            return;
                        }
                        int tIdx = tamperIdxCache.computeIfAbsent(obs.getDataStreamID(), dsId -> {
                            DataComponent s = getRecordStructure(db, dsId);
                            return findLeadingScalarIndex(
                                    s, c -> RADHelper.DEF_TAMPER.equalsIgnoreCase(c.getDefinition()) || "tamperStatus".equalsIgnoreCase(c.getName()), 1);
                        });
                        if (safeBoolean(obs.getResult(), tIdx)) {
                            LocalDate day = LocalDate.ofInstant(obs.getResultTime(), ZoneOffset.UTC);
                            byUtcDate.computeIfAbsent(day, d -> new ObservationMetrics()).tamper++;
                        }
                    });
                }
            }
        }

        // Convert to chronological yyyy-MM-dd LinkedHashMap
        List<LocalDate> sortedDays = new ArrayList<>(byUtcDate.keySet());
        Collections.sort(sortedDays);
        Map<String, ObservationMetrics> out = new LinkedHashMap<>(sortedDays.size());
        for (LocalDate d : sortedDays) {
            out.put(d.toString(), byUtcDate.get(d));
        }
        return out;
    }

    /**
     * Single-pass CommandStatusStore aggregation per lane for Adjudication Report.
     * Includes built-in codes (0-11), configured custom disposition codes (>11), and automated EML Non-Threat (9999).
     */
    public static AdjudicationLaneSummary aggregateAdjudicationsForLane(
            OSCARServiceModule module,
            String laneUid,
            Instant start,
            Instant end
    ) {
        Map<Integer, DispositionCodeSpec> specByCode = new LinkedHashMap<>();
        for (DispositionCodeSpec builtin : BUILTIN_DISPOSITION_CODES) {
            specByCode.put(builtin.code(), builtin);
        }

        // Include administrator-configured custom disposition codes (> 11)
        if (module != null && module.getConfiguration() != null && module.getConfiguration().customAdjudicationCodes != null) {
            List<AdjudicationCodeConfig> customSorted = new ArrayList<>(module.getConfiguration().customAdjudicationCodes);
            customSorted.removeIf(Objects::isNull);
            customSorted.sort(Comparator.comparingInt(c -> c.code));
            for (AdjudicationCodeConfig custom : customSorted) {
                if (custom.code > 11
                        && custom.code != Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE
                        && custom.code != Adjudication.EML_NON_THREAT_CODE
                        && custom.label != null
                        && !custom.label.isBlank()) {
                    String fullLabel = custom.label.trim().startsWith("Code ")
                            ? custom.label.trim()
                            : ("Code " + custom.code + ": " + custom.label.trim());
                    specByCode.put(custom.code, new DispositionCodeSpec(custom.code, fullLabel, "Custom"));
                }
            }
        }

        specByCode.put(
                Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE,
                new DispositionCodeSpec(Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE, "Extended Occupancy Gamma Alarm", "Automated")
        );
        specByCode.put(
                Adjudication.EML_NON_THREAT_CODE,
                new DispositionCodeSpec(Adjudication.EML_NON_THREAT_CODE, "EML Non-Threat", "Automated")
        );

        Map<Integer, Long> countsByCode = new LinkedHashMap<>();
        for (Integer code : specByCode.keySet()) {
            countsByCode.put(code, 0L);
        }

        Map<String, Long> isotopeCounts = new LinkedHashMap<>();
        for (String symbol : STANDARD_ISOTOPES.keySet()) {
            isotopeCounts.put(symbol, 0L);
        }

        List<Map<String, String>> detailRows = new ArrayList<>();
        List<AdjudicationDetailRecord> structuredRecords = new ArrayList<>();
        long totalRecords = 0L;

        if (module != null && module.getParentHub() != null && laneUid != null && !laneUid.isBlank()) {
            Iterator<ICommandStatus> it = Utils.queryCommandStatus(module, laneUid, start, end);
            int seq = 1;
            while (it.hasNext()) {
                ICommandStatus entry = it.next();
                if (entry == null || entry.getResult() == null || entry.getResult().getInlineRecords() == null) {
                    continue;
                }
                List<DataBlock> blocks = entry.getResult().getInlineRecords().stream().toList();
                if (blocks.isEmpty() || blocks.get(0) == null) {
                    continue;
                }

                DataBlock block = blocks.get(0);
                try {
                    int index = 0;
                    String username = nonNull(block.getStringValue(index++));
                    String feedback = nonNull(block.getStringValue(index++));
                    int code = block.getIntValue(index++);
                    int isotopeCount = Math.max(0, block.getIntValue(index++));

                    List<String> recordIsotopes = new ArrayList<>(isotopeCount);
                    for (int i = 0; i < isotopeCount; i++) {
                        String iso = nonNull(block.getStringValue(index++));
                        if (!iso.isBlank()) {
                            recordIsotopes.add(iso);
                        }
                    }

                    String secondaryStatus = nonNull(block.getStringValue(index++));
                    int fileCount = Math.max(0, block.getIntValue(index++));
                    List<String> filePaths = new ArrayList<>(fileCount);
                    for (int i = 0; i < fileCount; i++) {
                        String fp = nonNull(block.getStringValue(index++));
                        if (!fp.isBlank()) {
                            filePaths.add(fp);
                        }
                    }

                    String occupancyObsId = nonNull(block.getStringValue(index++));
                    String vehicleId = nonNull(block.getStringValue(index));

                    totalRecords++;

                    if (!specByCode.containsKey(code)) {
                        String dynamicLabel = "Code " + code + ": Custom Disposition";
                        specByCode.put(code, new DispositionCodeSpec(code, dynamicLabel, "Custom"));
                    }
                    countsByCode.merge(code, 1L, Long::sum);

                    for (String recordedIso : recordIsotopes) {
                        String trimmedIso = recordedIso.trim();
                        boolean matchedStandard = false;
                        for (Map.Entry<String, String> std : STANDARD_ISOTOPES.entrySet()) {
                            if (trimmedIso.equalsIgnoreCase(std.getValue()) || trimmedIso.equalsIgnoreCase(std.getKey())) {
                                isotopeCounts.merge(std.getKey(), 1L, Long::sum);
                                matchedStandard = true;
                                break;
                            }
                        }
                        if (!matchedStandard && !trimmedIso.isEmpty()) {
                            isotopeCounts.merge(trimmedIso, 1L, Long::sum);
                        }
                    }

                    DispositionCodeSpec matchedSpec = specByCode.get(code);
                    String formattedCode = (matchedSpec != null && !matchedSpec.label().isBlank())
                            ? ((code == Adjudication.EML_NON_THREAT_CODE || code == Adjudication.EXTENDED_OCCUPANCY_GAMMA_CODE)
                                ? (code + " (" + matchedSpec.label() + ")")
                                : matchedSpec.label())
                            : String.valueOf(code);

                    String reportTimeStr = entry.getReportTime() != null
                            ? UTC_DATETIME_SHORT_FMT.format(entry.getReportTime())
                            : "-";
                    String reportTimeIso = entry.getReportTime() != null
                            ? DateTimeFormatter.ISO_INSTANT.format(entry.getReportTime())
                            : "";

                    String formattedIsotopes = recordIsotopes.isEmpty()
                            ? "None (0)"
                            : String.join(", ", recordIsotopes) + " (" + isotopeCount + ")";
                    String formattedFiles = filePaths.isEmpty()
                            ? "None (0)"
                            : String.join(", ", filePaths) + " (" + fileCount + ")";

                    int currentSeq = seq++;
                    ParsedEmlFeedback parsedEml = parseEmlFeedback(feedback);

                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("#", String.valueOf(currentSeq));
                    row.put("Time (UTC)", reportTimeStr);
                    row.put("Username", username.isEmpty() ? "-" : username);
                    row.put("Feedback", feedback);
                    row.put("Adjudication Code", formattedCode);
                    row.put("Isotope Count", String.valueOf(isotopeCount));
                    row.put("Isotopes", formattedIsotopes);
                    row.put("Secondary Inspection Status", secondaryStatus.isEmpty() ? "-" : secondaryStatus);
                    row.put("File Path Count", String.valueOf(fileCount));
                    row.put("File Paths", formattedFiles);
                    row.put("Occupancy Obs ID", occupancyObsId.isEmpty() ? "-" : occupancyObsId);
                    row.put("Vehicle ID", vehicleId.isEmpty() ? "-" : vehicleId);
                    detailRows.add(row);

                    structuredRecords.add(new AdjudicationDetailRecord(
                            laneUid,
                            currentSeq,
                            reportTimeIso,
                            reportTimeStr,
                            username.isEmpty() ? "-" : username,
                            code,
                            formattedCode,
                            matchedSpec != null ? matchedSpec.group() : "Other",
                            List.copyOf(recordIsotopes),
                            secondaryStatus.isEmpty() ? "-" : secondaryStatus,
                            vehicleId.isEmpty() ? "-" : vehicleId,
                            occupancyObsId.isEmpty() ? "-" : occupancyObsId,
                            parsedEml.operatorNotes(),
                            TableGenerator.formatAdjudicationFeedback(feedback),
                            parsedEml.emlDecision(),
                            parsedEml.emlSourceType(),
                            parsedEml.normProbPct(),
                            parsedEml.nonEmittingProbPct(),
                            parsedEml.threatProbPct(),
                            parsedEml.vehicleClass(),
                            parsedEml.vehicleLengthMeters(),
                            List.copyOf(filePaths)
                    ));
                } catch (Exception ignored) {
                    // Skip malformed command status records safely without aborting the report
                }
            }
        }

        return new AdjudicationLaneSummary(
                laneUid,
                totalRecords,
                new ArrayList<>(specByCode.values()),
                countsByCode,
                isotopeCounts,
                detailRows,
                structuredRecords
        );
    }

    private record ParsedEmlFeedback(
            String operatorNotes,
            String emlDecision,
            String emlSourceType,
            Double normProbPct,
            Double nonEmittingProbPct,
            Double threatProbPct,
            Integer vehicleClass,
            Double vehicleLengthMeters
    ) {}

    private static ParsedEmlFeedback parseEmlFeedback(String rawFeedback) {
        if (rawFeedback == null || rawFeedback.isBlank()) {
            return new ParsedEmlFeedback("", "", "", null, null, null, null, null);
        }
        int markerIdx = rawFeedback.indexOf(Adjudication.EML_METADATA_PREFIX);
        if (markerIdx < 0) {
            return new ParsedEmlFeedback(rawFeedback.trim(), "", "", null, null, null, null, null);
        }
        String notes = rawFeedback.substring(0, markerIdx).trim();
        String jsonPart = rawFeedback.substring(markerIdx + Adjudication.EML_METADATA_PREFIX.length()).trim();
        try {
            JsonElement parsed = JsonParser.parseString(jsonPart);
            if (!parsed.isJsonObject()) {
                return new ParsedEmlFeedback(notes, "", "", null, null, null, null, null);
            }
            JsonObject obj = parsed.getAsJsonObject();
            String decision = getJsonString(obj, "result");
            if (decision.isEmpty()) {
                decision = getJsonString(obj, "emlDecision");
            }
            String sourceType = getJsonString(obj, "overallSourceType");
            Double pNorm = getJsonPct(obj, "probabilityNORM");
            Double pNonEmit = getJsonPct(obj, "probabilityNonEmitting");
            Double pThreat = getJsonPct(obj, "probabilityThreat");
            Integer vClass = getJsonInt(obj, "vehicleClass");
            Double vLen = getJsonDouble(obj, "vehicleLength");
            return new ParsedEmlFeedback(notes, decision, sourceType, pNorm, pNonEmit, pThreat, vClass, vLen);
        } catch (Exception e) {
            return new ParsedEmlFeedback(notes, "", "", null, null, null, null, null);
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Double getJsonDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            double v = obj.get(key).getAsDouble();
            return Double.isFinite(v) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double getJsonPct(JsonObject obj, String key) {
        Double v = getJsonDouble(obj, key);
        return v == null ? null : v * 100.0;
    }

    private static Integer getJsonInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<LocalDate, ObservationMetrics> seedUtcDayBuckets(Instant start, Instant end) {
        Map<LocalDate, ObservationMetrics> buckets = new LinkedHashMap<>();
        if (start == null || end == null) {
            return buckets;
        }
        LocalDate firstDay = LocalDate.ofInstant(start, ZoneOffset.UTC);
        Instant inclusiveEnd = end.isAfter(start) ? end.minusNanos(1L) : start;
        LocalDate lastDay = LocalDate.ofInstant(inclusiveEnd, ZoneOffset.UTC);

        LocalDate cur = firstDay;
        while (!cur.isAfter(lastDay)) {
            buckets.put(cur, new ObservationMetrics());
            cur = cur.plusDays(1L);
        }
        return buckets;
    }

    private static DataStreamFilter buildScopedDataStreamFilter(String[] scopedLanes, String... observedProperties) {
        DataStreamFilter.Builder dsb = new DataStreamFilter.Builder()
                .withObservedProperties(observedProperties);
        if (scopedLanes != null && scopedLanes.length > 0) {
            dsb.withSystems()
                    .withUniqueIDs(scopedLanes)
                    .includeMembers(true)
                    .done();
        }
        return dsb.build();
    }

    private static void classifyFaultState(String alarmState, ObservationMetrics metrics) {
        if (alarmState == null || metrics == null) {
            return;
        }
        switch (alarmState) {
            case "Fault - Gamma High" -> metrics.gammaHighFault++;
            case "Fault - Gamma Low" -> metrics.gammaLowFault++;
            case "Fault - Neutron High" -> metrics.neutronHighFault++;
            case "Fault - Extended Occupancy" -> metrics.extendedOccupancyFault++;
            default -> {
                // Non-fault foreground states ignored
            }
        }
    }

    private static IObsSystemDatabase getDatabase(OSCARServiceModule module) {
        if (module == null || module.getParentHub() == null || module.getParentHub().getDatabaseRegistry() == null) {
            return null;
        }
        return module.getParentHub().getDatabaseRegistry().getFederatedDatabase();
    }

    private static long countOccupancies(OSCARServiceModule module, IObsSystemDatabase db, ObsFilter filter) {
        if (db == null || db.getObservationStore() == null) {
            return 0L;
        }
        if (hasMvObsStore(module)) {
            try (var keys = db.getObservationStore().selectKeys(filter)) {
                return keys.count();
            }
        }
        return db.getObservationStore().countMatchingEntries(filter);
    }

    private static boolean hasMvObsStore(OSCARServiceModule module) {
        if (module == null || module.getParentHub() == null || module.getParentHub().getDatabaseRegistry() == null) {
            return false;
        }
        for (var database : module.getParentHub().getDatabaseRegistry().getObsSystemDatabases()) {
            if (database != null && database.getObservationStore() != null
                    && database.getObservationStore().getClass().getSimpleName().startsWith("MV")) {
                return true;
            }
        }
        return false;
    }

    private static DataComponent getRecordStructure(IObsSystemDatabase db, BigId dataStreamId) {
        if (db == null || dataStreamId == null || db.getDataStreamStore() == null) {
            return null;
        }
        IDataStreamInfo info = db.getDataStreamStore().get(new DataStreamKey(dataStreamId));
        return info != null ? info.getRecordStructure() : null;
    }

    private static OccupancyIndices resolveOccupancyIndices(IObsSystemDatabase db, BigId dsId) {
        return extractOccupancyIndices(getRecordStructure(db, dsId));
    }

    private static OccupancyIndices extractOccupancyIndices(DataComponent struct) {
        int gIdx = findLeadingScalarIndex(struct, c -> "gammaAlarm".equalsIgnoreCase(c.getName()), 5);
        int nIdx = findLeadingScalarIndex(struct, c -> "neutronAlarm".equalsIgnoreCase(c.getName()), 6);
        int adjCountIdx = findLeadingScalarIndex(
                struct,
                c -> c.getName() != null
                        && c.getName().toLowerCase(Locale.ROOT).contains("adjudicat")
                        && c.getName().toLowerCase(Locale.ROOT).contains("count"),
                9
        );
        return new OccupancyIndices(gIdx, nIdx, adjCountIdx);
    }

    private static boolean hasEmlNonThreatMarker(DataBlock block, OccupancyIndices idx) {
        if (block == null || idx == null || idx.adjCountIdx() < 0) {
            return false;
        }
        int count = safeInt(block, idx.adjCountIdx());
        if (count <= 0 || count > 256) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            String marker = safeString(block, idx.adjCountIdx() + 1 + i);
            if (Adjudication.EML_NON_THREAT_MARKER.equalsIgnoreCase(marker)) {
                return true;
            }
        }
        return false;
    }

    private static int resolveAlarmStateIndex(IObsSystemDatabase db, BigId dsId) {
        return extractAlarmStateIndex(getRecordStructure(db, dsId));
    }

    private static int extractAlarmStateIndex(DataComponent struct) {
        return findLeadingScalarIndex(
                struct,
                c -> RADHelper.DEF_ALARM.equalsIgnoreCase(c.getDefinition())
                        || RADHelper.DEF_EXTENDED_OCCUPANCY_ALARM_STATE.equalsIgnoreCase(c.getDefinition())
                        || (c.getName() != null && c.getName().toLowerCase().contains("alarmstate")),
                1
        );
    }

    private static int findLeadingScalarIndex(DataComponent struct, Predicate<DataComponent> matcher, int fallbackIndex) {
        if (struct == null) {
            return fallbackIndex;
        }
        int atomIndex = 0;
        int count = struct.getComponentCount();
        for (int i = 0; i < count; i++) {
            DataComponent child = struct.getComponent(i);
            if (child == null) {
                continue;
            }
            if (matcher.test(child)) {
                return atomIndex;
            }
            if (child instanceof DataArray) {
                // Stop before variable-size arrays; all scalar alarm/status fields reside in the leading header
                break;
            }
            if (child instanceof ScalarComponent) {
                atomIndex++;
            } else {
                atomIndex += Math.max(1, child.getComponentCount());
            }
        }
        return fallbackIndex;
    }

    private static boolean containsDefinition(DataComponent comp, String targetDefinition) {
        if (comp == null || targetDefinition == null) {
            return false;
        }
        if (targetDefinition.equalsIgnoreCase(comp.getDefinition())) {
            return true;
        }
        int count = comp.getComponentCount();
        for (int i = 0; i < count; i++) {
            if (containsDefinition(comp.getComponent(i), targetDefinition)) {
                return true;
            }
        }
        return false;
    }

    private static boolean safeBoolean(DataBlock block, int idx) {
        try {
            return block != null && idx >= 0 && block.getBooleanValue(idx);
        } catch (Exception e) {
            return false;
        }
    }

    private static int safeInt(DataBlock block, int idx) {
        try {
            return block != null && idx >= 0 && idx < block.getAtomCount() ? block.getIntValue(idx) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safeString(DataBlock block, int idx) {
        try {
            return block != null && idx >= 0 ? block.getStringValue(idx) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
