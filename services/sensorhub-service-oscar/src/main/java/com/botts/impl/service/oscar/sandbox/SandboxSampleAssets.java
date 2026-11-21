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
import org.sensorhub.api.sensor.PositionConfig;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates deterministic, CC0-1.0, self-contained training sample files for the
 * Hands-On Training Sandbox ({@code GET /sensorhub/oscar/sandbox/samples/<name>})
 * and populates a local read-only WCO UFF 3.0 sample directory so trainees can
 * practice every Operator, Supervisor, and Administrator workflow with zero
 * external files or network shares required.
 */
public final class SandboxSampleAssets {

    public static final String FILE_TUTORIAL_CSV = "tutorial-1-lane.csv";
    public static final String FILE_SAMPLE_N42 = "sample-cs137-co60.n42";
    public static final String FILE_SAMPLE_UFF = "sample-cargo-uff3.uff";
    public static final String FILE_SAMPLE_PMTILES = "sample-regional-map.pmtiles";
    public static final String FILE_SAMPLE_POLICY = "sample-policy-pack.json";

    public static final String UFF_UUID_EXACT = "31130001-5285-4725-8001-000000000001";
    public static final String UFF_UUID_FUZZY = "31130002-4829-4108-8002-000000000002";

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
                        "label", "WCO UFF 3.0 16-Bit Cargo X-Ray + HEMD Package (.uff)",
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
                            TrainingSandboxEnvironment.PLATE_1,
                            "NII-GANTRY-01",
                            Instant.now().minusSeconds(120)));
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
        Path exactFile = uffDir.resolve(UFF_UUID_EXACT + ".uff");
        if (!Files.isRegularFile(exactFile)) {
            Files.write(exactFile, buildSampleWcoUff3Archive(
                    UFF_UUID_EXACT,
                    TrainingSandboxEnvironment.CONTAINER_1,
                    TrainingSandboxEnvironment.PLATE_1,
                    "NII-GANTRY-01",
                    Instant.now().minusSeconds(90)));
        }
        // Second UFF archive with 1-character OCR confusable (last digit '5' -> 'S' or container 2)
        String fuzzyContainer = TrainingSandboxEnvironment.CONTAINER_1.substring(0, 10) + "S";
        Path fuzzyFile = uffDir.resolve(UFF_UUID_FUZZY + ".uff");
        if (!Files.isRegularFile(fuzzyFile)) {
            Files.write(fuzzyFile, buildSampleWcoUff3Archive(
                    UFF_UUID_FUZZY,
                    fuzzyContainer,
                    TrainingSandboxEnvironment.PLATE_2,
                    "NII-GANTRY-02",
                    Instant.now().minusSeconds(300)));
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
                      <RadInstrumentComponentVersion>3.11.3</RadInstrumentComponentVersion>
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
        int imgW = 320;
        int imgH = 140;
        byte[] tiff16 = buildSynthetic16BitCargoTiff(imgW, imgH);
        byte[] hemdPng = buildSynthetic8BitPalettizedHemdPng(imgW, imgH);
        byte[] previewJpg = buildSyntheticPreviewJpeg(imgW, imgH, containerId);
        byte[] n42Bytes = buildSampleN42Xml().getBytes(StandardCharsets.UTF_8);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <UFF xmlns="http://wcoomd.org/uff/UFF3">
                  <Id>%s</Id>
                  <Target>
                    <EventId>target-1</EventId>
                    <TimeStamp>%s</TimeStamp>
                    <InspectionSystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>OSCAR Training Sandbox (Synthetic CC0)</Manufacturer>
                      <ModelName>Dual-Energy 6/4 MeV Portal X-Ray</ModelName>
                      <SerialNumber>SBX-UFF-3113</SerialNumber>
                    </InspectionSystem>
                    <Vehicle>
                      <FrontPlateNum>%s</FrontPlateNum>
                      <VehicleType>Tractor-Semitrailer 40ft</VehicleType>
                      <Weight>24800</Weight>
                      <Container>
                        <ContainerNum>%s</ContainerNum>
                        <Length>40</Length>
                        <Weight>18400</Weight>
                        <Declaration>
                          <CustomsId>SBX-DECL-2026-3113</CustomsId>
                          <Consignor><Company><Name>Sandbox Ceramic &amp; Industrial Supply</Name></Company></Consignor>
                          <Consignee><Company><Name>Training Terminal Gatehouse</Name></Company></Consignee>
                          <GoodsDescription>
                            <HSCode>690721</HSCode>
                            <Description>Glazed Ceramic Floor Tiles (Practice Shipment — Non-Operational)</Description>
                            <Unit>PALLET</Unit>
                            <Quantity>18</Quantity>
                          </GoodsDescription>
                        </Declaration>
                      </Container>
                    </Vehicle>
                  </Target>
                  <Scan>
                    <EventId>scan-1</EventId>
                    <ScannerCaseId>CASE-%s</ScannerCaseId>
                    <TimeStamp>%s</TimeStamp>
                    <Duration>9200</Duration>
                    <XraySystem>
                      <Identifier>%s</Identifier>
                      <Manufacturer>OSCAR Training Sandbox (Synthetic CC0)</Manufacturer>
                      <ModelName>Dual-Energy 6/4 MeV Portal X-Ray</ModelName>
                      <SerialNumber>SBX-UFF-3113</SerialNumber>
                      <Country>US</Country>
                    </XraySystem>
                    <OcrResult>
                      <OcrType>Container</OcrType>
                      <Value>%s</Value>
                      <Confidence>0.97</Confidence>
                      <PlateLocation>Side</PlateLocation>
                      <CcrDetail><ContainerType>45G1</ContainerType></CcrDetail>
                    </OcrResult>
                    <OutputFile>
                      <FileId>rad-1</FileId>
                      <Type>RadiationDetection</Type>
                      <URI>scan-1/portal-spectrum.n42</URI>
                    </OutputFile>
                    <XRayFile>
                      <FileId>xray-side-1</FileId>
                      <URI>scan-1/xray-side-16bit.tiff</URI>
                      <View>Left</View>
                      <Modality>Transmission</Modality>
                      <ScanningMode>DriveThrough</ScanningMode>
                      <Energy>6.0</Energy>
                      <Energy>4.0</Energy>
                      <Speed>2200</Speed>
                      <HorizontalPixelSize>5.0</HorizontalPixelSize>
                      <VerticalPixelSize>5.0</VerticalPixelSize>
                      <HEMDInfo>
                        <HEMDFileURI>scan-1/xray-side-hemd.png</HEMDFileURI>
                        <HEMDMap>
                          <ZMap>
                            <Name>Organic</Name>
                            <PaletteStart>1</PaletteStart>
                            <PaletteEnd>85</PaletteEnd>
                            <EffectiveZStart>1</EffectiveZStart>
                            <EffectiveZEnd>10</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>Inorganic / Intermediate</Name>
                            <PaletteStart>86</PaletteStart>
                            <PaletteEnd>170</PaletteEnd>
                            <EffectiveZStart>11</EffectiveZStart>
                            <EffectiveZEnd>20</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>Metallic</Name>
                            <PaletteStart>171</PaletteStart>
                            <PaletteEnd>230</PaletteEnd>
                            <EffectiveZStart>21</EffectiveZStart>
                            <EffectiveZEnd>56</EffectiveZEnd>
                          </ZMap>
                          <ZMap>
                            <Name>High-Z Shielding</Name>
                            <PaletteStart>231</PaletteStart>
                            <PaletteEnd>255</PaletteEnd>
                            <EffectiveZStart>57</EffectiveZStart>
                            <EffectiveZEnd>94</EffectiveZEnd>
                          </ZMap>
                        </HEMDMap>
                      </HEMDInfo>
                    </XRayFile>
                  </Scan>
                  <Analysis>
                    <Assessment>Suspect</Assessment>
                    <Result>High-density shielding anomaly detected in forward pallet bay</Result>
                    <Algorithm>
                      <Name>Sandbox-ATD-HighZ</Name>
                      <Vendor>OSCAR Training Academy</Vendor>
                    </Algorithm>
                    <Annotation>
                      <Id>ann-highz-1</Id>
                      <Description>High-Z Shielded Cask Anomaly (Z_eff &gt; 72)</Description>
                      <Comment>Correlates with forward gamma peak on RPM count profile</Comment>
                      <Confidence>94</Confidence>
                      <FileURI>scan-1/xray-side-16bit.tiff</FileURI>
                      <BoundingBox>
                        <Shape>Rectangle</Shape>
                        <Left>96</Left>
                        <Top>48</Top>
                        <Right>146</Right>
                        <Bottom>98</Bottom>
                      </BoundingBox>
                    </Annotation>
                  </Analysis>
                </UFF>
                """.formatted(
                uffUuid,
                scanTime.toString(),
                scannerId,
                frontPlate,
                containerId,
                uffUuid.substring(0, 8).toUpperCase(Locale.ROOT),
                scanTime.toString(),
                scannerId,
                containerId);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(196_608);
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            putStoredEntry(zip, "version", "3.0.0\n".getBytes(StandardCharsets.UTF_8));
            putStoredEntry(zip, uffUuid + ".xml", xml.getBytes(StandardCharsets.UTF_8));
            putStoredEntry(zip, "preview.jpg", previewJpg);
            putStoredEntry(zip, "scan-1/xray-side-16bit.tiff", tiff16);
            putStoredEntry(zip, "scan-1/xray-side-hemd.png", hemdPng);
            putStoredEntry(zip, "scan-1/portal-spectrum.n42", n42Bytes);
        }
        return bos.toByteArray();
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

    /**
     * Builds an uncompressed 16-bit grayscale baseline TIFF (`uint16`, little-endian)
     * showing a container silhouette, cargo pallets, and a dense High-Z shielded box
     * at `[96..146, 48..98]` with genuine 16-bit radiographic dynamic range (`4000..62000`).
     */
    private static byte[] buildSynthetic16BitCargoTiff(int width, int height) {
        int pixelBytes = width * height * 2;
        int tagCount = 10;
        int ifdOffset = 8;
        int ifdSize = 2 + (tagCount * 12) + 4;
        int pixelOffset = ifdOffset + ifdSize;

        ByteBuffer buf = ByteBuffer.allocate(pixelOffset + pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        // Little-endian TIFF magic "II" + 42 + IFD offset 8
        buf.put((byte) 'I').put((byte) 'I');
        buf.putShort((short) 42);
        buf.putInt(ifdOffset);

        buf.putShort((short) tagCount);
        writeTiffTag(buf, 256, 4, 1, width);       // ImageWidth
        writeTiffTag(buf, 257, 4, 1, height);      // ImageLength
        writeTiffTag(buf, 258, 3, 1, 16);          // BitsPerSample = 16
        writeTiffTag(buf, 259, 3, 1, 1);           // Compression = 1 (Uncompressed)
        writeTiffTag(buf, 262, 3, 1, 1);           // PhotometricInterpretation = 1 (BlackIsZero transmission)
        writeTiffTag(buf, 273, 4, 1, pixelOffset); // StripOffsets
        writeTiffTag(buf, 277, 3, 1, 1);           // SamplesPerPixel = 1
        writeTiffTag(buf, 278, 4, 1, height);      // RowsPerStrip = height
        writeTiffTag(buf, 279, 4, 1, pixelBytes);  // StripByteCounts
        writeTiffTag(buf, 339, 3, 1, 1);           // SampleFormat = 1 (uint)
        buf.putInt(0);                             // Next IFD = 0

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int transmission = 58_500; // Open air (high X-ray transmission)
                boolean inContainer = (x >= 24 && x <= width - 24 && y >= 22 && y <= height - 28);
                if (inContainer) {
                    int rib = ((x - 24) % 10 < 2) ? 3_500 : 0;
                    transmission = 44_000 - rib;
                    // Homogeneous pallets across container floor
                    if (y >= 54 && y <= height - 34 && ((x - 36) % 44 < 36)) {
                        transmission = 28_500 - ((y - 54) * 95);
                    }
                    // High-Z dense shielded anomaly in forward bay [96..146, 48..98]
                    if (x >= 96 && x <= 146 && y >= 48 && y <= 98) {
                        double dx = (x - 121.0) / 25.0;
                        double dy = (y - 73.0) / 25.0;
                        double r2 = dx * dx + dy * dy;
                        transmission = r2 <= 0.45 ? 4_200 : 8_800;
                    }
                }
                // Chassis & axles underneath
                if (y > height - 28 && y <= height - 18 && x >= 20 && x <= width - 20) {
                    transmission = 14_000;
                }
                buf.putShort((short) Math.max(1000, Math.min(65000, transmission)));
            }
        }
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

    private static byte[] buildSynthetic8BitPalettizedHemdPng(int width, int height) throws IOException {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        r[0] = (byte) 245; g[0] = (byte) 247; b[0] = (byte) 250; // Air
        for (int i = 1; i <= 85; i++) {   // Organic (orange)
            r[i] = (byte) 234; g[i] = (byte) 138; b[i] = (byte) 46;
        }
        for (int i = 86; i <= 170; i++) { // Inorganic ceramic (green)
            r[i] = (byte) 34; g[i] = (byte) 166; b[i] = (byte) 96;
        }
        for (int i = 171; i <= 230; i++) { // Metallic steel (blue)
            r[i] = (byte) 48; g[i] = (byte) 112; b[i] = (byte) 216;
        }
        for (int i = 231; i <= 255; i++) { // High-Z shielding (magenta/crimson)
            r[i] = (byte) 190; g[i] = (byte) 24; b[i] = (byte) 93;
        }

        IndexColorModel icm = new IndexColorModel(8, 256, r, g, b);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, icm);
        WritableRaster raster = img.getRaster();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = 0;
                if (x >= 24 && x <= width - 24 && y >= 22 && y <= height - 28) {
                    idx = 190; // Steel container wall
                    if (y >= 54 && y <= height - 34 && ((x - 36) % 44 < 36)) {
                        idx = 120; // Inorganic ceramic pallet
                    }
                    if (x >= 96 && x <= 146 && y >= 48 && y <= 98) {
                        idx = 245; // High-Z anomaly!
                    }
                } else if (y > height - 28 && y <= height - 18 && x >= 20 && x <= width - 20) {
                    idx = 205; // Steel chassis
                }
                raster.setSample(x, y, 0, idx);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(16_384);
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] buildSyntheticPreviewJpeg(int width, int height, String containerId) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(241, 245, 249));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(51, 65, 85));
        g.drawRect(24, 22, width - 48, height - 50);
        g.setColor(new Color(148, 163, 184));
        g.fillRect(36, 54, width - 72, height - 88);
        g.setColor(new Color(190, 24, 93));
        g.fillRect(96, 48, 50, 50);
        g.setColor(new Color(15, 23, 42));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        g.drawString("SANDBOX UFF 3.0 · " + containerId, 26, 16);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
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

        // Uncompressed PMTiles v3 root directory with 1 entry (tileId=0, runLength=1, length=tileBytes.length, offset=0)
        ByteArrayOutputStream dirOut = new ByteArrayOutputStream(32);
        writeVarint(dirOut, 1);                 // numEntries = 1
        writeVarint(dirOut, 0);                 // tileId delta = 0
        writeVarint(dirOut, 1);                 // runLength = 1
        writeVarint(dirOut, tileBytes.length);  // byte length
        writeVarint(dirOut, 1);                 // offset + 1 (1 => offset 0)
        byte[] rootDir = dirOut.toByteArray();

        long rootDirOffset = 127L;
        long jsonOffset = rootDirOffset + rootDir.length;
        long tileDataOffset = jsonOffset + jsonMeta.length;
        int totalSize = (int) (tileDataOffset + tileBytes.length);

        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        out.put("PMTiles".getBytes(StandardCharsets.US_ASCII));
        out.put((byte) 3);                      // specVersion = 3
        out.putLong(rootDirOffset);             // 8: rootDirOffset
        out.putLong(rootDir.length);            // 16: rootDirBytes
        out.putLong(jsonOffset);                // 24: jsonMetadataOffset
        out.putLong(jsonMeta.length);           // 32: jsonMetadataBytes
        out.putLong(0L);                        // 40: leafDirsOffset
        out.putLong(0L);                        // 48: leafDirsBytes
        out.putLong(tileDataOffset);            // 56: tileDataOffset
        out.putLong(tileBytes.length);          // 64: tileDataBytes
        out.putLong(1L);                        // 72: addressedTilesCount
        out.putLong(1L);                        // 80: tileEntriesCount
        out.putLong(1L);                        // 88: tileContentsCount
        out.put((byte) 1);                      // 96: clustered = true
        out.put((byte) 1);                      // 97: internalCompression = 1 (none)
        out.put((byte) 1);                      // 98: tileCompression = 1 (none)
        out.put((byte) 2);                      // 99: tileType = 2 (PNG)
        out.put((byte) 0);                      // 100: minZoom = 0
        out.put((byte) 14);                     // 101: maxZoom = 14
        out.putInt((int) Math.round(-84.50 * 1e7)); // 102: minLonE7
        out.putInt((int) Math.round(35.80 * 1e7));  // 106: minLatE7
        out.putInt((int) Math.round(-84.10 * 1e7)); // 110: maxLonE7
        out.putInt((int) Math.round(36.10 * 1e7));  // 114: maxLatE7
        out.put((byte) 12);                     // 118: centerZoom = 12
        out.putInt((int) Math.round(-84.31 * 1e7)); // 119: centerLonE7
        out.putInt((int) Math.round(35.93 * 1e7));  // 123: centerLatE7

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
