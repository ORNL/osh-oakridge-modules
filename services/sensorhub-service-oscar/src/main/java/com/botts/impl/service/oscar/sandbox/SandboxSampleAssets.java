/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.botts.impl.service.oscar.spreadsheet.SpreadsheetParser;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.CustomCameraConfig;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.RapiscanRPMConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.sensorhub.api.sensor.PositionConfig;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates deterministic, CC0-1.0, self-contained training sample files for the
 * Hands-On Training Sandbox ({@code GET /sensorhub/oscar/sandbox/samples/<name>})
 * and populates a local read-only WCO UFF 3.0 sample directory using full-resolution
 * {@code 2048x600} 16-bit Beer-Lambert tractor-trailer truck radiographs paired with
 * the sandbox's moving-truck OCR camera streams ({@code SCFU8572724} / {@code SWT03}
 * and {@code RSTU2402941} / {@code M50M1}).
 */
public final class SandboxSampleAssets {

    public static final String FILE_TUTORIAL_CSV = "tutorial-1-lane.csv";
    public static final String FILE_SAMPLE_N42 = "sample-cs137-co60.n42";
    public static final String FILE_SAMPLE_UFF = "sample-cargo-uff3.uff";
    public static final String FILE_SAMPLE_PMTILES = "sample-regional-map.pmtiles";
    public static final String FILE_SAMPLE_POLICY = "sample-policy-pack.json";

    /** Primary Tier-3 High-Z Shielded Anomaly tractor-trailer UFF 3.0 package (matches SCFU8572724 / SWT03). */
    public static final String UFF_UUID_EXACT = "dfb985c4-eb26-41ab-938b-ad2a1f5966d4";
    /** Secondary Tier-3 Homogeneous NORM Granite tractor-trailer UFF 3.0 package (matches RSTU2402941 / M50M1). */
    public static final String UFF_UUID_NORM = "772e9494-96b1-433b-902e-fb39a864edc6";
    /** Tier-2 1-character OCR confusable (SCFU8S72724 vs SCFU8572724) for fuzzy OCR +/-1 correlation training. */
    public static final String UFF_UUID_FUZZY = "393a7a3f-a3f2-46a3-988a-3f21bf99857c";

    private static final int XRAY_WIDTH = 2048;
    private static final int XRAY_HEIGHT = 600;
    private static final int PREVIEW_WIDTH = 820;
    private static final int PREVIEW_HEIGHT = 240;

    public record SamplePayload(String fileName, String contentType, byte[] bytes) {}

    private SandboxSampleAssets() {
    }

    public static List<Map<String, String>> listAvailableSamples() {
        return List.of(
                Map.of(
                        "fileName", FILE_TUTORIAL_CSV,
                        "label", "1-Lane Tutorial Configuration CSV",
                        "usedIn", "Track 3 — Bulk Lane Configuration Import (/sensorhub/admin)"),
                Map.of(
                        "fileName", FILE_SAMPLE_N42,
                        "label", "Cs-137 + Co-60 Secondary Spectrum (.n42)",
                        "usedIn", "Track 1 — Spectroscopy & Isotope Identification"),
                Map.of(
                        "fileName", FILE_SAMPLE_UFF,
                        "label", "WCO UFF 3.0 16-Bit Truck X-Ray + HEMD Package (SCFU8572724 .uff)",
                        "usedIn", "Track 1 & 3 — Cargo NII Radiograph Inspection"),
                Map.of(
                        "fileName", FILE_SAMPLE_PMTILES,
                        "label", "Air-Gapped Regional Basemap (.pmtiles v3)",
                        "usedIn", "Track 3 — Runtime Extensions Offline Map (/extensions)"),
                Map.of(
                        "fileName", FILE_SAMPLE_POLICY,
                        "label", "Agency Disposition Policy Pack (.json)",
                        "usedIn", "Track 3 — Runtime Extensions Policy Pack (/extensions)")
        );
    }

    public static SamplePayload buildSample(String name, int rapiscanPort, int rtspPort) throws IOException {
        if (name == null) {
            return null;
        }
        return switch (name.trim()) {
            case FILE_TUTORIAL_CSV -> new SamplePayload(
                    FILE_TUTORIAL_CSV,
                    "text/csv; charset=utf-8",
                    buildTutorialCsv(rapiscanPort, rtspPort).getBytes(StandardCharsets.UTF_8));
            case FILE_SAMPLE_N42 -> new SamplePayload(
                    FILE_SAMPLE_N42,
                    "application/xml; charset=utf-8",
                    buildSampleN42Xml().getBytes(StandardCharsets.UTF_8));
            case FILE_SAMPLE_UFF -> new SamplePayload(
                    FILE_SAMPLE_UFF,
                    "application/zip",
                    buildSampleWcoUff3Archive(
                            UFF_UUID_EXACT,
                            TrainingSandboxEnvironment.CONTAINER_1,
                            TrainingSandboxEnvironment.CONTAINER_1,
                            TrainingSandboxEnvironment.PLATE_1,
                            "EAGLE-P60-LANE01",
                            Instant.now().minusSeconds(120),
                            true,
                            true));
            case FILE_SAMPLE_PMTILES -> new SamplePayload(
                    FILE_SAMPLE_PMTILES,
                    "application/octet-stream",
                    buildMinimalValidPmTilesV3());
            case FILE_SAMPLE_POLICY -> new SamplePayload(
                    FILE_SAMPLE_POLICY,
                    "application/json; charset=utf-8",
                    buildSamplePolicyPackJson().getBytes(StandardCharsets.UTF_8));
            default -> null;
        };
    }

    public static Path populateLocalUffSampleShare(Path baseDir) throws IOException {
        Path uffDir = baseDir.resolve("uff-share");
        Files.createDirectories(uffDir);

        // 1. Primary truck UFF 3.0 package: SCFU8572724 / SWT03 with High-Z Shielded Anomaly + Manifest PDF + N42
        Path exactFile = uffDir.resolve(UFF_UUID_EXACT + ".uff");
        if (!Files.isRegularFile(exactFile) || Files.size(exactFile) < 300_000L) {
            Files.write(exactFile, buildSampleWcoUff3Archive(
                    UFF_UUID_EXACT,
                    TrainingSandboxEnvironment.CONTAINER_1,
                    TrainingSandboxEnvironment.CONTAINER_1,
                    TrainingSandboxEnvironment.PLATE_1,
                    "EAGLE-P60-LANE01",
                    Instant.now().minusSeconds(90),
                    true,
                    true));
        }

        // 2. Secondary truck UFF 3.0 package: RSTU2402941 / M50M1 Homogeneous NORM Granite + Manifest PDF + N42
        Path normFile = uffDir.resolve(UFF_UUID_NORM + ".uff");
        if (!Files.isRegularFile(normFile) || Files.size(normFile) < 300_000L) {
            Files.write(normFile, buildSampleWcoUff3Archive(
                    UFF_UUID_NORM,
                    TrainingSandboxEnvironment.CONTAINER_2,
                    TrainingSandboxEnvironment.CONTAINER_2,
                    TrainingSandboxEnvironment.PLATE_2,
                    "HCVM-PORTAL-02",
                    Instant.now().minusSeconds(210),
                    false,
                    true));
        }

        // 3. Fuzzy OCR +/-1 twin: blank customs declaration + 1-character OCR confusable (SCFU8S72724 vs SCFU8572724)
        String fuzzyOcrValue = "SCFU8S72724";
        Path fuzzyFile = uffDir.resolve(UFF_UUID_FUZZY + ".uff");
        if (!Files.isRegularFile(fuzzyFile) || Files.size(fuzzyFile) < 300_000L) {
            Files.write(fuzzyFile, buildSampleWcoUff3Archive(
                    UFF_UUID_FUZZY,
                    "",
                    fuzzyOcrValue,
                    TrainingSandboxEnvironment.PLATE_1,
                    "HCVM-PORTAL-02",
                    Instant.now().minusSeconds(340),
                    false,
                    false));
        }
        return uffDir;
    }

    public static String buildTutorialCsv(int rapiscanPort, int rtspPort) {
        LaneConfig lane = new LaneConfig();
        lane.moduleClass = LaneSystem.class.getCanonicalName();
        lane.name = "DemoLane01";
        lane.uniqueID = "demo-lane-01";
        lane.autoStart = true;
        lane.autoDelete = true;
        lane.operationalViewKeys = List.of("north-gate");

        PositionConfig.LLALocation loc = new PositionConfig.LLALocation();
        loc.lat = 35.9312;
        loc.lon = -84.3100;
        lane.location = loc;

        RapiscanRPMConfig rpm = new RapiscanRPMConfig();
        rpm.remoteHost = "127.0.0.1";
        rpm.remotePort = rapiscanPort;
        rpm.emlConfig = new RapiscanRPMConfig.EMLConfig();
        rpm.emlConfig.emlEnabled = true;
        rpm.emlConfig.isCollimated = true;
        rpm.emlConfig.laneWidth = 4.8;

        CustomCameraConfig cam = new CustomCameraConfig();
        cam.remoteHost = "127.0.0.1:" + rtspPort;
        cam.streamPath = "/tutorial-cam";
        cam.username = "";
        cam.password = "";

        lane.laneOptionsConfig = new LaneOptionsConfig();
        lane.laneOptionsConfig.rpmConfig = rpm;
        lane.laneOptionsConfig.ffmpegConfig = List.of(cam);

        SpreadsheetParser parser = new SpreadsheetParser();
        parser.getRetentionPolicyConfig().archiveEnabled = false;
        return parser.serialize(List.of(lane));
    }

    public static String buildSampleN42Xml() {
        StringBuilder counts = new StringBuilder(4096);
        int channels = 512;
        // 0..1536 keV (3 keV per channel): Cs-137 @ 662 keV (ch 221), Co-60 @ 1173 keV (ch 391) & 1332 keV (ch 444)
        for (int ch = 0; ch < channels; ch++) {
            double continuum = Math.max(2.0, 48.0 * Math.exp(-ch / 135.0));
            double cs137 = 640.0 * Math.exp(-Math.pow((ch - 221) / 5.2, 2.0));
            double co60a = 380.0 * Math.exp(-Math.pow((ch - 391) / 6.0, 2.0));
            double co60b = 340.0 * Math.exp(-Math.pow((ch - 444) / 6.3, 2.0));
            int val = (int) Math.round(continuum + cs137 + co60a + co60b);
            if (ch > 0) {
                counts.append(' ');
            }
            counts.append(val);
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <RadInstrumentData xmlns="http://physics.nist.gov/N42/2006/N42" n42DocUUID="31130000-0000-4000-8000-000000000662">
                  <RadInstrumentInformation id="riid-sandbox-1">
                    <RadInstrumentManufacturerName>OSCAR Training Sandbox (Synthetic CC0)</RadInstrumentManufacturerName>
                    <RadInstrumentModelName>RIID-NaI-Tutorial</RadInstrumentModelName>
                    <RadInstrumentVersion>
                      <RadInstrumentComponentName>Firmware</RadInstrumentComponentName>
                      <RadInstrumentComponentVersion>3.11.12</RadInstrumentComponentVersion>
                    </RadInstrumentVersion>
                  </RadInstrumentInformation>
                  <EnergyCalibration id="ecal-1">
                    <CoefficientValues>0.0 3.0 0.0</CoefficientValues>
                  </EnergyCalibration>
                  <RadMeasurement id="meas-fg-1">
                    <MeasurementClassCode>Foreground</MeasurementClassCode>
                    <StartDateTime>2026-11-21T12:00:00Z</StartDateTime>
                    <RealTimeDuration>PT60.0S</RealTimeDuration>
                    <Spectrum id="spec-fg-1" energyCalibrationReference="ecal-1">
                      <LiveTimeDuration>PT58.8S</LiveTimeDuration>
                      <ChannelData>%s</ChannelData>
                    </Spectrum>
                  </RadMeasurement>
                  <AnalysisResults id="ana-1">
                    <NuclideAnalysisResults>
                      <Nuclide>
                        <NuclideIdentifiedIndicator>true</NuclideIdentifiedIndicator>
                        <NuclideName>Cs-137</NuclideName>
                        <NuclideIDConfidenceValue>0.98</NuclideIDConfidenceValue>
                        <NuclideCategoryDescription>Industrial</NuclideCategoryDescription>
                      </Nuclide>
                      <Nuclide>
                        <NuclideIdentifiedIndicator>true</NuclideIdentifiedIndicator>
                        <NuclideName>Co-60</NuclideName>
                        <NuclideIDConfidenceValue>0.95</NuclideIDConfidenceValue>
                        <NuclideCategoryDescription>Industrial</NuclideCategoryDescription>
                      </Nuclide>
                    </NuclideAnalysisResults>
                  </AnalysisResults>
                </RadInstrumentData>
                """.formatted(counts.toString());
    }

    public static String buildSamplePolicyPackJson() {
        return """
                {
                  "packId": "sandbox-port-authority-policy",
                  "version": "2026.11",
                  "agencyName": "OSCAR Training Sandbox Port Authority (Non-Operational)",
                  "codes": [
                    {
                      "code": 101,
                      "label": "Code 101: Verified Agricultural Potassium-40 Manifest",
                      "labels": {
                        "en": "Code 101: Verified Agricultural Potassium-40 Manifest",
                        "es": "Código 101: Manifiesto agrícola de Potasio-40 verificado",
                        "fr": "Code 101 : Manifeste agricole Potassium-40 vérifié",
                        "el": "Κωδικός 101: Επαληθευμένο αγροτικό δηλωτικό Καλίου-40"
                      }
                    },
                    {
                      "code": 102,
                      "label": "Code 102: Secondary RIID + Cargo NII Cleared",
                      "labels": {
                        "en": "Code 102: Secondary RIID + Cargo NII Cleared",
                        "es": "Código 102: Inspección secundaria RIID + Carga NII aprobada",
                        "fr": "Code 102 : Inspection secondaire RIID + Cargo NII validée",
                        "el": "Κωδικός 102: Δευτερογενής έλεγχος RIID + Φορτίου NII ολοκληρώθηκε"
                      }
                    }
                  ],
                  "operatorChecklist": [
                    "Confirm container number matches camera OCR crop and shipping manifest.",
                    "Attach secondary .n42 spectrum or link WCO UFF 16-bit X-ray radiograph before release.",
                    "Escalate any Neutron alarm immediately — never release Neutron triggers as NORM."
                  ]
                }
                """;
    }

    public static byte[] buildSampleWcoUff3Archive(
            String uffUuid,
            String containerId,
            String frontPlate,
            String scannerId,
            Instant scanTime
    ) throws IOException {
        return buildSampleWcoUff3Archive(
                uffUuid,
                containerId,
                containerId,
                frontPlate,
                scannerId,
                scanTime,
                true,
                true);
    }

    public static byte[] buildSampleWcoUff3Archive(
            String uffUuid,
            String declaredContainerId,
            String ocrContainerValue,
            String frontPlate,
            String scannerId,
            Instant scanTime,
            boolean includeHighZAnomaly,
            boolean includeManifestAndN42
    ) throws IOException {
        String displayContainer = (declaredContainerId != null && !declaredContainerId.isBlank())
                ? declaredContainerId
                : ocrContainerValue;
        boolean isSecondaryTruck = TrainingSandboxEnvironment.CONTAINER_2.equals(displayContainer);
        Color contColor = isSecondaryTruck
                ? SandboxRtspCameraStreamer.LANE2_CONTAINER_COLOR
                : SandboxRtspCameraStreamer.LANE1_CONTAINER_COLOR;
        Color cabColor = isSecondaryTruck
                ? SandboxRtspCameraStreamer.LANE2_CAB_COLOR
                : SandboxRtspCameraStreamer.LANE1_CAB_COLOR;
        String gateLabel = isSecondaryTruck ? "DEMOLANE02 · SOUTH GATE" : "DEMOLANE01 · NORTH GATE";

        // 1. Synthesize full-truck 2048x600 16-bit Beer-Lambert tractor-trailer radiograph + HEMD + preview.jpg
        SynthesizedTruckRadiograph rad = synthesizeFullTruckRadiographAndHemd(includeHighZAnomaly);

        // 2. Render matching CC0 optical camera JPEGs showing the same moving tractor-trailer as the sandbox lane
        byte[] ocrRearJpg = SandboxRtspCameraStreamer.renderOpticalRearCropJpeg(
                ocrContainerValue, frontPlate, gateLabel, contColor, cabColor);
        byte[] ocrFrontJpg = SandboxRtspCameraStreamer.renderOpticalFrontCropJpeg(
                ocrContainerValue, frontPlate, gateLabel, contColor, cabColor);

        String hsCode = includeHighZAnomaly ? "3926.90" : "6802.93";
        String commodity = includeHighZAnomaly
                ? "Molded Polypropylene Household Storage Articles"
                : "Polished Granite Architectural Slabs in A-Frames";
        String consignor = includeHighZAnomaly
                ? "Iberia Ceramicas Export S.A."
                : "Rhein-Kali Agrar GmbH";
        String consignee = includeHighZAnomaly
                ? "Atlantic Builders Supply Corp."
                : "Southeast Distribution Partners LLC";
        int cargoWeightKg = includeHighZAnomaly ? 10_494 : 21_640;
        int grossVehicleKg = cargoWeightKg + 7_069;

        byte[] manifestPdf = includeManifestAndN42
                ? buildTradeManifestPdf(uffUuid, displayContainer, frontPlate, scannerId, scanTime, hsCode, commodity, consignor, consignee, cargoWeightKg)
                : new byte[0];
        byte[] n42Bytes = includeManifestAndN42
                ? buildSampleN42Xml().getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        String targetEventUuid = deterministicUuid(uffUuid, "target");
        String scanEventUuid = deterministicUuid(uffUuid, "scan");
        String analysisEventUuid = deterministicUuid(uffUuid, "analysis");
        String xrayFileUuid = deterministicUuid(uffUuid, "xray");
        String ocrRearUuid = deterministicUuid(uffUuid, "ocr-rear");
        String ocrFrontUuid = deterministicUuid(uffUuid, "ocr-front");
        String manifestUuid = deterministicUuid(uffUuid, "manifest");
        String radUuid = deterministicUuid(uffUuid, "n42");
        String annotationUuid = deterministicUuid(uffUuid, "ann-1");

        String targetFolder = "event" + targetEventUuid.replace("-", "");
        String scanFolder = "event" + scanEventUuid.replace("-", "");
        String analysisFolder = "event" + analysisEventUuid.replace("-", "");

        String xrayFileName = "Xray-TX-LeftRight-1-" + xrayFileUuid + ".tif";
        String hemdFileName = "HEMD-TX-LeftRight-1-" + xrayFileUuid + ".png";
        String ocrRearFileName = "Ocr-Rear-1-" + ocrRearUuid + ".jpg";
        String ocrFrontFileName = "Ocr-Front-1-" + ocrFrontUuid + ".jpg";
        String manifestFileName = "Manifest-1-" + manifestUuid + ".pdf";
        String radFileName = "RadiationDetection-1-" + radUuid + ".n42";

        String targetTs = scanTime.minusSeconds(45).toString();
        String scanTs = scanTime.toString();
        String analysisTs = scanTime.plusSeconds(14).toString();

        String containerXmlBlock = "";
        if (declaredContainerId != null && !declaredContainerId.isBlank()) {
            containerXmlBlock = """
                      <Container>
                        <Position>0</Position>
                        <ContainerNum>%s</ContainerNum>
                        <Weight>%d</Weight>
                        <Length>40</Length>
                        <Declaration>
                          <Consignor>
                            <Company>
                              <Name>%s</Name>
                              <Address>
                                <StreetAddress><StreetLine>Industrial Port Terminal 18</StreetLine></StreetAddress>
                                <City>Valencia</City>
                                <State>VAL</State>
                                <Country>ES</Country>
                                <PostalCode>46024</PostalCode>
                              </Address>
                            </Company>
                          </Consignor>
                          <Consignee>
                            <Company>
                              <Name>%s</Name>
                              <Address>
                                <StreetAddress><StreetLine>2950 Riverfront Pkwy</StreetLine></StreetAddress>
                                <City>Charleston</City>
                                <State>SC</State>
                                <Country>US</Country>
                                <PostalCode>29405</PostalCode>
                              </Address>
                            </Company>
                          </Consignee>
                          <CustomsId>SBX-DECL-%s</CustomsId>
                          <GoodsDescription>
                            <HSCode>%s</HSCode>
                            <Description>%s (TRAINING SANDBOX — NON-OPERATIONAL)</Description>
                            <Unit>packs</Unit>
                            <Quantity>360</Quantity>
                          </GoodsDescription>
                        </Declaration>
                      </Container>
                    """.formatted(
                    declaredContainerId,
                    cargoWeightKg,
                    consignor,
                    consignee,
                    uffUuid.substring(0, 8).toUpperCase(Locale.ROOT),
                    hsCode,
                    commodity);
        } else {
            containerXmlBlock = """
                      <Container>
                        <Position>0</Position>
                        <ContainerNum/>
                        <Length>40</Length>
                      </Container>
                    """;
        }

        String manifestXmlBlock = includeManifestAndN42 ? """
                    <Document>
                      <FileId>%s</FileId>
                      <URI>%s</URI>
                      <Type>Manifest</Type>
                      <Hash>%s</Hash>
                    </Document>
                """.formatted(manifestUuid, manifestFileName, sha384Hex(manifestPdf)) : "";

        String radXmlBlock = includeManifestAndN42 ? """
                    <OutputFile>
                      <FileId>%s</FileId>
                      <URI>%s</URI>
                      <Type>RadiationDetection</Type>
                      <Hash>%s</Hash>
                    </OutputFile>
                """.formatted(radUuid, radFileName, sha384Hex(n42Bytes)) : "";

        String analysisBlock = includeHighZAnomaly ? """
                  <Analysis>
                    <EventId>%s</EventId>
                    <InspectionSystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>Rapiscan Systems</Manufacturer>
                      <ModelName>Eagle P60</ModelName>
                      <SerialNumber>EP60-SBX-0101</SerialNumber>
                    </InspectionSystem>
                    <TimeStamp>%s</TimeStamp>
                    <Duration>1420</Duration>
                    <Assessment>Automated</Assessment>
                    <Algorithm>
                      <Type>Detector</Type>
                      <Name>OSCAR-NII-CargoInspect</Name>
                      <Description>Automated high-energy cargo density and High-Z shielding detector</Description>
                      <Vendor>OSCAR Training Academy</Vendor>
                      <Version>3.11.12</Version>
                      <ModelVersion>2026.11</ModelVersion>
                    </Algorithm>
                    <Result>Found</Result>
                    <Annotation>
                      <AnnotationId>%s</AnnotationId>
                      <Description>High-Z Metallic Attenuation Anomaly</Description>
                      <Comment>Localized high-attenuation block (82cm x 53cm) at longitudinal fraction 0.72 vs declared HS %s</Comment>
                      <Confidence>94</Confidence>
                      <FileURI>%s/%s</FileURI>
                      <FileId>%s</FileId>
                      <Shape>Rectangle</Shape>
                      <BoundingBox>
                        <Top>0.52833</Top>
                        <Left>0.75000</Left>
                        <Bottom>0.64000</Bottom>
                        <Right>0.80029</Right>
                      </BoundingBox>
                    </Annotation>
                  </Analysis>
                """.formatted(
                analysisEventUuid,
                scannerId,
                analysisTs,
                annotationUuid,
                hsCode,
                scanFolder,
                xrayFileName,
                xrayFileUuid) : """
                  <Analysis>
                    <EventId>%s</EventId>
                    <InspectionSystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>Smiths Detection</Manufacturer>
                      <ModelName>HCVP60</ModelName>
                      <SerialNumber>HCVP-SBX-0204</SerialNumber>
                    </InspectionSystem>
                    <TimeStamp>%s</TimeStamp>
                    <Duration>980</Duration>
                    <Assessment>Automated</Assessment>
                    <Algorithm>
                      <Type>Detector</Type>
                      <Name>OSCAR-NII-CargoInspect</Name>
                      <Description>Automated homogeneous cargo profile verifier</Description>
                      <Vendor>OSCAR Training Academy</Vendor>
                      <Version>3.11.12</Version>
                      <ModelVersion>2026.11</ModelVersion>
                    </Algorithm>
                    <Result>Not Found</Result>
                    <Annotation>
                      <AnnotationId>%s</AnnotationId>
                      <Description>Homogeneous NORM Cargo Verified (%s)</Description>
                      <Comment>Uniform 16-bit attenuation profile consistent with declared mineral cargo</Comment>
                      <Confidence>98</Confidence>
                      <FileURI>%s/%s</FileURI>
                      <FileId>%s</FileId>
                      <Shape>Rectangle</Shape>
                      <BoundingBox>
                        <Top>0.35833</Top>
                        <Left>0.26855</Left>
                        <Bottom>0.69667</Bottom>
                        <Right>0.92188</Right>
                      </BoundingBox>
                    </Annotation>
                  </Analysis>
                """.formatted(
                analysisEventUuid,
                scannerId,
                analysisTs,
                annotationUuid,
                hsCode,
                scanFolder,
                xrayFileName,
                xrayFileUuid);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <UFF xmlns="http://tempuri.org/UFF3.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <Id>%s</Id>
                  <Target>
                    <EventId>%s</EventId>
                    <InspectionSystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>Rapiscan Systems</Manufacturer>
                      <ModelName>Eagle P60</ModelName>
                      <SerialNumber>EP60-SBX-0101</SerialNumber>
                    </InspectionSystem>
                    <TimeStamp>%s</TimeStamp>
                    <Vehicle>
                %s      <FrontPlateNum>%s</FrontPlateNum>
                      <VehicleType>Truck</VehicleType>
                      <Weight>%d</Weight>
                    </Vehicle>
                %s  </Target>
                  <Scan>
                    <EventId>%s</EventId>
                    <ScannerCaseId>%s-SBX-%s</ScannerCaseId>
                    <XraySystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>Rapiscan Systems</Manufacturer>
                      <ModelName>Eagle P60</ModelName>
                      <SerialNumber>EP60-SBX-0101</SerialNumber>
                      <Country>US</Country>
                      <View>
                        <View>LeftRight</View>
                        <Modality>TX</Modality>
                        <Source>Linac</Source>
                        <Energy>6</Energy>
                        <Energy>4</Energy>
                      </View>
                    </XraySystem>
                    <TimeStamp>%s</TimeStamp>
                    <Duration>7148</Duration>
                    <OcrResult>
                      <OcrType>CCR</OcrType>
                      <Value>%s</Value>
                      <Confidence>%s</Confidence>
                      <PlateLocation>Rear</PlateLocation>
                      <OutputFile>
                        <FileId>%s</FileId>
                        <URI>%s</URI>
                        <Type>CCR</Type>
                        <Hash>%s</Hash>
                        <View>Back</View>
                      </OutputFile>
                      <TimeStamp>%s</TimeStamp>
                      <CcrDetail>
                        <ContainerType>45G1</ContainerType>
                      </CcrDetail>
                    </OcrResult>
                    <OcrResult>
                      <OcrType>LPR</OcrType>
                      <Value>%s</Value>
                      <Confidence>0.97</Confidence>
                      <PlateLocation>Front</PlateLocation>
                      <OutputFile>
                        <FileId>%s</FileId>
                        <URI>%s</URI>
                        <Type>LPR</Type>
                        <Hash>%s</Hash>
                        <View>Front</View>
                      </OutputFile>
                      <TimeStamp>%s</TimeStamp>
                    </OcrResult>
                %s    <XRayFile>
                      <FileId>%s</FileId>
                      <URI>%s</URI>
                      <Type>X-ray</Type>
                      <Hash>%s</Hash>
                      <View>LeftRight</View>
                      <Energy>6</Energy>
                      <Energy>4</Energy>
                      <Modality>TX</Modality>
                      <ScanningMode>Portal</ScanningMode>
                      <Speed>2285</Speed>
                      <HorizontalPixelSize>8.0</HorizontalPixelSize>
                      <VerticalPixelSize>8.0</VerticalPixelSize>
                      <HEMDInfo>
                        <FileId>%s</FileId>
                        <HEMDFileURI>%s</HEMDFileURI>
                        <HEMDMap>
                          <ZMap>
                            <Name>Organic</Name>
                            <PaletteStart>1</PaletteStart>
                            <PaletteEnd>11</PaletteEnd>
                            <EffectiveZStart>6</EffectiveZStart>
                            <EffectiveZEnd>10</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>Intermediate</Name>
                            <PaletteStart>12</PaletteStart>
                            <PaletteEnd>20</PaletteEnd>
                            <EffectiveZStart>11</EffectiveZStart>
                            <EffectiveZEnd>18</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>Inorganic</Name>
                            <PaletteStart>21</PaletteStart>
                            <PaletteEnd>25</PaletteEnd>
                            <EffectiveZStart>21</EffectiveZStart>
                            <EffectiveZEnd>30</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>High-Z Shielding</Name>
                            <PaletteStart>26</PaletteStart>
                            <PaletteEnd>30</PaletteEnd>
                            <EffectiveZStart>31</EffectiveZStart>
                            <EffectiveZEnd>82</EffectiveZEnd>
                          </ZMap>
                        </HEMDMap>
                      </HEMDInfo>
                    </XRayFile>
                  </Scan>
                %s</UFF>
                """.formatted(
                uffUuid,
                targetEventUuid,
                scannerId,
                targetTs,
                containerXmlBlock,
                declaredContainerId != null && !declaredContainerId.isBlank() ? frontPlate : "",
                grossVehicleKg,
                manifestXmlBlock,
                scanEventUuid,
                scannerId,
                uffUuid.substring(0, 8).toUpperCase(Locale.ROOT),
                scannerId,
                scanTs,
                ocrContainerValue,
                declaredContainerId != null && !declaredContainerId.isBlank() ? "0.99" : "0.86",
                ocrRearUuid,
                ocrRearFileName,
                sha384Hex(ocrRearJpg),
                scanTs,
                frontPlate,
                ocrFrontUuid,
                ocrFrontFileName,
                sha384Hex(ocrFrontJpg),
                scanTs,
                radXmlBlock,
                xrayFileUuid,
                xrayFileName,
                sha384Hex(rad.tiff16Bytes),
                xrayFileUuid,
                hemdFileName,
                analysisBlock);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(524_288);
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            putStoredEntry(zip, "version", "3.0".getBytes(StandardCharsets.UTF_8));
            putStoredEntry(zip, uffUuid + ".xml", xml.getBytes(StandardCharsets.UTF_8));
            putStoredEntry(zip, "preview.jpg", rad.previewJpegBytes);
            putDirectoryEntry(zip, targetFolder + "/");
            if (includeManifestAndN42) {
                putStoredEntry(zip, targetFolder + "/" + manifestFileName, manifestPdf);
            }
            putDirectoryEntry(zip, scanFolder + "/");
            putStoredEntry(zip, scanFolder + "/" + xrayFileName, rad.tiff16Bytes);
            putStoredEntry(zip, scanFolder + "/" + hemdFileName, rad.hemdPngBytes);
            putStoredEntry(zip, scanFolder + "/" + ocrRearFileName, ocrRearJpg);
            putStoredEntry(zip, scanFolder + "/" + ocrFrontFileName, ocrFrontJpg);
            if (includeManifestAndN42) {
                putStoredEntry(zip, scanFolder + "/" + radFileName, n42Bytes);
            }
            putDirectoryEntry(zip, analysisFolder + "/");
        }
        return bos.toByteArray();
    }

    private static void putDirectoryEntry(ZipOutputStream zip, String dirName) throws IOException {
        ZipEntry entry = new ZipEntry(dirName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(0);
        entry.setCompressedSize(0);
        entry.setCrc(0);
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static void putStoredEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private record SynthesizedTruckRadiograph(byte[] tiff16Bytes, byte[] hemdPngBytes, byte[] previewJpegBytes) {}

    /**
     * Synthesizes a commercial-grade {@code 2048x600} 16-bit Beer-Lambert tractor-trailer truck
     * transmission radiograph (Deflate-compressed {@code uint16} TIFF), linked 8-bit palettized
     * HEMD effective-Z material map PNG, and {@code 820x240} tone-mapped {@code preview.jpg}
     * matching the official OSCAR WCO UFF 3.0 truck dataset geometry.
     */
    private static SynthesizedTruckRadiograph synthesizeFullTruckRadiographAndHemd(boolean includeHighZAnomaly) throws IOException {
        final int W = XRAY_WIDTH;
        final int H = XRAY_HEIGHT;
        float[] tau = new float[W * H];
        byte[] hemd = new byte[W * H];

        // 1. Portal roadbed & reinforced steel guide trench (y = 542..599)
        for (int y = 542; y < H; y++) {
            float roadTau = 2.68f + 1.20f * ((y - 542) / 57.0f);
            byte roadHemd = (byte) (y < 555 ? 22 : 18);
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                tau[rowOff + x] = roadTau;
                hemd[rowOff + x] = roadHemd;
            }
        }

        // 2. Tractor cab shell (cab_left: x = 115..468), windshield cutout, driver seat,
        //    cast-iron 6-cylinder engine block, transmission, and cylindrical organic diesel saddle tank
        for (int x = 115; x < 468; x++) {
            float tFrac = (x - 115) / 352.0f;
            int roofY = Math.round(215.0f - 75.0f * Math.min(1.0f, tFrac * 2.1f));
            for (int y = roofY; y < 422; y++) {
                int idx = y * W + x;
                tau[idx] += 0.34f;
                hemd[idx] = 21;
            }
        }
        // Cab windshield & side glass cutout (HEMD 14)
        for (int y = 172; y < 268; y++) {
            int rowOff = y * W;
            for (int x = 145; x < 255; x++) {
                tau[rowOff + x] = Math.max(0.12f, tau[rowOff + x] - 0.16f);
                hemd[rowOff + x] = 14;
            }
        }
        // Driver seat & steering column (Organic HEMD 7)
        for (int y = 250; y < 385; y++) {
            int rowOff = y * W;
            for (int x = 228; x < 288; x++) {
                tau[rowOff + x] += 0.48f;
                hemd[rowOff + x] = 7;
            }
        }
        // Heavy cast-iron 6-cylinder engine block & transmission (HEMD 25 / 24)
        for (int y = 305; y < 448; y++) {
            int rowOff = y * W;
            for (int x = 128; x < 273; x++) {
                float cylRipple = 0.18f * (float) Math.sin(((x - 128) / 145.0) * 18.0);
                tau[rowOff + x] += 2.85f + cylRipple;
                hemd[rowOff + x] = 25;
            }
            for (int x = 273; x < 343; x++) {
                tau[rowOff + x] += 2.35f;
                hemd[rowOff + x] = 24;
            }
        }
        // Cylindrical diesel fuel saddle tank (Organic HEMD 6)
        for (int y = 382; y < 448; y++) {
            float yn = ((y - 382) / 32.5f) - 1.0f;
            float chord = (float) Math.sqrt(Math.max(0.0f, 1.0f - yn * yn));
            int rowOff = y * W;
            for (int x = 300; x < 412; x++) {
                tau[rowOff + x] += 1.05f * chord;
                hemd[rowOff + x] = 6;
            }
        }

        // 3. Full-length gooseneck container chassis I-beam & fifth-wheel kingpin
        for (int y = 425; y < 461; y++) {
            int rowOff = y * W;
            for (int x = 122; x < 1998; x++) {
                tau[rowOff + x] += 2.75f;
                hemd[rowOff + x] = 24;
            }
        }
        for (int y = 410; y < 438; y++) {
            int rowOff = y * W;
            for (int x = 418; x < 548; x++) {
                tau[rowOff + x] += 2.65f;
                hemd[rowOff + x] = 25;
            }
        }

        // Cross-member ribs along trailer chassis bed
        for (int xm = 540; xm < 1985; xm += 46) {
            for (int y = 418; y < 446; y++) {
                for (int x = xm; x < Math.min(W, xm + 6); x++) {
                    tau[y * W + x] += 0.95f;
                }
            }
        }

        // 4. Corrugated 40-ft Corten steel shipping container (x = 525..2000, y = 92..425)
        for (int y = 92; y < 425; y++) {
            int rowOff = y * W;
            boolean topOrBottomRail = (y < 104 || y >= 415);
            for (int x = 525; x < 2000; x++) {
                boolean cornerPost = (x < 538 || x >= 1986);
                float rib = (((x - 538) % 14) < 5) ? 0.08f : 0.0f;
                tau[rowOff + x] += (topOrBottomRail || cornerPost) ? 1.85f : (0.28f + rib);
                hemd[rowOff + x] = (byte) ((topOrBottomRail || cornerPost) ? 24 : 22);
            }
        }

        // 5. Sixteen palletized cargo bays inside 40-ft container (x = 550..1888, y = 215..416)
        float palletBaseTau = includeHighZAnomaly ? 0.38f : 0.98f;
        byte palletHemd = (byte) (includeHighZAnomaly ? 6 : 16); // Organic polypropylene vs Mineral NORM granite
        for (int p = 0; p < 16; p++) {
            int px0 = 550 + p * 84;
            int px1 = px0 + 76;
            for (int y = 215; y < 416; y++) {
                int rowOff = y * W;
                boolean skidBase = (y >= 404);
                for (int x = px0; x < px1; x++) {
                    tau[rowOff + x] += skidBase ? 0.55f : palletBaseTau;
                    hemd[rowOff + x] = skidBase ? (byte) 5 : palletHemd;
                }
            }
        }

        // 6. Localized High-Z Metallic Attenuation Anomaly (82cm x 53cm => x = 1536..1639, y = 317..384)
        if (includeHighZAnomaly) {
            for (int y = 317; y <= 384; y++) {
                int rowOff = y * W;
                for (int x = 1536; x <= 1639; x++) {
                    tau[rowOff + x] += 4.65f;
                    hemd[rowOff + x] = 29; // Deep violet High-Z shielding band (26..30)
                }
            }
        }

        // 7. Five dual-tire + cast-steel hub axle assemblies (steer, tandem drive, tandem trailer)
        int[] wheelCentersX = {185, 356, 432, 1785, 1872};
        int wheelCenterY = 486;
        int tireOuterR = 54;
        int hubInnerR = 27;
        for (int cx : wheelCentersX) {
            for (int y = wheelCenterY - tireOuterR; y <= wheelCenterY + tireOuterR; y++) {
                if (y < 0 || y >= H) continue;
                int dy = y - wheelCenterY;
                int rowOff = y * W;
                for (int x = cx - tireOuterR; x <= cx + tireOuterR; x++) {
                    if (x < 0 || x >= W) continue;
                    int dx = x - cx;
                    int r2 = dx * dx + dy * dy;
                    if (r2 <= hubInnerR * hubInnerR) {
                        tau[rowOff + x] += 2.65f;
                        hemd[rowOff + x] = 25; // Steel hub/drum
                    } else if (r2 <= tireOuterR * tireOuterR) {
                        tau[rowOff + x] += 0.92f;
                        hemd[rowOff + x] = 8;  // Vulcanized rubber tire
                    }
                }
            }
        }

        // 8. Convert optical thickness tau -> 16-bit Beer-Lambert transmission I = I0 * exp(-tau)
        //    with unexposed collimator margins (x < 10 || x >= W - 10 => 0)
        byte[] rawLittleEndianPixels = new byte[W * H * 2];
        ByteBuffer pixBuf = ByteBuffer.wrap(rawLittleEndianPixels).order(ByteOrder.LITTLE_ENDIAN);
        BufferedImage fullGrayPreview = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] previewRow = new int[W];

        for (int y = 0; y < H; y++) {
            float yn = (y / (H * 0.5f)) - 1.0f;
            float beamProfile = 63_400.0f * (1.0f - 0.065f * yn * yn);
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                int u16;
                if (x < 10 || x >= W - 10) {
                    u16 = 0;
                    hemd[rowOff + x] = 0;
                } else {
                    float pulse = 1.0f + 0.0035f * (float) Math.sin(x * 0.09);
                    float transmitted = (float) (beamProfile * pulse * Math.exp(-tau[rowOff + x]));
                    u16 = Math.max(220, Math.min(65_000, Math.round(transmitted)));
                }
                pixBuf.putShort((short) (u16 & 0xFFFF));
                int g8 = Math.max(0, Math.min(255, (u16 * 255) / 64_000));
                previewRow[x] = (g8 << 16) | (g8 << 8) | g8;
            }
            fullGrayPreview.setRGB(0, y, W, 1, previewRow, 0, W);
        }

        byte[] tiff16Bytes = buildDeflate16BitGrayscaleTiff(W, H, rawLittleEndianPixels);
        byte[] hemdPngBytes = encodePalettizedHemdPng(W, H, hemd);

        BufferedImage scaledPreview = new BufferedImage(PREVIEW_WIDTH, PREVIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D gp = scaledPreview.createGraphics();
        gp.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gp.drawImage(fullGrayPreview, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT, null);
        gp.dispose();
        ByteArrayOutputStream prevBos = new ByteArrayOutputStream(32_768);
        ImageIO.write(scaledPreview, "jpg", prevBos);

        return new SynthesizedTruckRadiograph(tiff16Bytes, hemdPngBytes, prevBos.toByteArray());
    }

    private static byte[] buildDeflate16BitGrayscaleTiff(int width, int height, byte[] rawLePixels) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        byte[] compressed;
        try {
            deflater.setInput(rawLePixels);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(rawLePixels.length / 3);
            byte[] chunk = new byte[32_768];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                if (n > 0) {
                    bos.write(chunk, 0, n);
                } else {
                    break;
                }
            }
            compressed = bos.toByteArray();
        } finally {
            deflater.end();
        }

        int tagCount = 10;
        int ifdOffset = 8;
        int ifdSize = 2 + (tagCount * 12) + 4;
        int pixelOffset = ifdOffset + ifdSize;

        ByteBuffer buf = ByteBuffer.allocate(pixelOffset + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I');
        buf.putShort((short) 42);
        buf.putInt(ifdOffset);

        buf.putShort((short) tagCount);
        writeTiffTag(buf, 256, 4, 1, width);             // ImageWidth
        writeTiffTag(buf, 257, 4, 1, height);            // ImageLength
        writeTiffTag(buf, 258, 3, 1, 16);                // BitsPerSample = 16
        writeTiffTag(buf, 259, 3, 1, 8);                 // Compression = 8 (Adobe Deflate / zlib)
        writeTiffTag(buf, 262, 3, 1, 1);                 // PhotometricInterpretation = 1 (BlackIsZero transmission)
        writeTiffTag(buf, 273, 4, 1, pixelOffset);       // StripOffsets
        writeTiffTag(buf, 277, 3, 1, 1);                 // SamplesPerPixel = 1
        writeTiffTag(buf, 278, 4, 1, height);            // RowsPerStrip = height
        writeTiffTag(buf, 279, 4, 1, compressed.length); // StripByteCounts
        writeTiffTag(buf, 339, 3, 1, 1);                 // SampleFormat = 1 (uint16)
        buf.putInt(0);                                   // Next IFD = 0
        buf.put(compressed);
        return buf.array();
    }

    private static void writeTiffTag(ByteBuffer buf, int tag, int fieldType, int count, int valueOrOffset) {
        buf.putShort((short) tag);
        buf.putShort((short) fieldType);
        buf.putInt(count);
        if (fieldType == 3 && count == 1) {
            buf.putShort((short) valueOrOffset);
            buf.putShort((short) 0);
        } else {
            buf.putInt(valueOrOffset);
        }
    }

    private static byte[] encodePalettizedHemdPng(int width, int height, byte[] indices) throws IOException {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        // 0: Air / unexposed (#FFFFFF)
        r[0] = (byte) 255; g[0] = (byte) 255; b[0] = (byte) 255;
        // 1..11: Organic (Z_eff 6..10) — warm amber/orange ramp
        for (int idx = 1; idx <= 11; idx++) {
            double t = (idx - 1) / 10.0;
            r[idx] = (byte) Math.round(255 - 37 * t);
            g[idx] = (byte) Math.round(216 - 108 * t);
            b[idx] = (byte) Math.round(138 - 124 * t);
        }
        // 12..20: Intermediate / Mineral / NORM (Z_eff 11..18) — green/teal ramp
        for (int idx = 12; idx <= 20; idx++) {
            double t = (idx - 12) / 8.0;
            r[idx] = (byte) Math.round(118 - 98 * t);
            g[idx] = (byte) Math.round(216 - 68 * t);
            b[idx] = (byte) Math.round(128 - 4 * t);
        }
        // 21..25: Inorganic Steel/Iron (Z_eff 21..30) — royal blue ramp
        for (int idx = 21; idx <= 25; idx++) {
            double t = (idx - 21) / 4.0;
            r[idx] = (byte) Math.round(72 - 42 * t);
            g[idx] = (byte) Math.round(146 - 72 * t);
            b[idx] = (byte) Math.round(236 - 38 * t);
        }
        // 26..30: High-Z Shielding (Z_eff 31..82) — deep indigo/violet ramp
        for (int idx = 26; idx <= 30; idx++) {
            double t = (idx - 26) / 4.0;
            r[idx] = (byte) Math.round(98 - 46 * t);
            g[idx] = (byte) Math.round(28 - 20 * t);
            b[idx] = (byte) Math.round(176 - 58 * t);
        }
        for (int idx = 31; idx < 256; idx++) {
            r[idx] = (byte) 128; g[idx] = (byte) 128; b[idx] = (byte) 128;
        }

        IndexColorModel icm = new IndexColorModel(8, 256, r, g, b);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, icm);
        WritableRaster raster = img.getRaster();
        int[] rowSamples = new int[width];
        for (int y = 0; y < height; y++) {
            int rowOff = y * width;
            for (int x = 0; x < width; x++) {
                rowSamples[x] = indices[rowOff + x] & 0xFF;
            }
            raster.setSamples(0, y, width, 1, 0, rowSamples);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(65_536);
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] buildTradeManifestPdf(
            String uffUuid,
            String containerId,
            String frontPlate,
            String scannerId,
            Instant scanTime,
            String hsCode,
            String commodity,
            String consignor,
            String consignee,
            int weightKg
    ) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontReg = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.newLineAtOffset(50, 735);
                cs.showText("TRAINING SANDBOX — NON-OPERATIONAL BILL OF LADING / CUSTOMS MANIFEST");
                cs.endText();

                String[] lines = {
                        "WCO UFF 3.0 Package UUID: " + uffUuid,
                        "NII Scanner System:       " + scannerId + " (6/4 MeV Dual-Energy Portal TX)",
                        "Scan Timestamp (UTC):     " + scanTime.toString(),
                        "Container Number (ISO):   " + containerId + " (40ft High-Cube 45G1)",
                        "Tractor Front Plate:      " + frontPlate,
                        "Consignor (Shipper):      " + consignor,
                        "Consignee (Receiver):     " + consignee,
                        "Declared HS Code:         " + hsCode,
                        "Declared Goods:           " + commodity,
                        "Declared Net Cargo Mass:  " + weightKg + " kg (360 export packs)",
                        "",
                        "NOTICE: Synthetic CC0 practice manifest generated by OSCAR Hands-On Training Sandbox."
                };
                float y = 695f;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(line.startsWith("NOTICE:") ? fontBold : fontReg, 10.5f);
                    cs.newLineAtOffset(50, y);
                    cs.showText(line.replace("—", "-"));
                    cs.endText();
                    y -= 20f;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String deterministicUuid(String seedUuid, String role) {
        return UUID.nameUUIDFromBytes((seedUuid + ":" + role).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha384Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-384");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-384 unavailable", e);
        }
    }

    /**
     * Builds a compact, standards-compliant Protomaps PMTiles v3 raster archive
     * (127-byte header + uncompressed 1-entry root directory + JSON metadata +
     * 256x256 PNG tile centered on East Tennessee) so {@code OfflineMapManager}
     * validates and serves it with zero external tools.
     */
    public static byte[] buildMinimalValidPmTilesV3() throws IOException {
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new Color(226, 236, 224));
        g.fillRect(0, 0, 256, 256);
        g.setColor(new Color(147, 197, 253));
        g.fillPolygon(new int[]{0, 90, 256, 256, 60}, new int[]{190, 130, 40, 82, 225}, 5);
        g.setColor(new Color(251, 191, 36));
        g.fillRect(0, 118, 256, 8);
        g.setColor(new Color(30, 41, 59));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("OSCAR SANDBOX BASEMAP", 28, 108);
        g.dispose();

        ByteArrayOutputStream tileOut = new ByteArrayOutputStream(4096);
        ImageIO.write(tile, "png", tileOut);
        byte[] tileBytes = tileOut.toByteArray();

        byte[] jsonMeta = "{\"name\":\"OSCAR Training Sandbox Regional Map\",\"format\":\"png\",\"attribution\":\"CC0 Training Basemap\"}"
                .getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream dirOut = new ByteArrayOutputStream(32);
        writeVarint(dirOut, 1);
        writeVarint(dirOut, 0);
        writeVarint(dirOut, 1);
        writeVarint(dirOut, tileBytes.length);
        writeVarint(dirOut, 1);
        byte[] rootDir = dirOut.toByteArray();

        long rootDirOffset = 127L;
        long jsonOffset = rootDirOffset + rootDir.length;
        long tileDataOffset = jsonOffset + jsonMeta.length;
        int totalSize = (int) (tileDataOffset + tileBytes.length);

        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        out.put("PMTiles".getBytes(StandardCharsets.US_ASCII));
        out.put((byte) 3);
        out.putLong(rootDirOffset);
        out.putLong(rootDir.length);
        out.putLong(jsonOffset);
        out.putLong(jsonMeta.length);
        out.putLong(0L);
        out.putLong(0L);
        out.putLong(tileDataOffset);
        out.putLong(tileBytes.length);
        out.putLong(1L);
        out.putLong(1L);
        out.putLong(1L);
        out.put((byte) 1);
        out.put((byte) 1);
        out.put((byte) 1);
        out.put((byte) 2);
        out.put((byte) 0);
        out.put((byte) 14);
        out.putInt((int) Math.round(-84.50 * 1e7));
        out.putInt((int) Math.round(35.80 * 1e7));
        out.putInt((int) Math.round(-84.10 * 1e7));
        out.putInt((int) Math.round(36.10 * 1e7));
        out.put((byte) 12);
        out.putInt((int) Math.round(-84.31 * 1e7));
        out.putInt((int) Math.round(35.93 * 1e7));

        out.put(rootDir);
        out.put(jsonMeta);
        out.put(tileBytes);
        return out.array();
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
