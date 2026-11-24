/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import com.botts.impl.service.bucket.filesystem.FileSystemBucketStore;
import com.botts.impl.service.oscar.uff.WcoUffModels.UffCaseMetadata;
import com.botts.impl.service.oscar.uff.WcoUffModels.UffSearchMatch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class CargoUffIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void defaultDisabledAndParsesUff2AndUff3WithFuzzyOcrAndStaging() throws Exception {
        Path tempDir = tempFolder.getRoot().toPath();
        Path uffStateDir = tempDir.resolve("extensions/uff");
        Path bucketDir = tempDir.resolve("buckets");
        Files.createDirectories(bucketDir);
        FileSystemBucketStore bucketStore = new FileSystemBucketStore(bucketDir);

        CargoUffManager manager = new CargoUffManager(
                uffStateDir,
                LoggerFactory.getLogger(CargoUffIntegrationTest.class),
                () -> bucketStore
        );
        manager.restoreOnStartup();

        Map<String, Object> initial = manager.getPublicActiveInfo();
        assertEquals(Boolean.FALSE, initial.get("enabled"));

        // Build synthetic commercial NAS folder layout: 2026/11/20/<ScannerID>/<uuid>.uff
        Path nasRoot = tempDir.resolve("nas-share/2026/11/20");
        Path lane1 = nasRoot.resolve("EAGLE-P60-LANE01");
        Path lane2 = nasRoot.resolve("VACIS-M6500-03");
        Files.createDirectories(lane1);
        Files.createDirectories(lane2);

        String uuidSparse = "67c041a5-3500-415a-b571-1e805de813f5";
        String uuidTier3 = "772e9494-96b1-433b-902e-fb39a864edc6";

        writeSampleUff2Sparse(lane1.resolve(uuidSparse + ".uff"), uuidSparse);
        writeSampleUff3Full(lane2.resolve(uuidTier3 + ".uff"), uuidTier3, "MSMU648419B"); // 1-char OCR noise: 3 -> B!

        Map<String, Object> status = manager.updateConfig(
                true,
                "local",
                "",
                "",
                tempDir.resolve("nas-share").toString(),
                "",
                "",
                "SuperSecretReadOnlyPass!",
                false,
                60,
                true
        );
        assertEquals(Boolean.TRUE, status.get("enabled"));
        assertEquals(2, status.get("indexedCount"));
        assertEquals(1L, status.get("uff2Count"));
        assertEquals(1L, status.get("uff3Count"));
        assertEquals(Boolean.TRUE, status.get("passwordConfigured"));
        String persistedJson = Files.readString(uffStateDir.resolve("uff-config.json"), StandardCharsets.UTF_8);
        assertFalse("Plaintext password must never be persisted", persistedJson.contains("SuperSecretReadOnlyPass!"));

        // 1. Omnibox UUID / Windows UNC path lookup for Tier-1 Sparse scan
        @SuppressWarnings("unchecked")
        List<UffSearchMatch> byUnc = (List<UffSearchMatch>) manager.search(
                "\\\\nas.port.local\\uff\\2026\\11\\20\\EAGLE-P60-LANE01\\" + uuidSparse + ".uff",
                null, null, null, null, null, null
        ).get("matches");
        assertEquals(1, byUnc.size());
        assertEquals(uuidSparse, byUnc.get(0).uffUuid());
        assertEquals("2.0", byUnc.get(0).uffVersion());

        // 2. 1-Character OCR Fuzzy Correlation (querying exact ground-truth MSMU6484193 matches scanner OCR MSMU648419B)
        @SuppressWarnings("unchecked")
        List<UffSearchMatch> fuzzyMatches = (List<UffSearchMatch>) manager.search(
                null, "MSMU 648419-3", null, null, 0, true, 10
        ).get("matches");
        assertEquals(1, fuzzyMatches.size());
        assertEquals(uuidTier3, fuzzyMatches.get(0).uffUuid());
        assertTrue(fuzzyMatches.get(0).fuzzyMatch());
        assertEquals("MSMU648419B", fuzzyMatches.get(0).matchedIdentifier());
        assertTrue(fuzzyMatches.get(0).hasManifestPdf());
        assertTrue(fuzzyMatches.get(0).hasRadiationN42());
        assertEquals(1, fuzzyMatches.get(0).annotationCount());

        // 3. Zero-download adjudication staging extracts .uffref.json + manifest PDF + .n42 into bucketStore
        Map<String, Object> staged = manager.stageForAdjudication(uuidTier3);
        assertEquals(Boolean.TRUE, staged.get("ok"));
        String refKey = (String) staged.get("referenceKey");
        assertNotNull(refKey);
        assertFalse(((String) staged.get("manifestUri")).isEmpty());
        assertFalse(((String) staged.get("n42Uri")).isEmpty());
        assertFalse(((String) staged.get("n42ContentBase64")).isEmpty());

        // Re-inspecting via .uffref.json bucket key resolves the full UFF 3.0 metadata cleanly
        UffCaseMetadata viaRef = manager.getCaseMetadata(null, refKey);
        assertEquals(uuidTier3, viaRef.uffUuid());
        assertEquals("6802.93", viaRef.declaration().hsCode());
        assertEquals(1, viaRef.xrayFiles().size());

        // 4. Reset returns cleanly to disabled with zero indexed state
        Map<String, Object> afterReset = manager.resetToDisabled();
        assertEquals(Boolean.FALSE, afterReset.get("enabled"));
        assertEquals(0, afterReset.get("indexedCount"));
    }

    @Test
    public void parsesSmbUncAndUriVariantsAndVerifiesLiveLabSmbIfAvailable() throws Exception {
        Path tempDir = tempFolder.getRoot().toPath();
        UffSmbConnector.ParsedSmbEndpoint unc = UffSmbConnector.parseSmbTarget(
                "\\\\192.168.0.60\\oscar-uff\\smb-date-archive", "");
        assertEquals("192.168.0.60", unc.host());
        assertEquals(445, unc.port());
        assertEquals("oscar-uff", unc.shareName());
        assertEquals("smb-date-archive", unc.subPath());

        UffSmbConnector.ParsedSmbEndpoint smbUri = UffSmbConnector.parseSmbTarget(
                "smb://192.168.0.60:445/oscar-uff", "smb-hot-drop");
        assertEquals("192.168.0.60", smbUri.host());
        assertEquals("oscar-uff", smbUri.shareName());
        assertEquals("smb-hot-drop", smbUri.subPath());

        // If running on Nimo with local lab SMB credentials present, exercise live SMBv3!
        Path passFile = Paths.get(System.getProperty("user.home"), ".config/oscar-uff-smb/smb.pass");
        if (Files.isRegularFile(passFile)) {
            String pass = Files.readString(passFile, StandardCharsets.UTF_8).trim();
            CargoUffManager mgr = new CargoUffManager(
                    tempDir.resolve("live-smb"),
                    LoggerFactory.getLogger(CargoUffIntegrationTest.class),
                    () -> null
            );
            Map<String, Object> testRes = mgr.testConnection(
                    "smb",
                    "\\\\127.0.0.1\\oscar-uff",
                    "smb-date-archive",
                    "",
                    "WORKGROUP",
                    "nii_reader",
                    pass
            );
            assertEquals(Boolean.TRUE, testRes.get("ok"));
            assertEquals(160, testRes.get("discoveredUffCount"));
        }
    }

    private static void writeSampleUff2Sparse(Path dest, String uffUuid) throws IOException {
        String scanEv = "763a3f4d-8da3-4f21-9f7b-a8db6594e75b";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <UFF>
                  <Target>
                    <EventId>3e3bf3d7-9f51-418f-a249-4243ee446d10</EventId>
                    <InspectionSystem>
                      <Identifier>EAGLE-P60-LANE01</Identifier>
                      <Manufacturer>Rapiscan</Manufacturer>
                      <ModelName>Eagle P60</ModelName>
                    </InspectionSystem>
                    <TimeStamp>2026-11-20T13:47:01Z</TimeStamp>
                    <Vehicle>
                      <Container><Position>0</Position><ContainerNum/></Container>
                      <FrontPlateNum/>
                    </Vehicle>
                  </Target>
                  <Scan>
                    <EventId>%s</EventId>
                    <TimeStamp>2026-11-20T13:47:46Z</TimeStamp>
                    <XRayFile>
                      <URI>xray1.tif</URI>
                      <Type>X-ray</Type>
                      <Modality>Transmission</Modality>
                      <HorizontalPixelSize>4.0</HorizontalPixelSize>
                      <VerticalPixelSize>4.0</VerticalPixelSize>
                      <HEMDInfo><HEMDFileURI>hemd1.png</HEMDFileURI></HEMDInfo>
                    </XRayFile>
                  </Scan>
                </UFF>
                """.formatted(scanEv);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            putEntry(zos, "version", "2.0".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, uffUuid + ".xml", xml.getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "preview.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
            putEntry(zos, "event-" + scanEv + "/xray1.tif", new byte[]{0x49, 0x49, 0x2A, 0x00});
            putEntry(zos, "event-" + scanEv + "/hemd1.png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        }
        Files.write(dest, bos.toByteArray());
    }

    private static void writeSampleUff3Full(Path dest, String uffUuid, String ocrContainer) throws IOException {
        String targetEv = "ebd115b0-dcce-4a71-b23e-8f0f51cbb854";
        String scanEv = "8d31a0b4-423a-45e0-bb29-b7456215cea3";
        String targetDir = "event" + targetEv.replace("-", "");
        String scanDir = "event" + scanEv.replace("-", "");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <UFF xmlns="http://tempuri.org/UFF3.0.xsd">
                  <Id>%s</Id>
                  <Target>
                    <EventId>%s</EventId>
                    <InspectionSystem>
                      <Identifier>VACIS-M6500-03</Identifier>
                      <Manufacturer>Leidos</Manufacturer>
                      <ModelName>VACIS M6500</ModelName>
                    </InspectionSystem>
                    <TimeStamp>2026-11-20T14:10:00Z</TimeStamp>
                    <Vehicle>
                      <Container>
                        <Position>0</Position>
                        <ContainerNum/>
                        <Weight>15768</Weight>
                        <Length>40</Length>
                        <Declaration>
                          <CustomsId>US-CBP-TEST</CustomsId>
                          <GoodsDescription>
                            <HSCode>6802.93</HSCode>
                            <Description>Polished Granite Architectural Slabs</Description>
                          </GoodsDescription>
                        </Declaration>
                      </Container>
                    </Vehicle>
                    <Document>
                      <FileId>5f32e97b-e482-4edd-b023-ede6a3f33358</FileId>
                      <URI>Manifest-1-5f32e97b.pdf</URI>
                      <Type>Manifest</Type>
                    </Document>
                  </Target>
                  <Scan>
                    <EventId>%s</EventId>
                    <TimeStamp>2026-11-20T14:12:00Z</TimeStamp>
                    <OcrResult>
                      <OcrType>CCR</OcrType>
                      <Value>%s</Value>
                      <Confidence>0.91</Confidence>
                    </OcrResult>
                    <OutputFile>
                      <FileId>dafb4ff3-2800-4e1a-852e-1684424b1471</FileId>
                      <URI>RadiationDetection-1-dafb4ff3.n42</URI>
                      <Type>RadiationDetection</Type>
                    </OutputFile>
                    <XRayFile>
                      <FileId>32f80438-6270-41fd-98bf-8e919e522ca9</FileId>
                      <URI>Xray-TX-LeftRight-1.tif</URI>
                      <Type>X-ray</Type>
                      <Modality>TX</Modality>
                      <Energy>6</Energy>
                      <Energy>4</Energy>
                      <HorizontalPixelSize>4.0</HorizontalPixelSize>
                      <VerticalPixelSize>4.0</VerticalPixelSize>
                      <HEMDInfo>
                        <HEMDFileURI>HEMD-TX-LeftRight-1.png</HEMDFileURI>
                        <HEMDMap>
                          <ZMap><Name>Organic</Name><PaletteStart>1</PaletteStart><PaletteEnd>11</PaletteEnd><EffectiveZStart>6</EffectiveZStart><EffectiveZEnd>10</EffectiveZEnd></ZMap>
                          <ZMap><Name>Intermediate</Name><PaletteStart>12</PaletteStart><PaletteEnd>20</PaletteEnd><EffectiveZStart>11</EffectiveZStart><EffectiveZEnd>18</EffectiveZEnd></ZMap>
                          <ZMap><Name>Inorganic</Name><PaletteStart>21</PaletteStart><PaletteEnd>30</PaletteEnd><EffectiveZStart>21</EffectiveZStart><EffectiveZEnd>82</EffectiveZEnd></ZMap>
                        </HEMDMap>
                      </HEMDInfo>
                    </XRayFile>
                  </Scan>
                  <Analysis>
                    <Assessment>Automated</Assessment>
                    <Result>Anomaly</Result>
                    <Annotation>
                      <AnnotationId>ann-1</AnnotationId>
                      <Description>High-Density Attenuation Block</Description>
                      <Confidence>96</Confidence>
                      <Shape>Rectangle</Shape>
                      <BoundingBox><Top>0.35</Top><Left>0.50</Left><Bottom>0.65</Bottom><Right>0.62</Right></BoundingBox>
                    </Annotation>
                  </Analysis>
                </UFF>
                """.formatted(uffUuid, targetEv, scanEv, ocrContainer);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            putEntry(zos, "version", "3.0".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, uffUuid + ".xml", xml.getBytes(StandardCharsets.UTF_8));
            putEntry(zos, "preview.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
            putEntry(zos, targetDir + "/Manifest-1-5f32e97b.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, scanDir + "/RadiationDetection-1-dafb4ff3.n42", "<RadInstrumentData/>".getBytes(StandardCharsets.UTF_8));
            putEntry(zos, scanDir + "/Xray-TX-LeftRight-1.tif", new byte[]{0x49, 0x49, 0x2A, 0x00});
            putEntry(zos, scanDir + "/HEMD-TX-LeftRight-1.png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        }
        Files.write(dest, bos.toByteArray());
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }
}
