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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free ECMA-376 OpenXML (.xlsx) workbook writer using standard JDK {@link ZipOutputStream}.
 * Produces strict, styled spreadsheets with frozen header rows, auto-filters, explicit column widths,
 * typed numeric cells, and inline-string cells that never evaluate untrusted strings as formulas.
 */
public final class XlsxWorkbookWriter {

    public enum CellKind {
        TEXT,
        INTEGER,
        DECIMAL
    }

    public record CellValue(CellKind kind, String textValue, double numericValue) {
        public static CellValue text(String value) {
            return new CellValue(CellKind.TEXT, value == null ? "" : value, 0.0);
        }

        public static CellValue integer(long value) {
            return new CellValue(CellKind.INTEGER, null, value);
        }

        public static CellValue decimal(Double value) {
            if (value == null || !Double.isFinite(value)) {
                return text("-");
            }
            return new CellValue(CellKind.DECIMAL, null, value);
        }
    }

    public record SheetSpec(
            String name,
            List<String> headers,
            List<Double> columnWidths,
            List<List<CellValue>> rows,
            boolean autoFilter
    ) {}

    private static final int STYLE_HEADER = 1;
    private static final int STYLE_TEXT_EVEN = 2;
    private static final int STYLE_INT_EVEN = 3;
    private static final int STYLE_DEC_EVEN = 4;
    private static final int STYLE_TEXT_ODD = 5;
    private static final int STYLE_INT_ODD = 6;
    private static final int STYLE_DEC_ODD = 7;

    private XlsxWorkbookWriter() {}

    public static void writeWorkbook(OutputStream out, List<SheetSpec> sheets) throws IOException {
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("At least one worksheet is required");
        }
        List<SheetSpec> safeSheets = new ArrayList<>(sheets);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", buildContentTypesXml(safeSheets.size()));
            writeEntry(zip, "_rels/.rels", buildRootRelsXml());
            writeEntry(zip, "xl/workbook.xml", buildWorkbookXml(safeSheets));
            writeEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml(safeSheets.size()));
            writeEntry(zip, "xl/styles.xml", buildStylesXml());
            for (int i = 0; i < safeSheets.size(); i++) {
                writeEntry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", buildWorksheetXml(safeSheets.get(i)));
            }
            zip.finish();
        }
    }

    private static void writeEntry(ZipOutputStream zip, String entryName, String xml) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zip.putNextEntry(entry);
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String buildContentTypesXml(int sheetCount) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
          .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
          .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    private static String buildRootRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String buildWorkbookXml(List<SheetSpec> sheets) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
          .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
          .append("<sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            String safeName = sanitizeSheetName(sheets.get(i).name(), i + 1);
            sb.append("<sheet name=\"").append(escapeXmlAttr(safeName))
              .append("\" sheetId=\"").append(i + 1)
              .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets></workbook>");
        return sb.toString();
    }

    private static String buildWorkbookRelsXml(int sheetCount) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Relationship Id=\"rId").append(i)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
              .append(i).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheetCount + 1)
          .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
          .append("</Relationships>");
        return sb.toString();
    }

    private static String buildStylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"2\">"
                + "<numFmt numFmtId=\"164\" formatCode=\"#,##0\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"0.0\"/>"
                + "</numFmts>"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"10\"/><color rgb=\"FF0F172A\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
                + "</fonts>"
                + "<fills count=\"4\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1B365D\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF8FAFC\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "</fills>"
                + "<borders count=\"2\">"
                + "<border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border>"
                + "<left style=\"thin\"><color rgb=\"FFCBD5E1\"/></left>"
                + "<right style=\"thin\"><color rgb=\"FFCBD5E1\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFCBD5E1\"/></top>"
                + "<bottom style=\"thin\"><color rgb=\"FFCBD5E1\"/></bottom>"
                + "<diagonal/>"
                + "</border>"
                + "</borders>"
                + "<cellStyleXfs count=\"1\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "</cellStyleXfs>"
                + "<cellXfs count=\"8\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"left\" vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"right\" vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"right\" vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"left\" vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"right\" vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"right\" vertical=\"top\"/></xf>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String buildWorksheetXml(SheetSpec sheet) {
        List<String> headers = sheet.headers() != null ? sheet.headers() : List.of();
        List<List<CellValue>> rows = sheet.rows() != null ? sheet.rows() : List.of();
        int colCount = Math.max(1, headers.size());
        int totalRows = 1 + rows.size();
        String lastColRef = columnName(colCount - 1);

        StringBuilder sb = new StringBuilder(Math.max(2048, totalRows * colCount * 48));
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
          .append("<dimension ref=\"A1:").append(lastColRef).append(totalRows).append("\"/>")
          .append("<sheetViews><sheetView workbookViewId=\"0\">")
          .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
          .append("</sheetView></sheetViews>")
          .append("<sheetFormatPr defaultRowHeight=\"16\"/>");

        if (!headers.isEmpty()) {
            sb.append("<cols>");
            List<Double> widths = sheet.columnWidths();
            for (int c = 0; c < headers.size(); c++) {
                double w = (widths != null && c < widths.size() && widths.get(c) != null && widths.get(c) > 4.0)
                        ? widths.get(c)
                        : 18.0;
                sb.append("<col min=\"").append(c + 1)
                  .append("\" max=\"").append(c + 1)
                  .append("\" width=\"").append(String.format(Locale.US, "%.1f", w))
                  .append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }

        sb.append("<sheetData>");

        // Row 1: Header
        sb.append("<row r=\"1\" ht=\"22\" customHeight=\"1\">");
        for (int c = 0; c < headers.size(); c++) {
            String cellRef = columnName(c) + "1";
            appendInlineStrCell(sb, cellRef, STYLE_HEADER, headers.get(c));
        }
        sb.append("</row>");

        // Data rows
        for (int r = 0; r < rows.size(); r++) {
            int excelRow = r + 2;
            boolean zebra = (r % 2 == 1);
            List<CellValue> rowCells = rows.get(r);
            sb.append("<row r=\"").append(excelRow).append("\">");
            if (rowCells != null) {
                for (int c = 0; c < rowCells.size(); c++) {
                    CellValue val = rowCells.get(c);
                    if (val == null) {
                        continue;
                    }
                    String cellRef = columnName(c) + excelRow;
                    switch (val.kind()) {
                        case INTEGER -> sb.append("<c r=\"").append(cellRef)
                                .append("\" s=\"").append(zebra ? STYLE_INT_ODD : STYLE_INT_EVEN)
                                .append("\"><v>").append(Math.round(val.numericValue())).append("</v></c>");
                        case DECIMAL -> sb.append("<c r=\"").append(cellRef)
                                .append("\" s=\"").append(zebra ? STYLE_DEC_ODD : STYLE_DEC_EVEN)
                                .append("\"><v>").append(String.format(Locale.US, "%.2f", val.numericValue())).append("</v></c>");
                        case TEXT -> appendInlineStrCell(
                                sb,
                                cellRef,
                                zebra ? STYLE_TEXT_ODD : STYLE_TEXT_EVEN,
                                guardSpreadsheetFormulaPrefix(val.textValue())
                        );
                    }
                }
            }
            sb.append("</row>");
        }

        sb.append("</sheetData>");
        if (sheet.autoFilter() && !headers.isEmpty()) {
            sb.append("<autoFilter ref=\"A1:").append(lastColRef).append(totalRows).append("\"/>");
        }
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static void appendInlineStrCell(StringBuilder sb, String cellRef, int styleIdx, String rawText) {
        String safe = escapeXmlText(rawText);
        boolean preserveSpace = !safe.isEmpty() && (Character.isWhitespace(safe.charAt(0))
                || Character.isWhitespace(safe.charAt(safe.length() - 1))
                || safe.indexOf('\n') >= 0);
        sb.append("<c r=\"").append(cellRef).append("\" s=\"").append(styleIdx).append("\" t=\"inlineStr\"><is>");
        if (preserveSpace) {
            sb.append("<t xml:space=\"preserve\">").append(safe).append("</t>");
        } else {
            sb.append("<t>").append(safe).append("</t>");
        }
        sb.append("</is></c>");
    }

    /**
     * Prevents spreadsheet formula injection when exported cells are copied or re-imported via CSV/clipboard.
     */
    public static String guardSpreadsheetFormulaPrefix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        char first = raw.charAt(0);
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r') {
            return "'" + raw;
        }
        if (first == '-' && raw.length() > 1 && !Character.isDigit(raw.charAt(1))) {
            return "'" + raw;
        }
        return raw;
    }

    static String columnName(int zeroBasedCol) {
        int col = zeroBasedCol + 1;
        StringBuilder name = new StringBuilder(3);
        while (col > 0) {
            int rem = (col - 1) % 26;
            name.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return name.toString();
    }

    private static String sanitizeSheetName(String raw, int fallbackIndex) {
        if (raw == null || raw.isBlank()) {
            return "Sheet" + fallbackIndex;
        }
        String cleaned = raw.replaceAll("[\\\\/*?:\\[\\]]", " ").trim();
        if (cleaned.isEmpty()) {
            return "Sheet" + fallbackIndex;
        }
        return cleaned.length() > 31 ? cleaned.substring(0, 31).trim() : cleaned;
    }

    private static String escapeXmlAttr(String raw) {
        return escapeXmlText(raw).replace("\"", "&quot;");
    }

    private static String escapeXmlText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '&') {
                out.append("&amp;");
            } else if (ch == '<') {
                out.append("&lt;");
            } else if (ch == '>') {
                out.append("&gt;");
            } else if (ch == '\r') {
                // Normalize CR/CRLF to LF inside worksheet text
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    continue;
                }
                out.append('\n');
            } else if (ch == '\t' || ch == '\n' || (ch >= 0x20 && ch <= 0xD7FF) || (ch >= 0xE000 && ch <= 0xFFFD)) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
