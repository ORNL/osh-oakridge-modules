package com.botts.impl.sensor.kromek.d3s;

import net.opengis.swe.v20.DataBlock;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Self-contained Kromek D3S spectra CSV frame parsing, multi-file selection, truncated/corrupt
 * row recovery, epoch deduplication, and clean worker start/stop lifecycle tests.
 */
public class D3sProtocolReplayAndLifecycleTest {

    private static final String HEADER_LINES = String.join("\n",
            "Bin Number, 0, 1, 2, 3, 4",
            "Energy[keV], -12.65, 0.48, 32.10, 661.70, 1332.50",
            "Time,Date,Detection_ID,Confidence,UNUSED,UNUSED,Latitude,Longitude,Processing Time(ms),Sensor Temp (degC), Battery (%),Livetime(ms),Neutron Count,Dose, Dose Unit, Output(0), Output(1), Output(2), Output(3), Output(4)"
    ) + "\n";

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private SensorHub hub;

    @After
    public void tearDown() {
        if (hub != null) {
            try {
                hub.stop();
            } catch (Exception ignored) {
            }
            hub = null;
        }
    }

    @Test
    public void selectsNewestValidSpectraFileParsesDoseCountsAndDeduplicatesUntilNewRowArrives()
            throws Exception {
        File dataDir = tempFolder.newFolder("d3s-data");

        // Non-matching ancillary file
        Files.writeString(new File(dataDir, "init.ini").toPath(), "[D3S]\nmode=search\n", StandardCharsets.UTF_8);

        // Older valid Spectra file
        Files.writeString(
                new File(dataDir, "07.10.2023_11.40.00_SGM200615_Spectra.csv").toPath(),
                HEADER_LINES + "11:40:01.100,07/10/2023,NODET,0,NA,NA,NA,NA,12.0,30.0,85.0,1000.0,0,0.021,µSv/h,0,1,0,0,0\n",
                StandardCharsets.UTF_8);

        // Newer valid Spectra file (starting with NA GPS row)
        File activeSpectra = new File(dataDir, "07.10.2023_11.45.12_SGM200615_Spectra.csv");
        Files.writeString(
                activeSpectra.toPath(),
                HEADER_LINES + "11:45:13.250,07/10/2023,NODET,0,NA,NA,NA,NA,15.5,31.0,84.0,999.0,1,0.035,µSv/h,0,2,1,0,0\n",
                StandardCharsets.UTF_8);

        // Even newer filename timestamp, but truncated (< 4 lines) -> must be ignored!
        Files.writeString(
                new File(dataDir, "07.10.2023_12.00.00_SGM200615_Spectra.csv").toPath(),
                "Bin Number, 0, 1\nEnergy[keV], 0.0, 3.0\n",
                StandardCharsets.UTF_8);

        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        D3sConfig config = (D3sConfig) reg.createModuleConfig(new D3sDescriptor());
        config.id = "kromek-d3s-01";
        config.name = "Kromek D3S 01";
        config.serialNumber = "SGM200615";
        config.dataPath = dataDir.getAbsolutePath();

        D3sSensor sensor = (D3sSensor) reg.loadModule(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        assertFalse("Sensor must report disconnected before start without NPE", sensor.isConnected());

        List<DataBlock> published = new CopyOnWriteArrayList<>();
        CountDownLatch firstEvent = new CountDownLatch(1);
        CountDownLatch secondEvent = new CountDownLatch(2);
        CountDownLatch thirdEvent = new CountDownLatch(3);

        sensor.output.pollIntervalMillis = 25L;
        sensor.output.registerListener(event -> {
            if (event instanceof DataEvent de) {
                published.add(de.getRecords()[0].clone());
                firstEvent.countDown();
                secondEvent.countDown();
                thirdEvent.countDown();
            }
        });

        reg.startModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
        assertTrue("Sensor must report connected while worker is active", sensor.isConnected());

        assertTrue("Expected initial D3S observation", firstEvent.await(3, TimeUnit.SECONDS));
        // Allow multiple poll cycles with unchanged file to verify phenomenonTime deduplication
        Thread.sleep(90L);
        assertEquals("Unchanged phenomenonTime must not emit duplicate events", 1, published.size());

        DataBlock first = published.get(0);
        assertEquals("07.10.2023_11.45.12_SGM200615_Spectra.csv", first.getStringValue(0));
        assertEquals("NODET", first.getStringValue(3));
        assertEquals(0.0, first.getDoubleValue(4), 0.001);
        assertTrue("NA latitude must decode to NaN", Double.isNaN(first.getDoubleValue(5)));
        assertTrue("NA longitude must decode to NaN", Double.isNaN(first.getDoubleValue(6)));
        assertEquals(15.5, first.getDoubleValue(7), 0.001);
        assertEquals(31.0, first.getDoubleValue(8), 0.001);
        assertEquals(84.0, first.getDoubleValue(9), 0.001);
        assertEquals(999.0, first.getDoubleValue(10), 0.001);
        assertEquals(1, first.getIntValue(11)); // neutronCount
        assertEquals(0.035, first.getDoubleValue(12), 0.0001); // dose (uSv/h)
        assertEquals("SGM200615", sensor.output.deviceSerialNum);

        // Append a new Cs-137 detection row with numeric GPS coordinates
        Files.writeString(
                activeSpectra.toPath(),
                "11:45:15.500,07/10/2023,Cs-137,96.5,NA,NA,35.9311,-84.3102,84.25,31.5,83.0,1001.0,4,0.485,µSv/h,1,4,12,85,2\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertTrue("Expected updated Cs-137 D3S observation", secondEvent.await(3, TimeUnit.SECONDS));
        DataBlock second = published.get(1);
        assertEquals("Cs-137", second.getStringValue(3));
        assertEquals(96.5, second.getDoubleValue(4), 0.001);
        assertEquals(35.9311, second.getDoubleValue(5), 0.0001);
        assertEquals(-84.3102, second.getDoubleValue(6), 0.0001);
        assertEquals(4, second.getIntValue(11));
        assertEquals(0.485, second.getDoubleValue(12), 0.0001);

        // Append a row with unsupported dose unit -> must flag -999 sentinel cleanly
        Files.writeString(
                activeSpectra.toPath(),
                "11:45:17.500,07/10/2023,Co-60,88.0,NA,NA,35.9312,-84.3103,60.0,31.5,83.0,1000.0,2,150.0,cpm,1,3,5,10,40\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertTrue("Expected sentinel dose unit observation", thirdEvent.await(3, TimeUnit.SECONDS));
        assertEquals(-999.0, published.get(2).getDoubleValue(12), 0.001);

        Thread workerRef = sensor.output.worker;
        long stopStartNanos = System.nanoTime();
        reg.stopModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 3_000));
        long stopElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartNanos);

        assertFalse("D3S worker thread must terminate on stop", workerRef.isAlive());
        assertFalse("Sensor must report disconnected after stop", sensor.isConnected());
        assertTrue("Stop must interrupt sleep promptly (< 1500 ms), took " + stopElapsedMillis + " ms",
                stopElapsedMillis < 1_500L);
    }

    @Test
    public void truncatedOrCorruptCsvRowsAreRejectedAndReaderRecoversWhenValidRowArrives() throws Exception {
        File dataDir = tempFolder.newFolder("d3s-corrupt-recovery");
        File spectraFile = new File(dataDir, "08.15.2023_09.10.00_SGM300111_Spectra.csv");

        // Start with a truncated/corrupt data row (missing numeric columns)
        Files.writeString(
                spectraFile.toPath(),
                HEADER_LINES + "09:10:01.000,08/15/2023,CORRUPT_TRUNCATED_ROW\n",
                StandardCharsets.UTF_8);

        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        D3sConfig config = (D3sConfig) reg.createModuleConfig(new D3sDescriptor());
        config.id = "kromek-d3s-recovery";
        config.name = "Kromek D3S Recovery";
        config.serialNumber = "SGM300111";
        config.dataPath = dataDir.getAbsolutePath();

        D3sSensor sensor = (D3sSensor) reg.loadModule(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        sensor.output.pollIntervalMillis = 25L;

        List<DataBlock> published = new CopyOnWriteArrayList<>();
        CountDownLatch recovered = new CountDownLatch(1);
        sensor.output.registerListener(event -> {
            if (event instanceof DataEvent de) {
                published.add(de.getRecords()[0].clone());
                recovered.countDown();
            }
        });

        reg.startModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));

        // Let several poll cycles pass over corrupt row and a second malformed-number row
        Thread.sleep(80L);
        Files.writeString(
                spectraFile.toPath(),
                "09:10:02.000,08/15/2023,Ir-192,NOT_A_DOUBLE,NA,NA,NA,NA,10.0,28.0,90.0,1000.0,0,0.12,µSv/h\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Thread.sleep(80L);
        assertTrue("Corrupt/truncated rows must never publish partial DataBlocks", published.isEmpty());

        // Append a valid measurement row and verify automatic recovery
        Files.writeString(
                spectraFile.toPath(),
                "09:10:04.000,08/15/2023,Ir-192,94.0,NA,NA,35.9000,-84.3000,52.0,28.5,90.0,1000.0,3,0.620,µSv/h,2,5,8,1,0\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertTrue("Reader must recover and publish valid row after corrupt rows",
                recovered.await(3, TimeUnit.SECONDS));
        assertEquals(1, published.size());
        assertEquals("Ir-192", published.get(0).getStringValue(3));
        assertEquals(3, published.get(0).getIntValue(11));
        assertEquals(0.620, published.get(0).getDoubleValue(12), 0.0001);

        reg.stopModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 3_000));
    }
}
