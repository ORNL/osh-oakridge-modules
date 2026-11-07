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

package com.botts.impl.service.oscar.reports.types;

import com.botts.impl.service.oscar.OSCARServiceModule;
import com.botts.impl.service.oscar.reports.helpers.ChartGenerator;
import com.botts.impl.service.oscar.reports.helpers.PdfDocumentBuilder;
import com.botts.impl.service.oscar.reports.helpers.ReportCmdType;
import com.botts.impl.service.oscar.reports.helpers.ReportDataAggregator;
import com.botts.impl.service.oscar.reports.helpers.ReportFormat;
import com.botts.impl.service.oscar.reports.helpers.TableGenerator;
import com.botts.impl.service.oscar.reports.helpers.Utils;
import com.botts.impl.service.oscar.reports.helpers.XlsxWorkbookWriter;
import com.botts.impl.service.oscar.reports.helpers.XlsxWorkbookWriter.CellValue;
import com.botts.impl.system.lane.LaneSystem;
import com.google.gson.stream.JsonWriter;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.module.IModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LaneReport extends Report {

    private final String laneUIDs;
    private final ReportFormat format;
    private final TableGenerator tableGenerator;

    public LaneReport(OutputStream out, Instant startTime, Instant endTime, String laneUIDs, OSCARServiceModule module) {
        this(out, startTime, endTime, laneUIDs, ReportFormat.PDF, module);
    }

    public LaneReport(
            OutputStream out,
            Instant startTime,
            Instant endTime,
            String laneUIDs,
            ReportFormat format,
            OSCARServiceModule module
    ) {
        super(out, startTime, endTime, module);
        this.laneUIDs = laneUIDs;
        this.format = format != null ? format : ReportFormat.PDF;
        this.tableGenerator = new TableGenerator();
    }

    @Override
    public void generate() throws DataStoreException {
        String nodeId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";

        List<String> targetLanes = ReportDataAggregator.resolveTargetLaneUIDs(module, laneUIDs);
        Map<String, String> multilineLaneLabels = ReportDataAggregator.resolveLaneDisplayLabels(module, targetLanes, true);
        Map<String, String> laneFriendlyNames = resolveLaneNamesOnly(module, targetLanes);
        Map<String, ReportDataAggregator.ObservationMetrics> perLane =
                ReportDataAggregator.aggregateMetricsByLane(module, targetLanes, start, end);

        ReportDataAggregator.ObservationMetrics totals = new ReportDataAggregator.ObservationMetrics();
        for (ReportDataAggregator.ObservationMetrics m : perLane.values()) {
            totals.add(m);
        }

        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);

        try {
            switch (format) {
                case CSV -> writeCsv(perLane, totals, laneFriendlyNames);
                case JSON -> writeJson(nodeId, scopeLine, targetLanes, perLane, totals, laneFriendlyNames);
                case XLSX -> writeXlsx(nodeId, scopeLine, targetLanes, perLane, totals, laneFriendlyNames);
                case PDF -> writePdf(nodeId, scopeLine, targetLanes, perLane, totals, multilineLaneLabels);
            }
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate Lane Report (" + format + ")", e);
        }
    }

    private void writePdf(
            String nodeId,
            String scopeLine,
            List<String> targetLanes,
            Map<String, ReportDataAggregator.ObservationMetrics> perLane,
            ReportDataAggregator.ObservationMetrics totals,
            Map<String, String> multilineLaneLabels
    ) throws IOException {
        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);

        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder(
                "Lane Report",
                nodeId,
                PdfDocumentBuilder.Orientation.LANDSCAPE
        )) {
            pdf.drawReportBanner(new PdfDocumentBuilder.HeaderMetadata(
                    "Lane Report",
                    nodeId,
                    null,
                    scopeLine,
                    start,
                    end
            ));

            pdf.drawKpiCards(List.of(
                    new PdfDocumentBuilder.KpiCard(
                            "Lanes Reported",
                            intFmt.format(targetLanes.size()),
                            targetLanes.size() == 1 ? "Single-lane scope" : "Multi-lane comparison",
                            PdfDocumentBuilder.ACCENT_BLUE
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Total Occupancies",
                            intFmt.format(totals.totalOccupancies),
                            "Alarm Rate: " + Utils.formatPercentage(totals.alarmOccupancyRatePct()),
                            PdfDocumentBuilder.ACCENT_SKY
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Total Alarms",
                            intFmt.format(totals.totalAlarms()),
                            "EML Suppressed: " + intFmt.format(totals.emlSuppressed)
                                    + " (" + Utils.formatPercentage(totals.emlAlarmRatePct()) + ")",
                            ChartGenerator.COLOR_GAMMA
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Faults & Tampers",
                            intFmt.format(totals.totalFaultsAndTamper()),
                            String.format(Locale.US, "Tamper: %s | Faults: %s",
                                    intFmt.format(totals.tamper),
                                    intFmt.format(totals.gammaHighFault + totals.gammaLowFault + totals.neutronHighFault)),
                            ChartGenerator.COLOR_TAMPER
                    )
            ));

            // Single unified per-lane table so every lane is listed once with both alarm and fault metrics
            pdf.drawSectionHeader(
                    "Per-Lane Alarm, Occupancy & Fault Breakdown",
                    "Complete radiation alarm, EML suppression, occupancy rate, and state-of-health metrics per lane",
                    80f
            );
            Map<String, Map<String, String>> unifiedTableByLane = new LinkedHashMap<>();
            for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
                unifiedTableByLane.put(entry.getKey(), formatUnifiedLaneRow(entry.getValue(), intFmt));
            }
            tableGenerator.drawLanesTable(pdf, unifiedTableByLane, multilineLaneLabels);

            pdf.finishAndSave(out);
        }
    }

    private void writeCsv(
            Map<String, ReportDataAggregator.ObservationMetrics> perLane,
            ReportDataAggregator.ObservationMetrics totals,
            Map<String, String> laneFriendlyNames
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            XlsxWorkbookWriter.writeCsvRow(writer, laneColumnHeaders());
            for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
                String uid = entry.getKey();
                String laneName = laneFriendlyNames.getOrDefault(uid, uid);
                XlsxWorkbookWriter.writeCsvRow(writer, buildCsvRow(laneName, uid, entry.getValue()));
            }
            if (perLane.size() > 1) {
                String totalLabel = "All Selected Lanes (" + perLane.size() + ")";
                XlsxWorkbookWriter.writeCsvRow(writer, buildCsvRow(totalLabel, "TOTAL", totals));
            }
            writer.flush();
        }
    }

    private static List<String> buildCsvRow(String laneName, String laneUid, ReportDataAggregator.ObservationMetrics m) {
        return List.of(
                XlsxWorkbookWriter.guardSpreadsheetFormulaPrefix(laneName),
                laneUid,
                String.valueOf(m.totalOccupancies),
                String.valueOf(m.totalAlarms()),
                String.valueOf(m.gammaAlarm),
                String.valueOf(m.neutronAlarm),
                String.valueOf(m.gammaNeutronAlarm),
                String.valueOf(m.emlSuppressed),
                String.format(Locale.US, "%.2f", m.alarmOccupancyRatePct()),
                String.format(Locale.US, "%.2f", m.emlAlarmRatePct()),
                String.valueOf(m.totalFaultsAndTamper()),
                String.valueOf(m.tamper),
                String.valueOf(m.gammaHighFault),
                String.valueOf(m.gammaLowFault),
                String.valueOf(m.neutronHighFault)
        );
    }

    private void writeJson(
            String nodeId,
            String scopeLine,
            List<String> targetLanes,
            Map<String, ReportDataAggregator.ObservationMetrics> perLane,
            ReportDataAggregator.ObservationMetrics totals,
            Map<String, String> laneFriendlyNames
    ) throws IOException {
        try (JsonWriter jw = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))) {
            jw.setIndent("  ");
            jw.setHtmlSafe(false);
            jw.beginObject();
            jw.name("reportType").value(ReportCmdType.LANE.name());
            jw.name("reportTitle").value("Lane Report");
            jw.name("nodeId").value(nodeId);
            jw.name("scope").value(scopeLine);
            jw.name("startDateTime").value(start != null ? DateTimeFormatter.ISO_INSTANT.format(start) : "");
            jw.name("endDateTime").value(end != null ? DateTimeFormatter.ISO_INSTANT.format(end) : "");
            jw.name("generatedAt").value(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            jw.name("summary").beginObject();
            jw.name("laneCount").value(targetLanes.size());
            jw.name("totalOccupancies").value(totals.totalOccupancies);
            jw.name("totalAlarms").value(totals.totalAlarms());
            jw.name("gammaAlarm").value(totals.gammaAlarm);
            jw.name("neutronAlarm").value(totals.neutronAlarm);
            jw.name("gammaNeutronAlarm").value(totals.gammaNeutronAlarm);
            jw.name("emlSuppressed").value(totals.emlSuppressed);
            jw.name("alarmOccupancyRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(totals.alarmOccupancyRatePct()));
            jw.name("emlAlarmRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(totals.emlAlarmRatePct()));
            jw.name("totalFaultsAndTamper").value(totals.totalFaultsAndTamper());
            jw.name("tamper").value(totals.tamper);
            jw.name("gammaHighFault").value(totals.gammaHighFault);
            jw.name("gammaLowFault").value(totals.gammaLowFault);
            jw.name("neutronHighFault").value(totals.neutronHighFault);
            jw.endObject();

            jw.name("records").beginArray();
            for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
                String uid = entry.getKey();
                String laneName = laneFriendlyNames.getOrDefault(uid, uid);
                ReportDataAggregator.ObservationMetrics m = entry.getValue();
                jw.beginObject();
                jw.name("laneName").value(laneName);
                jw.name("laneUid").value(uid);
                jw.name("totalOccupancies").value(m.totalOccupancies);
                jw.name("totalAlarms").value(m.totalAlarms());
                jw.name("gammaAlarm").value(m.gammaAlarm);
                jw.name("neutronAlarm").value(m.neutronAlarm);
                jw.name("gammaNeutronAlarm").value(m.gammaNeutronAlarm);
                jw.name("emlSuppressed").value(m.emlSuppressed);
                jw.name("alarmOccupancyRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(m.alarmOccupancyRatePct()));
                jw.name("emlAlarmRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(m.emlAlarmRatePct()));
                jw.name("totalFaultsAndTamper").value(m.totalFaultsAndTamper());
                jw.name("tamper").value(m.tamper);
                jw.name("gammaHighFault").value(m.gammaHighFault);
                jw.name("gammaLowFault").value(m.gammaLowFault);
                jw.name("neutronHighFault").value(m.neutronHighFault);
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
            jw.flush();
        }
    }

    private void writeXlsx(
            String nodeId,
            String scopeLine,
            List<String> targetLanes,
            Map<String, ReportDataAggregator.ObservationMetrics> perLane,
            ReportDataAggregator.ObservationMetrics totals,
            Map<String, String> laneFriendlyNames
    ) throws IOException {
        List<String> headers = laneColumnHeaders();
        List<Double> widths = List.of(
                22.0, 28.0, 18.0, 16.0, 16.0, 16.0, 20.0, 18.0,
                22.0, 18.0, 20.0, 14.0, 14.0, 14.0, 16.0
        );

        List<List<CellValue>> laneRows = new ArrayList<>(perLane.size() + 1);
        for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
            String uid = entry.getKey();
            String laneName = laneFriendlyNames.getOrDefault(uid, uid);
            laneRows.add(buildXlsxRow(laneName, uid, entry.getValue()));
        }
        if (perLane.size() > 1) {
            laneRows.add(buildXlsxRow("All Selected Lanes (" + perLane.size() + ")", "TOTAL", totals));
        }

        List<List<CellValue>> summaryRows = List.of(
                List.of(CellValue.text("Report Title"), CellValue.text("Lane Report")),
                List.of(CellValue.text("Node ID"), CellValue.text(nodeId)),
                List.of(CellValue.text("Scope"), CellValue.text(scopeLine)),
                List.of(CellValue.text("Start (UTC)"), CellValue.text(start != null ? DateTimeFormatter.ISO_INSTANT.format(start) : "")),
                List.of(CellValue.text("End (UTC)"), CellValue.text(end != null ? DateTimeFormatter.ISO_INSTANT.format(end) : "")),
                List.of(CellValue.text("Lanes Reported"), CellValue.integer(targetLanes.size())),
                List.of(CellValue.text("Total Occupancies"), CellValue.integer(totals.totalOccupancies)),
                List.of(CellValue.text("Total Alarms"), CellValue.integer(totals.totalAlarms())),
                List.of(CellValue.text("EML Suppressed"), CellValue.integer(totals.emlSuppressed)),
                List.of(CellValue.text("Total Faults & Tampers"), CellValue.integer(totals.totalFaultsAndTamper()))
        );

        XlsxWorkbookWriter.writeWorkbook(out, List.of(
                new XlsxWorkbookWriter.SheetSpec("Lane Statistics", headers, widths, laneRows, true),
                new XlsxWorkbookWriter.SheetSpec("Summary", List.of("Metric", "Value"), List.of(28.0, 46.0), summaryRows, false)
        ));
    }

    private static List<CellValue> buildXlsxRow(String laneName, String laneUid, ReportDataAggregator.ObservationMetrics m) {
        return List.of(
                CellValue.text(laneName),
                CellValue.text(laneUid),
                CellValue.integer(m.totalOccupancies),
                CellValue.integer(m.totalAlarms()),
                CellValue.integer(m.gammaAlarm),
                CellValue.integer(m.neutronAlarm),
                CellValue.integer(m.gammaNeutronAlarm),
                CellValue.integer(m.emlSuppressed),
                CellValue.decimal(m.alarmOccupancyRatePct()),
                CellValue.decimal(m.emlAlarmRatePct()),
                CellValue.integer(m.totalFaultsAndTamper()),
                CellValue.integer(m.tamper),
                CellValue.integer(m.gammaHighFault),
                CellValue.integer(m.gammaLowFault),
                CellValue.integer(m.neutronHighFault)
        );
    }

    private static List<String> laneColumnHeaders() {
        return List.of(
                "Lane Name",
                "Lane UID",
                "Total Occupancies",
                "Total Alarms",
                "Gamma Alarm",
                "Neutron Alarm",
                "Gamma-Neutron Alarm",
                "EML Suppressed",
                "Alarm Occupancy Rate (%)",
                "EML Alarm Rate (%)",
                "Total Faults & Tampers",
                "Tamper",
                "Gamma-High",
                "Gamma-Low",
                "Neutron-High"
        );
    }

    private static Map<String, String> resolveLaneNamesOnly(OSCARServiceModule module, List<String> laneUIDs) {
        Map<String, String> names = new HashMap<>();
        if (module != null && module.getParentHub() != null && module.getParentHub().getModuleRegistry() != null) {
            for (IModule<?> mod : module.getParentHub().getModuleRegistry().getLoadedModules()) {
                if (mod instanceof LaneSystem lane && lane.getUniqueIdentifier() != null && lane.getName() != null && !lane.getName().isBlank()) {
                    names.put(lane.getUniqueIdentifier().trim(), lane.getName().trim());
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (laneUIDs != null) {
            for (String uid : laneUIDs) {
                if (uid != null && !uid.isBlank()) {
                    String trimmed = uid.trim();
                    out.put(trimmed, names.getOrDefault(trimmed, trimmed));
                }
            }
        }
        return out;
    }

    @Override
    public String getReportType() {
        return ReportCmdType.LANE.name();
    }

    private static Map<String, String> formatUnifiedLaneRow(ReportDataAggregator.ObservationMetrics m, NumberFormat intFmt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Occupancies", intFmt.format(m.totalOccupancies));
        row.put("Gamma", intFmt.format(m.gammaAlarm));
        row.put("Neutron", intFmt.format(m.neutronAlarm));
        row.put("G+N", intFmt.format(m.gammaNeutronAlarm));
        row.put("EML Supp.", intFmt.format(m.emlSuppressed));
        row.put("Alarm Rate", Utils.formatPercentage(m.alarmOccupancyRatePct()));
        row.put("EML Rate", Utils.formatPercentage(m.emlAlarmRatePct()));
        row.put("Tamper", intFmt.format(m.tamper));
        row.put("G-High", intFmt.format(m.gammaHighFault));
        row.put("G-Low", intFmt.format(m.gammaLowFault));
        row.put("N-High", intFmt.format(m.neutronHighFault));
        return row;
    }
}
