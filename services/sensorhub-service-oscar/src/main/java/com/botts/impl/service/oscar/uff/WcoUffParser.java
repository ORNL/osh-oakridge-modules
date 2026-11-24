/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import com.botts.impl.service.oscar.uff.WcoUffModels.AnnotationInfo;
import com.botts.impl.service.oscar.uff.WcoUffModels.DeclarationInfo;
import com.botts.impl.service.oscar.uff.WcoUffModels.HemdZMapBand;
import com.botts.impl.service.oscar.uff.WcoUffModels.OcrResultInfo;
import com.botts.impl.service.oscar.uff.WcoUffModels.RadiationFileInfo;
import com.botts.impl.service.oscar.uff.WcoUffModels.UffCaseMetadata;
import com.botts.impl.service.oscar.uff.WcoUffModels.XRayFileInfo;
import com.botts.impl.service.oscar.vehicle.VehicleIncidentAggregator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dual-version WCO {@code UFF 2.0} &amp; {@code UFF 3.0.0} XML metadata parser, ZIP entry
 * URI resolver, omnibox UUID/filename extractor, and 1-character OCR confusable matcher.
 */
public final class WcoUffParser {

    private static final int MAX_VERSION_BYTES = 256;
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private WcoUffParser() {}

    /**
     * Extracts a canonical lowercase UFF UUID or normalized {@code .uff} filename stem from
     * a raw UUID, {@code <uuid>.uff} filename, {@code uff-link-<uuid>.uffref.json}, Windows UNC
     * path ({@code \\server\share\2026\11\20\SCANNER\<uuid>.uff}), mapped drive path, or POSIX path.
     *
     * @return lowercase UUID string if present, else empty string when input does not look like a UFF reference
     */
    public static String extractUffUuidFromInput(String rawInput) {
        if (rawInput == null) {
            return "";
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean hasUffHint = lower.endsWith(".uff")
                || lower.endsWith(".uffref.json")
                || lower.contains("\\")
                || lower.contains("/")
                || UUID_PATTERN.matcher(trimmed).matches();
        if (!hasUffHint) {
            return "";
        }
        String leaf = trimmed.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) {
            leaf = leaf.substring(slash + 1);
        }
        Matcher m = UUID_PATTERN.matcher(leaf);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * Parses a WCO {@code .uff} archive via random-access {@link UffZipReader} without reading
     * heavy X-ray TIFF or HEMD PNG payloads.
     */
    public static UffCaseMetadata parseArchiveMetadata(
            UffZipReader zip,
            String archiveFileName,
            String relativePath,
            long archiveSizeBytes,
            long archiveMtimeMillis
    ) throws IOException {
        Map<String, UffZipReader.ZipEntryMeta> entries = zip.entries();

        String versionStr = "";
        if (entries.containsKey("version")) {
            versionStr = new String(zip.readEntryBytesBounded("version", MAX_VERSION_BYTES), StandardCharsets.UTF_8).trim();
        }

        // Locate root <uuid>.xml (skip nested event entries and .n42 files)
        UffZipReader.ZipEntryMeta rootXmlMeta = null;
        for (UffZipReader.ZipEntryMeta meta : entries.values()) {
            if (meta.directory()) {
                continue;
            }
            String name = meta.name();
            if (!name.contains("/")
                    && name.toLowerCase(Locale.ROOT).endsWith(".xml")
                    && !name.toLowerCase(Locale.ROOT).endsWith(".n42")) {
                rootXmlMeta = meta;
                break;
            }
        }
        if (rootXmlMeta == null) {
            throw new IOException("Invalid UFF archive '" + archiveFileName + "': missing root <uuid>.xml descriptor");
        }

        byte[] xmlBytes = zip.readEntryBytesBounded(rootXmlMeta.name(), MAX_XML_BYTES);
        Document doc = parseSecureXml(xmlBytes);
        Element root = doc.getDocumentElement();
        if (root == null || !"UFF".equalsIgnoreCase(localNameOf(root))) {
            throw new IOException("Invalid UFF XML root element in '" + archiveFileName + "'");
        }

        String ns = root.getNamespaceURI() != null ? root.getNamespaceURI() : "";
        String xmlId = childText(root, "Id");
        if (versionStr.isEmpty()) {
            versionStr = (ns.contains("UFF3") || !xmlId.isEmpty()) ? "3.0" : "2.0";
        }

        String uffUuid = !xmlId.isEmpty() ? xmlId.trim().toLowerCase(Locale.ROOT) : "";
        if (uffUuid.isEmpty()) {
            uffUuid = extractUffUuidFromInput(rootXmlMeta.name());
        }
        if (uffUuid.isEmpty()) {
            uffUuid = extractUffUuidFromInput(archiveFileName);
        }
        if (uffUuid.isEmpty()) {
            String stem = rootXmlMeta.name();
            int dot = stem.lastIndexOf('.');
            uffUuid = (dot > 0 ? stem.substring(0, dot) : stem).toLowerCase(Locale.ROOT);
        }

        Element targetEl = firstChildElement(root, "Target");
        Element scanEl = firstChildElement(root, "Scan");
        Element analysisEl = firstChildElement(root, "Analysis");

        String targetEventId = targetEl != null ? childText(targetEl, "EventId") : "";
        String scanEventId = scanEl != null ? childText(scanEl, "EventId") : "";

        // Scanner / XraySystem identification
        Element xraySys = scanEl != null ? firstChildElement(scanEl, "XraySystem") : null;
        Element inspSys = targetEl != null ? firstChildElement(targetEl, "InspectionSystem") : null;
        String scannerId = firstNonBlank(
                xraySys != null ? childText(xraySys, "Identifier") : "",
                inspSys != null ? childText(inspSys, "Identifier") : ""
        );
        String manufacturer = firstNonBlank(
                xraySys != null ? childText(xraySys, "Manufacturer") : "",
                inspSys != null ? childText(inspSys, "Manufacturer") : ""
        );
        String modelName = firstNonBlank(
                xraySys != null ? childText(xraySys, "ModelName") : "",
                inspSys != null ? childText(inspSys, "ModelName") : ""
        );
        String serialNumber = firstNonBlank(
                xraySys != null ? childText(xraySys, "SerialNumber") : "",
                inspSys != null ? childText(inspSys, "SerialNumber") : ""
        );
        String country = xraySys != null ? childText(xraySys, "Country") : "";
        Element locEl = xraySys != null ? firstChildElement(xraySys, "Location") : null;
        Double lat = locEl != null ? parseDoubleOrNull(childText(locEl, "Latitude")) : null;
        Double lon = locEl != null ? parseDoubleOrNull(childText(locEl, "Longitude")) : null;

        String scannerCaseId = scanEl != null ? childText(scanEl, "ScannerCaseId") : "";
        String scanTimestamp = firstNonBlank(
                scanEl != null ? childText(scanEl, "TimeStamp") : "",
                targetEl != null ? childText(targetEl, "TimeStamp") : ""
        );
        long scanEpochMs = parseEpochMillis(scanTimestamp, archiveMtimeMillis);
        Long scanDurationMs = scanEl != null ? parseLongOrNull(childText(scanEl, "Duration")) : null;

        // Target / Vehicle / Container / Declaration
        Element vehicleEl = targetEl != null ? firstChildElement(targetEl, "Vehicle") : null;
        Element containerEl = vehicleEl != null ? firstChildElement(vehicleEl, "Container") : null;
        String containerNum = containerEl != null ? childText(containerEl, "ContainerNum") : "";
        Double containerWeightKg = containerEl != null ? parseDoubleOrNull(childText(containerEl, "Weight")) : null;
        Integer containerLengthFt = containerEl != null ? parseIntOrNull(childText(containerEl, "Length")) : null;
        String frontPlateNum = vehicleEl != null ? childText(vehicleEl, "FrontPlateNum") : "";
        String vehicleType = vehicleEl != null ? childText(vehicleEl, "VehicleType") : "";
        Double vehicleWeightKg = vehicleEl != null ? parseDoubleOrNull(childText(vehicleEl, "Weight")) : null;

        DeclarationInfo declaration = null;
        Element declEl = containerEl != null ? firstChildElement(containerEl, "Declaration") : null;
        if (declEl != null) {
            String customsId = childText(declEl, "CustomsId");
            Element consignorCo = firstDescendant(declEl, "Consignor", "Company");
            Element consigneeCo = firstDescendant(declEl, "Consignee", "Company");
            String consignorName = consignorCo != null ? childText(consignorCo, "Name") : "";
            String consignorLoc = formatAddressSummary(consignorCo != null ? firstChildElement(consignorCo, "Address") : null);
            String consigneeName = consigneeCo != null ? childText(consigneeCo, "Name") : "";
            String consigneeLoc = formatAddressSummary(consigneeCo != null ? firstChildElement(consigneeCo, "Address") : null);
            Element goodsEl = firstChildElement(declEl, "GoodsDescription");
            String hsCode = goodsEl != null ? childText(goodsEl, "HSCode") : "";
            String goodsDesc = goodsEl != null ? childText(goodsEl, "Description") : "";
            String unit = goodsEl != null ? childText(goodsEl, "Unit") : "";
            Double qty = goodsEl != null ? parseDoubleOrNull(childText(goodsEl, "Quantity")) : null;
            if (!customsId.isEmpty() || !hsCode.isEmpty() || !goodsDesc.isEmpty() || !consignorName.isEmpty()) {
                declaration = new DeclarationInfo(
                        customsId, consignorName, consignorLoc, consigneeName, consigneeLoc,
                        hsCode, goodsDesc, unit, qty);
            }
        }

        // Manifest PDF under <Target><Document>
        String manifestEntryPath = "";
        long manifestSizeBytes = 0L;
        if (targetEl != null) {
            for (Element docEl : childElements(targetEl, "Document")) {
                String uri = childText(docEl, "URI");
                String type = childText(docEl, "Type");
                if (!uri.isEmpty() && ("Manifest".equalsIgnoreCase(type) || uri.toLowerCase(Locale.ROOT).endsWith(".pdf"))) {
                    UffZipReader.ZipEntryMeta resolved = resolveUriEntry(entries, targetEventId, uri);
                    if (resolved != null) {
                        manifestEntryPath = resolved.name();
                        manifestSizeBytes = resolved.uncompressedSize();
                        break;
                    }
                }
            }
        }

        // Collect all normalized & display Vehicle IDs across Target + Scan/OcrResult
        Set<String> normIds = new LinkedHashSet<>();
        Set<String> dispIds = new LinkedHashSet<>();
        addIdentifier(containerNum, normIds, dispIds);
        addIdentifier(frontPlateNum, normIds, dispIds);

        List<OcrResultInfo> ocrResults = new ArrayList<>();
        if (scanEl != null) {
            for (Element ocrEl : childElements(scanEl, "OcrResult")) {
                String ocrType = childText(ocrEl, "OcrType");
                String val = childText(ocrEl, "Value");
                String normVal = VehicleIncidentAggregator.normalizeVehicleId(val);
                addIdentifier(val, normIds, dispIds);
                Double conf = parseDoubleOrNull(childText(ocrEl, "Confidence"));
                String plateLoc = childText(ocrEl, "PlateLocation");
                Element outEl = firstChildElement(ocrEl, "OutputFile");
                String view = outEl != null ? childText(outEl, "View") : "";
                String imgUri = outEl != null ? childText(outEl, "URI") : "";
                UffZipReader.ZipEntryMeta imgMeta = !imgUri.isEmpty()
                        ? resolveUriEntry(entries, scanEventId, imgUri)
                        : null;
                Element ccrDetail = firstChildElement(ocrEl, "CcrDetail");
                String contType = ccrDetail != null ? childText(ccrDetail, "ContainerType") : "";
                ocrResults.add(new OcrResultInfo(
                        ocrType,
                        val,
                        normVal,
                        conf,
                        plateLoc,
                        view,
                        imgMeta != null ? imgMeta.name() : "",
                        contType
                ));
            }
        }

        // RadiationDetection (.n42) files under <Scan><OutputFile>
        List<RadiationFileInfo> radiationFiles = new ArrayList<>();
        if (scanEl != null) {
            for (Element outEl : childElements(scanEl, "OutputFile")) {
                String type = childText(outEl, "Type");
                String uri = childText(outEl, "URI");
                String fileId = childText(outEl, "FileId");
                if (!uri.isEmpty() && ("RadiationDetection".equalsIgnoreCase(type) || uri.toLowerCase(Locale.ROOT).endsWith(".n42"))) {
                    UffZipReader.ZipEntryMeta radMeta = resolveUriEntry(entries, scanEventId, uri);
                    if (radMeta != null) {
                        radiationFiles.add(new RadiationFileInfo(
                                fileId, radMeta.name(), radMeta.uncompressedSize(),
                                type.isEmpty() ? "RadiationDetection" : type));
                    }
                }
            }
        }

        // XRayFile + HEMDInfo
        List<XRayFileInfo> xrayFiles = new ArrayList<>();
        if (scanEl != null) {
            for (Element xrEl : childElements(scanEl, "XRayFile")) {
                String fileId = childText(xrEl, "FileId");
                String uri = childText(xrEl, "URI");
                UffZipReader.ZipEntryMeta xrMeta = resolveUriEntry(entries, scanEventId, uri);
                if (xrMeta == null) {
                    continue;
                }
                String view = childText(xrEl, "View");
                String modality = childText(xrEl, "Modality");
                String scanningMode = childText(xrEl, "ScanningMode");
                List<Double> energies = new ArrayList<>();
                for (Element enEl : childElements(xrEl, "Energy")) {
                    Double d = parseDoubleOrNull(enEl.getTextContent());
                    if (d != null) {
                        energies.add(d);
                    }
                }
                double speedSum = 0.0;
                int speedCount = 0;
                for (Element spEl : childElements(xrEl, "Speed")) {
                    Double s = parseDoubleOrNull(spEl.getTextContent());
                    if (s != null) {
                        speedSum += s;
                        speedCount++;
                    }
                }
                Double avgSpeed = speedCount > 0 ? Math.round((speedSum / speedCount) * 10.0) / 10.0 : null;
                Double hPix = parseDoubleOrNull(childText(xrEl, "HorizontalPixelSize"));
                Double vPix = parseDoubleOrNull(childText(xrEl, "VerticalPixelSize"));

                String hemdPath = "";
                long hemdSize = 0L;
                List<HemdZMapBand> zMaps = new ArrayList<>();
                Element hemdInfoEl = firstChildElement(xrEl, "HEMDInfo");
                if (hemdInfoEl != null) {
                    String hemdUri = childText(hemdInfoEl, "HEMDFileURI");
                    if (!hemdUri.isEmpty()) {
                        UffZipReader.ZipEntryMeta hmMeta = resolveUriEntry(entries, scanEventId, hemdUri);
                        if (hmMeta != null) {
                            hemdPath = hmMeta.name();
                            hemdSize = hmMeta.uncompressedSize();
                        }
                    }
                    Element hemdMapEl = firstChildElement(hemdInfoEl, "HEMDMap");
                    if (hemdMapEl != null) {
                        for (Element zmEl : childElements(hemdMapEl, "ZMap")) {
                            String zName = childText(zmEl, "Name");
                            Integer ps = parseIntOrNull(childText(zmEl, "PaletteStart"));
                            Integer pe = parseIntOrNull(childText(zmEl, "PaletteEnd"));
                            Integer zs = parseIntOrNull(childText(zmEl, "EffectiveZStart"));
                            Integer ze = parseIntOrNull(childText(zmEl, "EffectiveZEnd"));
                            if (ps != null && pe != null) {
                                zMaps.add(new HemdZMapBand(
                                        zName,
                                        ps,
                                        pe,
                                        zs != null ? zs : 0,
                                        ze != null ? ze : 0
                                ));
                            }
                        }
                    }
                }

                xrayFiles.add(new XRayFileInfo(
                        fileId,
                        xrMeta.name(),
                        xrMeta.uncompressedSize(),
                        view,
                        modality,
                        scanningMode,
                        List.copyOf(energies),
                        hPix,
                        vPix,
                        avgSpeed,
                        hemdPath,
                        hemdSize,
                        List.copyOf(zMaps)
                ));
            }
        }

        // Analysis & Annotations
        String analysisAssessment = "";
        String analysisResult = "";
        String analysisAlgName = "";
        String analysisVendor = "";
        List<AnnotationInfo> annotations = new ArrayList<>();
        if (analysisEl != null) {
            analysisAssessment = childText(analysisEl, "Assessment");
            analysisResult = childText(analysisEl, "Result");
            Element algEl = firstChildElement(analysisEl, "Algorithm");
            if (algEl != null) {
                analysisAlgName = childText(algEl, "Name");
                analysisVendor = childText(algEl, "Vendor");
            }
            for (Element annEl : childElements(analysisEl, "Annotation")) {
                Element bbEl = firstChildElement(annEl, "BoundingBox");
                if (bbEl == null) {
                    continue;
                }
                Double top = parseDoubleOrNull(childText(bbEl, "Top"));
                Double left = parseDoubleOrNull(childText(bbEl, "Left"));
                Double bottom = parseDoubleOrNull(childText(bbEl, "Bottom"));
                Double right = parseDoubleOrNull(childText(bbEl, "Right"));
                if (top == null || left == null || bottom == null || right == null) {
                    continue;
                }
                annotations.add(new AnnotationInfo(
                        childText(annEl, "AnnotationId"),
                        childText(annEl, "Description"),
                        childText(annEl, "Comment"),
                        parseIntOrNull(childText(annEl, "Confidence")),
                        childText(annEl, "FileURI"),
                        firstNonBlank(childText(annEl, "Shape"), "Rectangle"),
                        clamp01(top),
                        clamp01(left),
                        clamp01(bottom),
                        clamp01(right)
                ));
            }
        }

        UffZipReader.ZipEntryMeta previewMeta = entries.get("preview.jpg");
        if (previewMeta == null) {
            for (UffZipReader.ZipEntryMeta m : entries.values()) {
                if (!m.directory() && m.name().toLowerCase(Locale.ROOT).endsWith("preview.jpg")) {
                    previewMeta = m;
                    break;
                }
            }
        }

        return new UffCaseMetadata(
                uffUuid,
                versionStr,
                archiveFileName,
                relativePath,
                archiveSizeBytes,
                archiveMtimeMillis,
                scannerId,
                manufacturer,
                modelName,
                serialNumber,
                scannerCaseId,
                country,
                lat,
                lon,
                scanTimestamp,
                scanEpochMs,
                scanDurationMs,
                containerNum,
                frontPlateNum,
                vehicleType,
                containerWeightKg,
                vehicleWeightKg,
                containerLengthFt,
                List.copyOf(normIds),
                List.copyOf(dispIds),
                List.copyOf(ocrResults),
                declaration,
                manifestEntryPath,
                manifestSizeBytes,
                List.copyOf(radiationFiles),
                List.copyOf(xrayFiles),
                analysisAssessment,
                analysisResult,
                analysisAlgName,
                analysisVendor,
                List.copyOf(annotations),
                previewMeta != null ? previewMeta.name() : "",
                previewMeta != null ? previewMeta.uncompressedSize() : 0L
        );
    }

    /**
     * Resolves an internal {@code <URI>} against both WCO {@code UFF 3.0}
     * ({@code event<uuid_no_hyphens>/<URI>}) and {@code UFF 2.0} ({@code event-<uuid>/<URI>})
     * directory conventions.
     */
    public static UffZipReader.ZipEntryMeta resolveUriEntry(
            Map<String, UffZipReader.ZipEntryMeta> entries,
            String eventId,
            String rawUri
    ) {
        if (rawUri == null || rawUri.isBlank()) {
            return null;
        }
        String cleanUri = UffZipReader.sanitizeEntryName(rawUri);
        if (cleanUri.isEmpty()) {
            return null;
        }
        UffZipReader.ZipEntryMeta direct = entries.get(cleanUri);
        if (direct != null && !direct.directory()) {
            return direct;
        }
        if (eventId != null && !eventId.isBlank()) {
            String evTrim = eventId.trim();
            String evCompact = evTrim.replace("-", "");
            for (String prefix : List.of("event" + evCompact, "event-" + evTrim, evTrim, evCompact)) {
                UffZipReader.ZipEntryMeta candidate = entries.get(prefix + "/" + cleanUri);
                if (candidate != null && !candidate.directory()) {
                    return candidate;
                }
            }
        }
        String suffix = "/" + cleanUri;
        UffZipReader.ZipEntryMeta fallback = null;
        for (UffZipReader.ZipEntryMeta m : entries.values()) {
            if (!m.directory() && (m.name().equalsIgnoreCase(cleanUri) || m.name().endsWith(suffix))) {
                if (fallback != null) {
                    // Ambiguous suffix across multiple events: return first deterministic match
                    return fallback;
                }
                fallback = m;
            }
        }
        return fallback;
    }

    /**
     * Evaluates whether two normalized alphanumeric container/plate identifiers differ by at most
     * one character (handling scanner CCR/LPR confusables such as {@code 0<->O}, {@code 8<->B},
     * {@code 5<->S}, {@code 6<->G}, {@code 1<->I}, or a single dropped/extra check character).
     */
    public static boolean isSingleCharOcrFuzzyMatch(String normQuery, String normCandidate) {
        if (normQuery == null || normCandidate == null) {
            return false;
        }
        if (normQuery.length() < 6 || normCandidate.length() < 6) {
            return false;
        }
        if (normQuery.equals(normCandidate)) {
            return true;
        }
        int lenDiff = Math.abs(normQuery.length() - normCandidate.length());
        if (lenDiff > 1) {
            return false;
        }
        if (lenDiff == 0) {
            int mismatches = 0;
            for (int i = 0; i < normQuery.length(); i++) {
                if (normQuery.charAt(i) != normCandidate.charAt(i)) {
                    if (++mismatches > 1) {
                        return false;
                    }
                }
            }
            return mismatches == 1;
        }
        // Length differs by 1 (e.g., dropped check digit)
        String shorter = normQuery.length() < normCandidate.length() ? normQuery : normCandidate;
        String longer = normQuery.length() < normCandidate.length() ? normCandidate : normQuery;
        int si = 0;
        int li = 0;
        int skips = 0;
        while (si < shorter.length() && li < longer.length()) {
            if (shorter.charAt(si) == longer.charAt(li)) {
                si++;
                li++;
            } else {
                if (++skips > 1) {
                    return false;
                }
                li++;
            }
        }
        return true;
    }

    private static Document parseSecureXml(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder db = dbf.newDocumentBuilder();
            return db.parse(new ByteArrayInputStream(xmlBytes));
        } catch (Exception e) {
            throw new IOException("Failed parsing WCO UFF XML descriptor: " + e.getMessage(), e);
        }
    }

    private static void addIdentifier(String raw, Set<String> normIds, Set<String> dispIds) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String norm = VehicleIncidentAggregator.normalizeVehicleId(trimmed);
        if (!norm.isEmpty()) {
            normIds.add(norm);
            dispIds.add(trimmed);
        }
    }

    private static String formatAddressSummary(Element addressEl) {
        if (addressEl == null) {
            return "";
        }
        String city = childText(addressEl, "City");
        String state = childText(addressEl, "State");
        String country = childText(addressEl, "Country");
        List<String> parts = new ArrayList<>();
        if (!city.isEmpty()) parts.add(city);
        if (!state.isEmpty()) parts.add(state);
        if (!country.isEmpty()) parts.add(country);
        return String.join(", ", parts);
    }

    private static String localNameOf(Node n) {
        String ln = n.getLocalName();
        if (ln != null && !ln.isEmpty()) {
            return ln;
        }
        String name = n.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static Element firstChildElement(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equalsIgnoreCase(localNameOf(n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static Element firstDescendant(Element parent, String firstChild, String secondChild) {
        Element c1 = firstChildElement(parent, firstChild);
        return c1 != null ? firstChildElement(c1, secondChild) : null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        if (parent == null) {
            return list;
        }
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equalsIgnoreCase(localNameOf(n))) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private static String childText(Element parent, String localName) {
        Element el = firstChildElement(parent, localName);
        if (el == null) {
            return "";
        }
        String txt = el.getTextContent();
        return txt != null ? txt.trim() : "";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b != null ? b.trim() : "";
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseEpochMillis(String iso, long fallbackMs) {
        if (iso != null && !iso.isBlank()) {
            try {
                return Instant.parse(iso.trim()).toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return fallbackMs;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
