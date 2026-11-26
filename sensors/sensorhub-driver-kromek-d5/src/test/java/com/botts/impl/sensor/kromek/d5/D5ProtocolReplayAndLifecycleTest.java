package com.botts.impl.sensor.kromek.d5;

import com.botts.impl.sensor.kromek.d5.enums.KromekSerialDosemeterSource;
import com.botts.impl.sensor.kromek.d5.enums.KromekSerialPowerSource;
import com.botts.impl.sensor.kromek.d5.reports.Constants;
import com.botts.impl.sensor.kromek.d5.reports.KromekDetectorRadiometricsV1Report;
import com.botts.impl.sensor.kromek.d5.reports.KromekSerialDoseInfoReport;
import com.botts.impl.sensor.kromek.d5.reports.KromekSerialRadiometricStatusReport;
import com.botts.impl.sensor.kromek.d5.reports.KromekSerialStatusReport;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataRecord;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.comm.TCPCommProviderConfig;
import org.sensorhub.impl.comm.TCPConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD_EXT;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_FRAMING_ESC_BYTE;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_FRAMING_FRAME_BYTE;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_MESSAGE_MODE;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_ACK_REPORT_ID_ERROR;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_ACK_ID;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_DOSE_INFO_ID;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_RADIOMETRICS_V1_ID;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_RADIOMETRIC_STATUS_REPORT;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_SPECTRUM_MAX_BINS;
import static com.botts.impl.sensor.kromek.d5.reports.Constants.KROMEK_SERIAL_REPORTS_IN_STATUS_ID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Self-contained Kromek D5 SLIP binary serial/TCP frame replay, 4096-channel radiometrics
 * spectrum/count/dose decoding, corrupt/truncated frame recovery, and reader stop lifecycle tests.
 */
public class D5ProtocolReplayAndLifecycleTest {

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
    public void slipFramingRoundTripsEscapedControlBytesAndRejectsTruncatedOrInvalidFrames()
            throws Exception {
        byte[] rawDataWithControls = new byte[]{
                0x09, 0x00, 0x00, 0x07,
                KROMEK_SERIAL_FRAMING_FRAME_BYTE, // 0xC0 inside payload/header
                KROMEK_SERIAL_FRAMING_ESC_BYTE,   // 0xDB inside payload/header
                (byte) 0xDC, (byte) 0xDD, 0x42
        };

        byte[] encoded = Shared.encodeSLIP(rawDataWithControls);
        assertEquals(KROMEK_SERIAL_FRAMING_FRAME_BYTE, encoded[0]);
        assertEquals(KROMEK_SERIAL_FRAMING_FRAME_BYTE, encoded[encoded.length - 1]);

        // Prepend line noise + extra idle 0xC0 framing flags before the encoded frame
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(new byte[]{0x13, 0x55, KROMEK_SERIAL_FRAMING_FRAME_BYTE, KROMEK_SERIAL_FRAMING_FRAME_BYTE});
        wire.write(encoded);

        byte[] receivedBody = Shared.receiveData(new ByteArrayInputStream(wire.toByteArray()));
        byte[] decoded = Shared.decodeSLIP(receivedBody);
        assertArrayEquals(rawDataWithControls, decoded);

        // Invalid SLIP escape byte (0xDB followed by 0x00) and dangling trailing 0xDB must throw
        assertThrows(IllegalArgumentException.class,
                () -> Shared.decodeSLIP(new byte[]{KROMEK_SERIAL_FRAMING_ESC_BYTE, 0x00}));
        assertThrows(IllegalArgumentException.class,
                () -> Shared.decodeSLIP(new byte[]{0x01, KROMEK_SERIAL_FRAMING_ESC_BYTE}));

        // EOF before start byte, EOF right after start byte, and EOF mid-frame must throw EOFException
        assertThrows(EOFException.class,
                () -> Shared.receiveData(new ByteArrayInputStream(new byte[]{0x01, 0x02})));
        assertThrows(EOFException.class,
                () -> Shared.receiveData(new ByteArrayInputStream(new byte[]{KROMEK_SERIAL_FRAMING_FRAME_BYTE})));
        assertThrows(EOFException.class,
                () -> Shared.receiveData(new ByteArrayInputStream(
                        new byte[]{KROMEK_SERIAL_FRAMING_FRAME_BYTE, 0x07, 0x00, 0x00})));

        // Declared length mismatch inside valid SLIP delimiters must throw IOException in sendRequest
        byte[] lengthMismatch = Shared.encodeSLIP(new byte[]{0x20, 0x00, 0x00, 0x07, (byte) 0xD3, 0x00, 0x00});
        assertThrows(IOException.class, () -> Shared.sendRequest(
                new KromekSerialDoseInfoReport(),
                new ByteArrayInputStream(lengthMismatch),
                new ByteArrayOutputStream()));
    }

    @Test
    public void binaryReportDecodingParses4096ChannelSpectrumCountsDoseAndIgnoresAckFrames()
            throws Exception {
        byte[] radiometricsFrame = buildSlipFrame(
                KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD,
                KROMEK_SERIAL_REPORTS_IN_RADIOMETRICS_V1_ID,
                buildRadiometricsV1Payload(
                        0x0000_0001L,
                        15_000L,
                        99L,
                        1.25f,
                        0.45f,
                        3.75f,
                        14_800L,
                        18L,
                        2_450, // 24.50 C
                        14_900L,
                        2_840L,
                        2_625, // 26.25 C
                        12));

        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        var parsedRad = (KromekDetectorRadiometricsV1Report) Shared.sendRequest(
                new KromekDetectorRadiometricsV1Report(),
                new ByteArrayInputStream(radiometricsFrame),
                outbound);
        assertNotNull(parsedRad);
        assertTrue("Expected outbound SLIP request bytes", outbound.size() >= 9);

        DataRecord radRecord = parsedRad.createDataRecord();
        DataBlock radBlock = radRecord.createDataBlock();
        parsedRad.setDataBlock(radBlock, radRecord, 1_700_000_000.5);

        assertEquals(1_700_000_000.5, radBlock.getDoubleValue(0), 0.001);
        assertEquals(1L, radBlock.getLongValue(1)); // status
        assertEquals(15_000L, radBlock.getLongValue(2)); // realTimeMs
        assertEquals(99L, radBlock.getLongValue(3)); // sequenceNumber
        assertEquals(1.25, radBlock.getDoubleValue(4), 0.001); // dose (uSv)
        assertEquals(0.45, radBlock.getDoubleValue(5), 0.001); // doseRate (uSv/h)
        assertEquals(3.75, radBlock.getDoubleValue(6), 0.001); // doseUserAccumulated
        assertEquals(14_800L, radBlock.getLongValue(7)); // neutronLiveTime
        assertEquals(18L, radBlock.getLongValue(8)); // neutronCounts
        assertEquals(24.50, radBlock.getDoubleValue(9), 0.01); // neutronTemperature
        assertEquals(14_900L, radBlock.getLongValue(10)); // gammaLiveTime
        assertEquals(2_840L, radBlock.getLongValue(11)); // gammaCounts
        assertEquals(26.25, radBlock.getDoubleValue(12), 0.01); // gammaTemperature
        assertEquals(12, radBlock.getIntValue(13)); // spectrumBitsSize
        // Spectrum bins start at index 14: verify bin 0 (0x00C0 = 192), bin 1 (0x00DB = 219), bin 4095 (0xFFFF = 65535)
        assertEquals(192, radBlock.getIntValue(14));
        assertEquals(219, radBlock.getIntValue(15));
        assertEquals(65_535, radBlock.getIntValue(14 + KROMEK_SERIAL_REPORTS_IN_SPECTRUM_MAX_BINS - 1));

        // Dose Info report
        byte[] doseFrame = buildSlipFrame(
                KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD,
                KROMEK_SERIAL_REPORTS_IN_DOSE_INFO_ID,
                buildDoseInfoPayload(125.5f, 1.5f, 0.75f, 0.18f,
                        KromekSerialDosemeterSource.KROMEK_DOSEMETER_SOURCE_HIGH_DOSE_DETECTOR));
        var parsedDose = (KromekSerialDoseInfoReport) Shared.sendRequest(
                new KromekSerialDoseInfoReport(),
                new ByteArrayInputStream(doseFrame),
                new ByteArrayOutputStream());
        assertNotNull(parsedDose);
        DataRecord doseRecord = parsedDose.createDataRecord();
        DataBlock doseBlock = doseRecord.createDataBlock();
        parsedDose.setDataBlock(doseBlock, doseRecord, 1_700_000_001.0);
        assertEquals(125.5, doseBlock.getDoubleValue(1), 0.001);
        assertEquals(1.5, doseBlock.getDoubleValue(2), 0.001);
        assertEquals(0.75, doseBlock.getDoubleValue(3), 0.001);
        assertEquals(0.18, doseBlock.getDoubleValue(4), 0.001);
        assertEquals("KROMEK_DOSEMETER_SOURCE_HIGH_DOSE_DETECTOR", doseBlock.getStringValue(5));

        // ACK and ACK_ERROR frames must return null cleanly without throwing
        byte[] ackFrame = buildSlipFrame(KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD,
                KROMEK_SERIAL_REPORTS_IN_ACK_ID, new byte[]{0x00});
        byte[] ackErrFrame = buildSlipFrame(KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD,
                KROMEK_SERIAL_REPORTS_ACK_REPORT_ID_ERROR, new byte[]{0x01});
        assertNull(Shared.sendRequest(new KromekSerialDoseInfoReport(),
                new ByteArrayInputStream(ackFrame), new ByteArrayOutputStream()));
        assertNull(Shared.sendRequest(new KromekSerialDoseInfoReport(),
                new ByteArrayInputStream(ackErrFrame), new ByteArrayOutputStream()));
    }

    @Test
    public void fullD5SensorOverLoopbackTcpRecoversFromCorruptFrameHonorsPollingRateAndStopsCleanly()
            throws Exception {
        LoopbackD5TcpServer server = track(new LoopbackD5TcpServer());
        // Corrupt the very first RadiometricsV1 frame to verify router resilience and recovery on tick 2
        server.corruptFirstRadiometricsResponse.set(true);

        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        D5Config config = (D5Config) reg.createModuleConfig(new D5Descriptor());
        config.id = "kromek-d5-01";
        config.name = "Kromek D5 Handheld 01";
        config.serialNumber = "D5-SN-9001";
        config.outputs.enableKromekDetectorRadiometricsV1Report = true;
        config.outputs.enableKromekSerialRadiometricStatusReport = true;
        config.outputs.enableKromekSerialDoseInfoReport = true;
        config.outputs.enableKromekSerialStatusReport = true; // pollingRate = 10

        TCPCommProviderConfig comm = new TCPCommProviderConfig();
        comm.connection.connectTimeout = 1_500;
        comm.connection.reconnectAttempts = 2;
        comm.connection.checkReachability = false;
        TCPConfig tcp = new TCPConfig();
        tcp.remoteHost = "127.0.0.1";
        tcp.remotePort = server.getPort();
        comm.protocol = tcp;
        config.commSettings = comm;

        D5Sensor sensor = (D5Sensor) reg.loadModule(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        assertEquals("urn:osh:sensor:kromek:d5:D5-SN-9001", sensor.getUniqueIdentifier());
        assertFalse("Sensor must report disconnected before start", sensor.isConnected());

        // Verify distinct output registration names (no static "Report" collision!)
        assertNotNull(sensor.getOutputs().get("KromekDetectorRadiometricsV1Report"));
        assertNotNull(sensor.getOutputs().get("KromekSerialRadiometricStatusReport"));
        assertNotNull(sensor.getOutputs().get("KromekSerialDoseInfoReport"));
        assertNotNull(sensor.getOutputs().get("KromekSerialStatusReport"));

        List<DataBlock> radEvents = new CopyOnWriteArrayList<>();
        List<DataBlock> radStatusEvents = new CopyOnWriteArrayList<>();
        List<DataBlock> doseEvents = new CopyOnWriteArrayList<>();
        List<DataBlock> devStatusEvents = new CopyOnWriteArrayList<>();
        CountDownLatch recoveredRadLatch = new CountDownLatch(1);

        IEventListener listener = event -> {
            if (event instanceof DataEvent de) {
                String outName = de.getSource().getName();
                DataBlock block = de.getRecords()[0];
                switch (outName) {
                    case "KromekDetectorRadiometricsV1Report" -> {
                        radEvents.add(block);
                        recoveredRadLatch.countDown();
                    }
                    case "KromekSerialRadiometricStatusReport" -> radStatusEvents.add(block);
                    case "KromekSerialDoseInfoReport" -> doseEvents.add(block);
                    case "KromekSerialStatusReport" -> devStatusEvents.add(block);
                    default -> {
                    }
                }
            }
        };

        sensor.getOutputs().values().forEach(out -> out.registerListener(listener));

        reg.startModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
        assertTrue("Sensor must report connected while commProvider is active", sensor.isConnected());

        // Tick 0 corrupts RadiometricsV1, then Tick 1 (~2s) recovers RadiometricsV1
        assertTrue("Expected RadiometricsV1 recovery after initial corrupt frame",
                recoveredRadLatch.await(5, TimeUnit.SECONDS));

        assertFalse("RadiometricStatus output should have published", radStatusEvents.isEmpty());
        assertFalse("DoseInfo output should have published", doseEvents.isEmpty());
        // KromekSerialStatusReport has pollingRate = 10 -> published on tick 0, skipped on ticks 1..9
        assertEquals("StatusReport (pollingRate=10) must only fire once across initial ticks",
                1, devStatusEvents.size());

        DataBlock statusBlock = devStatusEvents.get(0);
        assertEquals("KROMEK_POWER_SOURCE_USB", statusBlock.getStringValue(2));
        assertEquals(27, statusBlock.getIntValue(3)); // temperature
        assertEquals(92, statusBlock.getIntValue(6)); // batteryLevel

        DataBlock radStatusBlock = radStatusEvents.get(0);
        assertTrue(radStatusBlock.getBooleanValue(2)); // gammaCpsAlarmActive
        assertEquals(35.9311f, radStatusBlock.getFloatValue(4), 0.001f);
        assertEquals(-84.3102f, radStatusBlock.getFloatValue(5), 0.001f);

        D5MessageRouter routerRef = sensor.messageRouter;
        reg.stopModule(sensor.getLocalID());
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 5_000));
        assertTrue("D5MessageRouter scheduler must terminate cleanly on stop", routerRef.isStopped());
        assertFalse("Sensor must report disconnected after stop", sensor.isConnected());
    }

    @Test
    public void locationOutputDefaultsDisabledFiltersZeroCoordsAndSensorAutoReconnectsAfterPeerDrop()
            throws Exception {
        assertFalse("D5ConfigOutputs.enableLocationOutput must default to false",
                new D5ConfigOutputs().enableLocationOutput);

        // Unit-level D5LocationOutput checks: (0,0) ignored, ms timestamps, latestRecord populated
        D5Sensor unitSensor = new D5Sensor() {
            @Override
            public String getUniqueIdentifier() {
                return "urn:osh:sensor:kromek:d5:test-01";
            }
        };
        D5LocationOutput locOut = new D5LocationOutput(unitSensor);
        locOut.doInit();
        List<DataEvent> locEvents = new CopyOnWriteArrayList<>();
        locOut.registerListener(ev -> {
            if (ev instanceof DataEvent de) {
                locEvents.add(de);
            }
        });
        locOut.onNewFix(0.0, 0.0);
        assertEquals(0, locEvents.size());
        assertNull(locOut.getLatestRecord());

        long beforeMs = System.currentTimeMillis();
        locOut.onNewFix(35.9311, -84.3102);
        long afterMs = System.currentTimeMillis();
        assertEquals(1, locEvents.size());
        assertTrue(locEvents.get(0).getTimeStamp() >= beforeMs && locEvents.get(0).getTimeStamp() <= afterMs);
        assertNotNull(locOut.getLatestRecord());
        assertEquals(35.9311, locOut.getLatestRecord().getDoubleValue(1), 1e-6);
        assertEquals(-84.3102, locOut.getLatestRecord().getDoubleValue(2), 1e-6);
        assertFalse(locOut.getRecordDescription().hasData());

        // End-to-end TCP drop & automatic reconnect test with fast poll/reconnect intervals
        LoopbackD5TcpServer server = track(new LoopbackD5TcpServer());
        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        D5Config config = new D5Config();
        config.id = "kromek-d5-reconnect";
        config.name = "Kromek D5 Reconnect Walker";
        config.serialNumber = "D5-RECONN-01";
        config.outputs.enableKromekDetectorRadiometricsV1Report = false;
        config.outputs.enableKromekSerialRadiometricStatusReport = true;
        config.outputs.enableLocationOutput = true;

        TCPCommProviderConfig comm = new TCPCommProviderConfig();
        comm.connection.connectTimeout = 1_500;
        comm.connection.reconnectAttempts = 1;
        comm.connection.checkReachability = false;
        TCPConfig tcp = new TCPConfig();
        tcp.remoteHost = "127.0.0.1";
        tcp.remotePort = server.getPort();
        comm.protocol = tcp;
        config.commSettings = comm;

        D5Sensor sensor = new D5Sensor() {
            @Override long routerPollPeriodMs() { return 80L; }
            @Override long reconnectInitialDelayMs() { return 80L; }
        };
        sensor.setParentHub(hub);
        sensor.setConfiguration(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        assertNotNull("Expected location output when enableLocationOutput=true",
                sensor.getOutputs().get("location"));

        CountDownLatch initialFixLatch = new CountDownLatch(1);
        AtomicBoolean postDropPhase = new AtomicBoolean(false);
        CountDownLatch postReconnectFixLatch = new CountDownLatch(1);

        sensor.getOutputs().get("location").registerListener(ev -> {
            if (ev instanceof DataEvent) {
                initialFixLatch.countDown();
                if (postDropPhase.get()) {
                    postReconnectFixLatch.countDown();
                }
            }
        });

        sensor.start();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
        assertTrue("Expected initial location fix over TCP", initialFixLatch.await(5, TimeUnit.SECONDS));

        // Force-close active client sockets on the server while keeping ServerSocket open for reconnect
        server.dropActiveClients();
        postDropPhase.set(true);

        assertTrue("Expected D5Sensor to detect 3 failed cycles, reconnect, and resume publishing fixes",
                postReconnectFixLatch.await(5, TimeUnit.SECONDS));

        sensor.stop();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 5_000));
    }

    private <T extends Closeable> T track(T closeable) {
        resources.add(closeable);
        return closeable;
    }

    private static byte[] buildSlipFrame(byte componentId, byte reportId, byte[] payload) {
        int totalLen = Constants.KROMEK_SERIAL_MESSAGE_OVERHEAD
                + Constants.KROMEK_SERIAL_REPORTS_HEADER_OVERHEAD
                + payload.length;
        ByteBuffer raw = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        raw.putShort((short) totalLen);
        raw.put(KROMEK_SERIAL_MESSAGE_MODE);
        raw.put(componentId);
        raw.put(reportId);
        raw.put(payload);
        raw.putShort((short) 0x1234); // 2-byte CRC trailer
        return Shared.encodeSLIP(raw.array());
    }

    private static byte[] buildRadiometricsV1Payload(
            long status, long realTimeMs, long seq, float dose, float doseRate, float userDose,
            long neutronLiveMs, long neutronCounts, int neutronTempHundredths,
            long gammaLiveMs, long gammaCounts, int gammaTempHundredths, int bitsSize) {
        ByteBuffer buf = ByteBuffer.allocate(54 + KROMEK_SERIAL_REPORTS_IN_SPECTRUM_MAX_BINS * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt((int) status);
        buf.putInt((int) realTimeMs);
        buf.putInt((int) seq);
        buf.putFloat(dose);
        buf.putFloat(doseRate);
        buf.putFloat(userDose);
        buf.putInt((int) neutronLiveMs);
        buf.putInt((int) neutronCounts);
        buf.putShort((short) neutronTempHundredths);
        buf.putFloat(0.0f); // neutronReserved
        buf.putInt((int) gammaLiveMs);
        buf.putInt((int) gammaCounts);
        buf.putShort((short) gammaTempHundredths);
        buf.putFloat(0.0f); // gammaReserved
        buf.put((byte) bitsSize);
        buf.put((byte) 0); // spectrumReserved

        // Populate spectrum bins including 0x00C0 (SLIP frame byte), 0x00DB (SLIP escape byte), and 0xFFFF
        buf.putShort((short) 0x00C0);
        buf.putShort((short) 0x00DB);
        for (int i = 2; i < KROMEK_SERIAL_REPORTS_IN_SPECTRUM_MAX_BINS - 1; i++) {
            buf.putShort((short) (i % 50));
        }
        buf.putShort((short) 0xFFFF);
        return buf.array();
    }

    private static byte[] buildDoseInfoPayload(float lifetime, float powerUp, float user,
                                               float rate, KromekSerialDosemeterSource source) {
        ByteBuffer buf = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(lifetime);
        buf.putFloat(powerUp);
        buf.putFloat(user);
        buf.putFloat(rate);
        buf.putInt(0);
        buf.put((byte) source.ordinal());
        return buf.array();
    }

    private static byte[] buildRadiometricStatusPayload() {
        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0); // doseAlarmActive = false
        buf.put((byte) 1); // gammaCpsAlarmActive = true
        buf.put((byte) 0); // neutronCpsAlarmActive = false
        buf.putFloat(35.9311f);
        buf.putFloat(-84.3102f);
        buf.putInt(1_700_000_100);
        buf.putShort((short) 1);
        buf.put(new byte[]{1, 2, 95});
        return buf.array();
    }

    private static byte[] buildDeviceStatusPayload() {
        ByteBuffer buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0); // appStatus
        buf.put((byte) KromekSerialPowerSource.KROMEK_POWER_SOURCE_USB.ordinal());
        buf.put((byte) 27); // temperature C
        buf.put((byte) 0); // detectorStatus0
        buf.put((byte) 0); // detectorStatus1
        buf.put((byte) 92); // batteryLevel %
        buf.put((byte) 5); // batteryChargeRate
        buf.put((byte) 29); // batteryTemperature
        buf.put((byte) 0x01); // usbStatus
        buf.put((byte) 0x00); // btStatus
        buf.put((byte) 0); // detectorStatus2
        return buf.array();
    }

    private static final class LoopbackD5TcpServer implements Closeable {
        final AtomicBoolean corruptFirstRadiometricsResponse = new AtomicBoolean(false);
        final AtomicInteger radiometricsRequests = new AtomicInteger(0);
        private final ServerSocket serverSocket;
        private final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean closed = new AtomicBoolean();

        LoopbackD5TcpServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(() -> {
                while (!closed.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        clients.add(client);
                        Thread worker = new Thread(() -> serveClient(client), "kromek-d5-loopback-client");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException e) {
                        if (!closed.get()) {
                            break;
                        }
                    }
                }
            }, "kromek-d5-loopback-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void serveClient(Socket client) {
            try (InputStream in = client.getInputStream();
                 OutputStream out = client.getOutputStream()) {
                while (!closed.get() && !client.isClosed()) {
                    byte[] reqBody = Shared.receiveData(in);
                    byte[] decodedReq = Shared.decodeSLIP(reqBody);
                    byte compId = decodedReq[3];
                    byte reportId = decodedReq[4];

                    byte[] reply;
                    if (reportId == KROMEK_SERIAL_REPORTS_IN_RADIOMETRICS_V1_ID) {
                        int n = radiometricsRequests.incrementAndGet();
                        if (n == 1 && corruptFirstRadiometricsResponse.getAndSet(false)) {
                            // Deliberately malformed SLIP frame (declared length 99 != actual 7)
                            reply = Shared.encodeSLIP(new byte[]{99, 0, 0, compId, reportId, 0, 0});
                        } else {
                            reply = buildSlipFrame(compId, reportId, buildRadiometricsV1Payload(
                                    1L, 20_000L, n, 0.85f, 0.22f, 2.10f,
                                    19_900L, 4L, (short) 2500, 19_950L, 1_420L, (short) 2550, 12));
                        }
                    } else if (reportId == KROMEK_SERIAL_REPORTS_IN_RADIOMETRIC_STATUS_REPORT) {
                        reply = buildSlipFrame(compId, reportId, buildRadiometricStatusPayload());
                    } else if (reportId == KROMEK_SERIAL_REPORTS_IN_DOSE_INFO_ID) {
                        reply = buildSlipFrame(compId, reportId, buildDoseInfoPayload(
                                50.0f, 1.2f, 0.4f, 0.15f,
                                KromekSerialDosemeterSource.KROMEK_DOSEMETER_SOURCE_GAMMA_DETECTOR));
                    } else if (reportId == KROMEK_SERIAL_REPORTS_IN_STATUS_ID) {
                        reply = buildSlipFrame(compId, reportId, buildDeviceStatusPayload());
                    } else {
                        reply = buildSlipFrame(KROMEK_SERIAL_COMPONENT_INTERFACE_BOARD_EXT,
                                KROMEK_SERIAL_REPORTS_IN_ACK_ID, new byte[]{0});
                    }

                    out.write(reply);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }

        void dropActiveClients() {
            synchronized (clients) {
                for (Socket s : clients) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                clients.clear();
            }
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            dropActiveClients();
            serverSocket.close();
        }
    }
}
