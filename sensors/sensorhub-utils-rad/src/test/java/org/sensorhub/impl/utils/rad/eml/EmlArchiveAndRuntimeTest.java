package org.sensorhub.impl.utils.rad.eml;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.model.Adjudication;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class EmlArchiveAndRuntimeTest {
    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("oscar-eml-test-");
    }

    @After
    public void tearDown() {
        EmlArchiveExtractor.deleteRecursivelyQuietly(tempDir);
    }

    @Test
    public void rejectsTraversalAbsoluteAndSymlinkArchiveEntries() throws Exception {
        Path traversalZip = tempDir.resolve("traversal.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(traversalZip))) {
            zos.putNextEntry(new ZipEntry("../../escape.txt"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        try {
            EmlArchiveExtractor.extract(traversalZip, "traversal.zip", tempDir.resolve("out1"));
            fail("Expected traversal ZIP entry to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("traversal"));
        }

        Path absZip = tempDir.resolve("abs.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(absZip))) {
            zos.putNextEntry(new ZipEntry("/etc/passwd"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        try {
            EmlArchiveExtractor.extract(absZip, "abs.jar", tempDir.resolve("out2"));
            fail("Expected absolute ZIP entry to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Absolute"));
        }

        Path symlinkTarGz = tempDir.resolve("symlink.tar.gz");
        try (OutputStream fos = Files.newOutputStream(symlinkTarGz);
             GZIPOutputStream gos = new GZIPOutputStream(fos)) {
            writeTarEntry(gos, "EML-Mock/link", new byte[0], (byte) '2');
            gos.write(new byte[1024]);
        }
        try {
            EmlArchiveExtractor.extract(symlinkTarGz, "symlink.tar.gz", tempDir.resolve("out3"));
            fail("Expected TAR symlink entry to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Symbolic"));
        }
    }

    @Test
    public void loadsCleanRoomMockPluginFromJarAndTarGzAndEvaluatesReleaseVsInvestigate() throws Exception {
        Path mockJar = buildSyntheticMockPluginJar(tempDir.resolve("eml-mock-1.0.2.jar"));
        Path mockTarGz = buildSyntheticMockPluginTarGz(tempDir.resolve("EML-Mock-v1.0.2.tar.gz"), mockJar);

        EmlRuntimeRegistry registry = new EmlRuntimeRegistry();
        try {
            assertFalse(registry.isLoaded());

            EmlRuntimeStatus jarStatus = registry.installFromArchive("eml-mock-1.0.2.jar", mockJar);
            assertTrue(jarStatus.loaded());
            assertEquals("1.0.2-mock", jarStatus.versionId());
            assertEquals("multi_site_collimated", jarStatus.collimatedModelId());
            assertTrue(registry.isLoaded());

            EmlLaneParameters params = new EmlLaneParameters("lane1", 1, true, 4.82, 20, 5);
            EmlEvaluationResult normResult = registry.evaluate(params, Stream.of(
                    "NS, 00:00:01,100,100,100,100,0,0,0,0",
                    "GS, 00:00:02",
                    "GA, 00:00:02,380,380,380,380",
                    "GX, 00:00:03,1"));
            assertNotNull(normResult);
            assertTrue(normResult.isAutoReleaseNonThreat());
            assertEquals("RELEASE", normResult.result());
            assertEquals(0.975, normResult.releaseProbability(), 1e-6);

            String feedback = normResult.toAdjudicationFeedback();
            assertTrue(feedback.contains("Automated EML Non-Threat release"));
            assertTrue(feedback.contains(Adjudication.EML_METADATA_PREFIX));
            assertTrue(feedback.contains("\"disposition\":\"EML Non-Threat\""));
            assertTrue(feedback.contains("\"overallSourceType\":\"NORM\""));

            // Hot-swap with .tar.gz archive and verify INVESTIGATE classification does NOT auto-release
            EmlRuntimeStatus tarStatus = registry.installFromArchive("EML-Mock-v1.0.2.tar.gz", mockTarGz);
            assertTrue(tarStatus.loaded());
            assertEquals("EML-Mock-v1.0.2.tar.gz", tarStatus.fileName());

            EmlEvaluationResult spikeResult = registry.evaluate(params, Stream.of(
                    "NS, 00:00:01,100,100,100,100,0,0,0,0",
                    "GS, 00:00:02",
                    "GA, 00:00:02,8500,100,100,100",
                    "GX, 00:00:03,1"));
            assertNotNull(spikeResult);
            assertFalse(spikeResult.isAutoReleaseNonThreat());
            assertEquals("INVESTIGATE", spikeResult.result());
            assertEquals(0.991, spikeResult.investigateProbability(), 1e-6);
        } finally {
            registry.unload();
        }
    }

    @Test
    public void loadsCustomSpiAndZeroDependencyManifestAlgorithms() throws Exception {
        Path spiJar = buildCustomSpiPluginJar(tempDir.resolve("custom-spi-assessor-2.0.0.jar"));
        Path manifestJar = buildZeroDependencyManifestPluginJar(tempDir.resolve("zero-dep-assessor-3.1.0.jar"));

        EmlRuntimeRegistry registry = new EmlRuntimeRegistry();
        try {
            EmlRuntimeStatus spiStatus = registry.installFromArchive("custom-spi-assessor-2.0.0.jar", spiJar);
            assertTrue(spiStatus.loaded());
            assertEquals("2.0.0-spi", spiStatus.versionId());
            assertEquals("spectral-ratio-collimated", spiStatus.collimatedModelId());
            assertEquals("spectral-ratio-uncollimated", spiStatus.uncollimatedModelId());

            EmlLaneParameters params = new EmlLaneParameters("lane3", 3, true, 4.82, 20, 5);
            EmlEvaluationResult norm = registry.evaluate(params, Stream.of(
                    "GS, 10:00:01",
                    "GA, 10:00:01,350,350,350,350",
                    "GX, 10:00:02,7"));
            assertNotNull(norm);
            assertTrue(norm.isAutoReleaseNonThreat());
            assertEquals("RELEASE", norm.result());
            assertEquals(0.985, norm.releaseProbability(), 1e-6);

            EmlEvaluationResult threat = registry.evaluate(params, Stream.of(
                    "GS, 10:00:01",
                    "GA, 10:00:01,9900,350,350,350",
                    "GX, 10:00:02,8"));
            assertNotNull(threat);
            assertFalse(threat.isAutoReleaseNonThreat());
            assertEquals("INVESTIGATE", threat.result());

            // Hot-swap with a zero-dependency standalone JAR registered via META-INF/MANIFEST.MF
            EmlRuntimeStatus mfStatus = registry.installFromArchive("zero-dep-assessor-3.1.0.jar", manifestJar);
            assertTrue(mfStatus.loaded());
            assertEquals("3.1.0-standalone", mfStatus.versionId());
            assertEquals("zero-dep-model", mfStatus.collimatedModelId());

            EmlEvaluationResult mfNorm = registry.evaluate(params, Stream.of(
                    "GS, 10:05:01",
                    "GA, 10:05:01,320,320,320,320",
                    "GX, 10:05:02,9"));
            assertNotNull(mfNorm);
            assertTrue(mfNorm.isAutoReleaseNonThreat());
            assertEquals("RELEASE", mfNorm.result());
            assertEquals(0.96, mfNorm.releaseProbability(), 1e-6);
            assertTrue(mfNorm.toAdjudicationFeedback().contains("\"modelId\":\"zero-dep-model\""));
        } finally {
            registry.unload();
        }
    }

    private static Path buildCustomSpiPluginJar(Path targetJar) throws Exception {
        Path srcDir = Files.createTempDirectory(targetJar.getParent(), "spi-src-");
        Path classesDir = Files.createTempDirectory(targetJar.getParent(), "spi-cls-");
        Path srcFile = srcDir.resolve("example/custom/CustomSpiAssessor.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, """
                package example.custom;
                import org.sensorhub.impl.utils.rad.eml.EmlAlarmAssessmentAlgorithm;
                import org.sensorhub.impl.utils.rad.eml.EmlEvaluationResult;
                import org.sensorhub.impl.utils.rad.eml.EmlLaneParameters;
                import org.sensorhub.impl.utils.rad.eml.EmlSourceBreakdown;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                public class CustomSpiAssessor implements EmlAlarmAssessmentAlgorithm {
                    @Override
                    public void initialize(Path extractedPluginRoot) throws Exception {
                        if (!Files.isDirectory(extractedPluginRoot)) {
                            throw new IllegalStateException("Missing extracted root");
                        }
                    }
                    @Override public String getVersionId() { return "2.0.0-spi"; }
                    @Override public String getCollimatedModelId() { return "spectral-ratio-collimated"; }
                    @Override public String getUncollimatedModelId() { return "spectral-ratio-uncollimated"; }
                    @Override
                    public EmlEvaluationResult evaluate(EmlLaneParameters params, List<String> lines) {
                        boolean spike = lines.stream().anyMatch(l -> l.contains(",9900,"));
                        String model = params.collimated() ? getCollimatedModelId() : getUncollimatedModelId();
                        double rel = spike ? 0.015 : 0.985;
                        double inv = 1.0 - rel;
                        EmlSourceBreakdown overall = new EmlSourceBreakdown(
                                spike ? "Threat" : "NORM", model, 0, 0, 0, 0, 0.0, spike ? 0.0 : rel, spike ? inv : 0.0);
                        return new EmlEvaluationResult(
                                getVersionId(), model, 0.05, params.portId(), params.laneId(),
                                System.currentTimeMillis(), 1L, "ALARM", true, false, false,
                                spike ? "INVESTIGATE" : "RELEASE", false, "", inv, rel,
                                true, false, 1, 5.0, "", List.of(overall), overall);
                    }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                srcFile.toString());
        assertEquals(0, rc);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetJar));
             Stream<Path> walk = Files.walk(classesDir)) {
            addZipText(zos, "META-INF/services/" + EmlAlarmAssessmentAlgorithm.class.getName(),
                    "example.custom.CustomSpiAssessor\n");
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
            }
        }
        return targetJar;
    }

    private static Path buildZeroDependencyManifestPluginJar(Path targetJar) throws Exception {
        Path srcDir = Files.createTempDirectory(targetJar.getParent(), "mf-src-");
        Path classesDir = Files.createTempDirectory(targetJar.getParent(), "mf-cls-");
        Path srcFile = srcDir.resolve("example/standalone/ZeroDepAssessor.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, """
                package example.standalone;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                public class ZeroDepAssessor {
                    public String getVersionId() { return "3.1.0-standalone"; }
                    public String getCollimatedModelId() { return "zero-dep-model"; }
                    public Map<String, Object> evaluate(Map<String, Object> laneParams, List<String> lines) {
                        boolean spike = lines.stream().anyMatch(l -> l.contains(",9900,"));
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("result", spike ? "INVESTIGATE" : "RELEASE");
                        out.put("releaseProbability", spike ? 0.04 : 0.96);
                        out.put("investigateProbability", spike ? 0.96 : 0.04);
                        out.put("overallSourceType", spike ? "Threat" : "NORM");
                        return out;
                    }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-d", classesDir.toString(),
                srcFile.toString());
        assertEquals(0, rc);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetJar));
             Stream<Path> walk = Files.walk(classesDir)) {
            addZipText(zos, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\n"
                    + EmlAlarmAssessmentAlgorithm.MANIFEST_CLASS_ATTRIBUTE + ": example.standalone.ZeroDepAssessor\r\n\r\n");
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
            }
        }
        return targetJar;
    }

    @Test
    public void registersAndClaimsPendingAutoAdjudicationsWithRoundingTolerance() {
        EmlSourceBreakdown overall = new EmlSourceBreakdown(
                "NORM", "multi_site_collimated", 0.5, 2.5, 1.0, 1.2, 0.02, 0.96, 0.02);
        EmlEvaluationResult result = new EmlEvaluationResult(
                "1.0.2", "multi_site_collimated", 0.05, "lane7", 7L, 1700000000000L, 42L,
                "ALARM", true, false, false, "RELEASE", false, "",
                0.02, 0.98, true, false, 1, 5.2, "", List.of(overall), overall);

        String producer = "urn:osh:sensor:rapiscan:test007";
        double ts = 1762360000.1234;
        PendingEmlAutoAdjudicationRegistry.register(producer, ts, 42, result);

        EmlEvaluationResult claimed = PendingEmlAutoAdjudicationRegistry.claim(producer, ts + 0.0004, 42);
        assertNotNull(claimed);
        assertEquals("RELEASE", claimed.result());
        assertNull(PendingEmlAutoAdjudicationRegistry.claim(producer, ts, 42));
    }

    @Test
    public void evaluatesRealLocalEmlBundleWhenSystemPropertyProvided() throws Exception {
        String realJarProp = System.getProperty("oscar.test.eml.jar");
        if (realJarProp == null || realJarProp.isBlank()) {
            return;
        }
        Path realJar = Paths.get(realJarProp);
        if (!Files.isRegularFile(realJar)) {
            return;
        }

        EmlRuntimeRegistry registry = new EmlRuntimeRegistry();
        try {
            EmlRuntimeStatus status = registry.installFromArchive(realJar.getFileName().toString(), realJar);
            assertTrue(status.loaded());
            assertFalse(status.versionId().isBlank());

            List<String> normLines = buildUniformNormOccupancyLines();
            EmlLaneParameters params = new EmlLaneParameters("lane1", 1, true, 4.82, 20, 5);
            EmlEvaluationResult normEval = registry.evaluate(params, normLines.stream());
            assertNotNull(normEval);
            assertEquals("RELEASE", normEval.result());
            assertTrue(normEval.isAutoReleaseNonThreat());
            assertNotNull(normEval.overallSource());

            List<String> threatLines = buildLocalizedSpikeOccupancyLines();
            EmlEvaluationResult threatEval = registry.evaluate(params, threatLines.stream());
            assertNotNull(threatEval);
            assertEquals("INVESTIGATE", threatEval.result());
            assertFalse(threatEval.isAutoReleaseNonThreat());
        } finally {
            registry.unload();
        }
    }

    private static List<String> buildUniformNormOccupancyLines() {
        List<String> lines = new ArrayList<>();
        for (int s = 0; s < 30; s++) {
            lines.add(String.format("NS, 12:00:%02d,250,250,250,250,0,0,0,0", s));
        }
        lines.add("GS, 12:00:30");
        for (int tick = 0; tick < 25; tick++) {
            int sec = 30 + (tick / 5);
            boolean alarmTick = tick >= 3 && tick <= 21;
            int g = alarmTick ? 420 : 260;
            lines.add(String.format("%s, 12:00:%02d,%d,%d,%d,%d", alarmTick ? "GA" : "GB", sec, g, g, g, g));
            if (tick % 5 == 4) {
                lines.add(String.format("NB, 12:00:%02d,0,0,0,0", sec));
            }
        }
        lines.add("GX, 12:00:35,24");
        return lines;
    }

    private static List<String> buildLocalizedSpikeOccupancyLines() {
        List<String> lines = new ArrayList<>();
        for (int s = 0; s < 30; s++) {
            lines.add(String.format("NS, 12:10:%02d,250,250,250,250,0,0,0,0", s));
        }
        lines.add("GS, 12:10:30");
        for (int tick = 0; tick < 25; tick++) {
            int sec = 30 + (tick / 5);
            boolean spike = (tick == 11 || tick == 12);
            int g1 = spike ? 4600 : 255;
            lines.add(String.format("%s, 12:10:%02d,%d,255,255,255", spike ? "GA" : "GB", sec, g1));
            if (tick % 5 == 4) {
                lines.add(String.format("NB, 12:10:%02d,0,0,0,0", sec));
            }
        }
        lines.add("GX, 12:10:35,24");
        return lines;
    }

    public static Path buildSyntheticMockPluginJar(Path targetJar) throws Exception {
        Path srcDir = Files.createTempDirectory(targetJar.getParent(), "mock-src-");
        Path classesDir = Files.createTempDirectory(targetJar.getParent(), "mock-cls-");

        Map<String, String> sources = Map.ofEntries(
                Map.entry("gov/llnl/utility/xml/bind/DocumentReader.java", """
                        package gov.llnl.utility.xml.bind;
                        import java.nio.file.Path;
                        public class DocumentReader<T> {
                            private final Class<T> cls;
                            private DocumentReader(Class<T> cls) { this.cls = cls; }
                            public static <T> DocumentReader<T> create(Class<T> cls) { return new DocumentReader<>(cls); }
                            public T loadFile(Path path) throws Exception {
                                if (cls.getName().endsWith("LaneDatabase")) {
                                    return cls.cast(Class.forName("gov.llnl.ernie.vm250.VM250LaneDatabase", true, cls.getClassLoader()).getConstructor().newInstance());
                                }
                                return cls.cast(Class.forName("gov.llnl.ernie.vm250.VM250Analysis", true, cls.getClassLoader()).getConstructor().newInstance());
                            }
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/LaneDefinition.java", """
                        package gov.llnl.ernie.data;
                        public class LaneDefinition {
                            private final boolean collimated;
                            public LaneDefinition(boolean collimated) { this.collimated = collimated; }
                            public boolean isCollimated() { return collimated; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/LaneDatabase.java", """
                        package gov.llnl.ernie.data;
                        public interface LaneDatabase {}
                        """),
                Map.entry("gov/llnl/ernie/vm250/VM250LaneDatabase.java", """
                        package gov.llnl.ernie.vm250;
                        import gov.llnl.ernie.data.LaneDatabase;
                        import gov.llnl.ernie.data.LaneDefinition;
                        import java.util.List;
                        public class VM250LaneDatabase implements LaneDatabase {
                            public Iterable<LaneDefinition> getDefaultLanes() {
                                return List.of(new LaneDefinition(true), new LaneDefinition(false));
                            }
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/LaneImpl.java", """
                        package gov.llnl.ernie.data;
                        public class LaneImpl {
                            public boolean collimated = true;
                            public double width = 4.82;
                            public void setPanels(int panels) {}
                            public void setLaneWidth(double w) { this.width = w; }
                            public void assign(LaneDefinition def) { this.collimated = def.isCollimated(); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/Record.java", """
                        package gov.llnl.ernie.data;
                        public interface Record {}
                        """),
                Map.entry("gov/llnl/ernie/vm250/data/VM250RecordInternal.java", """
                        package gov.llnl.ernie.vm250.data;
                        import gov.llnl.ernie.data.LaneImpl;
                        import java.util.List;
                        public class VM250RecordInternal {
                            public final List<String> lines;
                            public final LaneImpl lane = new LaneImpl();
                            public VM250RecordInternal(List<String> lines) { this.lines = lines; }
                            public LaneImpl getLane() { return lane; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/vm250/data/VM250Record.java", """
                        package gov.llnl.ernie.vm250.data;
                        import gov.llnl.ernie.data.Record;
                        public class VM250Record implements Record {
                            public final VM250RecordInternal internal;
                            public VM250Record(VM250RecordInternal internal) { this.internal = internal; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/vm250/tools/DailyFileLoader.java", """
                        package gov.llnl.ernie.vm250.tools;
                        import gov.llnl.ernie.data.LaneDatabase;
                        import gov.llnl.ernie.vm250.data.VM250RecordInternal;
                        import java.util.List;
                        import java.util.stream.Stream;
                        public class DailyFileLoader {
                            public DailyFileLoader(LaneDatabase db) {}
                            public void setDefaultIntervals(int i) {}
                            public void setDefaultOccupancyHoldin(int h) {}
                            public void setBatchMode(boolean b) {}
                            public VM250RecordInternal loadFirstOccupancy(Stream<String> lines) {
                                List<String> collected = lines.toList();
                                return collected.isEmpty() ? null : new VM250RecordInternal(collected);
                            }
                        }
                        """),
                Map.entry("gov/llnl/ernie/AnalysisResult.java", """
                        package gov.llnl.ernie;
                        public class AnalysisResult {
                            public final boolean spike;
                            public AnalysisResult(boolean spike) { this.spike = spike; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/classifier/Classifier.java", """
                        package gov.llnl.ernie.classifier;
                        import java.nio.file.Paths;
                        public class Classifier {
                            public final String modelId;
                            public Classifier(String modelFile, String metricFile) {
                                String fn = Paths.get(modelFile).getFileName().toString();
                                int dot = fn.lastIndexOf('.');
                                this.modelId = dot > 0 ? fn.substring(0, dot) : fn;
                            }
                            public String[] getFeatureNames() { return new String[]{"f1"}; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/Analysis.java", """
                        package gov.llnl.ernie;
                        import gov.llnl.ernie.data.Record;
                        public abstract class Analysis {
                            public abstract AnalysisResult processRecord(Record record);
                            public AnalysisResult allocateResult() { return new AnalysisResult(false); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/vm250/VM250Analysis.java", """
                        package gov.llnl.ernie.vm250;
                        import gov.llnl.ernie.Analysis;
                        import gov.llnl.ernie.AnalysisResult;
                        import gov.llnl.ernie.classifier.Classifier;
                        import gov.llnl.ernie.data.Record;
                        import gov.llnl.ernie.vm250.data.VM250Record;
                        public class VM250Analysis extends Analysis {
                            public String modelId = "multi_site_collimated";
                            public void setClassifier(Classifier c) { this.modelId = c.modelId; }
                            @Override
                            public AnalysisResult processRecord(Record record) {
                                VM250Record r = (VM250Record) record;
                                boolean spike = r.internal.lines.stream().anyMatch(l -> l.contains(",8500,"));
                                return new AnalysisResult(spike);
                            }
                        }
                        """),
                Map.entry("gov/llnl/ernie/api/Results.java", """
                        package gov.llnl.ernie.api;
                        import gov.llnl.ernie.Analysis;
                        import gov.llnl.ernie.AnalysisResult;
                        import gov.llnl.ernie.vm250.VM250Analysis;
                        import java.time.Instant;
                        public class Results {
                            private final VM250Analysis analysis;
                            private final AnalysisResult res;
                            private String portId = "";
                            private int laneId = 0;
                            public static class Thresholds {
                                public double getPrimary() { return 0.05; }
                            }
                            public static class Source {
                                private final boolean spike;
                                private final String model;
                                Source(boolean spike, String model) { this.spike = spike; this.model = model; }
                                String getSourceType() { return spike ? "Threat" : "NORM"; }
                                String getClassifierUsed() { return model; }
                                double getxLocation1() { return 0.5; }
                                double getxLocation2() { return 2.5; }
                                double getyLocation() { return 1.0; }
                                double getzLocation() { return 1.2; }
                                double getProbabilityNonEmitting() { return 0.015; }
                                double getProbabilityNORM() { return spike ? 0.009 : 0.960; }
                                double getProbabilityThreat() { return spike ? 0.991 : 0.025; }
                            }
                            Results(Analysis a, AnalysisResult r) {
                                this.analysis = (VM250Analysis) a;
                                this.res = r;
                            }
                            public void setPortID(String p) { this.portId = p; }
                            public void setLaneID(int l) { this.laneId = l; }
                            public String getResult() { return res.spike ? "INVESTIGATE" : "RELEASE"; }
                            public boolean isFallback() { return false; }
                            public Throwable getException() { return null; }
                            public double getReleaseProbability() { return res.spike ? 0.009 : 0.975; }
                            public double getInvestigateProbability() { return res.spike ? 0.991 : 0.025; }
                            public boolean getERNIEGammaAlert() { return true; }
                            public boolean getERNIENeutronAlert() { return false; }
                            public int getVehicleClass() { return 1; }
                            public double getVehicleLength() { return 5.1; }
                            public String getYellowLightMessage() { return ""; }
                            public String getVersionID() { return "1.0.2-mock"; }
                            public String getModelID() { return analysis.modelId; }
                            public Thresholds getThresholds() { return new Thresholds(); }
                            public String getPortID() { return portId; }
                            public int getLaneID() { return laneId; }
                            public Instant getDateTime() { return Instant.ofEpochMilli(1700000000000L); }
                            public long getSegmentId() { return 1L; }
                            public String getRPMResult() { return "ALARM"; }
                            public boolean getRPMGammaAlert() { return true; }
                            public boolean getRPMNeutronAlert() { return false; }
                            public boolean getRPMScanError() { return false; }
                            public int getNumberOfSources() { return 1; }
                            public Source getSource(int idx) { return new Source(res.spike, analysis.modelId); }
                            public Source getOverallSource() { return new Source(res.spike, analysis.modelId); }
                        }
                        """)
        );

        List<String> compilerArgs = new ArrayList<>();
        compilerArgs.add("-d");
        compilerArgs.add(classesDir.toString());
        for (var entry : sources.entrySet()) {
            Path file = srcDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            compilerArgs.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK compiler required for synthetic mock EML test", compiler);
        int rc = compiler.run(null, null, null, compilerArgs.toArray(new String[0]));
        assertEquals("Synthetic mock EML sources must compile cleanly", 0, rc);

        Path innerClassesJar = srcDir.resolve("mock-ernie-runtime.jar");
        try (ZipOutputStream innerZip = new ZipOutputStream(Files.newOutputStream(innerClassesJar));
             Stream<Path> walk = Files.walk(classesDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                innerZip.putNextEntry(new ZipEntry(rel));
                innerZip.write(Files.readAllBytes(p));
                innerZip.closeEntry();
            }
        }

        try (ZipOutputStream bundleZip = new ZipOutputStream(Files.newOutputStream(targetJar))) {
            addZipText(bundleZip, "config/vm250Analysis.xml", "<vm250Analysis/>");
            addZipText(bundleZip, "config/vm250LaneDatabase.xml", "<laneDatabase/>");
            addZipText(bundleZip, "config/CMU_metric.csv", "feature,weight\nf1,1.0\n");
            addZipText(bundleZip, "config/multi_site_collimated.txt", "mock-collimated-weights\n");
            addZipText(bundleZip, "config/multi_site_uncollimated.txt", "mock-uncollimated-weights\n");
            bundleZip.putNextEntry(new ZipEntry("jars/gov.llnl.ernie.mock.jar"));
            bundleZip.write(Files.readAllBytes(innerClassesJar));
            bundleZip.closeEntry();
        }
        return targetJar;
    }

    private static Path buildSyntheticMockPluginTarGz(Path targetTarGz, Path sourceJar) throws Exception {
        Path unpack = Files.createTempDirectory(targetTarGz.getParent(), "tar-src-");
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(sourceJar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = unpack.resolve(e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, zis.readAllBytes());
                }
            }
        }

        try (OutputStream fos = Files.newOutputStream(targetTarGz);
             GZIPOutputStream gos = new GZIPOutputStream(fos);
             Stream<Path> walk = Files.walk(unpack)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = "EML-VM250-v1.0.2-mock/" + unpack.relativize(p).toString().replace('\\', '/');
                writeTarEntry(gos, rel, Files.readAllBytes(p), (byte) '0');
            }
            gos.write(new byte[1024]);
        }
        return targetTarGz;
    }

    private static void addZipText(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void writeTarEntry(OutputStream out, String name, byte[] data, byte typeFlag) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        writeOctal(header, 100, 8, 0600);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = typeFlag;
        byte[] magic = "ustar\0".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';
        long sum = 0;
        for (byte b : header) {
            sum += (b & 0xFF);
        }
        String chk = String.format("%06o\0 ", sum);
        System.arraycopy(chk.getBytes(StandardCharsets.UTF_8), 0, header, 148, 8);
        out.write(header);
        if (data.length > 0) {
            out.write(data);
            int pad = (512 - (data.length % 512)) % 512;
            if (pad > 0) {
                out.write(new byte[pad]);
            }
        }
    }

    private static void writeOctal(byte[] buf, int offset, int length, long value) {
        String oct = Long.toOctalString(value);
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        for (int i = oct.length(); i < length - 1; i++) {
            padded.write('0');
        }
        padded.writeBytes(oct.getBytes(StandardCharsets.UTF_8));
        padded.write(0);
        byte[] bytes = padded.toByteArray();
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length));
    }
}
