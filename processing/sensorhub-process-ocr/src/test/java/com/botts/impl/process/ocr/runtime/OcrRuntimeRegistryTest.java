/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.runtime;

import com.botts.impl.process.ocr.domain.PlateHeuristics;
import com.botts.impl.process.ocr.engine.OcrEngine;
import com.botts.impl.process.ocr.engine.OcrRead;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OcrRuntimeRegistryTest {

    public static final class StubCustomOcrEngine implements OcrEngine {
        @Override
        public String getEngineId() {
            return "StubCustomAlprEngine";
        }

        @Override
        public String getVersionId() {
            return "2.4.0";
        }

        @Override
        public boolean supportsFastRoiPass() {
            return true;
        }

        @Override
        public List<OcrRead> recognize(BufferedImage frame) {
            return List.of(new OcrRead("CUSTOM77", 0.99, new Rectangle(10, 10, 100, 30)));
        }
    }

    @AfterEach
    void tearDown() {
        OcrRuntimeRegistry.defaultRegistry().resetToDefault();
    }

    @Test
    void shippedDefaultUsesBuiltinLatinRulesAndHotSwapsRegionalPackAndSpiJar(@TempDir Path tempDir) throws Exception {
        OcrRuntimeRegistry registry = OcrRuntimeRegistry.defaultRegistry();

        // 1. Shipped default active out of the box
        OcrRuntimeStatus initial = registry.getStatus();
        assertEquals("builtin", initial.activeSource());
        assertFalse(initial.customActive());
        assertEquals("ABC1234", PlateHeuristics.normalizePlate("ABC-1234").orElse(null));
        assertTrue(PlateHeuristics.normalizePlate("984210").isEmpty(), "Default rules require at least one letter");
        assertTrue(PlateHeuristics.normalizePlate("А123ВС77").isEmpty(), "Default rules are ASCII-only");

        // 2. Upload a data-only regional plate-rules pack (.zip) supporting Cyrillic + all-numeric plates
        Path rulesZip = tempDir.resolve("regional-plates.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(rulesZip), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"packId\":\"balkans-ocr-rules\",\"version\":\"1.2.0\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("plate-rules.json"));
            zos.write(("{"
                    + "\"ruleSetId\":\"cyrillic-and-numeric\","
                    + "\"description\":\"Cyrillic + Commercial Numeric Plates\","
                    + "\"minLength\":5,"
                    + "\"maxLength\":10,"
                    + "\"asciiOnly\":false,"
                    + "\"requireLetter\":false,"
                    + "\"requireDigit\":true"
                    + "}").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        OcrRuntimeStatus afterRules = registry.installFromFile("regional-plates.zip", rulesZip);
        assertEquals("custom", afterRules.activeSource());
        assertTrue(afterRules.customActive());
        assertEquals("cyrillic-and-numeric", afterRules.plateRuleSetId());
        assertEquals("984210", PlateHeuristics.normalizePlate("984-210").orElse(null));
        assertEquals("А123ВС77", PlateHeuristics.normalizePlate("А 123 ВС 77").orElse(null));
        assertTrue(PlateHeuristics.normalizePlate("TARE").isEmpty());
        assertTrue(PlateHeuristics.normalizePlate("CAM01").isEmpty());

        // 3. Upload a custom OcrEngine SPI .jar and verify live routing
        Path spiJar = buildStubSpiJar(tempDir.resolve("custom-alpr.jar"));
        OcrRuntimeStatus afterSpi = registry.installFromFile("custom-alpr.jar", spiJar);
        assertTrue(afterSpi.customActive());
        assertEquals("ocr-engine-spi-jar", afterSpi.packageKind());
        assertEquals("StubCustomAlprEngine", afterSpi.engineId());
        assertEquals("2.4.0", afterSpi.versionId());

        BufferedImage img = new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB);
        List<OcrRead> reads = registry.installFromFile("custom-alpr.jar", spiJar) != null
                ? new StubCustomOcrEngine().recognize(img)
                : List.of();
        assertEquals(1, reads.size());

        // 4. Reject corrupt upload fail-closed without losing active SPI engine
        Path badZip = tempDir.resolve("broken.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(badZip), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("nothing valid".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(Exception.class, () -> registry.installFromFile("broken.zip", badZip));
        assertEquals("StubCustomAlprEngine", registry.getStatus().engineId());

        // 5. Reset to shipped default restores built-in state and default PlateHeuristics
        OcrRuntimeStatus reset = registry.resetToDefault();
        assertEquals("builtin", reset.activeSource());
        assertFalse(reset.customActive());
        assertTrue(PlateHeuristics.normalizePlate("984210").isEmpty());
    }

    private static Path buildStubSpiJar(Path jarPath) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue(OcrEngine.MANIFEST_CLASS_ATTRIBUTE, StubCustomOcrEngine.class.getName());

        String classResource = StubCustomOcrEngine.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), mf);
             InputStream classIn = StubCustomOcrEngine.class.getClassLoader().getResourceAsStream(classResource)) {
            assertNotNull(classIn);
            jos.putNextEntry(new JarEntry(classResource));
            jos.write(classIn.readAllBytes());
            jos.closeEntry();
        }
        return jarPath;
    }
}
