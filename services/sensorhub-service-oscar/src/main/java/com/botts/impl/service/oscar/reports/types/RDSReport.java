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

import com.botts.impl.service.bucket.BucketServiceConfig;
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
import com.google.gson.stream.JsonWriter;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.module.IModule;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RDSReport extends Report {

    private static final double BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0;

    private final String siteId;
    private final String laneUIDs;
    private final ReportFormat format;
    private final TableGenerator tableGenerator;

    private record StorageCandidate(String label, Path path) {}

    private record StorageVolumeRow(
            String label,
            double usedGb,
            double usedPct,
            double usableGb,
            double freeGb,
            double totalGb
    ) {}

    public RDSReport(OutputStream out, Instant startTime, Instant endTime, String laneUIDs, OSCARServiceModule module) {
        this(out, startTime, endTime, laneUIDs, ReportFormat.PDF, module);
    }

    public RDSReport(
            OutputStream out,
            Instant startTime,
            Instant endTime,
            String laneUIDs,
            ReportFormat format,
            OSCARServiceModule module
    ) {
        super(out, startTime, endTime, module);
        this.siteId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";
        this.laneUIDs = laneUIDs;
        this.format = format != null ? format : ReportFormat.PDF;
        this.tableGenerator = new TableGenerator();
    }

    @Override
    public void generate() throws DataStoreException {
        ReportDataAggregator.ObservationMetrics metrics =
                ReportDataAggregator.aggregateScopeMetrics(module, laneUIDs, start, end);
        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);
        List<StorageVolumeRow> storageVolumes = collectStorageVolumes();

        try {
            switch (format) {
                case CSV -> writeCsv(metrics, storageVolumes);
                case JSON -> writeJson(scopeLine, metrics, storageVolumes);
                case XLSX -> writeXlsx(scopeLine, metrics, storageVolumes);
                case PDF -> writePdf(scopeLine, metrics, storageVolumes);
            }
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate RDS Site Report (" + format + ")", e);
        }
    }

    private void writePdf(
            String scopeLine,
            ReportDataAggregator.ObservationMetrics metrics,
            List<StorageVolumeRow> storageVolumes
    ) throws IOException {
        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);

        try (PdfDocumentBuilder pdf = new PdfDocumentBuilder("RDS Site Report", siteId)) {
            pdf.drawReportBanner(new PdfDocumentBuilder.HeaderMetadata(
                    "RDS Site Report",
                    siteId,
                    null,
                    scopeLine,
                    start,
                    end
            ));

            pdf.drawKpiCards(List.of(
                    new PdfDocumentBuilder.KpiCard(
                            "Total Occupancies",
                            intFmt.format(metrics.totalOccupancies),
                            "Alarm Occupancy Rate: " + Utils.formatPercentage(metrics.alarmOccupancyRatePct()),
                            PdfDocumentBuilder.ACCENT_SKY
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Total Alarms",
                            intFmt.format(metrics.totalAlarms()),
                            "Occupied Alarm Triggers",
                            ChartGenerator.COLOR_GAMMA
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "EML Suppressed",
                            intFmt.format(metrics.emlSuppressed),
                            "EML Alarm Rate: " + Utils.formatPercentage(metrics.emlAlarmRatePct()),
                            ChartGenerator.COLOR_EML_SUPPRESSED
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Faults & Tampers",
                            intFmt.format(metrics.totalFaultsAndTamper()),
                            "Detector SOH & Tamper Events",
                            ChartGenerator.COLOR_TAMPER
                    )
            ));

            // 1. Unified Detector Channel & Fault Breakdown (subtypes only — headline totals/rates are in the KPI cards above)
            pdf.drawSectionHeader(
                    "Detector Channel & Fault Breakdown",
                    "Granular alarm channel triggers, state-of-health faults, and tamper alerts",
                    70f
            );
            Map<String, String> channelBreakdown = new LinkedHashMap<>();
            channelBreakdown.put("Gamma Alarm", intFmt.format(metrics.gammaAlarm));
            channelBreakdown.put("Neutron Alarm", intFmt.format(metrics.neutronAlarm));
            channelBreakdown.put("Gamma-Neutron Alarm", intFmt.format(metrics.gammaNeutronAlarm));
            channelBreakdown.put("Gamma-High Fault", intFmt.format(metrics.gammaHighFault));
            channelBreakdown.put("Gamma-Low Fault", intFmt.format(metrics.gammaLowFault));
            channelBreakdown.put("Neutron-High Fault", intFmt.format(metrics.neutronHighFault));
            channelBreakdown.put("Extended Occupancy Fault", intFmt.format(metrics.extendedOccupancyFault));
            channelBreakdown.put("Tamper", intFmt.format(metrics.tamper));
            tableGenerator.drawSummaryTable(pdf, channelBreakdown);

            // 2. Drive Storage Availability
            pdf.drawSectionHeader("Drive Storage Availability", "Filesystem capacity across active OSCAR data and system volumes", 80f);
            drawDriveStorageTable(pdf, storageVolumes);

            pdf.finishAndSave(out);
        }
    }

    private void writeCsv(
            ReportDataAggregator.ObservationMetrics metrics,
            List<StorageVolumeRow> storageVolumes
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            XlsxWorkbookWriter.writeCsvRow(writer, List.of(
                    "Category",
                    "Metric / Volume",
                    "Value / Used",
                    "Unit / Usable",
                    "Free",
                    "Total Capacity"
            ));

            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Gamma Alarm", String.valueOf(metrics.gammaAlarm), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Neutron Alarm", String.valueOf(metrics.neutronAlarm), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Gamma-Neutron Alarm", String.valueOf(metrics.gammaNeutronAlarm), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Total Alarms", String.valueOf(metrics.totalAlarms()), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "EML Suppressed", String.valueOf(metrics.emlSuppressed), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Total Occupancies", String.valueOf(metrics.totalOccupancies), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "Alarm Occupancy Rate", String.format(Locale.US, "%.2f", metrics.alarmOccupancyRatePct()), "%", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Alarm Statistics", "EML Alarm Rate", String.format(Locale.US, "%.2f", metrics.emlAlarmRatePct()), "%", "", ""));

            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Tamper", String.valueOf(metrics.tamper), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Gamma-High", String.valueOf(metrics.gammaHighFault), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Gamma-Low", String.valueOf(metrics.gammaLowFault), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Neutron-High", String.valueOf(metrics.neutronHighFault), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Extended Occupancy", String.valueOf(metrics.extendedOccupancyFault), "count", "", ""));
            XlsxWorkbookWriter.writeCsvRow(writer, List.of("Fault Statistics", "Total Faults & Tampers", String.valueOf(metrics.totalFaultsAndTamper()), "count", "", ""));

            for (StorageVolumeRow vol : storageVolumes) {
                XlsxWorkbookWriter.writeCsvRow(writer, List.of(
                        "Drive Storage",
                        XlsxWorkbookWriter.guardSpreadsheetFormulaPrefix(vol.label()),
                        String.format(Locale.US, "%.1f GB (%.1f%%)", vol.usedGb(), vol.usedPct()),
                        String.format(Locale.US, "%.1f GB", vol.usableGb()),
                        String.format(Locale.US, "%.1f GB", vol.freeGb()),
                        String.format(Locale.US, "%.1f GB", vol.totalGb())
                ));
            }
            writer.flush();
        }
    }

    private void writeJson(
            String scopeLine,
            ReportDataAggregator.ObservationMetrics metrics,
            List<StorageVolumeRow> storageVolumes
    ) throws IOException {
        try (JsonWriter jw = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))) {
            jw.setIndent("  ");
            jw.setHtmlSafe(false);
            jw.beginObject();
            jw.name("reportType").value(ReportCmdType.RDS_SITE.name());
            jw.name("reportTitle").value("RDS Site Report");
            jw.name("nodeId").value(siteId);
            jw.name("scope").value(scopeLine);
            jw.name("startDateTime").value(start != null ? DateTimeFormatter.ISO_INSTANT.format(start) : "");
            jw.name("endDateTime").value(end != null ? DateTimeFormatter.ISO_INSTANT.format(end) : "");
            jw.name("generatedAt").value(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            jw.name("summary").beginObject();
            jw.name("totalOccupancies").value(metrics.totalOccupancies);
            jw.name("totalAlarms").value(metrics.totalAlarms());
            jw.name("gammaAlarm").value(metrics.gammaAlarm);
            jw.name("neutronAlarm").value(metrics.neutronAlarm);
            jw.name("gammaNeutronAlarm").value(metrics.gammaNeutronAlarm);
            jw.name("emlSuppressed").value(metrics.emlSuppressed);
            jw.name("alarmOccupancyRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(metrics.alarmOccupancyRatePct()));
            jw.name("emlAlarmRatePct").value(XlsxWorkbookWriter.roundTwoDecimals(metrics.emlAlarmRatePct()));
            jw.name("totalFaultsAndTamper").value(metrics.totalFaultsAndTamper());
            jw.name("tamper").value(metrics.tamper);
            jw.name("gammaHighFault").value(metrics.gammaHighFault);
            jw.name("gammaLowFault").value(metrics.gammaLowFault);
            jw.name("neutronHighFault").value(metrics.neutronHighFault);
            jw.name("extendedOccupancyFault").value(metrics.extendedOccupancyFault);
            jw.name("storageVolumeCount").value(storageVolumes.size());
            jw.endObject();

            jw.name("records").beginArray();
            writeJsonMetricRecord(jw, "Alarm Statistics", "Gamma Alarm", String.valueOf(metrics.gammaAlarm), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "Neutron Alarm", String.valueOf(metrics.neutronAlarm), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "Gamma-Neutron Alarm", String.valueOf(metrics.gammaNeutronAlarm), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "Total Alarms", String.valueOf(metrics.totalAlarms()), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "EML Suppressed", String.valueOf(metrics.emlSuppressed), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "Total Occupancies", String.valueOf(metrics.totalOccupancies), "count", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "Alarm Occupancy Rate", Utils.formatPercentage(metrics.alarmOccupancyRatePct()), "%", "");
            writeJsonMetricRecord(jw, "Alarm Statistics", "EML Alarm Rate", Utils.formatPercentage(metrics.emlAlarmRatePct()), "%", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Tamper", String.valueOf(metrics.tamper), "count", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Gamma-High", String.valueOf(metrics.gammaHighFault), "count", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Gamma-Low", String.valueOf(metrics.gammaLowFault), "count", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Neutron-High", String.valueOf(metrics.neutronHighFault), "count", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Extended Occupancy", String.valueOf(metrics.extendedOccupancyFault), "count", "");
            writeJsonMetricRecord(jw, "Fault Statistics", "Total Faults & Tampers", String.valueOf(metrics.totalFaultsAndTamper()), "count", "");
            for (StorageVolumeRow vol : storageVolumes) {
                writeJsonMetricRecord(
                        jw,
                        "Drive Storage",
                        vol.label(),
                        String.format(Locale.US, "%.1f GB (%.1f%%)", vol.usedGb(), vol.usedPct()),
                        "GB",
                        String.format(Locale.US, "Usable: %.1f GB | Free: %.1f GB | Total: %.1f GB",
                                vol.usableGb(), vol.freeGb(), vol.totalGb())
                );
            }
            jw.endArray();

            jw.endObject();
            jw.flush();
        }
    }

    private static void writeJsonMetricRecord(
            JsonWriter jw,
            String category,
            String metric,
            String value,
            String unit,
            String details
    ) throws IOException {
        jw.beginObject();
        jw.name("category").value(category);
        jw.name("metric").value(metric);
        jw.name("value").value(value);
        jw.name("unit").value(unit);
        jw.name("details").value(details);
        jw.endObject();
    }

    private void writeXlsx(
            String scopeLine,
            ReportDataAggregator.ObservationMetrics metrics,
            List<StorageVolumeRow> storageVolumes
    ) throws IOException {
        List<List<CellValue>> statRows = List.of(
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Gamma Alarm"), CellValue.integer(metrics.gammaAlarm), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Neutron Alarm"), CellValue.integer(metrics.neutronAlarm), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Gamma-Neutron Alarm"), CellValue.integer(metrics.gammaNeutronAlarm), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Total Alarms"), CellValue.integer(metrics.totalAlarms()), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("EML Suppressed"), CellValue.integer(metrics.emlSuppressed), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Total Occupancies"), CellValue.integer(metrics.totalOccupancies), CellValue.text("count")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("Alarm Occupancy Rate"), CellValue.decimal(metrics.alarmOccupancyRatePct()), CellValue.text("%")),
                List.of(CellValue.text("Alarm Statistics"), CellValue.text("EML Alarm Rate"), CellValue.decimal(metrics.emlAlarmRatePct()), CellValue.text("%")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Tamper"), CellValue.integer(metrics.tamper), CellValue.text("count")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Gamma-High"), CellValue.integer(metrics.gammaHighFault), CellValue.text("count")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Gamma-Low"), CellValue.integer(metrics.gammaLowFault), CellValue.text("count")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Neutron-High"), CellValue.integer(metrics.neutronHighFault), CellValue.text("count")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Extended Occupancy"), CellValue.integer(metrics.extendedOccupancyFault), CellValue.text("count")),
                List.of(CellValue.text("Fault Statistics"), CellValue.text("Total Faults & Tampers"), CellValue.integer(metrics.totalFaultsAndTamper()), CellValue.text("count"))
        );

        List<List<CellValue>> storageRows = new ArrayList<>(storageVolumes.size());
        for (StorageVolumeRow vol : storageVolumes) {
            storageRows.add(List.of(
                    CellValue.text(vol.label()),
                    CellValue.decimal(vol.usedGb()),
                    CellValue.decimal(vol.usedPct()),
                    CellValue.decimal(vol.usableGb()),
                    CellValue.decimal(vol.freeGb()),
                    CellValue.decimal(vol.totalGb())
            ));
        }

        List<List<CellValue>> summaryRows = List.of(
                List.of(CellValue.text("Report Title"), CellValue.text("RDS Site Report")),
                List.of(CellValue.text("Node ID"), CellValue.text(siteId)),
                List.of(CellValue.text("Scope"), CellValue.text(scopeLine)),
                List.of(CellValue.text("Start (UTC)"), CellValue.text(start != null ? DateTimeFormatter.ISO_INSTANT.format(start) : "")),
                List.of(CellValue.text("End (UTC)"), CellValue.text(end != null ? DateTimeFormatter.ISO_INSTANT.format(end) : "")),
                List.of(CellValue.text("Total Occupancies"), CellValue.integer(metrics.totalOccupancies)),
                List.of(CellValue.text("Total Alarms"), CellValue.integer(metrics.totalAlarms())),
                List.of(CellValue.text("EML Suppressed"), CellValue.integer(metrics.emlSuppressed)),
                List.of(CellValue.text("Total Faults & Tampers"), CellValue.integer(metrics.totalFaultsAndTamper())),
                List.of(CellValue.text("Storage Volumes Reported"), CellValue.integer(storageVolumes.size()))
        );

        XlsxWorkbookWriter.writeWorkbook(out, List.of(
                new XlsxWorkbookWriter.SheetSpec(
                        "Alarm & Fault Statistics",
                        List.of("Category", "Metric", "Value", "Unit"),
                        List.of(22.0, 28.0, 16.0, 12.0),
                        statRows,
                        true
                ),
                new XlsxWorkbookWriter.SheetSpec(
                        "Drive Storage Availability",
                        List.of("Volume / Mount", "Used (GB)", "Used (%)", "Usable (GB)", "Free (GB)", "Total Capacity (GB)"),
                        List.of(38.0, 16.0, 14.0, 16.0, 16.0, 20.0),
                        storageRows,
                        true
                ),
                new XlsxWorkbookWriter.SheetSpec(
                        "Summary",
                        List.of("Metric", "Value"),
                        List.of(28.0, 46.0),
                        summaryRows,
                        false
                )
        ));
    }

    @Override
    public String getReportType() {
        return ReportCmdType.RDS_SITE.name();
    }

    private List<StorageVolumeRow> collectStorageVolumes() {
        List<StorageCandidate> candidates = new ArrayList<>();

        if (module != null && module.getBucketService() instanceof IModule<?> bucketMod
                && bucketMod.getConfiguration() instanceof BucketServiceConfig bCfg
                && bCfg.fileStoreRootDir != null && !bCfg.fileStoreRootDir.isBlank()) {
            Path p = Path.of(bCfg.fileStoreRootDir).toAbsolutePath().normalize();
            candidates.add(new StorageCandidate("Object Store (" + p + ")", p));
        }

        if (module != null && module.getConfiguration() != null
                && module.getConfiguration().storagePressureRetentionConfig != null
                && module.getConfiguration().storagePressureRetentionConfig.storagePath != null
                && !module.getConfiguration().storagePressureRetentionConfig.storagePath.isBlank()) {
            Path p = Path.of(module.getConfiguration().storagePressureRetentionConfig.storagePath).toAbsolutePath().normalize();
            candidates.add(new StorageCandidate("Retention Storage (" + p + ")", p));
        }

        Path oscarDataDir = Path.of("/var/lib/oscar");
        if (Files.exists(oscarDataDir)) {
            candidates.add(new StorageCandidate("OSCAR Data (/var/lib/oscar)", oscarDataDir));
        }
        Path oscarArchiveDir = Path.of("/var/lib/oscar/archive");
        if (Files.exists(oscarArchiveDir)) {
            candidates.add(new StorageCandidate("Parquet Archive (/var/lib/oscar/archive)", oscarArchiveDir));
        }

        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                String pathStr = root.getPath();
                candidates.add(new StorageCandidate("System Volume (" + pathStr + ")", root.toPath()));
            }
        }

        Set<FileStore> seenStores = new LinkedHashSet<>();
        List<StorageVolumeRow> rows = new ArrayList<>();

        for (StorageCandidate candidate : candidates) {
            try {
                Path target = existingAncestor(candidate.path());
                if (target == null) {
                    continue;
                }
                FileStore store = Files.getFileStore(target);
                if (!seenStores.add(store)) {
                    continue;
                }
                long totalBytes = store.getTotalSpace();
                if (totalBytes <= 0L) {
                    continue;
                }
                long usableBytes = Math.max(0L, store.getUsableSpace());
                long freeBytes = Math.max(0L, store.getUnallocatedSpace());
                long usedBytes = Math.max(0L, totalBytes - usableBytes);
                double usedPct = (usedBytes * 100.0) / totalBytes;

                rows.add(new StorageVolumeRow(
                        candidate.label(),
                        usedBytes / BYTES_PER_GIB,
                        usedPct,
                        usableBytes / BYTES_PER_GIB,
                        freeBytes / BYTES_PER_GIB,
                        totalBytes / BYTES_PER_GIB
                ));
            } catch (Exception ignored) {
                // Skip inaccessible virtual filesystems cleanly
            }
        }
        return rows;
    }

    private void drawDriveStorageTable(PdfDocumentBuilder pdf, List<StorageVolumeRow> storageVolumes) throws IOException {
        List<List<String>> rows = new ArrayList<>(storageVolumes.size());
        for (StorageVolumeRow vol : storageVolumes) {
            rows.add(List.of(
                    vol.label(),
                    String.format(Locale.US, "%.1f GB (%.1f%%)", vol.usedGb(), vol.usedPct()),
                    String.format(Locale.US, "%.1f GB", vol.usableGb()),
                    String.format(Locale.US, "%.1f GB", vol.freeGb()),
                    String.format(Locale.US, "%.1f GB", vol.totalGb())
            ));
        }

        List<TableGenerator.ColumnSpec> cols = List.of(
                new TableGenerator.ColumnSpec("Volume / Mount", 2.4f, TableGenerator.Align.LEFT),
                new TableGenerator.ColumnSpec("Used", 1.25f, TableGenerator.Align.RIGHT),
                new TableGenerator.ColumnSpec("Usable", 1.0f, TableGenerator.Align.RIGHT),
                new TableGenerator.ColumnSpec("Free", 1.0f, TableGenerator.Align.RIGHT),
                new TableGenerator.ColumnSpec("Total Capacity", 1.05f, TableGenerator.Align.RIGHT, true)
        );

        tableGenerator.drawTable(pdf, cols, rows, false, 7.8f);
    }

    private static Path existingAncestor(Path path) {
        Path cur = path;
        while (cur != null && !Files.exists(cur)) {
            cur = cur.getParent();
        }
        return cur;
    }
}
