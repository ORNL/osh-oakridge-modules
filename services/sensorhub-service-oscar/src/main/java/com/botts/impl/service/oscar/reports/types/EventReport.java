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
import com.botts.impl.service.oscar.reports.helpers.EventReportType;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EventReport extends Report {

    private final EventReportType eventType;
    private final String laneUIDs;
    private final TableGenerator tableGenerator;
    private final ChartGenerator chartGenerator;

    public EventReport(OutputStream outputStream, Instant startTime, Instant endTime,
                       EventReportType eventType, String laneUIDs, OSCARServiceModule module) {
        super(outputStream, startTime, endTime, module);
        this.eventType = eventType != null ? eventType : EventReportType.ALARMS;
        this.laneUIDs = laneUIDs;
        this.tableGenerator = new TableGenerator();
        this.chartGenerator = new ChartGenerator(module);
    }

    @Override
    public void generate() throws DataStoreException {
        String nodeId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";
        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);

        Map<String, ReportDataAggregator.ObservationMetrics> dailyMetrics =
                ReportDataAggregator.aggregateDailyEventMetrics(module, laneUIDs, start, end, eventType);

        ReportDataAggregator.ObservationMetrics totals = new ReportDataAggregator.ObservationMetrics();
        for (ReportDataAggregator.ObservationMetrics m : dailyMetrics.values()) {
            totals.add(m);
        }

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);

        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder("Event Report", nodeId)) {
            pdf.drawReportBanner(new PdfDocumentBuilder.HeaderMetadata(
                    "Event Report",
                    nodeId,
                    "Event Type: " + eventType,
                    scopeLine,
                    start,
                    end
            ));

            if (eventType == EventReportType.ALARMS_OCCUPANCIES) {
                renderAlarmsAndOccupancies(pdf, dailyMetrics, totals, intFmt);
            } else if (eventType == EventReportType.ALARMS) {
                renderAlarmsOnly(pdf, dailyMetrics, totals, intFmt);
            } else if (eventType == EventReportType.SOH) {
                renderSoh(pdf, dailyMetrics, totals, intFmt);
            } else {
                pdf.drawCalloutBox("No specific event subtype selected (Event Type: " + eventType + ").");
            }

            pdf.finishAndSave(out);
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate Event Report PDF", e);
        }
    }

    @Override
    public String getReportType() {
        return ReportCmdType.EVENT.name();
    }

    private void renderAlarmsAndOccupancies(
            PdfDocumentBuilder pdf,
            Map<String, ReportDataAggregator.ObservationMetrics> dailyMetrics,
            ReportDataAggregator.ObservationMetrics totals,
            NumberFormat intFmt
    ) throws IOException {
        pdf.drawKpiCards(List.of(
                new PdfDocumentBuilder.KpiCard(
                        "Total Occupancies",
                        intFmt.format(totals.totalOccupancies),
                        "Alarm Rate: " + Utils.formatPercentage(totals.alarmOccupancyRatePct()),
                        ChartGenerator.COLOR_OCCUPANCY_LINE
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Total Alarms",
                        intFmt.format(totals.totalAlarms()),
                        "Gamma: " + intFmt.format(totals.gammaAlarm),
                        ChartGenerator.COLOR_GAMMA
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Neutron & G+N Alarms",
                        intFmt.format(totals.neutronAlarm + totals.gammaNeutronAlarm),
                        "N: " + intFmt.format(totals.neutronAlarm) + " | G+N: " + intFmt.format(totals.gammaNeutronAlarm),
                        ChartGenerator.COLOR_GAMMA_NEUTRON
                ),
                new PdfDocumentBuilder.KpiCard(
                        "EML Suppressed",
                        intFmt.format(totals.emlSuppressed),
                        "EML Rate: " + Utils.formatPercentage(totals.emlAlarmRatePct()),
                        ChartGenerator.COLOR_EML_SUPPRESSED
                )
        ));

        List<String> dates = new ArrayList<>(dailyMetrics.keySet());
        List<Long> gammaVals = new ArrayList<>(dates.size());
        List<Long> gnVals = new ArrayList<>(dates.size());
        List<Long> neutronVals = new ArrayList<>(dates.size());
        List<Long> emlVals = new ArrayList<>(dates.size());
        List<Long> occVals = new ArrayList<>(dates.size());
        Map<String, Map<String, String>> tableByDate = new LinkedHashMap<>();

        for (String date : dates) {
            ReportDataAggregator.ObservationMetrics m = dailyMetrics.get(date);
            gammaVals.add(m.gammaAlarm);
            gnVals.add(m.gammaNeutronAlarm);
            neutronVals.add(m.neutronAlarm);
            emlVals.add(m.emlSuppressed);
            occVals.add(m.totalOccupancies);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("Gamma", String.valueOf(m.gammaAlarm));
            row.put("Gamma-Neutron", String.valueOf(m.gammaNeutronAlarm));
            row.put("Neutron", String.valueOf(m.neutronAlarm));
            row.put("EML-Suppressed", String.valueOf(m.emlSuppressed));
            row.put("Total Occupancy", String.valueOf(m.totalOccupancies));
            tableByDate.put(date, row);
        }

        pdf.drawSectionHeader(
                "Alarms and Occupancies by UTC Day",
                "Dual-axis view: stacked alarms/suppressions on left axis, total occupancies on independent right axis",
                245f
        );

        List<ChartGenerator.StackedSeries> barSeries = List.of(
                new ChartGenerator.StackedSeries("Gamma", ChartGenerator.COLOR_GAMMA, gammaVals),
                new ChartGenerator.StackedSeries("Gamma-Neutron", ChartGenerator.COLOR_GAMMA_NEUTRON, gnVals),
                new ChartGenerator.StackedSeries("Neutron", ChartGenerator.COLOR_NEUTRON, neutronVals),
                new ChartGenerator.StackedSeries("EML-Suppressed", ChartGenerator.COLOR_EML_SUPPRESSED, emlVals)
        );
        ChartGenerator.LineSeries occLine = new ChartGenerator.LineSeries(
                "Total Occupancy (Right Axis)", ChartGenerator.COLOR_OCCUPANCY_LINE, occVals
        );

        chartGenerator.drawDualAxisStackedBarLineChart(
                pdf,
                "Alarms and Occupancies",
                "Date (UTC)",
                "Alarm / Suppressed Count",
                dates,
                barSeries,
                "Total Occupancies",
                occLine
        );

        pdf.drawSectionHeader("Daily Breakdown Table", "Exact daily alarm, EML suppression, and occupancy counts (UTC)", 70f);
        tableGenerator.drawTableByDate(pdf, tableByDate, true);
    }

    private void renderAlarmsOnly(
            PdfDocumentBuilder pdf,
            Map<String, ReportDataAggregator.ObservationMetrics> dailyMetrics,
            ReportDataAggregator.ObservationMetrics totals,
            NumberFormat intFmt
    ) throws IOException {
        pdf.drawKpiCards(List.of(
                new PdfDocumentBuilder.KpiCard(
                        "Total Alarms",
                        intFmt.format(totals.totalAlarms()),
                        dailyMetrics.size() + (dailyMetrics.size() == 1 ? " UTC day" : " UTC days"),
                        ChartGenerator.COLOR_GAMMA
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Gamma Alarms",
                        intFmt.format(totals.gammaAlarm),
                        "Gamma-only triggers",
                        ChartGenerator.COLOR_GAMMA
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Neutron & G+N Alarms",
                        intFmt.format(totals.neutronAlarm + totals.gammaNeutronAlarm),
                        "N: " + intFmt.format(totals.neutronAlarm) + " | G+N: " + intFmt.format(totals.gammaNeutronAlarm),
                        ChartGenerator.COLOR_GAMMA_NEUTRON
                ),
                new PdfDocumentBuilder.KpiCard(
                        "EML Suppressed",
                        intFmt.format(totals.emlSuppressed),
                        "EML Rate: " + Utils.formatPercentage(totals.emlAlarmRatePct()),
                        ChartGenerator.COLOR_EML_SUPPRESSED
                )
        ));

        List<String> dates = new ArrayList<>(dailyMetrics.keySet());
        List<Long> gammaVals = new ArrayList<>(dates.size());
        List<Long> gnVals = new ArrayList<>(dates.size());
        List<Long> neutronVals = new ArrayList<>(dates.size());
        List<Long> emlVals = new ArrayList<>(dates.size());
        Map<String, Map<String, String>> tableByDate = new LinkedHashMap<>();

        for (String date : dates) {
            ReportDataAggregator.ObservationMetrics m = dailyMetrics.get(date);
            gammaVals.add(m.gammaAlarm);
            gnVals.add(m.gammaNeutronAlarm);
            neutronVals.add(m.neutronAlarm);
            emlVals.add(m.emlSuppressed);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("Gamma", String.valueOf(m.gammaAlarm));
            row.put("Gamma-Neutron", String.valueOf(m.gammaNeutronAlarm));
            row.put("Neutron", String.valueOf(m.neutronAlarm));
            row.put("EML-Suppressed", String.valueOf(m.emlSuppressed));
            tableByDate.put(date, row);
        }

        pdf.drawSectionHeader("Alarm Statistics by UTC Day", "Stacked daily radiation alarms and EML suppressions", 245f);
        chartGenerator.drawStackedBarChart(
                pdf,
                "Alarms",
                "Date (UTC)",
                "Count",
                dates,
                List.of(
                        new ChartGenerator.StackedSeries("Gamma", ChartGenerator.COLOR_GAMMA, gammaVals),
                        new ChartGenerator.StackedSeries("Gamma-Neutron", ChartGenerator.COLOR_GAMMA_NEUTRON, gnVals),
                        new ChartGenerator.StackedSeries("Neutron", ChartGenerator.COLOR_NEUTRON, neutronVals),
                        new ChartGenerator.StackedSeries("EML-Suppressed", ChartGenerator.COLOR_EML_SUPPRESSED, emlVals)
                )
        );

        pdf.drawSectionHeader("Daily Alarm Breakdown", "Exact daily counts across alarm categories (UTC)", 70f);
        tableGenerator.drawTableByDate(pdf, tableByDate, true);
    }

    private void renderSoh(
            PdfDocumentBuilder pdf,
            Map<String, ReportDataAggregator.ObservationMetrics> dailyMetrics,
            ReportDataAggregator.ObservationMetrics totals,
            NumberFormat intFmt
    ) throws IOException {
        pdf.drawKpiCards(List.of(
                new PdfDocumentBuilder.KpiCard(
                        "Total SOH Events",
                        intFmt.format(totals.totalFaultsAndTamper()),
                        dailyMetrics.size() + (dailyMetrics.size() == 1 ? " UTC day" : " UTC days"),
                        ChartGenerator.COLOR_TAMPER
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Gamma High Faults",
                        intFmt.format(totals.gammaHighFault),
                        "Fault - Gamma High",
                        ChartGenerator.COLOR_GAMMA
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Gamma Low Faults",
                        intFmt.format(totals.gammaLowFault),
                        "Fault - Gamma Low",
                        ChartGenerator.COLOR_GAMMA_LOW
                ),
                new PdfDocumentBuilder.KpiCard(
                        "Neutron High & Tamper",
                        intFmt.format(totals.neutronHighFault + totals.tamper),
                        "N-High: " + intFmt.format(totals.neutronHighFault) + " | Tamper: " + intFmt.format(totals.tamper),
                        ChartGenerator.COLOR_NEUTRON
                )
        ));

        List<String> dates = new ArrayList<>(dailyMetrics.keySet());
        List<Long> ghVals = new ArrayList<>(dates.size());
        List<Long> glVals = new ArrayList<>(dates.size());
        List<Long> nhVals = new ArrayList<>(dates.size());
        List<Long> tamperVals = new ArrayList<>(dates.size());
        Map<String, Map<String, String>> tableByDate = new LinkedHashMap<>();

        for (String date : dates) {
            ReportDataAggregator.ObservationMetrics m = dailyMetrics.get(date);
            ghVals.add(m.gammaHighFault);
            glVals.add(m.gammaLowFault);
            nhVals.add(m.neutronHighFault);
            tamperVals.add(m.tamper);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("Gamma High", String.valueOf(m.gammaHighFault));
            row.put("Gamma Low", String.valueOf(m.gammaLowFault));
            row.put("Neutron High", String.valueOf(m.neutronHighFault));
            row.put("Tamper", String.valueOf(m.tamper));
            tableByDate.put(date, row);
        }

        pdf.drawSectionHeader("State of Health (SOH) by UTC Day", "Daily detector fault and tamper occurrences", 245f);
        chartGenerator.drawStackedBarChart(
                pdf,
                "SOH",
                "Date (UTC)",
                "Count",
                dates,
                List.of(
                        new ChartGenerator.StackedSeries("Gamma High", ChartGenerator.COLOR_GAMMA, ghVals),
                        new ChartGenerator.StackedSeries("Gamma Low", ChartGenerator.COLOR_GAMMA_LOW, glVals),
                        new ChartGenerator.StackedSeries("Neutron High", ChartGenerator.COLOR_NEUTRON, nhVals),
                        new ChartGenerator.StackedSeries("Tamper", ChartGenerator.COLOR_TAMPER, tamperVals)
                )
        );

        pdf.drawSectionHeader("Daily SOH Breakdown", "Exact daily fault and tamper counts (UTC)", 70f);
        tableGenerator.drawTableByDate(pdf, tableByDate, true);
    }
}
