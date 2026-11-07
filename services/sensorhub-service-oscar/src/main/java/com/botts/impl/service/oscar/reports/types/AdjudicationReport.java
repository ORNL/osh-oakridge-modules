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
import org.sensorhub.api.datastore.DataStoreException;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AdjudicationReport extends Report {

    private final String laneUIDs;
    private final TableGenerator tableGenerator;
    private final ChartGenerator chartGenerator;

    public AdjudicationReport(OutputStream out, Instant startTime, Instant endTime, String laneUIDs, OSCARServiceModule module) {
        super(out, startTime, endTime, module);
        this.laneUIDs = laneUIDs;
        this.tableGenerator = new TableGenerator();
        this.chartGenerator = new ChartGenerator(module);
    }

    @Override
    public void generate() throws DataStoreException {
        String nodeId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";

        List<String> targetLanes = ReportDataAggregator.resolveTargetLaneUIDs(module, laneUIDs);
        Map<String, String> inlineLaneLabels = ReportDataAggregator.resolveLaneDisplayLabels(module, targetLanes, false);

        // Single-pass aggregation per lane
        Map<String, ReportDataAggregator.AdjudicationLaneSummary> summariesByLane = new LinkedHashMap<>();
        long grandTotalRecords = 0L;
        long realAlarmCount = 0L;
        long innocentOrAutoCount = 0L;
        long falseAlarmCount = 0L;
        Set<String> activeIsotopesAcrossScope = new LinkedHashSet<>();

        for (String laneUid : targetLanes) {
            ReportDataAggregator.AdjudicationLaneSummary summary =
                    ReportDataAggregator.aggregateAdjudicationsForLane(module, laneUid, start, end);
            summariesByLane.put(laneUid, summary);
            grandTotalRecords += summary.totalRecords();

            for (ReportDataAggregator.DispositionCodeSpec spec : summary.dispositionSpecs()) {
                long c = summary.countsByCode().getOrDefault(spec.code(), 0L);
                if (c <= 0L) {
                    continue;
                }
                switch (spec.group()) {
                    case "Real Alarm" -> realAlarmCount += c;
                    case "Innocent Alarm", "Automated" -> innocentOrAutoCount += c;
                    case "False Alarm" -> falseAlarmCount += c;
                    default -> {
                        // Tracked in full Disposition breakdown table
                    }
                }
            }
            for (Map.Entry<String, Long> isoEntry : summary.isotopeCountsBySymbol().entrySet()) {
                if (isoEntry.getValue() != null && isoEntry.getValue() > 0L) {
                    activeIsotopesAcrossScope.add(isoEntry.getKey());
                }
            }
        }

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);
        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);

        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder("Adjudication Report", nodeId)) {
            pdf.drawReportBanner(new PdfDocumentBuilder.HeaderMetadata(
                    "Adjudication Report",
                    nodeId,
                    null,
                    scopeLine,
                    start,
                    end
            ));

            pdf.drawKpiCards(List.of(
                    new PdfDocumentBuilder.KpiCard(
                            "Total Adjudications",
                            intFmt.format(grandTotalRecords),
                            targetLanes.size() + (targetLanes.size() == 1 ? " lane" : " lanes"),
                            PdfDocumentBuilder.ACCENT_BLUE
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Real Alarms",
                            intFmt.format(realAlarmCount),
                            "Codes 1 - 2",
                            ChartGenerator.GROUP_REAL_ALARM
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Innocent & EML Auto",
                            intFmt.format(innocentOrAutoCount),
                            "Codes 3 - 5 & EML Non-Threat",
                            ChartGenerator.GROUP_AUTOMATED
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "False Alarms",
                            intFmt.format(falseAlarmCount),
                            "Codes 6 - 8",
                            ChartGenerator.GROUP_FALSE_ALARM
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Isotopes Identified",
                            intFmt.format(activeIsotopesAcrossScope.size()),
                            activeIsotopesAcrossScope.isEmpty() ? "No isotopes recorded" : String.join(", ", activeIsotopesAcrossScope),
                            PdfDocumentBuilder.ACCENT_PURPLE
                    )
            ));

            if (targetLanes.isEmpty()) {
                pdf.drawCalloutBox("No lanes selected or configured for Adjudication Report.");
            }

            for (String laneUid : targetLanes) {
                String friendlyLane = inlineLaneLabels.getOrDefault(laneUid, laneUid);
                ReportDataAggregator.AdjudicationLaneSummary summary = summariesByLane.get(laneUid);
                renderLaneDispositionSection(pdf, friendlyLane, summary, intFmt);
                if (summary != null && summary.totalRecords() > 0L) {
                    renderLaneIsotopeSection(pdf, friendlyLane, summary, intFmt);
                }
            }

            pdf.finishAndSave(out);
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate Adjudication Report PDF", e);
        }
    }

    @Override
    public String getReportType() {
        return ReportCmdType.ADJUDICATION.name();
    }

    private void renderLaneDispositionSection(
            PdfDocumentBuilder pdf,
            String friendlyLane,
            ReportDataAggregator.AdjudicationLaneSummary summary,
            NumberFormat intFmt
    ) throws IOException {
        pdf.drawSectionHeader(
                "Disposition - " + friendlyLane,
                "Percentage and count breakdown by built-in, custom, and automated disposition codes",
                80f
        );

        if (summary == null || summary.totalRecords() == 0L) {
            pdf.drawCalloutBox("No data available for the selected time period and selected lane: " + friendlyLane);
            return;
        }

        List<ChartGenerator.HorizontalBarItem> barItems = new ArrayList<>();
        Map<String, Color> presentGroups = new LinkedHashMap<>();
        List<List<String>> dispTableRows = new ArrayList<>();

        for (ReportDataAggregator.DispositionCodeSpec spec : summary.dispositionSpecs()) {
            long count = summary.countsByCode().getOrDefault(spec.code(), 0L);
            double pct = (count * 100.0) / summary.totalRecords();
            Color groupColor = ChartGenerator.colorForDispositionGroup(spec.group());

            if (spec.label() != null && !spec.label().isBlank()) {
                presentGroups.putIfAbsent(spec.group(), groupColor);
                String callout = count > 0L
                        ? String.format(Locale.US, "%s (%.1f%%)", intFmt.format(count), pct)
                        : "0 (0.0%)";
                barItems.add(new ChartGenerator.HorizontalBarItem(
                        spec.label(),
                        pct,
                        callout,
                        groupColor,
                        count > 0L
                ));
            }

            dispTableRows.add(List.of(
                    String.valueOf(spec.code()),
                    (spec.label() == null || spec.label().isBlank()) ? "(None)" : spec.label(),
                    spec.group(),
                    intFmt.format(count),
                    String.format(Locale.US, "%.1f%%", pct)
            ));
        }

        List<ChartGenerator.LegendItem> groupLegend = new ArrayList<>();
        for (Map.Entry<String, Color> g : presentGroups.entrySet()) {
            groupLegend.add(new ChartGenerator.LegendItem(g.getKey(), g.getValue()));
        }

        chartGenerator.drawHorizontalBarChart(
                pdf,
                "Disposition",
                "% of Total Number of Records",
                barItems,
                groupLegend,
                true,
                false
        );

        List<TableGenerator.ColumnSpec> cols = List.of(
                new TableGenerator.ColumnSpec("Code", 0.55f, TableGenerator.Align.RIGHT),
                new TableGenerator.ColumnSpec("Label", 2.85f, TableGenerator.Align.LEFT),
                new TableGenerator.ColumnSpec("Group", 1.15f, TableGenerator.Align.LEFT),
                new TableGenerator.ColumnSpec("Count", 0.70f, TableGenerator.Align.RIGHT, true),
                new TableGenerator.ColumnSpec("Percent", 0.75f, TableGenerator.Align.RIGHT)
        );
        tableGenerator.drawTable(pdf, cols, dispTableRows, false, 7.6f);
    }

    private void renderLaneIsotopeSection(
            PdfDocumentBuilder pdf,
            String friendlyLane,
            ReportDataAggregator.AdjudicationLaneSummary summary,
            NumberFormat intFmt
    ) throws IOException {
        pdf.drawSectionHeader(
                "Isotope Results - " + friendlyLane,
                "Non-zero isotope identification counts recorded in completed adjudications",
                80f
        );

        Map<String, Long> isoCounts = summary != null ? summary.isotopeCountsBySymbol() : Map.of();
        Map<String, Long> nonZeroIsoCounts = new LinkedHashMap<>();
        List<ChartGenerator.HorizontalBarItem> barItems = new ArrayList<>();

        for (Map.Entry<String, Long> entry : isoCounts.entrySet()) {
            long count = entry.getValue() != null ? entry.getValue() : 0L;
            if (count <= 0L) {
                continue;
            }
            String symbol = entry.getKey();
            String fullName = ReportDataAggregator.STANDARD_ISOTOPES.get(symbol);
            String catLabel = (fullName != null && !fullName.equalsIgnoreCase(symbol))
                    ? symbol + " (" + fullName + ")"
                    : symbol;
            nonZeroIsoCounts.put(symbol, count);
            barItems.add(new ChartGenerator.HorizontalBarItem(
                    catLabel,
                    count,
                    intFmt.format(count),
                    ChartGenerator.COLOR_NEUTRON,
                    true
            ));
        }

        if (barItems.isEmpty()) {
            pdf.drawCalloutBox("No isotopes were identified in the completed adjudications for " + friendlyLane + ".");
            return;
        }

        chartGenerator.drawHorizontalBarChart(
                pdf,
                "Isotope Results",
                "Count",
                barItems,
                List.of(new ChartGenerator.LegendItem("Identified Isotope Count", ChartGenerator.COLOR_NEUTRON)),
                false,
                true
        );

        tableGenerator.drawIsotopeGridTable(pdf, nonZeroIsoCounts, ReportDataAggregator.STANDARD_ISOTOPES);
    }
}
