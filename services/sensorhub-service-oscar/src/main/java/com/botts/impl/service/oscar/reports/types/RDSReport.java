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
import com.botts.impl.service.oscar.reports.helpers.TableGenerator;
import com.botts.impl.service.oscar.reports.helpers.Utils;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.module.IModule;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
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
    private final TableGenerator tableGenerator;

    private record StorageCandidate(String label, Path path) {}

    public RDSReport(OutputStream out, Instant startTime, Instant endTime, String laneUIDs, OSCARServiceModule module) {
        super(out, startTime, endTime, module);
        this.siteId = (module != null && module.getConfiguration() != null && module.getConfiguration().nodeId != null)
                ? module.getConfiguration().nodeId
                : "oscar-node";
        this.laneUIDs = laneUIDs;
        this.tableGenerator = new TableGenerator();
    }

    @Override
    public void generate() throws DataStoreException {
        ReportDataAggregator.ObservationMetrics metrics =
                ReportDataAggregator.aggregateScopeMetrics(module, laneUIDs, start, end);

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);
        String scopeLine = ReportDataAggregator.buildBannerScopeSummary(module, laneUIDs);

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
                            "Alarm Rate: " + Utils.formatPercentage(metrics.alarmOccupancyRatePct()),
                            PdfDocumentBuilder.ACCENT_SKY
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Total Alarms",
                            intFmt.format(metrics.totalAlarms()),
                            String.format(Locale.US, "G: %s | N: %s | G+N: %s",
                                    intFmt.format(metrics.gammaAlarm),
                                    intFmt.format(metrics.neutronAlarm),
                                    intFmt.format(metrics.gammaNeutronAlarm)),
                            ChartGenerator.COLOR_GAMMA
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "EML Suppressed",
                            intFmt.format(metrics.emlSuppressed),
                            "EML Rate: " + Utils.formatPercentage(metrics.emlAlarmRatePct()),
                            ChartGenerator.COLOR_EML_SUPPRESSED
                    ),
                    new PdfDocumentBuilder.KpiCard(
                            "Faults & Tampers",
                            intFmt.format(metrics.totalFaultsAndTamper()),
                            String.format(Locale.US, "Tamper: %s | Faults: %s",
                                    intFmt.format(metrics.tamper),
                                    intFmt.format(metrics.gammaHighFault + metrics.gammaLowFault + metrics.neutronHighFault)),
                            ChartGenerator.COLOR_TAMPER
                    )
            ));

            // 1. Alarm Statistics
            pdf.drawSectionHeader("Alarm Statistics", "Aggregated occupancy, alarm, and EML suppression totals", 70f);
            Map<String, String> alarmOccCounts = new LinkedHashMap<>();
            alarmOccCounts.put("Gamma Alarm", intFmt.format(metrics.gammaAlarm));
            alarmOccCounts.put("Neutron Alarm", intFmt.format(metrics.neutronAlarm));
            alarmOccCounts.put("Gamma-Neutron Alarm", intFmt.format(metrics.gammaNeutronAlarm));
            alarmOccCounts.put("EML Suppressed", intFmt.format(metrics.emlSuppressed));
            alarmOccCounts.put("Total Occupancies", intFmt.format(metrics.totalOccupancies));
            alarmOccCounts.put("Alarm Occupancy Rate", Utils.formatPercentage(metrics.alarmOccupancyRatePct()));
            alarmOccCounts.put("EML Alarm Rate", Utils.formatPercentage(metrics.emlAlarmRatePct()));
            tableGenerator.drawSummaryTable(pdf, alarmOccCounts);

            // 2. Fault Statistics
            pdf.drawSectionHeader("Fault Statistics", "Sensor state-of-health faults and tamper alerts", 70f);
            Map<String, String> faultCounts = new LinkedHashMap<>();
            faultCounts.put("Tamper", intFmt.format(metrics.tamper));
            faultCounts.put("Gamma-High", intFmt.format(metrics.gammaHighFault));
            faultCounts.put("Gamma-Low", intFmt.format(metrics.gammaLowFault));
            faultCounts.put("Neutron-High", intFmt.format(metrics.neutronHighFault));
            tableGenerator.drawSummaryTable(pdf, faultCounts);

            // 3. Drive Storage Availability
            pdf.drawSectionHeader("Drive Storage Availability", "Filesystem capacity across active OSCAR data and system volumes", 80f);
            drawDriveStorageTable(pdf);

            pdf.finishAndSave(out);
        } catch (IOException e) {
            throw new DataStoreException("Failed to generate RDS Site Report PDF", e);
        }
    }

    @Override
    public String getReportType() {
        return ReportCmdType.RDS_SITE.name();
    }

    private void drawDriveStorageTable(PdfDocumentBuilder pdf) throws IOException {
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
        List<List<String>> rows = new ArrayList<>();

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

                rows.add(List.of(
                        candidate.label(),
                        String.format(Locale.US, "%.1f GB (%.1f%%)", usedBytes / BYTES_PER_GIB, usedPct),
                        String.format(Locale.US, "%.1f GB", usableBytes / BYTES_PER_GIB),
                        String.format(Locale.US, "%.1f GB", freeBytes / BYTES_PER_GIB),
                        String.format(Locale.US, "%.1f GB", totalBytes / BYTES_PER_GIB)
                ));
            } catch (Exception ignored) {
                // Skip inaccessible virtual filesystems cleanly
            }
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
