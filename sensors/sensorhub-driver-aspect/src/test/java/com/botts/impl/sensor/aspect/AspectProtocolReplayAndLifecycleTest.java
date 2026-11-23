package com.botts.impl.sensor.aspect;

import com.botts.impl.sensor.aspect.comm.ModbusTCPCommProviderConfig;
import com.botts.impl.sensor.aspect.comm.ModbusTCPConfig;
import com.botts.impl.sensor.aspect.enums.ChannelStatus;
import com.botts.impl.sensor.aspect.enums.Inputs;
import com.botts.impl.sensor.aspect.output.ConnectionStatusOutput;
import com.botts.impl.sensor.aspect.registers.DeviceDescriptionRegisters;
import com.botts.impl.sensor.aspect.registers.MonitorRegisters;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import net.opengis.swe.v20.DataBlock;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileObservationMode;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileRecord;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileWriterHandle;
import org.sensorhub.impl.utils.rad.dailyfile.IDailyFileJournalService;
import org.sensorhub.impl.utils.rad.model.Occupancy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Self-contained Modbus TCP wire-protocol replay, register decoding, malformed-packet resilience,
 * journal capture, and start/stop lifecycle tests for {@link AspectSensor}.
 */
public class AspectProtocolReplayAndLifecycleTest {

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
    public void modbusTcpFrameParsingDecodesDeviceDescriptionAndMonitorRegisters() throws Exception {
        LoopbackModbusServer server = track(new LoopbackModbusServer(2));
        server.enqueueMonitorFrame(MonitorSample.of(
                123_456,
                Inputs.Occup0.getValue(),
                ChannelStatus.Alarm1.getValue(),
                4_321,
                118.5f,
                14.25f,
                ChannelStatus.LowCount.getValue(),
                9,
                1.75f,
                0.5f,
                77,
                4,
                5_400,
                0x0003));
        server.enqueueMonitorFrame(MonitorSample.of(
                123_500,
                Inputs.None.getValue(),
                ChannelStatus.HighCount.getValue(),
                100,
                115.0f,
                12.0f,
                ChannelStatus.HighCount.getValue(),
                1,
                1.5f,
                0.25f,
                77,
                4,
                0,
                0));
        server.enqueueMonitorFrame(MonitorSample.of(
                123_600,
                Inputs.None.getValue(),
                ChannelStatus.LowCount.getValue(),
                2,
                115.0f,
                12.0f,
                ChannelStatus.None.getValue(),
                1,
                1.5f,
                0.25f,
                77,
                4,
                0,
                0));

        TCPMasterConnection conn = new TCPMasterConnection(InetAddress.getLoopbackAddress());
        conn.setPort(server.getPort());
        conn.setTimeout(1_500);
        conn.connect();
        resources.add(conn::close);

        DeviceDescriptionRegisters desc = new DeviceDescriptionRegisters(conn);
        desc.readRegisters(2);
        assertEquals(0xA5EC, desc.getSignature());
        assertEquals(8, desc.getNumberOfRegisters());
        assertEquals(1, desc.getMonitorRegistersIdentifier());
        assertEquals(100, desc.getMonitorRegistersBaseAddress());
        assertEquals(21, desc.getMonitorRegistersNumberOfRegisters());

        MonitorRegisters monitor = new MonitorRegisters(conn, 100, 21);
        monitor.readRegisters(2);
        assertEquals(123_456, monitor.getTimeElapsed());
        assertTrue(monitor.isOccupied());
        assertTrue(monitor.isGammaAlarm());
        assertFalse(monitor.isNeutronAlarm());
        assertEquals("Alarm", monitor.getGammaAlarmState());
        assertEquals("Fault - Neutron Low", monitor.getNeutronAlarmState());
        assertEquals(4_321, monitor.getGammaChannelCount());
        assertEquals(118.5f, monitor.getGammaChannelBackground(), 0.001f);
        assertEquals(14.25, monitor.getGammaChannelVariance(), 0.001);
        assertEquals(9, monitor.getNeutronChannelCount());
        assertEquals(1.75f, monitor.getNeutronChannelBackground(), 0.001f);
        assertEquals(0.5f, monitor.getNeutronChannelVariance(), 0.001f);
        assertEquals(77, monitor.getObjectCounter());
        assertEquals(4, monitor.getObjectMark());
        assertEquals(5_400, monitor.getObjectSpeed());
        assertEquals(0x0003, monitor.getOutputSignals());

        monitor.readRegisters(2);
        assertFalse(monitor.isOccupied());
        assertEquals("Fault - Gamma High", monitor.getGammaAlarmState());
        assertEquals("Fault - Neutron High", monitor.getNeutronAlarmState());

        monitor.readRegisters(2);
        assertEquals("Fault - Gamma Low", monitor.getGammaAlarmState());
        assertEquals("Background", monitor.getNeutronAlarmState());
    }

    @Test
    public void malformedTruncatedAndExceptionFramesThrowModbusExceptionWithoutCorruptingState() throws Exception {
        LoopbackModbusServer server = track(new LoopbackModbusServer(1));
        server.enqueueMonitorFrame(MonitorSample.background(1_000, 110, 2, 10));
        // j2mod ModbusTCPTransaction executes 1 initial attempt + 3 default retries = 4 wire attempts
        server.enqueueExceptionTransaction(0x02); // Illegal Data Address Modbus exception (FC | 0x80)
        server.enqueueRawCorruptMonitorFrame(new byte[]{0x00, 0x01}); // Truncated MBAP header + close

        TCPMasterConnection conn = new TCPMasterConnection(InetAddress.getLoopbackAddress());
        conn.setPort(server.getPort());
        conn.setTimeout(250);
        conn.connect();
        resources.add(conn::close);

        MonitorRegisters monitor = new MonitorRegisters(conn, 100, 21);
        monitor.readRegisters(1);
        assertEquals(110, monitor.getGammaChannelCount());
        assertEquals(10, monitor.getObjectCounter());

        // Modbus exception response must fail cleanly without overwriting prior valid state
        assertThrows(ModbusException.class, () -> monitor.readRegisters(1));
        assertEquals(110, monitor.getGammaChannelCount());
        assertEquals(10, monitor.getObjectCounter());

        // Truncated MBAP packet must fail cleanly with ModbusException
        assertThrows(ModbusException.class, () -> monitor.readRegisters(1));
    }

    @Test
    public void fullSensorReplayHandlesAddressScanOccupancyAlarmJournalAndCleanStop() throws Exception {
        // Unit #1 will reject requests; Unit #2 is the active Aspect RPM
        LoopbackModbusServer server = track(new LoopbackModbusServer(2));

        // Baseline unoccupied sample, then transient Modbus exception transaction, then occupancy entry -> alarm -> exit
        server.enqueueMonitorFrame(MonitorSample.background(10_000, 120, 2, 41));
        server.enqueueExceptionTransaction(0x04); // Slave Device Failure across 1 transaction mid-stream
        server.enqueueMonitorFrame(MonitorSample.of(
                10_200,
                Inputs.Occup0.getValue(),
                ChannelStatus.None.getValue(),
                460,
                118.0f,
                11.0f,
                ChannelStatus.None.getValue(),
                3,
                1.5f,
                0.4f,
                42,
                1,
                6_400,
                0x0001));
        server.enqueueMonitorFrame(MonitorSample.of(
                10_400,
                Inputs.Occup0.getValue() | Inputs.Occup1.getValue(),
                ChannelStatus.Alarm1.getValue(),
                3_950,
                118.0f,
                11.0f,
                ChannelStatus.Alarm2.getValue(),
                52,
                1.5f,
                0.4f,
                42,
                2,
                6_100,
                0x0003));
        server.enqueueMonitorFrame(MonitorSample.background(10_600, 125, 2, 42));
        // Hold steady background while awaiting test assertions
        server.setDefaultSteadySample(MonitorSample.background(10_800, 122, 2, 42), 25L);

        hub = new SensorHub();
        hub.start();
        var reg = hub.getModuleRegistry();

        ModuleConfig journalCfg = new ModuleConfig();
        journalCfg.id = "test-daily-journal";
        journalCfg.name = "Test Daily Journal";
        journalCfg.moduleClass = RecordingJournalService.class.getName();
        RecordingJournalService journalService = (RecordingJournalService) reg.loadModule(journalCfg);
        journalService.init();
        reg.startModule(journalService.getLocalID());
        assertTrue(journalService.waitForState(ModuleEvent.ModuleState.STARTED, 3_000));

        AspectConfig config = (AspectConfig) reg.createModuleConfig(new AspectDescriptor());
        config.id = "aspect-lane-01";
        config.name = "Aspect Lane 01";
        config.serialNumber = "aspect-sn-01";
        config.laneId = 1;
        config.dailyFileJournal.enabled = true;
        config.dailyFileJournal.required = true;
        config.dailyFileJournal.startupTimeoutSeconds = 2;

        config.commSettings = new ModbusTCPCommProviderConfig();
        config.commSettings.connection.connectTimeout = 1_500;
        config.commSettings.connection.reconnectAttempts = 2;
        config.commSettings.connection.reconnectPeriod = 5_000;
        config.commSettings.connection.checkReachability = false;
        ModbusTCPConfig modbusCfg = new ModbusTCPConfig();
        modbusCfg.remoteHost = "127.0.0.1";
        modbusCfg.remotePort = server.getPort();
        modbusCfg.addressRange = new ModbusTCPConfig.AddressRange();
        modbusCfg.addressRange.from = 1;
        modbusCfg.addressRange.to = 4;
        config.commSettings.protocol = modbusCfg;

        AspectSensor sensor = (AspectSensor) reg.loadModule(config);
        sensor.init();
        assertTrue(sensor.waitForState(ModuleEvent.ModuleState.INITIALIZED, 5_000));
        List<DataBlock> gammaRecords = new CopyOnWriteArrayList<>();
        List<DataBlock> neutronRecords = new CopyOnWriteArrayList<>();
        List<DataBlock> speedRecords = new CopyOnWriteArrayList<>();
        List<DataBlock> dailyRecords = new CopyOnWriteArrayList<>();
        CountDownLatch occupancyPublished = new CountDownLatch(1);
        List<DataBlock> occupancyRecords = new CopyOnWriteArrayList<>();

        IEventListener listener = event -> {
            if (event instanceof DataEvent dataEvent) {
                String outName = dataEvent.getSource().getName();
                for (DataBlock block : dataEvent.getRecords()) {
                    switch (outName) {
                        case "gammaCounts" -> gammaRecords.add(block);
                        case "neutronCounts" -> neutronRecords.add(block);
                        case "speed" -> speedRecords.add(block);
                        case "dailyFile" -> dailyRecords.add(block);
                        case "occupancy" -> {
                            occupancyRecords.add(block);
                            occupancyPublished.countDown();
                        }
                        default -> {
                        }
                    }
                }
            }
        };

        sensor.getGammaOutput().registerListener(listener);
        sensor.getNeutronOutput().registerListener(listener);
        sensor.getSpeedOutput().registerListener(listener);
        sensor.getDailyFileOutput().registerListener(listener);
        sensor.getOccupancyOutput().registerListener(listener);

        reg.startModule(sensor.getLocalID());
        assertTrue("AspectSensor did not enter STARTED state",
                sensor.waitForState(ModuleEvent.ModuleState.STARTED, 5_000));
        assertEquals("Address scan should skip failing unit #1 and select unit #2",
                2, sensor.primaryDeviceAddress);

        // Speed up scan cadence for fast deterministic replay
        sensor.messageHandler.scanIntervalMs = 1L;
        sensor.messageHandler.backgroundIntervalMs = 20L;

        assertTrue("Expected completed Aspect occupancy event",
                occupancyPublished.await(5, TimeUnit.SECONDS));

        Occupancy occ = Occupancy.toOccupancy(occupancyRecords.get(0));
        assertEquals(42, occ.getOccupancyCount());
        double occStart = occ.getStartTime();
        double occEnd = occ.getEndTime();
        assertTrue("Occupancy start/end timestamps must be ordered", occStart > 0 && occEnd >= occStart);
        assertEquals(1.5, occ.getNeutronBackground(), 0.001);
        assertTrue("Expected gamma alarm latched on occupancy", occ.hasGammaAlarm());
        assertTrue("Expected neutron alarm latched on occupancy", occ.hasNeutronAlarm());
        assertEquals(3_950, occ.getMaxGammaCount());
        assertEquals(52, occ.getMaxNeutronCount());

        assertFalse("Expected gamma observations during scan", gammaRecords.isEmpty());
        assertTrue("Expected at least one Alarm state in gamma stream",
                gammaRecords.stream().anyMatch(b -> "Alarm".equals(b.getStringValue(1)) && b.getIntValue(2) == 3_950));
        assertTrue("Expected at least one Alarm state in neutron stream",
                neutronRecords.stream().anyMatch(b -> "Alarm".equals(b.getStringValue(1)) && b.getIntValue(2) == 52));
        assertFalse("Expected speed observations during occupied scan", speedRecords.isEmpty());
        assertFalse("Expected legacy dailyFile observations", dailyRecords.isEmpty());
        assertFalse("Expected durable daily-file journal records", journalService.records.isEmpty());
        assertFalse("Journal writer must stay open while sensor is running", journalService.writerClosed.get());

        // Verify live heartbeat published connected=true on the started sensor's connectionStatus output
        assertNotNull(sensor.getConnectionStatusOutput().getLatestRecord());
        assertTrue(sensor.getConnectionStatusOutput().getLatestRecord().getBooleanValue(1));

        // Verify ConnectionStatusOutput heartbeat deduplication and immediate state-change publication
        ConnectionStatusOutput isolatedConnOut = new ConnectionStatusOutput(sensor);
        isolatedConnOut.init();
        List<Boolean> connStates = new CopyOnWriteArrayList<>();
        CountDownLatch threeConnEvents = new CountDownLatch(3);
        isolatedConnOut.registerListener(e -> {
            if (e instanceof DataEvent de) {
                connStates.add(de.getRecords()[0].getBooleanValue(1));
                threeConnEvents.countDown();
            }
        });
        isolatedConnOut.onNewMessage(true, 1_000L);
        isolatedConnOut.onNewMessage(true, 5_000L); // suppressed (< 30s)
        isolatedConnOut.onNewMessage(true,
                1_000L + ConnectionStatusOutput.HEARTBEAT_INTERVAL_MILLIS); // heartbeat
        isolatedConnOut.onNewMessage(false,
                1_500L + ConnectionStatusOutput.HEARTBEAT_INTERVAL_MILLIS); // immediate transition
        assertTrue(threeConnEvents.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(true, true, false), connStates);

        Thread readerThread = sensor.messageHandler.thread;
        reg.stopModule(sensor.getLocalID());
        assertTrue("AspectSensor did not enter STOPPED state",
                sensor.waitForState(ModuleEvent.ModuleState.STOPPED, 5_000));
        assertFalse("Aspect reader thread must terminate on stop", readerThread.isAlive());
        assertTrue("Daily-file journal writer must close on stop", journalService.writerClosed.get());
    }

    @Test
    public void offlineInitPreservesUniqueIdentifierAndEmptyAddressRangeThrowsConnectException() throws Exception {
        AspectConfig offlineCfg = new AspectConfig();
        offlineCfg.id = "aspect-offline";
        offlineCfg.serialNumber = "lane-09";
        AspectSensor offlineSensor = new AspectSensor() {
            @Override
            public void tryConnection() throws SensorHubException {
                throw new SensorHubException("Simulated offline Aspect RPM");
            }
        };
        offlineSensor.setConfiguration(offlineCfg);

        assertThrows(SensorHubException.class, offlineSensor::doInit);
        assertEquals("urn:osh:sensor:aspect:lane-09", offlineSensor.getUniqueIdentifier());
        assertNotNull(offlineSensor.getUniqueIdentifier());

        // Address range where no unit responds must throw ConnectException cleanly
        LoopbackModbusServer emptyServer = track(new LoopbackModbusServer(99));
        TCPMasterConnection conn = new TCPMasterConnection(InetAddress.getLoopbackAddress());
        conn.setPort(emptyServer.getPort());
        conn.setTimeout(500);
        conn.connect();
        resources.add(conn::close);

        AspectConfig scanCfg = new AspectConfig();
        scanCfg.serialNumber = "scan-empty";
        scanCfg.commSettings = new ModbusTCPCommProviderConfig();
        ModbusTCPConfig proto = new ModbusTCPConfig();
        proto.addressRange = new ModbusTCPConfig.AddressRange();
        proto.addressRange.from = 1;
        proto.addressRange.to = 3;
        scanCfg.commSettings.protocol = proto;

        AspectSensor scanSensor = new AspectSensor();
        scanSensor.setConfiguration(scanCfg);
        scanSensor.commProviderModule = new com.botts.impl.sensor.aspect.comm.ModbusTCPCommProvider() {
            @Override
            public TCPMasterConnection getConnection() {
                return conn;
            }
        };
        assertThrows(ConnectException.class, scanSensor::initMsgHandler);
    }

    private <T extends Closeable> T track(T closeable) {
        resources.add(closeable);
        return closeable;
    }

    private record MonitorSample(
            int timeElapsed,
            int inputSignals,
            int gammaStatus,
            int gammaCount,
            float gammaBg,
            float gammaVar,
            int neutronStatus,
            int neutronCount,
            float neutronBg,
            float neutronVar,
            int objectCount,
            int objectMark,
            int objectSpeed,
            int outputSignals
    ) {
        static MonitorSample of(int timeElapsed, int inputSignals, int gammaStatus, int gammaCount,
                                float gammaBg, float gammaVar, int neutronStatus, int neutronCount,
                                float neutronBg, float neutronVar, int objectCount, int objectMark,
                                int objectSpeed, int outputSignals) {
            return new MonitorSample(timeElapsed, inputSignals, gammaStatus, gammaCount, gammaBg, gammaVar,
                    neutronStatus, neutronCount, neutronBg, neutronVar, objectCount, objectMark,
                    objectSpeed, outputSignals);
        }

        static MonitorSample background(int timeElapsed, int gammaCount, int neutronCount, int objectCount) {
            return new MonitorSample(timeElapsed, Inputs.None.getValue(), ChannelStatus.None.getValue(),
                    gammaCount, 118.0f, 11.0f, ChannelStatus.None.getValue(), neutronCount, 1.5f, 0.4f,
                    objectCount, 0, 0, 0);
        }

        short[] toRegisters() {
            short[] regs = new short[21];
            putIntLEWords(regs, 0, timeElapsed);
            regs[2] = (short) inputSignals;
            regs[3] = (short) gammaStatus;
            putIntLEWords(regs, 4, gammaCount);
            putFloatLEWords(regs, 6, gammaBg);
            putFloatLEWords(regs, 8, gammaVar);
            regs[10] = (short) neutronStatus;
            putIntLEWords(regs, 11, neutronCount);
            putFloatLEWords(regs, 13, neutronBg);
            putFloatLEWords(regs, 15, neutronVar);
            regs[17] = (short) objectCount;
            regs[18] = (short) objectMark;
            regs[19] = (short) objectSpeed;
            regs[20] = (short) outputSignals;
            return regs;
        }

        private static void putIntLEWords(short[] regs, int offset, int value) {
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
            regs[offset] = bb.getShort(0);
            regs[offset + 1] = bb.getShort(2);
        }

        private static void putFloatLEWords(short[] regs, int offset, float value) {
            ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value);
            regs[offset] = bb.getShort(0);
            regs[offset + 1] = bb.getShort(2);
        }
    }

    private sealed interface ServerReply permits NormalMonitorReply, ExceptionReply, RawCorruptReply {}
    private record NormalMonitorReply(MonitorSample sample) implements ServerReply {}
    private record ExceptionReply(int exceptionCode) implements ServerReply {}
    private record RawCorruptReply(byte[] rawBytes) implements ServerReply {}

    /**
     * Minimal deterministic loopback Modbus TCP server speaking raw MBAP frames for FC=0x03.
     */
    private static final class LoopbackModbusServer implements Closeable {
        private final int validUnitId;
        private final ServerSocket serverSocket;
        private final Queue<ServerReply> scriptedReplies = new ConcurrentLinkedQueue<>();
        private final List<Socket> clientSockets = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile MonitorSample steadySample = MonitorSample.background(1, 100, 1, 1);
        private volatile long steadyDelayMillis = 5L;

        LoopbackModbusServer(int validUnitId) throws IOException {
            this.validUnitId = validUnitId;
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread acceptThread = new Thread(this::acceptLoop, "aspect-loopback-modbus-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void enqueueMonitorFrame(MonitorSample sample) {
            scriptedReplies.add(new NormalMonitorReply(sample));
        }

        void enqueueExceptionTransaction(int exceptionCode) {
            // j2mod ModbusTCPTransaction performs 1 initial attempt + 3 default retries = 4 requests
            for (int i = 0; i < 4; i++) {
                scriptedReplies.add(new ExceptionReply(exceptionCode));
            }
        }

        void enqueueRawCorruptMonitorFrame(byte[] rawBytes) {
            scriptedReplies.add(new RawCorruptReply(rawBytes));
        }

        void setDefaultSteadySample(MonitorSample sample, long delayMillis) {
            this.steadySample = sample;
            this.steadyDelayMillis = delayMillis;
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket client = serverSocket.accept();
                    clientSockets.add(client);
                    Thread worker = new Thread(() -> serveClient(client), "aspect-loopback-modbus-client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!closed.get()) {
                        break;
                    }
                }
            }
        }

        private void serveClient(Socket client) {
            try (DataInputStream in = new DataInputStream(client.getInputStream());
                 OutputStream out = client.getOutputStream()) {
                while (!closed.get() && !client.isClosed()) {
                    int txId = in.readUnsignedShort();
                    int protoId = in.readUnsignedShort();
                    int length = in.readUnsignedShort();
                    if (protoId != 0 || length < 6) {
                        break;
                    }
                    int unitId = in.readUnsignedByte();
                    int fc = in.readUnsignedByte();
                    int ref = in.readUnsignedShort();
                    int wordCount = in.readUnsignedShort();
                    if (length > 6) {
                        in.skipBytes(length - 6);
                    }

                    if (unitId != validUnitId || fc != 0x03) {
                        writeException(out, txId, unitId, fc, 0x02);
                        continue;
                    }

                    if (ref == 0 && wordCount == 8) {
                        short[] descRegs = new short[]{
                                (short) 0xA5EC, 8, 1, 100, 21, 0, 0, 0
                        };
                        writeRegisters(out, txId, unitId, fc, descRegs);
                    } else if (ref == 100 && wordCount == 21) {
                        ServerReply next = scriptedReplies.poll();
                        if (next instanceof ExceptionReply ex) {
                            writeException(out, txId, unitId, fc, ex.exceptionCode());
                        } else if (next instanceof RawCorruptReply corrupt) {
                            out.write(corrupt.rawBytes());
                            out.flush();
                            // Close socket after corrupt raw frame so j2mod sees truncated MBAP frame immediately
                            client.close();
                            return;
                        } else if (next instanceof NormalMonitorReply normal) {
                            if (steadyDelayMillis > 0) {
                                Thread.sleep(Math.min(10L, steadyDelayMillis));
                            }
                            writeRegisters(out, txId, unitId, fc, normal.sample().toRegisters());
                        } else {
                            if (steadyDelayMillis > 0) {
                                Thread.sleep(steadyDelayMillis);
                            }
                            writeRegisters(out, txId, unitId, fc, steadySample.toRegisters());
                        }
                    } else {
                        writeException(out, txId, unitId, fc, 0x02);
                    }
                }
            } catch (EOFException | InterruptedException ignored) {
            } catch (IOException ignored) {
            }
        }

        private static void writeRegisters(OutputStream out, int txId, int unitId, int fc, short[] regs)
                throws IOException {
            int byteCount = regs.length * 2;
            int pduPlusUnit = 3 + byteCount;
            ByteBuffer buf = ByteBuffer.allocate(6 + pduPlusUnit).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) txId);
            buf.putShort((short) 0);
            buf.putShort((short) pduPlusUnit);
            buf.put((byte) unitId);
            buf.put((byte) fc);
            buf.put((byte) byteCount);
            for (short reg : regs) {
                buf.putShort(reg);
            }
            out.write(buf.array());
            out.flush();
        }

        private static void writeException(OutputStream out, int txId, int unitId, int fc, int exCode)
                throws IOException {
            ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) txId);
            buf.putShort((short) 0);
            buf.putShort((short) 3);
            buf.put((byte) unitId);
            buf.put((byte) (fc | 0x80));
            buf.put((byte) exCode);
            out.write(buf.array());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            synchronized (clientSockets) {
                for (Socket s : clientSockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                clientSockets.clear();
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
