package com.botts.impl.sensor.rs350;

import com.botts.impl.sensor.rs350.messages.RS350Message;
import com.botts.impl.sensor.rs350.output.ConnectionStatusOutput;
import net.opengis.swe.v20.DataBlock;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.comm.TCPCommProviderConfig;
import org.sensorhub.impl.comm.TCPConfig;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileJournalConnection;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileObservationMode;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileRecord;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileWriterHandle;
import org.sensorhub.impl.utils.rad.dailyfile.IDailyFileJournalService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Self-contained STX/ETX N42 wire-protocol replay, spectrum/gross-count/alarm output verification,
 * malformed/truncated frame recovery, daily-file journal capture, and start/stop lifecycle tests
 * for {@link RS350Sensor} and {@link MessageHandler}.
 */
public class RS350ProtocolReplayAndLifecycleTest {

    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;

    private final List<Closeable> resources = new ArrayList<>();
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
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (IOException ignored) {
            }
        }
        resources.clear();
    }

    @Test
    public void stxEtxReplayRecoversFromNoiseTruncatedRestartMalformedXmlAndTrailingPartialFrame()
            throws Exception {
        String bgFrameXml = buildRs350N42Xml(true, false, false, "PT30.0S");
        String fgAlarmXml = buildRs350N42Xml(false, true, true, "PT1.0S");
        String fgClearXml = buildRs350N42Xml(false, true, false, "PT1.0S");

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        // 1. Pre-STX serial/TCP noise must be ignored
        wire.write("NOISE_BEFORE_STX\r\n".getBytes(StandardCharsets.UTF_8));
        // 2. Valid Status + Background frame
        writeStxEtxFrame(wire, bgFrameXml);
        // 3. Truncated frame missing ETX immediately superseded by a fresh STX + Foreground Alarm frame
        wire.write(STX);
        wire.write("<?xml version=\"1.0\"?><RadInstrumentData><truncated".getBytes(StandardCharsets.UTF_8));
        writeStxEtxFrame(wire, fgAlarmXml);
        // 4. Malformed XML inside valid STX..ETX must be caught without stopping the notifier
        writeStxEtxFrame(wire, "<?xml version=\"1.0\"?><NotValidN42><broken></NotValidN42>");
        // 5. Valid post-error Foreground recovery frame (clears hasAlarm latch)
        writeStxEtxFrame(wire, fgClearXml);
        // 6. Unterminated EOF tail without ETX must be discarded and must not journal or spin
        wire.write(STX);
        wire.write("<?xml version=\"1.0\"?><RadInstrumentData><unterminated-eof".getBytes(StandardCharsets.UTF_8));

        List<String> journaledPayloads = new CopyOnWriteArrayList<>();
        DailyFileJournalConnection journal = mock(DailyFileJournalConnection.class);
        when(journal.isRequired()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> {
            journaledPayloads.add(inv.getArgument(0));
            return null;
        }).when(journal).append(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Instant.class));

        RS350Sensor dummySensor = new RS350Sensor();
        List<RS350Message> statusMsgs = new CopyOnWriteArrayList<>();
        List<RS350Message> bgMsgs = new CopyOnWriteArrayList<>();
        List<RS350Message> fgMsgs = new CopyOnWriteArrayList<>();
        CountDownLatch twoFgProcessed = new CountDownLatch(2);
        List<RS350Message> alarmMsgs = new CopyOnWriteArrayList<>();

        MessageHandler handler = new MessageHandler(
                null,
                new ByteArrayInputStream(wire.toByteArray()),
                "</RadInstrumentData>",
                dummySensor,
                journal);
        handler.addStatusListener(statusMsgs::add);
        handler.addBackgroundListener(bgMsgs::add);
        handler.addForegroundListener(msg -> {
            fgMsgs.add(msg);
            twoFgProcessed.countDown();
        });
        handler.addAlarmListener(alarmMsgs::add);

        assertTrue("Expected both valid foreground frames (before and after malformed XML) to process",
                twoFgProcessed.await(5, TimeUnit.SECONDS));

        // Exactly the 4 complete STX..ETX frames should be journaled (not the 2 truncated frames)
        assertEquals(4, journaledPayloads.size());
        assertEquals(bgFrameXml, journaledPayloads.get(0));
        assertEquals(fgAlarmXml, journaledPayloads.get(1));
        assertEquals(fgClearXml, journaledPayloads.get(3));

        assertEquals(3, statusMsgs.size());
        RS350Message status = statusMsgs.get(0);
        assertEquals("RS350-4102", status.getRs350InstrumentCharacteristics().getDeviceName());
        assertEquals(87.5, status.getRs350InstrumentCharacteristics().getBatteryCharge(), 0.001);
        assertEquals("Search", status.getRs350Item().getRsiScanMode());
        assertEquals(300.0, status.getRs350Item().getRsiScanTimeoutNumber(), 0.001);
        assertTrue(status.getRs350Item().getRsiAnalysisEnabled());
        assertArrayEquals(new double[]{0.0, 3.0, 0.0},
                status.getRs350LinEnergyCalibration().getLinEnCal(), 0.0001);
        assertArrayEquals(new double[]{0.0, 6.0, 0.001},
                status.getRs350CmpEnergyCalibration().getCmpEnCal(), 0.0001);

        assertEquals(1, bgMsgs.size());
        var bg = bgMsgs.get(0).getRs350BackgroundMeasurement();
        assertEquals(30.0, bg.getRealTimeDuration(), 0.001);
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0), bg.getLinEnCalSpectrum());
        assertEquals(List.of(50.0, 60.0), bg.getCmpEnCalSpectrum());
        assertEquals(420.5, bg.getGammaGrossCount(), 0.001);
        assertEquals(1.5, bg.getNeutronGrossCount(), 0.001);

        assertEquals(2, fgMsgs.size());
        var fg = fgMsgs.get(0).getRs350ForegroundMeasurement();
        assertEquals(1.0, fg.getRealTimeDuration(), 0.001);
        assertEquals(List.of(100.0, 250.0, 90.0, 15.0), fg.getLinEnCalSpectrum());
        assertEquals(List.of(350.0, 105.0), fg.getCmpEnCalSpectrum());
        assertEquals(1_840.0, fg.getGammaGrossCount(), 0.001);
        assertEquals(6.0, fg.getNeutronGrossCount(), 0.001);
        assertEquals(2.85, fg.getDoseRate(), 0.001);
        assertEquals(35.9311, fg.getLat(), 0.0001);
        assertEquals(-84.3102, fg.getLon(), 0.0001);
        assertEquals(260.0, fg.getAlt(), 0.001);

        assertEquals(1, alarmMsgs.size());
        assertEquals("Gamma", alarmMsgs.get(0).getRs350RadAlarm().getCategoryCode());
        assertEquals("Elevated Cs-137 Gamma", alarmMsgs.get(0).getRs350RadAlarm().getDescription());
        assertEquals("RS350 Gamma Alert", alarmMsgs.get(0).getRs350DerivedData().getRemark());
        assertFalse("Clear post-alarm frame must reset alarm latch", handler.hasAlarm);

        handler.stopProcessing();
    }

    @Test
    public void fullSensorLoopbackTcpReplayPublishesOutputsJournalsRawXmlAndStopsCleanly() throws Exception {
        LoopbackRs350TcpServer server = track(new LoopbackRs350TcpServer());

        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        ModuleConfig journalCfg = new ModuleConfig();
        journalCfg.id = "rs350-test-journal";
        journalCfg.name = "RS350 Test Journal";
        journalCfg.moduleClass = RecordingJournalService.class.getName();
        RecordingJournalService journalService = (RecordingJournalService) reg.loadModule(journalCfg);
        journalService.init();
        reg.startModule(journalService.getLocalID());
        assertTrue(journalService.waitForState(ModuleEvent.ModuleState.STARTED, 3_000));

        RS350Config config = (RS350Config) reg.createModuleConfig(new RS350Descriptor());
        config.id = "rs350-sensor-01";
        config.name = "RS350 Backpack 01";
        config.serialNumber = "4102";
        config.dailyFileJournal.enabled = true;
        config.dailyFileJournal.required = true;
        config.dailyFileJournal.startupTimeoutSeconds = 2;

        config.commSettings = new TCPCommProviderConfig();
        config.commSettings.connection.connectTimeout = 1_500;
        config.commSettings.connection.reconnectAttempts = 2;
        config.commSettings.connection.reconnectPeriod = 5_000;
        config.commSettings.connection.checkReachability = false;
        TCPConfig tcp = new TCPConfig();
        tcp.remoteHost = "127.0.0.1";
        tcp.remotePort = server.getPort();
        config.commSettings.protocol = tcp;

        RS350Sensor sensor = (RS350Sensor) reg.loadModule(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        assertEquals("urn:osh:sensor:rsi:rs350:4102", sensor.getUniqueIdentifier());

        CountDownLatch statusLatch = new CountDownLatch(1);
        CountDownLatch bgLatch = new CountDownLatch(1);
        CountDownLatch fgLatch = new CountDownLatch(1);
        CountDownLatch alarmLatch = new CountDownLatch(1);
        List<DataBlock> statusBlocks = new CopyOnWriteArrayList<>();
        List<DataBlock> bgBlocks = new CopyOnWriteArrayList<>();
        List<DataBlock> fgBlocks = new CopyOnWriteArrayList<>();
        List<DataBlock> alarmBlocks = new CopyOnWriteArrayList<>();

        IEventListener listener = event -> {
            if (event instanceof DataEvent de) {
                String name = de.getSource().getName();
                DataBlock block = de.getRecords()[0];
                switch (name) {
                    case "status" -> {
                        statusBlocks.add(block);
                        statusLatch.countDown();
                    }
                    case "backgroundReport" -> {
                        bgBlocks.add(block);
                        bgLatch.countDown();
                    }
                    case "foregroundReport" -> {
                        fgBlocks.add(block);
                        fgLatch.countDown();
                    }
                    case "alarm" -> {
                        alarmBlocks.add(block);
                        alarmLatch.countDown();
                    }
                    default -> {
                    }
                }
            }
        };

        sensor.statusOutput.registerListener(listener);
        sensor.backgroundOutput.registerListener(listener);
        sensor.foregroundOutput.registerListener(listener);
        sensor.alarmOutput.registerListener(listener);

        reg.startModule(sensor.getLocalID());
        assertTrue("RS350Sensor did not reach STARTED state",
                sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
        assertTrue("Expected TCP client connection from RS350Sensor",
                server.awaitClientConnected(3, TimeUnit.SECONDS));

        String rawCombinedXml = buildRs350N42Xml(true, true, true, "PT2.0S");
        server.sendFrame(rawCombinedXml);

        assertTrue("Expected status DataEvent", statusLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Expected backgroundReport DataEvent", bgLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Expected foregroundReport DataEvent", fgLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Expected alarm DataEvent", alarmLatch.await(5, TimeUnit.SECONDS));

        // Verify SWE DataBlock field values across all 4 RS350 outputs
        DataBlock statusBlock = statusBlocks.get(0);
        assertEquals(87.5, statusBlock.getDoubleValue(1), 0.001);
        assertEquals("Search", statusBlock.getStringValue(2));
        assertEquals(300.0, statusBlock.getDoubleValue(3), 0.001);
        assertTrue(statusBlock.getBooleanValue(4));

        DataBlock bgBlock = bgBlocks.get(0);
        assertEquals(4, bgBlock.getIntValue(2)); // linearSpectrumCount
        assertEquals(2, bgBlock.getIntValue(7)); // compressedSpectrumCount
        assertEquals(421, bgBlock.getIntValue(10)); // gammaGrossCount (SWE Count rounds 420.5 -> 421)
        assertEquals(2, bgBlock.getIntValue(11)); // neutronGrossCount (SWE Count rounds 1.5 -> 2)

        DataBlock fgBlock = fgBlocks.get(0);
        assertEquals(4, fgBlock.getIntValue(2)); // linearSpectrumCount
        assertEquals(1_840, fgBlock.getIntValue(10)); // gammaGrossCount
        assertEquals(6, fgBlock.getIntValue(11)); // neutronGrossCount
        assertEquals(2.85, fgBlock.getDoubleValue(12), 0.001); // doseRate (uSv/h)

        DataBlock alarmBlock = alarmBlocks.get(0);
        assertEquals(2.0, alarmBlock.getDoubleValue(1), 0.001); // duration
        assertEquals("RS350 Gamma Alert", alarmBlock.getStringValue(2)); // remark
        assertEquals("Gamma", alarmBlock.getStringValue(4)); // alarmCategory
        assertEquals("Elevated Cs-137 Gamma", alarmBlock.getStringValue(5)); // alarmDescription

        // Raw XML including XML declaration must be preserved verbatim in the daily-file journal
        assertEquals(1, journalService.records.size());
        assertTrue(journalService.records.get(0).rawLine().startsWith("<?xml version=\"1.0\""));
        assertEquals(rawCombinedXml, journalService.records.get(0).rawLine());

        // Verify ConnectionStatusOutput deduplication and immediate state transitions
        ConnectionStatusOutput isolatedConnOut = new ConnectionStatusOutput(sensor);
        isolatedConnOut.init();
        List<Boolean> connEvents = new CopyOnWriteArrayList<>();
        CountDownLatch threeConnEvents = new CountDownLatch(3);
        isolatedConnOut.registerListener(e -> {
            if (e instanceof DataEvent de) {
                connEvents.add(de.getRecords()[0].getBooleanValue(1));
                threeConnEvents.countDown();
            }
        });
        isolatedConnOut.onNewMessage(true, 10_000L);
        isolatedConnOut.onNewMessage(true, 15_000L);
        isolatedConnOut.onNewMessage(true,
                10_000L + ConnectionStatusOutput.HEARTBEAT_INTERVAL_MILLIS);
        isolatedConnOut.onNewMessage(false,
                10_500L + ConnectionStatusOutput.HEARTBEAT_INTERVAL_MILLIS);
        assertTrue(threeConnEvents.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(true, true, false), connEvents);

        reg.stopModule(sensor.getLocalID());
        assertTrue("RS350Sensor did not reach STOPPED state",
                sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 5_000));
        assertTrue("Daily-file journal writer must close on stop", journalService.writerClosed.get());
    }

    private <T extends Closeable> T track(T closeable) {
        resources.add(closeable);
        return closeable;
    }

    private static void writeStxEtxFrame(OutputStream out, String payload) throws IOException {
        out.write(STX);
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.write(ETX);
    }

    private static String buildRs350N42Xml(boolean includeBackground, boolean includeForeground,
                                           boolean includeAlarm, String derivedDuration) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<RadInstrumentData xmlns=\"http://physics.nist.gov/N42/2011/N42\" n42DocUUID=\"11111111-2222-4333-8444-555555555555\">\n");
        sb.append("  <RadInstrumentInformation id=\"Info-1\">\n");
        sb.append("    <RadInstrumentManufacturerName>Radiation Solutions Inc</RadInstrumentManufacturerName>\n");
        sb.append("    <RadInstrumentIdentifier>RS350-4102</RadInstrumentIdentifier>\n");
        sb.append("    <RadInstrumentModelName>RS-350</RadInstrumentModelName>\n");
        sb.append("    <RadInstrumentClassCode>Backpack</RadInstrumentClassCode>\n");
        sb.append("    <RadInstrumentCharacteristics>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>DeviceName</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>RS350-4102</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>unit-less</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>string</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>BatteryCharge</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>87.5</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>percent</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>double</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("    </RadInstrumentCharacteristics>\n");
        sb.append("  </RadInstrumentInformation>\n");
        sb.append("  <RadDetectorInformation id=\"DetGamma\">\n");
        sb.append("    <RadDetectorCategoryCode>Gamma</RadDetectorCategoryCode>\n");
        sb.append("    <RadDetectorKindCode>NaI</RadDetectorKindCode>\n");
        sb.append("  </RadDetectorInformation>\n");
        sb.append("  <RadDetectorInformation id=\"DetNeutron\">\n");
        sb.append("    <RadDetectorCategoryCode>Neutron</RadDetectorCategoryCode>\n");
        sb.append("    <RadDetectorKindCode>He3</RadDetectorKindCode>\n");
        sb.append("  </RadDetectorInformation>\n");
        sb.append("  <RadItemInformation id=\"Item-1\">\n");
        sb.append("    <RadItemCharacteristics>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>ScanMode</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>Search</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>unit-less</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>string</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>ScanNumber</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>12</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>unit-less</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>double</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>ScanTimeoutNumber</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>300</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>seconds</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>double</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("      <Characteristic>\n");
        sb.append("        <CharacteristicName>AnalysisEnabled</CharacteristicName>\n");
        sb.append("        <CharacteristicValue>1</CharacteristicValue>\n");
        sb.append("        <CharacteristicValueUnits>unit-less</CharacteristicValueUnits>\n");
        sb.append("        <CharacteristicValueDataClassCode>integer</CharacteristicValueDataClassCode>\n");
        sb.append("      </Characteristic>\n");
        sb.append("    </RadItemCharacteristics>\n");
        sb.append("  </RadItemInformation>\n");
        sb.append("  <EnergyCalibration id=\"LinEnCal\">\n");
        sb.append("    <CoefficientValues>0.0 3.0 0.0</CoefficientValues>\n");
        sb.append("  </EnergyCalibration>\n");
        sb.append("  <EnergyCalibration id=\"CmpEnCal\">\n");
        sb.append("    <CoefficientValues>0.0 6.0 0.001</CoefficientValues>\n");
        sb.append("  </EnergyCalibration>\n");

        if (includeBackground) {
            sb.append("  <RadMeasurement id=\"BG-1\">\n");
            sb.append("    <MeasurementClassCode>Background</MeasurementClassCode>\n");
            sb.append("    <StartDateTime>2026-10-18T12:00:00Z</StartDateTime>\n");
            sb.append("    <RealTimeDuration>PT30.0S</RealTimeDuration>\n");
            sb.append("    <Spectrum id=\"BG-S1\" radDetectorInformationReference=\"DetGamma\" energyCalibrationReference=\"LinEnCal\">\n");
            sb.append("      <LiveTimeDuration>PT29.8S</LiveTimeDuration>\n");
            sb.append("      <ChannelData>10 20 30 40</ChannelData>\n");
            sb.append("    </Spectrum>\n");
            sb.append("    <Spectrum id=\"BG-S2\" radDetectorInformationReference=\"DetGamma\" energyCalibrationReference=\"CmpEnCal\">\n");
            sb.append("      <LiveTimeDuration>PT29.8S</LiveTimeDuration>\n");
            sb.append("      <ChannelData>50 60</ChannelData>\n");
            sb.append("    </Spectrum>\n");
            sb.append("    <GrossCounts id=\"BG-GC1\" radDetectorInformationReference=\"DetGamma\">\n");
            sb.append("      <LiveTimeDuration>PT29.8S</LiveTimeDuration>\n");
            sb.append("      <CountData>420.5</CountData>\n");
            sb.append("    </GrossCounts>\n");
            sb.append("    <GrossCounts id=\"BG-GC2\" radDetectorInformationReference=\"DetNeutron\">\n");
            sb.append("      <LiveTimeDuration>PT29.8S</LiveTimeDuration>\n");
            sb.append("      <CountData>1.5</CountData>\n");
            sb.append("    </GrossCounts>\n");
            sb.append("  </RadMeasurement>\n");
        }

        if (includeForeground) {
            sb.append("  <RadMeasurement id=\"FG-1\">\n");
            sb.append("    <MeasurementClassCode>Foreground</MeasurementClassCode>\n");
            sb.append("    <StartDateTime>2026-10-18T12:00:05Z</StartDateTime>\n");
            sb.append("    <RealTimeDuration>PT1.0S</RealTimeDuration>\n");
            sb.append("    <Spectrum id=\"FG-S1\" radDetectorInformationReference=\"DetGamma\" energyCalibrationReference=\"LinEnCal\">\n");
            sb.append("      <LiveTimeDuration>PT0.98S</LiveTimeDuration>\n");
            sb.append("      <ChannelData>100 250 90 15</ChannelData>\n");
            sb.append("    </Spectrum>\n");
            sb.append("    <Spectrum id=\"FG-S2\" radDetectorInformationReference=\"DetGamma\" energyCalibrationReference=\"CmpEnCal\">\n");
            sb.append("      <LiveTimeDuration>PT0.98S</LiveTimeDuration>\n");
            sb.append("      <ChannelData>350 105</ChannelData>\n");
            sb.append("    </Spectrum>\n");
            sb.append("    <GrossCounts id=\"FG-GC1\" radDetectorInformationReference=\"DetGamma\">\n");
            sb.append("      <LiveTimeDuration>PT0.98S</LiveTimeDuration>\n");
            sb.append("      <CountData>1840.0</CountData>\n");
            sb.append("    </GrossCounts>\n");
            sb.append("    <GrossCounts id=\"FG-GC2\" radDetectorInformationReference=\"DetNeutron\">\n");
            sb.append("      <LiveTimeDuration>PT0.98S</LiveTimeDuration>\n");
            sb.append("      <CountData>6.0</CountData>\n");
            sb.append("    </GrossCounts>\n");
            sb.append("    <DoseRate id=\"FG-DR1\" radDetectorInformationReference=\"DetGamma\">\n");
            sb.append("      <DoseRateValue>2.85</DoseRateValue>\n");
            sb.append("    </DoseRate>\n");
            sb.append("    <RadInstrumentState radInstrumentInformationReference=\"Info-1\">\n");
            sb.append("      <StateVector>\n");
            sb.append("        <GeographicPoint>\n");
            sb.append("          <LatitudeValue>35.9311</LatitudeValue>\n");
            sb.append("          <LongitudeValue>-84.3102</LongitudeValue>\n");
            sb.append("          <ElevationValue>260.0</ElevationValue>\n");
            sb.append("        </GeographicPoint>\n");
            sb.append("      </StateVector>\n");
            sb.append("    </RadInstrumentState>\n");
            sb.append("  </RadMeasurement>\n");
        }

        if (includeAlarm) {
            sb.append("  <DerivedData id=\"DD-1\">\n");
            sb.append("    <Remark>RS350 Gamma Alert</Remark>\n");
            sb.append("    <MeasurementClassCode>Foreground</MeasurementClassCode>\n");
            sb.append("    <StartDateTime>2026-10-18T12:00:05Z</StartDateTime>\n");
            sb.append("    <RealTimeDuration>").append(derivedDuration).append("</RealTimeDuration>\n");
            sb.append("  </DerivedData>\n");
            sb.append("  <AnalysisResults id=\"AR-1\">\n");
            sb.append("    <RadAlarm id=\"AL-1\">\n");
            sb.append("      <RadAlarmCategoryCode>Gamma</RadAlarmCategoryCode>\n");
            sb.append("      <RadAlarmDescription>Elevated Cs-137 Gamma</RadAlarmDescription>\n");
            sb.append("    </RadAlarm>\n");
            sb.append("  </AnalysisResults>\n");
        }

        sb.append("</RadInstrumentData>");
        return sb.toString();
    }

    private static final class LoopbackRs350TcpServer implements Closeable {
        private final ServerSocket serverSocket;
        private final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstClientConnected = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Socket latestClient;

        LoopbackRs350TcpServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(() -> {
                while (!closed.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        clients.add(client);
                        latestClient = client;
                        firstClientConnected.countDown();
                    } catch (IOException e) {
                        if (!closed.get()) {
                            break;
                        }
                    }
                }
            }, "rs350-loopback-tcp-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        boolean awaitClientConnected(long timeout, TimeUnit unit) throws InterruptedException {
            return firstClientConnected.await(timeout, unit);
        }

        void sendFrame(String xmlPayload) throws IOException {
            Socket target = latestClient;
            if (target == null) {
                throw new IOException("No RS350 client connected");
            }
            OutputStream out = target.getOutputStream();
            writeStxEtxFrame(out, xmlPayload);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            synchronized (clients) {
                for (Socket s : clients) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                clients.clear();
            }
            serverSocket.close();
        }
    }

    public static final class RecordingJournalService extends AbstractModule<ModuleConfig>
            implements IDailyFileJournalService {
        final List<DailyFileRecord> records = new CopyOnWriteArrayList<>();
        final AtomicBoolean writerClosed = new AtomicBoolean(false);

        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        public DailyFileWriterHandle openWriter(String laneId) {
            return new DailyFileWriterHandle() {
                @Override
                public String getLaneId() {
                    return laneId;
                }

                @Override
                public void append(DailyFileRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                    writerClosed.set(true);
                }
            };
        }

        @Override
        public DailyFileObservationMode getObservationMode() {
            return DailyFileObservationMode.FULL;
        }

        @Override
        public ZoneId getJournalZoneId() {
            return ZoneOffset.UTC;
        }

        @Override
        public boolean isPublished(String laneId, Instant startInclusive, Instant endExclusive) {
            return false;
        }
    }
}
