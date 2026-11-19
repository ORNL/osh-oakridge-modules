/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

import org.junit.After;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.model.WebIdAnalysis;
import org.sensorhub.impl.utils.rad.security.DynamicTrustManagerRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class SpectroscopyAndTrustRegistryTest {

    @After
    public void tearDown() throws Exception {
        SpectroscopyRuntimeRegistry.defaultRegistry().resetToDefault();
        DynamicTrustManagerRegistry.resetToSystemDefault();
    }

    @Test
    public void isotopeLibraryIdentifiesSyntheticCs137OfflineAndResetsCleanly() throws Exception {
        SpectroscopyRuntimeRegistry reg = SpectroscopyRuntimeRegistry.defaultRegistry();
        assertEquals("builtin", reg.getStatus().activeSource());
        assertFalse(reg.getStatus().customActive());

        Path tempDir = Files.createTempDirectory("oscar-spec-test-");
        try {
            Path libJson = tempDir.resolve("custom-isotopes.json");
            String json = """
                    {
                      "libraryId": "portal-snm-industrial-v1",
                      "version": "1.3.0",
                      "executionMode": "override",
                      "isotopes": [
                        { "name": "Cs-137", "category": "Industrial", "energiesKeV": [661.7], "windowKeV": 20.0, "minSnr": 3.5 },
                        { "name": "Co-60", "category": "Industrial", "energiesKeV": [1173.2, 1332.5], "windowKeV": 22.0, "minSnr": 3.5 }
                      ]
                    }
                    """;
            Files.writeString(libJson, json, StandardCharsets.UTF_8);

            SpectroscopyRuntimeStatus st = reg.installFromFile("custom-isotopes.json", libJson, null);
            assertTrue(st.customActive());
            assertEquals("portal-snm-industrial-v1", st.algorithmId());
            assertEquals("override", st.executionMode());
            assertEquals(2, st.isotopeCount());
            assertTrue(reg.isOverrideModeActive());

            String n42 = SpectroscopyRuntimeRegistry.buildSyntheticCs137N42Xml();
            WebIdAnalysis result = reg.evaluate(new SpectroscopyAnalysisInput(
                    n42.getBytes(StandardCharsets.UTF_8),
                    "cs137-sample.n42",
                    null,
                    null,
                    n42,
                    "Detective-EX",
                    false
            ));
            assertNotNull(result);
            assertEquals(0, result.getAnalysisError());
            assertFalse(result.getIsotopes().isEmpty());
            assertEquals("Cs-137", result.getIsotopes().get(0).getName());
            assertEquals("Industrial", result.getIsotopes().get(0).getType());

            SpectroscopyRuntimeStatus fallbackMode = reg.setExecutionMode("fallback");
            assertEquals("fallback", fallbackMode.executionMode());
            assertFalse(reg.isOverrideModeActive());

            SpectroscopyRuntimeStatus reset = reg.resetToDefault();
            assertEquals("builtin", reset.activeSource());
            assertFalse(reset.customActive());
        } finally {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    public void trustManagerRejectsPrivateKeysAndLoadsValidCaPem() throws Exception {
        String leakedKeyPem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC\n-----END PRIVATE KEY-----\n";
        try {
            DynamicTrustManagerRegistry.parseAndValidateCertificates("bad.pem", leakedKeyPem.getBytes(StandardCharsets.UTF_8));
            fail("Expected SecurityException when uploading a PEM containing a private key");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("PRIVATE KEY"));
        }

        // Generate a disposable self-signed CA via JDK keytool for verification
        Path tempDir = Files.createTempDirectory("oscar-ca-test-");
        try {
            Path ksPath = tempDir.resolve("ca.p12");
            Path pemPath = tempDir.resolve("enterprise-root-ca.pem");
            Process gen = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "testca",
                    "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA",
                    "-dname", "CN=OSCAR Enterprise Test Root CA, O=ORNL",
                    "-ext", "bc:c=ca:true",
                    "-validity", "365",
                    "-keystore", ksPath.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", "changeit"
            ).redirectErrorStream(true).start();
            assertEquals(0, gen.waitFor());

            Process exp = new ProcessBuilder(
                    "keytool", "-exportcert", "-rfc",
                    "-alias", "testca",
                    "-keystore", ksPath.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", "changeit",
                    "-file", pemPath.toString()
            ).redirectErrorStream(true).start();
            assertEquals(0, exp.waitFor());
            Files.deleteIfExists(ksPath);

            long genBefore = DynamicTrustManagerRegistry.getGeneration();
            DynamicTrustManagerRegistry.TrustStatus status = DynamicTrustManagerRegistry.reloadFromDirectory(tempDir);
            assertTrue(status.customActive());
            assertEquals(1, status.customCertificateCount());
            assertTrue(status.customCertificates().get(0).subject().contains("OSCAR Enterprise Test Root CA"));
            assertTrue(status.customCertificates().get(0).caCertificate());
            assertTrue(DynamicTrustManagerRegistry.getGeneration() > genBefore);

            DynamicTrustManagerRegistry.TrustStatus afterReset = DynamicTrustManagerRegistry.resetToSystemDefault();
            assertFalse(afterReset.customActive());
            assertEquals(0, afterReset.customCertificateCount());
        } finally {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
