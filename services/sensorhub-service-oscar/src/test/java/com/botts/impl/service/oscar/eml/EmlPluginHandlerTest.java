package com.botts.impl.service.oscar.eml;

import com.botts.impl.service.oscar.OSCARServiceConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.utils.rad.eml.EmlArchiveExtractor;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeRegistry;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class EmlPluginHandlerTest {
    private SensorHub hub;
    private Path tempDir;
    private Path storageDir;

    @Before
    public void setUp() throws Exception {
        hub = new SensorHub();
        hub.start();
        tempDir = Files.createTempDirectory("oscar-eml-handler-test-");
        storageDir = tempDir.resolve("eml-plugin");
    }

    @After
    public void tearDown() {
        if (hub != null) {
            EmlRuntimeRegistry.forHub(hub).unload();
            hub.stop();
        }
        EmlArchiveExtractor.deleteRecursivelyQuietly(tempDir);
    }

    @Test
    public void uploadsActivatesRestoresPrunesAndPreservesPreviousPluginOnCorruptUpload() throws Exception {
        OSCARServiceConfig config = new OSCARServiceConfig();
        EmlPluginHandler handler = new EmlPluginHandler(
                hub, LoggerFactory.getLogger(EmlPluginHandlerTest.class), config, storageDir);

        assertFalse(handler.isValidFileType("malware.exe", "application/octet-stream"));
        assertFalse(handler.isValidFileType("../escape.jar", "application/java-archive"));
        assertTrue(handler.isValidFileType("eml-vm250-1.0.2.jar", "application/java-archive"));
        assertTrue(handler.isValidFileType("EML-VM250-v1.0.2.tar.gz", "application/gzip"));

        Path validJar1 = buildMinimalValidMockJar(tempDir.resolve("eml-vm250-1.0.1.jar"), "1.0.1");
        try (OutputStream out = handler.handleUpload("eml-vm250-1.0.1.jar")) {
            out.write(Files.readAllBytes(validJar1));
        }
        assertTrue(handler.handleFile("eml-vm250-1.0.1.jar"));
        assertEquals("eml-vm250-1.0.1.jar", config.emlPluginPath);
        assertTrue(handler.getStatus().loaded());
        assertEquals("1.0.1", handler.getStatus().versionId());

        // Uploading a corrupt archive must fail closed and keep the active v1.0.1 plugin untouched
        try (OutputStream out = handler.handleUpload("corrupt-eml.jar")) {
            out.write("not-a-valid-zip".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(handler.handleFile("corrupt-eml.jar"));
        assertFalse(Files.exists(storageDir.resolve("corrupt-eml.jar")));
        assertTrue(Files.isRegularFile(storageDir.resolve("eml-vm250-1.0.1.jar")));
        assertEquals("eml-vm250-1.0.1.jar", config.emlPluginPath);
        assertTrue(handler.getStatus().loaded());

        // Uploading a newer valid archive replaces v1.0.1 and prunes the superseded archive
        Path validJar2 = buildMinimalValidMockJar(tempDir.resolve("eml-vm250-1.0.2.jar"), "1.0.2");
        try (OutputStream out = handler.handleUpload("eml-vm250-1.0.2.jar")) {
            out.write(Files.readAllBytes(validJar2));
        }
        assertTrue(handler.handleFile("eml-vm250-1.0.2.jar"));
        assertEquals("eml-vm250-1.0.2.jar", config.emlPluginPath);
        assertEquals("1.0.2", handler.getStatus().versionId());
        assertFalse("Superseded archive must be pruned", Files.exists(storageDir.resolve("eml-vm250-1.0.1.jar")));
        assertTrue(Files.isRegularFile(storageDir.resolve("eml-vm250-1.0.2.jar")));

        // Simulate restart by unloading runtime registry and restoring from .active-plugin marker
        EmlRuntimeRegistry.forHub(hub).unload();
        assertFalse(handler.getStatus().loaded());

        OSCARServiceConfig restartedConfig = new OSCARServiceConfig();
        EmlPluginHandler restartedHandler = new EmlPluginHandler(
                hub, LoggerFactory.getLogger(EmlPluginHandlerTest.class), restartedConfig, storageDir);
        restartedHandler.restoreConfiguredOrActivePlugin(null);
        assertTrue(restartedHandler.getStatus().loaded());
        assertEquals("1.0.2", restartedHandler.getStatus().versionId());
        assertEquals("eml-vm250-1.0.2.jar", restartedConfig.emlPluginPath);
    }

    private static Path buildMinimalValidMockJar(Path targetJar, String version) throws Exception {
        Path srcDir = Files.createTempDirectory(targetJar.getParent(), "handler-mock-src-");
        Path classesDir = Files.createTempDirectory(targetJar.getParent(), "handler-mock-cls-");

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
                        public class LaneDefinition { public boolean isCollimated() { return true; } }
                        """),
                Map.entry("gov/llnl/ernie/data/LaneDatabase.java", "package gov.llnl.ernie.data; public interface LaneDatabase {}"),
                Map.entry("gov/llnl/ernie/vm250/VM250LaneDatabase.java", """
                        package gov.llnl.ernie.vm250;
                        import gov.llnl.ernie.data.*;
                        import java.util.List;
                        public class VM250LaneDatabase implements LaneDatabase {
                            public Iterable<LaneDefinition> getDefaultLanes() { return List.of(new LaneDefinition()); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/LaneImpl.java", """
                        package gov.llnl.ernie.data;
                        public class LaneImpl {
                            public void setPanels(int p) {}
                            public void setLaneWidth(double w) {}
                            public void assign(LaneDefinition d) {}
                        }
                        """),
                Map.entry("gov/llnl/ernie/data/Record.java", "package gov.llnl.ernie.data; public interface Record {}"),
                Map.entry("gov/llnl/ernie/vm250/data/VM250RecordInternal.java", """
                        package gov.llnl.ernie.vm250.data;
                        import gov.llnl.ernie.data.LaneImpl;
                        public class VM250RecordInternal { public LaneImpl getLane() { return new LaneImpl(); } }
                        """),
                Map.entry("gov/llnl/ernie/vm250/data/VM250Record.java", """
                        package gov.llnl.ernie.vm250.data;
                        import gov.llnl.ernie.data.Record;
                        public class VM250Record implements Record { public VM250Record(VM250RecordInternal i) {} }
                        """),
                Map.entry("gov/llnl/ernie/vm250/tools/DailyFileLoader.java", """
                        package gov.llnl.ernie.vm250.tools;
                        import gov.llnl.ernie.data.LaneDatabase;
                        import gov.llnl.ernie.vm250.data.VM250RecordInternal;
                        import java.util.stream.Stream;
                        public class DailyFileLoader {
                            public DailyFileLoader(LaneDatabase db) {}
                            public void setDefaultIntervals(int i) {}
                            public void setDefaultOccupancyHoldin(int h) {}
                            public void setBatchMode(boolean b) {}
                            public VM250RecordInternal loadFirstOccupancy(Stream<String> s) { return new VM250RecordInternal(); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/AnalysisResult.java", "package gov.llnl.ernie; public class AnalysisResult {}"),
                Map.entry("gov/llnl/ernie/classifier/Classifier.java", """
                        package gov.llnl.ernie.classifier;
                        public class Classifier {
                            public Classifier(String m, String c) {}
                            public String[] getFeatureNames() { return new String[0]; }
                        }
                        """),
                Map.entry("gov/llnl/ernie/Analysis.java", """
                        package gov.llnl.ernie;
                        import gov.llnl.ernie.data.Record;
                        public abstract class Analysis {
                            public abstract AnalysisResult processRecord(Record r);
                            public AnalysisResult allocateResult() { return new AnalysisResult(); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/vm250/VM250Analysis.java", """
                        package gov.llnl.ernie.vm250;
                        import gov.llnl.ernie.*;
                        import gov.llnl.ernie.classifier.Classifier;
                        import gov.llnl.ernie.data.Record;
                        public class VM250Analysis extends Analysis {
                            public void setClassifier(Classifier c) {}
                            @Override public AnalysisResult processRecord(Record r) { return new AnalysisResult(); }
                        }
                        """),
                Map.entry("gov/llnl/ernie/api/Results.java", ("""
                        package gov.llnl.ernie.api;
                        import gov.llnl.ernie.*;
                        import java.time.Instant;
                        public class Results {
                            Results(Analysis a, AnalysisResult r) {}
                            public String getResult() { return "RELEASE"; }
                            public boolean isFallback() { return false; }
                            public Throwable getException() { return null; }
                            public double getReleaseProbability() { return 0.98; }
                            public double getInvestigateProbability() { return 0.02; }
                            public boolean getERNIEGammaAlert() { return false; }
                            public boolean getERNIENeutronAlert() { return false; }
                            public int getVehicleClass() { return 0; }
                            public double getVehicleLength() { return 0.0; }
                            public String getYellowLightMessage() { return ""; }
                            public String getVersionID() { return "%s"; }
                            public String getModelID() { return "multi_site_collimated"; }
                            public String getPortID() { return ""; }
                            public int getLaneID() { return 0; }
                            public Instant getDateTime() { return Instant.EPOCH; }
                            public long getSegmentId() { return 0L; }
                            public String getRPMResult() { return "NONE"; }
                            public boolean getRPMGammaAlert() { return false; }
                            public boolean getRPMNeutronAlert() { return false; }
                            public boolean getRPMScanError() { return false; }
                            public int getNumberOfSources() { return 0; }
                            public Object getSource(int i) { return null; }
                            public Object getOverallSource() { return null; }
                        }
                        """).formatted(version))
        );

        List<String> args = new ArrayList<>(List.of("-d", classesDir.toString()));
        for (var e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            args.add(f.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])));

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetJar));
             Stream<Path> walk = Files.walk(classesDir)) {
            zos.putNextEntry(new ZipEntry("config/vm250Analysis.xml"));
            zos.write("<vm250Analysis/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config/vm250LaneDatabase.xml"));
            zos.write("<laneDatabase/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config/CMU_metric.csv"));
            zos.write("feature,weight\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config/multi_site_collimated.txt"));
            zos.write("weights\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config/multi_site_uncollimated.txt"));
            zos.write("weights\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = classesDir.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
            }
        }
        return targetJar;
    }
}
