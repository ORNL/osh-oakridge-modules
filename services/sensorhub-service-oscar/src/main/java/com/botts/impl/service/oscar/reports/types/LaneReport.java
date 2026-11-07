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
import com.botts.impl.service.oscar.reports.helpers.TableGenerator;
import com.botts.impl.service.oscar.reports.helpers.Utils;
import org.sensorhub.api.datastore.DataStoreException;

import java.io.IOException;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LaneReport extends Report {

    private final String laneUIDs;
    private final TableGenerator tableGenerator;

    public LaneReport(OutputStream out, Instant startTime, Instant endTime, String laneUIDs, OSCARServiceModule module) {
        super(out, startTime, endTime, module);
        this.laneUIDs = laneUIDs;
        this.tableGenerator = new TableGenerator();
    }

    @Override
    public void generate() throws DataStoreException {
        String nodeId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";

        List<String> targetLanes = ReportDataAggregator.resolveTargetLaneUIDs(module, laneUIDs);
        Map<String, String> multilineLaneLabels = ReportDataAggregator.resolveLaneDisplayLabels(module, targetLanes, true);
        Map<String, ReportDataAggregator.ObservationMetrics> perLane =
                ReportDataAggregator.aggregateMetricsByLane(module, targetLanes, start, end);

        ReportDataAggregator.ObservationMetrics totals = new ReportDataAggregator.ObservationMetrics();
        for (ReportDataAggregator.ObservationMetrics m : perLane.values()) {
            totals.add(m);
        }

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);
        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);

        // Switch to Landscape automatically when many lanes or wide comparisons benefit, or Portrait for compact crisp tables
        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder("Lane Report", nodeId)) {
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

            // 1. Alarm Statistics per lane
            pdf.drawSectionHeader("Alarm Statistics", "Per-lane occupancy, radiation alarm, and EML suppression breakdown", 80f);
            Map<String, Map<String, String>> alarmTableByLane = new LinkedHashMap<>();
            for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
                alarmTableByLane.put(entry.getKey(), formatAlarmRow(entry.getValue(), intFmt));
            }
            if (perLane.size() > 1) {
                String totalKey = "All Selected Lanes (" + perLane.size() + ")";
                multilineLaneLabels.put(totalKey, totalKey);
                alarmTableByLane.put(totalKey, formatAlarmRow(totals, intFmt));
            }
            tableGenerator.drawLanesTable(pdf, alarmTableByLane, multilineLaneLabels);

            // 2. Fault Statistics per lane
            pdf.drawSectionHeader("Fault Statistics", "Per-lane state-of-health faults and tamper events", 80f);
            Map<String, Map<String, String>> faultTableByLane = new LinkedHashMap<>();
            for (Map.Entry<String, ReportDataAggregator.ObservationMetrics> entry : perLane.entrySet()) {
                faultTableByLane.put(entry.getKey(), formatFaultRow(entry.getValue(), intFmt));
            }
            if (perLane.size() > 1) {
                String totalKey = "All Selected Lanes (" + perLane.size() + ")";
                faultTableByLane.put(totalKey, formatFaultRow(totals, intFmt));
            }
            tableGenerator.drawLanesTable(pdf, faultTableByLane, multilineLaneLabels);

            pdf.finishAndSave(out);
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate Lane Report PDF", e);
        }
    }

    @Override
    public String getReportType() {
        return ReportCmdType.LANE.name();
    }

    private static Map<String, String> formatAlarmRow(ReportDataAggregator.ObservationMetrics m, NumberFormat intFmt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Gamma Alarm", intFmt.format(m.gammaAlarm));
        row.put("Neutron Alarm", intFmt.format(m.neutronAlarm));
        row.put("Gamma-Neutron Alarm", intFmt.format(m.gammaNeutronAlarm));
        row.put("EML Suppressed", intFmt.format(m.emlSuppressed));
        row.put("Total Occupancies", intFmt.format(m.totalOccupancies));
        row.put("Alarm Occupancy Rate", Utils.formatPercentage(m.alarmOccupancyRatePct()));
        row.put("EML Alarm Rate", Utils.formatPercentage(m.emlAlarmRatePct()));
        return row;
    }

    private static Map<String, String> formatFaultRow(ReportDataAggregator.ObservationMetrics m, NumberFormat intFmt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Tamper", intFmt.format(m.tamper));
        row.put("Gamma-High", intFmt.format(m.gammaHighFault));
        row.put("Gamma-Low", intFmt.format(m.gammaLowFault));
        row.put("Neutron-High", intFmt.format(m.neutronHighFault));
        return row;
    }
}
