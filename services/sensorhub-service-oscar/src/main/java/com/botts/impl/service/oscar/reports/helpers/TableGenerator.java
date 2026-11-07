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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.sensorhub.impl.utils.rad.model.Adjudication;

import java.awt.Color;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vector PDF table renderer backed by Apache PDFBox 3.
 * Supports wrapped tokens, zebra-striped rows, automatic header repetition across page breaks,
 * numeric right alignment, multi-column isotope grids, and Landscape wide adjudication tables.
 */
public class TableGenerator {

    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    public record ColumnSpec(String header, float weight, Align align, boolean boldValues) {
        public ColumnSpec(String header, float weight, Align align) {
            this(header, weight, align, false);
        }
    }

    private static final Color HEADER_BG = new Color(30, 41, 59);       // #1E293B
    private static final Color HEADER_DIVIDER = new Color(51, 65, 85);  // #334155
    private static final Color TOTAL_ROW_BG = new Color(241, 245, 249); // #F1F5F9

    public TableGenerator() {}

    /**
     * Core vector table renderer with multi-page header repetition and cell word/token wrapping.
     */
    public void drawTable(
            PdfDocumentBuilder pdf,
            List<ColumnSpec> columns,
            List<List<String>> rows,
            boolean highlightLastRowAsTotal,
            float fontSize
    ) throws IOException {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        float tableWidth = pdf.getContentWidth();
        float totalWeight = 0f;
        for (ColumnSpec col : columns) {
            totalWeight += Math.max(0.1f, col.weight());
        }
        float[] colWidths = new float[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            colWidths[i] = (Math.max(0.1f, columns.get(i).weight()) / totalWeight) * tableWidth;
        }

        PDType0Font regular = pdf.getRegularFont();
        PDType0Font bold = pdf.getBoldFont();

        float headerFontSize = Math.min(8.0f, fontSize + 0.3f);
        float headerLineStep = headerFontSize + 2.4f;
        float bodyLineStep = fontSize + 2.3f;
        float padX = 4.5f;
        float padY = 4.0f;

        // Pre-wrap header cells
        List<List<String>> wrappedHeaders = new ArrayList<>(columns.size());
        int maxHeaderLines = 1;
        for (int c = 0; c < columns.size(); c++) {
            List<String> lines = pdf.wrapText(bold, headerFontSize, columns.get(c).header(), colWidths[c] - 2f * padX);
            wrappedHeaders.add(lines);
            if (lines.size() > maxHeaderLines) {
                maxHeaderLines = lines.size();
            }
        }
        float headerHeight = maxHeaderLines * headerLineStep + 2f * padY + 2f;

        // Estimate first row height so header is never orphaned without at least one row
        float firstRowEstimate = 20f;
        if (rows != null && !rows.isEmpty()) {
            firstRowEstimate = measureRowHeight(pdf, regular, fontSize, bodyLineStep, padX, padY, colWidths, rows.get(0), 40);
        }
        pdf.ensureSpace(headerHeight + Math.min(firstRowEstimate, 120f) + 6f);

        drawHeaderRow(pdf, columns, colWidths, wrappedHeaders, headerFontSize, headerLineStep, padX, padY, headerHeight);

        if (rows == null || rows.isEmpty()) {
            drawEmptyRow(pdf, tableWidth, fontSize);
            pdf.moveCursorDown(10f);
            return;
        }

        float maxSinglePageBodyHeight = (pdf.getPageHeight() - PdfDocumentBuilder.MARGIN_TOP - PdfDocumentBuilder.MARGIN_BOTTOM)
                - headerHeight - 12f;
        int maxLinesPerCell = Math.max(4, (int) Math.floor((maxSinglePageBodyHeight - 2f * padY) / bodyLineStep));

        for (int r = 0; r < rows.size(); r++) {
            List<String> rowValues = rows.get(r);
            boolean isTotalRow = highlightLastRowAsTotal && (r == rows.size() - 1);
            PDType0Font baseRowFont = isTotalRow ? bold : regular;

            List<List<String>> wrappedCells = new ArrayList<>(columns.size());
            int maxCellLines = 1;
            for (int c = 0; c < columns.size(); c++) {
                String rawVal = (rowValues != null && c < rowValues.size() && rowValues.get(c) != null)
                        ? rowValues.get(c)
                        : "";
                PDType0Font cellFont = (isTotalRow || columns.get(c).boldValues()) ? bold : baseRowFont;
                List<String> lines = pdf.wrapText(cellFont, fontSize, rawVal, colWidths[c] - 2f * padX);
                if (lines.size() > maxLinesPerCell) {
                    List<String> clipped = new ArrayList<>(lines.subList(0, maxLinesPerCell));
                    String last = clipped.get(maxLinesPerCell - 1);
                    clipped.set(maxLinesPerCell - 1, pdf.truncateToWidth(cellFont, fontSize, last + "...", colWidths[c] - 2f * padX));
                    lines = clipped;
                }
                wrappedCells.add(lines);
                if (lines.size() > maxCellLines) {
                    maxCellLines = lines.size();
                }
            }

            float rowHeight = maxCellLines * bodyLineStep + 2f * padY;

            // Paginate and repeat header when row does not fit on current page
            if (pdf.getCursorY() - rowHeight < pdf.getBottomLimitY()) {
                pdf.startNewPage(pdf.getCurrentOrientation());
                drawHeaderRow(pdf, columns, colWidths, wrappedHeaders, headerFontSize, headerLineStep, padX, padY, headerHeight);
            }

            PDPageContentStream cs = pdf.getContentStream();
            float rowTopY = pdf.getCursorY();
            float rowBottomY = rowTopY - rowHeight;

            // Row background (zebra striping or bold total row)
            Color rowBg;
            if (isTotalRow) {
                rowBg = TOTAL_ROW_BG;
            } else {
                rowBg = (r % 2 == 1) ? PdfDocumentBuilder.SLATE_50 : Color.WHITE;
            }
            cs.setNonStrokingColor(rowBg);
            cs.addRect(PdfDocumentBuilder.MARGIN_X, rowBottomY, tableWidth, rowHeight);
            cs.fill();

            if (isTotalRow) {
                cs.setStrokingColor(PdfDocumentBuilder.SLATE_400);
                cs.setLineWidth(0.8f);
                cs.moveTo(PdfDocumentBuilder.MARGIN_X, rowTopY);
                cs.lineTo(PdfDocumentBuilder.MARGIN_X + tableWidth, rowTopY);
                cs.stroke();
            }

            // Bottom row border & outer left/right edges
            cs.setStrokingColor(PdfDocumentBuilder.SLATE_200);
            cs.setLineWidth(0.5f);
            cs.moveTo(PdfDocumentBuilder.MARGIN_X, rowBottomY);
            cs.lineTo(PdfDocumentBuilder.MARGIN_X + tableWidth, rowBottomY);
            cs.moveTo(PdfDocumentBuilder.MARGIN_X, rowBottomY);
            cs.lineTo(PdfDocumentBuilder.MARGIN_X, rowTopY);
            cs.moveTo(PdfDocumentBuilder.MARGIN_X + tableWidth, rowBottomY);
            cs.lineTo(PdfDocumentBuilder.MARGIN_X + tableWidth, rowTopY);
            cs.stroke();

            // Cell text & subtle vertical separators
            float colX = PdfDocumentBuilder.MARGIN_X;
            for (int c = 0; c < columns.size(); c++) {
                ColumnSpec spec = columns.get(c);
                float cw = colWidths[c];
                if (c > 0) {
                    cs.setStrokingColor(PdfDocumentBuilder.SLATE_200);
                    cs.setLineWidth(0.4f);
                    cs.moveTo(colX, rowBottomY);
                    cs.lineTo(colX, rowTopY);
                    cs.stroke();
                }

                PDType0Font cellFont = (isTotalRow || spec.boldValues()) ? bold : baseRowFont;
                List<String> lines = wrappedCells.get(c);
                float firstBaselineY = rowTopY - padY - fontSize * 0.82f;

                for (int li = 0; li < lines.size(); li++) {
                    String line = lines.get(li);
                    float lw = pdf.measureTextWidth(cellFont, fontSize, line);
                    float tx = switch (spec.align()) {
                        case RIGHT -> colX + cw - padX - lw;
                        case CENTER -> colX + (cw - lw) * 0.5f;
                        case LEFT -> colX + padX;
                    };
                    tx = Math.max(colX + 2f, tx);
                    pdf.drawText(cellFont, fontSize, PdfDocumentBuilder.SLATE_900,
                            tx, firstBaselineY - li * bodyLineStep, line);
                }

                colX += cw;
            }

            pdf.setCursorY(rowBottomY);
        }

        pdf.moveCursorDown(12f);
    }

    private float measureRowHeight(
            PdfDocumentBuilder pdf,
            PDType0Font font,
            float fontSize,
            float lineStep,
            float padX,
            float padY,
            float[] colWidths,
            List<String> rowValues,
            int maxLines
    ) {
        int maxCellLines = 1;
        for (int c = 0; c < colWidths.length; c++) {
            String raw = (rowValues != null && c < rowValues.size() && rowValues.get(c) != null) ? rowValues.get(c) : "";
            int lines = Math.min(maxLines, pdf.wrapText(font, fontSize, raw, colWidths[c] - 2f * padX).size());
            if (lines > maxCellLines) {
                maxCellLines = lines;
            }
        }
        return maxCellLines * lineStep + 2f * padY;
    }

    private void drawHeaderRow(
            PdfDocumentBuilder pdf,
            List<ColumnSpec> columns,
            float[] colWidths,
            List<List<String>> wrappedHeaders,
            float headerFontSize,
            float headerLineStep,
            float padX,
            float padY,
            float headerHeight
    ) throws IOException {
        PDPageContentStream cs = pdf.getContentStream();
        PDType0Font bold = pdf.getBoldFont();
        float tableWidth = pdf.getContentWidth();
        float topY = pdf.getCursorY();
        float bottomY = topY - headerHeight;

        cs.setNonStrokingColor(HEADER_BG);
        cs.addRect(PdfDocumentBuilder.MARGIN_X, bottomY, tableWidth, headerHeight);
        cs.fill();

        float colX = PdfDocumentBuilder.MARGIN_X;
        for (int c = 0; c < columns.size(); c++) {
            ColumnSpec spec = columns.get(c);
            float cw = colWidths[c];
            if (c > 0) {
                cs.setStrokingColor(HEADER_DIVIDER);
                cs.setLineWidth(0.5f);
                cs.moveTo(colX, bottomY);
                cs.lineTo(colX, topY);
                cs.stroke();
            }

            List<String> lines = wrappedHeaders.get(c);
            float textBlockH = lines.size() * headerLineStep;
            float firstBaselineY = topY - ((headerHeight - textBlockH) * 0.5f) - headerFontSize * 0.82f;

            for (int li = 0; li < lines.size(); li++) {
                String line = lines.get(li);
                float lw = pdf.measureTextWidth(bold, headerFontSize, line);
                float tx = switch (spec.align()) {
                    case RIGHT -> colX + cw - padX - lw;
                    case CENTER -> colX + (cw - lw) * 0.5f;
                    case LEFT -> colX + padX;
                };
                tx = Math.max(colX + 2f, tx);
                pdf.drawText(bold, headerFontSize, Color.WHITE, tx, firstBaselineY - li * headerLineStep, line);
            }
            colX += cw;
        }

        pdf.setCursorY(bottomY);
    }

    private void drawEmptyRow(PdfDocumentBuilder pdf, float tableWidth, float fontSize) throws IOException {
        PDPageContentStream cs = pdf.getContentStream();
        float topY = pdf.getCursorY();
        float bottomY = topY - 20f;
        cs.setNonStrokingColor(PdfDocumentBuilder.SLATE_50);
        cs.setStrokingColor(PdfDocumentBuilder.SLATE_200);
        cs.setLineWidth(0.5f);
        cs.addRect(PdfDocumentBuilder.MARGIN_X, bottomY, tableWidth, 20f);
        cs.fillAndStroke();
        pdf.drawText(pdf.getRegularFont(), fontSize, PdfDocumentBuilder.SLATE_500,
                PdfDocumentBuilder.MARGIN_X + 8f, bottomY + 6.5f, "No records for the selected window.");
        pdf.setCursorY(bottomY);
    }

    /**
     * Draws a horizontal summary metrics table (used in RDS Site Report and standalone tests).
     * Automatically chunks > 8 columns into clean stacked blocks so headers never squash.
     */
    public void drawSummaryTable(PdfDocumentBuilder pdf, Map<String, String> tableData) throws IOException {
        if (tableData == null || tableData.isEmpty()) {
            return;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(tableData.entrySet());
        int chunkSize = 8;
        for (int offset = 0; offset < entries.size(); offset += chunkSize) {
            List<Map.Entry<String, String>> slice = entries.subList(offset, Math.min(entries.size(), offset + chunkSize));
            List<ColumnSpec> cols = new ArrayList<>(slice.size());
            List<String> row = new ArrayList<>(slice.size());
            for (Map.Entry<String, String> e : slice) {
                Align align = looksNumericOrRate(e.getValue()) ? Align.RIGHT : Align.LEFT;
                cols.add(new ColumnSpec(e.getKey(), 1.0f, align, true));
                row.add(e.getValue());
            }
            drawTable(pdf, cols, List.of(row), false, 8.0f);
        }
    }

    /**
     * Draws a per-lane metrics table (Lane Report Alarm & Fault Statistics).
     */
    public void drawLanesTable(
            PdfDocumentBuilder pdf,
            Map<String, Map<String, String>> tableData,
            Map<String, String> laneDisplayLabels
    ) throws IOException {
        if (tableData == null || tableData.isEmpty()) {
            pdf.drawCalloutBox("No lane metrics available.");
            return;
        }
        List<String> statKeys = new ArrayList<>(tableData.values().iterator().next().keySet());
        List<ColumnSpec> cols = new ArrayList<>(statKeys.size() + 1);
        cols.add(new ColumnSpec("Lane (Name / UID)", 2.0f, Align.LEFT, false));
        for (String key : statKeys) {
            float weight = (key.contains("Occupancies") || key.contains("Suppressed")) ? 1.24f : 1.0f;
            cols.add(new ColumnSpec(key, weight, Align.RIGHT, false));
        }

        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : tableData.entrySet()) {
            String laneUid = entry.getKey();
            String display = (laneDisplayLabels != null && laneDisplayLabels.containsKey(laneUid))
                    ? laneDisplayLabels.get(laneUid)
                    : laneUid;
            List<String> row = new ArrayList<>(cols.size());
            row.add(display);
            Map<String, String> vals = entry.getValue();
            for (String key : statKeys) {
                row.add(vals.getOrDefault(key, "0"));
            }
            rows.add(row);
        }

        drawTable(pdf, cols, rows, false, 7.6f);
    }

    /**
     * Draws a daily metrics breakdown table with an automatic Total summary row at the bottom.
     */
    public void drawTableByDate(
            PdfDocumentBuilder pdf,
            Map<String, Map<String, String>> tableDataByDate,
            boolean includeTotalsRow
    ) throws IOException {
        if (tableDataByDate == null || tableDataByDate.isEmpty()) {
            pdf.drawCalloutBox("No daily observations found for the requested date range.");
            return;
        }
        Set<String> categorySet = new LinkedHashSet<>();
        for (Map<String, String> dayMap : tableDataByDate.values()) {
            categorySet.addAll(dayMap.keySet());
        }
        List<String> categories = new ArrayList<>(categorySet);

        List<ColumnSpec> cols = new ArrayList<>(1 + categories.size());
        cols.add(new ColumnSpec("Date (UTC)", 1.3f, Align.LEFT, false));
        for (String cat : categories) {
            cols.add(new ColumnSpec(cat, 1.0f, Align.RIGHT, false));
        }

        long[] columnSums = new long[categories.size()];
        boolean[] sumValid = new boolean[categories.size()];
        for (int i = 0; i < sumValid.length; i++) {
            sumValid[i] = true;
        }

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);
        List<List<String>> rows = new ArrayList<>(tableDataByDate.size() + 1);
        for (Map.Entry<String, Map<String, String>> dateEntry : tableDataByDate.entrySet()) {
            List<String> row = new ArrayList<>(cols.size());
            row.add(dateEntry.getKey());
            Map<String, String> vals = dateEntry.getValue();
            for (int c = 0; c < categories.size(); c++) {
                String raw = vals.getOrDefault(categories.get(c), "0");
                try {
                    long parsed = Long.parseLong(raw.replace(",", "").trim());
                    columnSums[c] += parsed;
                    row.add(intFmt.format(parsed));
                } catch (NumberFormatException ex) {
                    sumValid[c] = false;
                    row.add(raw);
                }
            }
            rows.add(row);
        }

        boolean appendTotal = includeTotalsRow && rows.size() > 1;
        if (appendTotal) {
            List<String> totalRow = new ArrayList<>(cols.size());
            totalRow.add("Total (" + tableDataByDate.size() + " days)");
            for (int c = 0; c < categories.size(); c++) {
                totalRow.add(sumValid[c] ? intFmt.format(columnSums[c]) : "-");
            }
            rows.add(totalRow);
        }

        drawTable(pdf, cols, rows, appendTotal, 7.6f);
    }

    /**
     * Draws the 31-isotope summary counts as a clean 4-pair (8-column) grid instead of a squashed 31-column strip.
     */
    public void drawIsotopeGridTable(
            PdfDocumentBuilder pdf,
            Map<String, Long> isotopeCountsBySymbol,
            Map<String, String> symbolToName
    ) throws IOException {
        if (isotopeCountsBySymbol == null || isotopeCountsBySymbol.isEmpty()) {
            return;
        }
        int pairsPerRow = 4;
        List<ColumnSpec> cols = new ArrayList<>(pairsPerRow * 2);
        for (int p = 0; p < pairsPerRow; p++) {
            cols.add(new ColumnSpec("Isotope", 1.75f, Align.LEFT, false));
            cols.add(new ColumnSpec("Count", 0.85f, Align.RIGHT, true));
        }

        NumberFormat intFmt = NumberFormat.getIntegerInstance(Locale.US);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(isotopeCountsBySymbol.entrySet());
        int totalRows = (int) Math.ceil(entries.size() / (double) pairsPerRow);
        List<List<String>> rows = new ArrayList<>(totalRows);

        for (int r = 0; r < totalRows; r++) {
            List<String> row = new ArrayList<>(pairsPerRow * 2);
            for (int p = 0; p < pairsPerRow; p++) {
                int idx = r + p * totalRows; // column-major order reads naturally down each column
                if (idx < entries.size()) {
                    Map.Entry<String, Long> e = entries.get(idx);
                    String sym = e.getKey();
                    String fullName = symbolToName != null ? symbolToName.get(sym) : null;
                    String display = (fullName != null && !fullName.equalsIgnoreCase(sym))
                            ? sym + " (" + fullName + ")"
                            : sym;
                    row.add(display);
                    row.add(intFmt.format(Math.max(0L, e.getValue())));
                } else {
                    row.add("");
                    row.add("");
                }
            }
            rows.add(row);
        }

        drawTable(pdf, cols, rows, false, 7.4f);
    }

    /**
     * Draws the wide 12-column Adjudication Details table on Landscape pages (792 x 612 pt)
     * with structured EML_METADATA formatting so zero right-side columns clip or wrap per-character.
     */
    public void drawAdjudicationDetailsTable(
            PdfDocumentBuilder pdf,
            Map<String, List<Map<String, String>>> adjTableData,
            Map<String, String> laneDisplayLabels
    ) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        if (adjTableData != null) {
            for (Map.Entry<String, List<Map<String, String>>> laneEntry : adjTableData.entrySet()) {
                String laneUid = laneEntry.getKey();
                String laneDisplay = (laneDisplayLabels != null && laneDisplayLabels.containsKey(laneUid))
                        ? laneDisplayLabels.get(laneUid)
                        : laneUid;
                List<Map<String, String>> records = laneEntry.getValue();
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (Map<String, String> rec : records) {
                    List<String> row = new ArrayList<>(12);
                    row.add(laneDisplay);
                    row.add(rec.getOrDefault("#", ""));
                    row.add(rec.getOrDefault("Time (UTC)", ""));
                    row.add(rec.getOrDefault("Username", ""));
                    row.add(rec.getOrDefault("Adjudication Code", ""));
                    row.add(rec.getOrDefault("Isotopes", ""));
                    row.add(rec.getOrDefault("Secondary Inspection Status", ""));
                    row.add(formatAdjudicationFeedback(rec.getOrDefault("Feedback", "")));
                    row.add(rec.getOrDefault("File Paths", ""));
                    row.add(rec.getOrDefault("Occupancy Obs ID", ""));
                    row.add(rec.getOrDefault("Vehicle ID", ""));
                    rows.add(row);
                }
            }
        }

        if (rows.isEmpty()) {
            pdf.drawCalloutBox("No completed adjudication records exist for the selected lanes and time period.");
            return;
        }

        List<ColumnSpec> cols = List.of(
                new ColumnSpec("Lane", 1.55f, Align.LEFT),
                new ColumnSpec("#", 0.38f, Align.RIGHT),
                new ColumnSpec("Time (UTC)", 1.05f, Align.LEFT),
                new ColumnSpec("User", 0.80f, Align.LEFT),
                new ColumnSpec("Disposition Code", 1.35f, Align.LEFT),
                new ColumnSpec("Isotopes (Cnt)", 1.20f, Align.LEFT),
                new ColumnSpec("Secondary Status", 0.95f, Align.LEFT),
                new ColumnSpec("Feedback / EML Summary", 2.45f, Align.LEFT),
                new ColumnSpec("Files (Cnt)", 1.35f, Align.LEFT),
                new ColumnSpec("Occupancy Obs ID", 1.15f, Align.LEFT),
                new ColumnSpec("Vehicle ID", 0.85f, Align.LEFT)
        );

        drawTable(pdf, cols, rows, false, 7.0f);
    }

    /**
     * Converts embedded EML_METADATA:{...} JSON payloads inside adjudication feedback into concise,
     * human-readable key metrics while preserving all operator notes and summary lines intact.
     */
    public static String formatAdjudicationFeedback(String rawFeedback) {
        if (rawFeedback == null || rawFeedback.isBlank()) {
            return "-";
        }
        int markerIdx = rawFeedback.indexOf(Adjudication.EML_METADATA_PREFIX);
        if (markerIdx < 0) {
            return rawFeedback.trim();
        }

        String humanPrefix = rawFeedback.substring(0, markerIdx).trim();
        String jsonPart = rawFeedback.substring(markerIdx + Adjudication.EML_METADATA_PREFIX.length()).trim();

        try {
            JsonElement parsed = JsonParser.parseString(jsonPart);
            if (!parsed.isJsonObject()) {
                return humanPrefix.isEmpty() ? jsonPart : humanPrefix;
            }
            JsonObject obj = parsed.getAsJsonObject();
            List<String> details = new ArrayList<>();

            String sourceType = getString(obj, "overallSourceType");
            if (!sourceType.isEmpty()) {
                details.add("Source: " + sourceType);
            }
            if (obj.has("vehicleClass") && !obj.get("vehicleClass").isJsonNull()) {
                String veh = "Class " + obj.get("vehicleClass").getAsInt();
                if (obj.has("vehicleLength") && !obj.get("vehicleLength").isJsonNull()) {
                    double len = obj.get("vehicleLength").getAsDouble();
                    if (len > 0.0) {
                        veh += String.format(Locale.US, " (%.1fm)", len);
                    }
                }
                details.add("Vehicle: " + veh);
            }

            Double pNorm = getDouble(obj, "probabilityNORM");
            Double pNonEmit = getDouble(obj, "probabilityNonEmitting");
            Double pThreat = getDouble(obj, "probabilityThreat");
            if (pNorm != null || pNonEmit != null || pThreat != null) {
                details.add(String.format(
                        Locale.US,
                        "Probs [NORM %.1f%%, Non-Emit %.1f%%, Threat %.1f%%]",
                        (pNorm != null ? pNorm * 100.0 : 0.0),
                        (pNonEmit != null ? pNonEmit * 100.0 : 0.0),
                        (pThreat != null ? pThreat * 100.0 : 0.0)
                ));
            }

            String structuredLine = details.isEmpty() ? "" : String.join(" | ", details);
            if (humanPrefix.isEmpty()) {
                return structuredLine.isEmpty() ? "EML Non-Threat" : structuredLine;
            }
            return structuredLine.isEmpty() ? humanPrefix : (humanPrefix + "\n" + structuredLine);
        } catch (Exception ignored) {
            return humanPrefix.isEmpty() ? rawFeedback.trim() : humanPrefix;
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Double getDouble(JsonObject obj, String key) {
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

    private static boolean looksNumericOrRate(String val) {
        if (val == null || val.isBlank()) {
            return false;
        }
        String s = val.trim();
        if (s.endsWith("%") || s.endsWith(" GB")) {
            s = s.replace("%", "").replace(" GB", "").trim();
        }
        s = s.replace(",", "");
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
