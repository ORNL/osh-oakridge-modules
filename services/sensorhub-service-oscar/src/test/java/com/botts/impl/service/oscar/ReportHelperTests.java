package com.botts.impl.service.oscar;

import com.botts.impl.service.oscar.reports.helpers.ChartGenerator;
import com.botts.impl.service.oscar.reports.helpers.PdfDocumentBuilder;
import com.botts.impl.service.oscar.reports.helpers.ReportDataAggregator;
import com.botts.impl.service.oscar.reports.helpers.TableGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
import org.sensorhub.impl.utils.rad.eml.EmlSourceBreakdown;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReportHelperTests {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void TestLanesTable() throws IOException {
        File dest = tempFolder.newFile("table_lanes_test.pdf");

        Map<String, String> testData1 = new LinkedHashMap<>();
        testData1.put("Gamma Alarm", "15");
        testData1.put("Neutron Alarm", "10");
        testData1.put("Gamma-Neutron Alarm", "5");
        testData1.put("EML Suppressed", "6");
        testData1.put("Total Occupancies", "200");
        testData1.put("Alarm Occupancy Rate", "15.00%");
        testData1.put("EML Alarm Rate", "16.67%");

        Map<String, String> testData2 = new LinkedHashMap<>();
        testData2.put("Gamma Alarm", "2");
        testData2.put("Neutron Alarm", "0");
        testData2.put("Gamma-Neutron Alarm", "1");
        testData2.put("EML Suppressed", "1");
        testData2.put("Total Occupancies", "120");
        testData2.put("Alarm Occupancy Rate", "2.50%");
        testData2.put("EML Alarm Rate", "25.00%");

        Map<String, Map<String, String>> testData = new LinkedHashMap<>();
        testData.put("urn:osh:system:lane:lane-1", testData1);
        testData.put("urn:osh:system:lane:lane-2", testData2);

        Map<String, String> labels = Map.of(
                "urn:osh:system:lane:lane-1", "Lane 1\nurn:osh:system:lane:lane-1",
                "urn:osh:system:lane:lane-2", "Lane 2\nurn:osh:system:lane:lane-2"
        );

        TableGenerator tableGenerator = new TableGenerator();
        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("Lane Report", "test-node")) {
            tableGenerator.drawLanesTable(pdf, testData, labels);
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            assertEquals(1, loaded.getNumberOfPages());
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("Lane 1"));
            assertTrue(text.contains("urn:osh:system:lane:lane-1"));
            assertTrue(text.contains("15.00%"));
            assertTrue(text.contains("16.67%"));
            assertTrue(text.contains("Page 1 of 1"));
            assertZeroRasterImages(loaded);
        }
    }

    @Test
    public void TestTable() throws IOException {
        File dest = tempFolder.newFile("table_test.pdf");

        Map<String, String> testData = new LinkedHashMap<>();
        testData.put("Gamma Alarm", "15");
        testData.put("Neutron Alarm", "10");
        testData.put("Gamma-Neutron Alarm", "5");
        testData.put("Total Occupancies", "200");
        testData.put("Alarm Occupancy Rate", "15.00%");
        testData.put("EML Suppressed", "5");
        testData.put("EML Alarm Rate", "14.29%");

        TableGenerator tableGenerator = new TableGenerator();
        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("RDS Site Report", "test-node")) {
            tableGenerator.drawSummaryTable(pdf, testData);
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            String text = new PDFTextStripper().getText(loaded).replaceAll("\\s+", " ");
            assertTrue(text.contains("Alarm Occupancy Rate"));
            assertTrue(text.contains("15.00%"));
            assertZeroRasterImages(loaded);
        }
    }

    @Test
    public void TestBarChart() throws IOException {
        File dest = tempFolder.newFile("chart_test1.pdf");

        List<ChartGenerator.HorizontalBarItem> items = List.of(
                new ChartGenerator.HorizontalBarItem("Code 1: Contraband Found", 10.0, "5 (10.0%)", ChartGenerator.GROUP_REAL_ALARM, true),
                new ChartGenerator.HorizontalBarItem("Code 3: Medical Isotope Found", 16.0, "8 (16.0%)", ChartGenerator.GROUP_INNOCENT, true),
                new ChartGenerator.HorizontalBarItem("Code 5: Declared Shipment of Radioactive Material", 0.0, "0 (0.0%)", ChartGenerator.GROUP_INNOCENT, false),
                new ChartGenerator.HorizontalBarItem("Code 6: Physical Inspection Negative", 12.0, "6 (12.0%)", ChartGenerator.GROUP_FALSE_ALARM, true),
                new ChartGenerator.HorizontalBarItem("EML Non-Threat", 62.0, "31 (62.0%)", ChartGenerator.GROUP_AUTOMATED, true)
        );

        List<ChartGenerator.LegendItem> legend = List.of(
                new ChartGenerator.LegendItem("Real Alarm", ChartGenerator.GROUP_REAL_ALARM),
                new ChartGenerator.LegendItem("Innocent Alarm", ChartGenerator.GROUP_INNOCENT),
                new ChartGenerator.LegendItem("False Alarm", ChartGenerator.GROUP_FALSE_ALARM),
                new ChartGenerator.LegendItem("Automated", ChartGenerator.GROUP_AUTOMATED)
        );

        ChartGenerator chartGenerator = new ChartGenerator();
        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("Adjudication Report", "test-node")) {
            chartGenerator.drawHorizontalBarChart(
                    pdf,
                    "Disposition",
                    "% of Total Number of Records",
                    items,
                    legend,
                    true,
                    false
            );

            // Also verify zero-count isotope horizontal bar chart never emits 0.0000000 ticks
            List<ChartGenerator.HorizontalBarItem> zeroIsoItems = new ArrayList<>();
            for (Map.Entry<String, String> e : ReportDataAggregator.STANDARD_ISOTOPES.entrySet()) {
                zeroIsoItems.add(new ChartGenerator.HorizontalBarItem(
                        e.getKey() + " (" + e.getValue() + ")", 0.0, "0", ChartGenerator.COLOR_NEUTRON, false));
            }
            chartGenerator.drawHorizontalBarChart(
                    pdf,
                    "Isotope Results",
                    "Count",
                    zeroIsoItems,
                    List.of(new ChartGenerator.LegendItem("Count", ChartGenerator.COLOR_NEUTRON)),
                    false,
                    true
            );
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("Disposition"));
            assertTrue(text.contains("31 (62.0%)"));
            assertTrue(text.contains("Cs-137 (Cesium137)"));
            assertFalse("Zero-count axis should never render fractional 0.0000000 ticks", text.contains("0.00000"));
            assertZeroRasterImages(loaded);
        }
    }

    @Test
    public void TestStackedBarChart() throws IOException {
        File dest = tempFolder.newFile("stacked_bar_chart_test.pdf");

        List<String> dates = new ArrayList<>();
        List<Long> gamma = new ArrayList<>();
        List<Long> gn = new ArrayList<>();
        List<Long> neutron = new ArrayList<>();
        List<Long> eml = new ArrayList<>();

        LocalDate base = LocalDate.of(2026, 10, 20);
        for (int i = 0; i < 10; i++) {
            dates.add(base.plusDays(i).toString());
            gamma.add((long) (3 + i));
            gn.add((long) (i % 3));
            neutron.add((long) (1 + (i % 2)));
            eml.add((long) (2 + (i % 4)));
        }

        ChartGenerator chartGenerator = new ChartGenerator();
        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("Event Report", "test-node")) {
            chartGenerator.drawStackedBarChart(
                    pdf,
                    "Alarms",
                    "Date (UTC)",
                    "Count",
                    dates,
                    List.of(
                            new ChartGenerator.StackedSeries("Gamma", ChartGenerator.COLOR_GAMMA, gamma),
                            new ChartGenerator.StackedSeries("Gamma-Neutron", ChartGenerator.COLOR_GAMMA_NEUTRON, gn),
                            new ChartGenerator.StackedSeries("Neutron", ChartGenerator.COLOR_NEUTRON, neutron),
                            new ChartGenerator.StackedSeries("EML-Suppressed", ChartGenerator.COLOR_EML_SUPPRESSED, eml)
                    )
            );
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("Alarms"));
            assertTrue(text.contains("2026-10-20"));
            assertTrue(text.contains("2026-10-29"));
            assertZeroRasterImages(loaded);
        }
    }

    @Test
    public void TestStackedBarLineOverlayChart() throws IOException {
        File dest = tempFolder.newFile("bar_line_chart.pdf");

        ChartGenerator chartGenerator = new ChartGenerator();
        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("Event Report", "test-node")) {
            // 1. Single-day window (verifies explicit Occupancies callout badge + independent dual Y-axes)
            chartGenerator.drawDualAxisStackedBarLineChart(
                    pdf,
                    "Alarms and Occupancies (1-Day)",
                    "Date (UTC)",
                    "Alarm / Suppressed Count",
                    List.of("2026-11-06"),
                    List.of(
                            new ChartGenerator.StackedSeries("Gamma", ChartGenerator.COLOR_GAMMA, List.of(2L)),
                            new ChartGenerator.StackedSeries("Gamma-Neutron", ChartGenerator.COLOR_GAMMA_NEUTRON, List.of(1L)),
                            new ChartGenerator.StackedSeries("Neutron", ChartGenerator.COLOR_NEUTRON, List.of(0L)),
                            new ChartGenerator.StackedSeries("EML-Suppressed", ChartGenerator.COLOR_EML_SUPPRESSED, List.of(3L))
                    ),
                    "Total Occupancies",
                    new ChartGenerator.LineSeries("Total Occupancy (Right Axis)", ChartGenerator.COLOR_OCCUPANCY_LINE, List.of(1840L))
            );

            // 2. Multi-day window
            List<String> dates = List.of("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05");
            chartGenerator.drawDualAxisStackedBarLineChart(
                    pdf,
                    "Alarms and Occupancies (5-Day)",
                    "Date (UTC)",
                    "Alarm / Suppressed Count",
                    dates,
                    List.of(
                            new ChartGenerator.StackedSeries("Gamma", ChartGenerator.COLOR_GAMMA, List.of(1L, 3L, 0L, 2L, 4L)),
                            new ChartGenerator.StackedSeries("EML-Suppressed", ChartGenerator.COLOR_EML_SUPPRESSED, List.of(2L, 1L, 1L, 0L, 3L))
                    ),
                    "Total Occupancies",
                    new ChartGenerator.LineSeries("Total Occupancy (Right Axis)", ChartGenerator.COLOR_OCCUPANCY_LINE, List.of(950L, 1210L, 1105L, 1430L, 1380L))
            );
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("Occupancies: 1,840"));
            assertTrue(text.contains("Total Occupancies"));
            assertTrue(text.contains("Alarm / Suppressed Count"));
            assertZeroRasterImages(loaded);
        }
    }

    @Test
    public void TestFormatAdjudicationFeedbackAndLandscapeTable() throws IOException {
        EmlEvaluationResult emlEval = new EmlEvaluationResult(
                "1.0.2", "ERNIE", 0.50, "PORT-1", 50L, Instant.now().toEpochMilli(), 1L,
                "ALARM", true, false, false, "RELEASE", false, "",
                0.015, 0.985, false, false, 4, 16.4, "",
                List.of(),
                new EmlSourceBreakdown("NORM", "ERNIE-Classifier", 0.0, 0.0, 0.0, 0.0, 0.04, 0.95, 0.01)
        );
        String rawFeedback = emlEval.toAdjudicationFeedback();
        String formatted = TableGenerator.formatAdjudicationFeedback(rawFeedback);
        assertTrue(formatted.contains("Automated EML Non-Threat release"));
        assertTrue(formatted.contains("Source: NORM"));
        assertTrue(formatted.contains("Vehicle: Class 4 (16.4m)"));
        assertTrue(formatted.contains("NORM 95.0%"));
        assertFalse("Raw unspaced JSON keys must not leak into formatted cell text", formatted.contains("\"probabilityNonEmitting\""));

        File dest = tempFolder.newFile("adj_landscape_test.pdf");
        TableGenerator tableGenerator = new TableGenerator();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("#", "1");
        row.put("Time (UTC)", LocalDate.of(2026, 11, 6).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        row.put("Username", "EML");
        row.put("Feedback", rawFeedback);
        row.put("Adjudication Code", "9999 (EML Non-Threat)");
        row.put("Isotopes", "Potassium (1)");
        row.put("Secondary Inspection Status", "NONE");
        row.put("File Paths", "None (0)");
        row.put("Occupancy Obs ID", "019a57f2-abc1-7000-8000-000000000050");
        row.put("Vehicle ID", "TRK-4092");

        try (FileOutputStream out = new FileOutputStream(dest);
             PdfDocumentBuilder pdf = new PdfDocumentBuilder("Adjudication Report", "test-node", PdfDocumentBuilder.Orientation.LANDSCAPE)) {
            tableGenerator.drawAdjudicationDetailsTable(
                    pdf,
                    Map.of("urn:osh:system:lane:lane-50", List.of(row)),
                    Map.of("urn:osh:system:lane:lane-50", "Lane 50\nurn:osh:system:lane:lane-50")
            );
            pdf.finishAndSave(out);
        }

        try (PDDocument loaded = Loader.loadPDF(dest)) {
            PDPage firstPage = loaded.getPage(0);
            assertEquals(792f, firstPage.getMediaBox().getWidth(), 0.5f);
            assertEquals(612f, firstPage.getMediaBox().getHeight(), 0.5f);
            String text = new PDFTextStripper().getText(loaded);
            assertTrue(text.contains("TRK-4092"));
            assertTrue(text.contains("Source: NORM"));
            assertZeroRasterImages(loaded);
        }
    }

    private static void assertZeroRasterImages(PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            if (page.getResources() == null) {
                continue;
            }
            for (COSName name : page.getResources().getXObjectNames()) {
                PDXObject xObj = page.getResources().getXObject(name);
                assertFalse("Report PDF should be pure vector without embedded raster images", xObj instanceof PDImageXObject);
            }
        }
    }
}
