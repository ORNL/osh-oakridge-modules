/*******************************************************************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.
 ******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java wire-compatible RS-350 backpack detector TCP loopback micro-emulator
 * bound strictly to {@code 127.0.0.1} inside the ephemeral Hands-On Training Sandbox
 * ({@code OSCAR_SANDBOX_MODE=true}).
 *
 * <p>Pushes 1 {@code STX (0x02) + ANSI N42.42 (2011) XML + ETX (0x03)} frame per second
 * along a synthetic Oak Ridge waypoint loop with periodic {@code Cs-137} hotspot
 * {@code <DerivedData>} and {@code <AnalysisResults><RadAlarm>} blocks so trainees
 * observe live GPS trail movement, 1,024-channel gamma spectra, and alarm occupancies
 * with zero physical hardware.</p>
 */
public final class SandboxRs350LoopbackServer implements Closeable {

    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final String N42_NAMESPACE = "http://physics.nist.gov/N42/2011/N42";

    private static final DateTimeFormatter ISO_UTC_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final double METERS_PER_DEG_LAT = 111_320.0;
    private static final double TICK_SECONDS = 1.0;
    private static final double WALKER_SPEED_MPS = 18.0;
    private static final double BACKGROUND_GAMMA_CPS = 1_200.0;
    private static final double BACKGROUND_NEUTRON_CPS = 0.4;
    private static final double ALARM_FACTOR = 1.6;
    private static final int BACKGROUND_BLOCK_EVERY_TICKS = 30;
    private static final double ELEVATION_METERS = 260.0;

    // Synthetic 8-waypoint Oak Ridge patrol loop (~565 m circumference; ~31 s per lap at 18 m/s)
    private static final double[][] WAYPOINT_LOOP = new double[][]{
            {35.93120, -84.30900},
            {35.93177, -84.30929},
            {35.93201, -84.31000},
            {35.93177, -84.31071},
            {35.93120, -84.31100},
            {35.93063, -84.31071},
            {35.93039, -84.31000},
            {35.93063, -84.30929},
    };

    // Cs-137 point-source hotspot positioned on the northern waypoint (662 keV -> channel 220 at 3 keV/ch)
    static final double HOTSPOT_LAT = 35.93201;
    static final double HOTSPOT_LON = -84.31000;
    private static final double HOTSPOT_INTENSITY_CPS_AT_1M = 250_000.0;
    private static final String HOTSPOT_ISOTOPE = "Cs-137";
    private static final int CS137_PEAK_KEV = 662;

    private final int requestedPort;
    private final String label;
    private final String serialNumber;
    private final String deviceName;
    private final Logger log;

    private final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tickCounter = new AtomicLong(0L);
    private final Object stateLock = new Object();

    // Start on segment 1 approaching waypoint 2 so newly connected walkers encounter a hotspot early
    private int segmentIndex = 1;
    private double segmentPositionMeters = 0.0;
    private double batteryPercent = 98.0;

    private ServerSocket serverSocket;
    private ExecutorService acceptExecutor;
    private ScheduledExecutorService tickScheduler;

    private static final class ClientSession implements Closeable {
        final Socket socket;
        final OutputStream out;

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setTcpNoDelay(true);
            this.out = socket.getOutputStream();
        }

        synchronized void sendFrame(byte[] framedBytes) throws IOException {
            out.write(framedBytes);
            out.flush();
        }

        @Override
        public synchronized void close() {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public SandboxRs350LoopbackServer(int port, String label, Logger log) {
        this.requestedPort = port;
        this.label = (label == null || label.isBlank()) ? "RS350-WALKER1" : label.trim();
        this.serialNumber = "WALKER1";
        this.deviceName = "RS350-WALKER1";
        this.log = log;
    }

    public synchronized void start() throws IOException {
        if (!TrainingSandboxEnvironment.isSandboxMode()) {
            return;
        }
        if (running.get()) {
            return;
        }

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try {
            this.serverSocket = new ServerSocket(requestedPort, 8, loopback);
        } catch (IOException bindErr) {
            if (requestedPort == 0) {
                throw bindErr;
            }
            this.serverSocket = new ServerSocket(0, 8, loopback);
            if (log != null) {
                log.warn("Sandbox RS-350 port {} busy for {}; bound fallback 127.0.0.1:{}",
                        requestedPort, label, this.serverSocket.getLocalPort());
            }
        }
        running.set(true);

        this.acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oscar-sandbox-rs350-accept-" + label);
            t.setDaemon(true);
            return t;
        });
        this.tickScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oscar-sandbox-rs350-tick-" + label);
            t.setDaemon(true);
            return t;
        });

        acceptExecutor.submit(this::acceptLoop);
        tickScheduler.scheduleAtFixedRate(this::emitTickSafely, 1_000L, 1_000L, TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getListeningPort() {
        ServerSocket ss = this.serverSocket;
        return ss != null ? ss.getLocalPort() : requestedPort;
    }

    public int getConnectedClientCount() {
        return clients.size();
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket ss = this.serverSocket;
            if (ss == null || ss.isClosed()) {
                break;
            }
            try {
                Socket sock = ss.accept();
                ClientSession session = new ClientSession(sock);
                clients.add(session);
                sendInitialBootstrapFrame(session);
            } catch (IOException e) {
                if (running.get() && log != null) {
                    log.debug("Sandbox RS-350 accept loop ({}) closed: {}", label, e.getMessage());
                }
                break;
            }
        }
    }

    private void sendInitialBootstrapFrame(ClientSession session) {
        try {
            double lat;
            double lon;
            double battery;
            synchronized (stateLock) {
                double[] pos = currentPositionLocked();
                lat = pos[0];
                lon = pos[1];
                battery = batteryPercent;
            }
            String xml = buildDocumentXml(lat, lon, battery, true, Instant.now());
            session.sendFrame(frameXml(xml));
        } catch (IOException e) {
            clients.remove(session);
            session.close();
        }
    }

    private void emitTickSafely() {
        if (!running.get()) {
            return;
        }
        try {
            byte[] framed = stepAndBuildFrameBytes(false);
            if (clients.isEmpty()) {
                return;
            }
            List<ClientSession> snapshot = List.copyOf(clients);
            for (ClientSession session : snapshot) {
                try {
                    session.sendFrame(framed);
                } catch (IOException e) {
                    clients.remove(session);
                    session.close();
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.debug("Sandbox RS-350 tick warning ({}): {}", label, e.getMessage());
            }
        }
    }

    byte[] stepAndBuildFrameBytes(boolean forceBackground) {
        double lat;
        double lon;
        double battery;
        long tick = tickCounter.incrementAndGet();
        synchronized (stateLock) {
            advanceWalkerLocked(TICK_SECONDS);
            batteryPercent = Math.max(15.0, batteryPercent - 0.002);
            double[] pos = currentPositionLocked();
            lat = pos[0];
            lon = pos[1];
            battery = batteryPercent;
        }
        boolean includeBackground = forceBackground || (tick % BACKGROUND_BLOCK_EVERY_TICKS == 1L);
        String xml = buildDocumentXml(lat, lon, battery, includeBackground, Instant.now());
        return frameXml(xml);
    }

    static byte[] frameXml(String xml) {
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 2);
        out.write(STX);
        out.writeBytes(payload);
        out.write(ETX);
        return out.toByteArray();
    }

    String buildDocumentXml(
            double lat,
            double lon,
            double batteryPct,
            boolean includeBackground,
            Instant timestamp
    ) {
        String nowIso = ISO_UTC_SECONDS.format(timestamp);
        double expectedGamma = expectedGammaCps(lat, lon);
        boolean alarming = expectedGamma > (BACKGROUND_GAMMA_CPS * ALARM_FACTOR);
        int fgGammaCounts = samplePoisson(expectedGamma);
        int fgNeutronCounts = samplePoisson(BACKGROUND_NEUTRON_CPS);
        double doseUsvPerHour = expectedGamma / 12_000.0;
        String activeIsotope = alarming ? HOTSPOT_ISOTOPE : null;

        StringBuilder sb = new StringBuilder(8_192);
        sb.append("<RadInstrumentData xmlns=\"").append(N42_NAMESPACE).append("\">\n");
        sb.append("  <RadInstrumentInformation id=\"InstInfo\">\n");
        sb.append("    <RadInstrumentManufacturerName>Radiation Solutions Inc</RadInstrumentManufacturerName>\n");
        sb.append("    <RadInstrumentIdentifier>").append(serialNumber).append("</RadInstrumentIdentifier>\n");
        sb.append("    <RadInstrumentModelName>RS-350</RadInstrumentModelName>\n");
        sb.append("    <RadInstrumentClassCode>Backpack</RadInstrumentClassCode>\n");
        sb.append("    <RadInstrumentVersion><RadInstrumentComponentName>Software</RadInstrumentComponentName>")
                .append("<RadInstrumentComponentVersion>sim-1.0</RadInstrumentComponentVersion></RadInstrumentVersion>\n");
        sb.append("    <RadInstrumentCharacteristics>\n");
        appendCharacteristic(sb, "DeviceName", deviceName, "unit-less", "string");
        appendCharacteristic(sb, "BatteryCharge", String.format(Locale.ROOT, "%.0f", batteryPct), "percent", "double");
        sb.append("    </RadInstrumentCharacteristics>\n");
        sb.append("  </RadInstrumentInformation>\n");
        sb.append("  <RadDetectorInformation id=\"DetGamma\">")
                .append("<RadDetectorCategoryCode>Gamma</RadDetectorCategoryCode>")
                .append("<RadDetectorKindCode>NaI</RadDetectorKindCode></RadDetectorInformation>\n");
        sb.append("  <RadDetectorInformation id=\"DetNeutron\">")
                .append("<RadDetectorCategoryCode>Neutron</RadDetectorCategoryCode>")
                .append("<RadDetectorKindCode>He3</RadDetectorKindCode></RadDetectorInformation>\n");
        sb.append("  <RadItemInformation id=\"ItemInfo\">\n");
        sb.append("    <RadItemCharacteristics>\n");
        appendCharacteristic(sb, "ScanMode", "Search", "unit-less", "string");
        appendCharacteristic(sb, "ScanNumber", "1", "unit-less", "double");
        appendCharacteristic(sb, "ScanTimeoutNumber", "0", "unit-less", "double");
        appendCharacteristic(sb, "AnalysisEnabled", "1", "unit-less", "integer");
        sb.append("    </RadItemCharacteristics>\n");
        sb.append("  </RadItemInformation>\n");
        sb.append("  <EnergyCalibration id=\"LinEnCal\">\n");
        sb.append("    <CoefficientValues>0 3.0 0</CoefficientValues>\n");
        sb.append("  </EnergyCalibration>\n");
        sb.append("  <EnergyCalibration id=\"CmpEnCal\">\n");
        sb.append("    <CoefficientValues>0 12.0 0</CoefficientValues>\n");
        sb.append("  </EnergyCalibration>\n");

        if (includeBackground) {
            int bgGamma = samplePoisson(BACKGROUND_GAMMA_CPS);
            int bgNeutron = samplePoisson(BACKGROUND_NEUTRON_CPS);
            appendMeasurementBlock(sb, "Background", "Bkg", nowIso, bgGamma, bgNeutron, null, null, null);
        }

        appendMeasurementBlock(
                sb,
                "Foreground",
                "Fg",
                nowIso,
                fgGammaCounts,
                fgNeutronCounts,
                doseUsvPerHour,
                new double[]{lat, lon, ELEVATION_METERS},
                activeIsotope);

        if (alarming) {
            sb.append("  <DerivedData id=\"Derived\">\n");
            sb.append("    <Remark>Alarm</Remark>\n");
            sb.append("    <MeasurementClassCode>Foreground</MeasurementClassCode>\n");
            sb.append("    <StartDateTime>").append(nowIso).append("</StartDateTime>\n");
            sb.append("    <RealTimeDuration>PT1.0S</RealTimeDuration>\n");
            sb.append("  </DerivedData>\n");
            sb.append("  <AnalysisResults id=\"Analysis\">\n");
            sb.append("    <RadAlarm id=\"Alm1\" radDetectorInformationReferences=\"DetGamma\">\n");
            sb.append("      <RadAlarmCategoryCode>Gamma</RadAlarmCategoryCode>\n");
            sb.append("      <RadAlarmDescription>").append(HOTSPOT_ISOTOPE).append("</RadAlarmDescription>\n");
            sb.append("    </RadAlarm>\n");
            sb.append("  </AnalysisResults>\n");
        }

        sb.append("</RadInstrumentData>");
        return sb.toString();
    }

    private static void appendCharacteristic(
            StringBuilder sb,
            String name,
            String value,
            String units,
            String dataClass
    ) {
        sb.append("      <Characteristic><CharacteristicName>").append(name).append("</CharacteristicName>")
                .append("<CharacteristicValue>").append(value).append("</CharacteristicValue>")
                .append("<CharacteristicValueUnits>").append(units).append("</CharacteristicValueUnits>")
                .append("<CharacteristicValueDataClassCode>").append(dataClass).append("</CharacteristicValueDataClassCode>")
                .append("</Characteristic>\n");
    }

    private static void appendMeasurementBlock(
            StringBuilder sb,
            String classCode,
            String mid,
            String startIso,
            int gammaCounts,
            int neutronCounts,
            Double doseUsvh,
            double[] gps,
            String isotope
    ) {
        Integer linPeak = HOTSPOT_ISOTOPE.equals(isotope) ? (CS137_PEAK_KEV / 3) : null;
        Integer cmpPeak = linPeak != null ? (linPeak / 4) : null;
        int[] linChannels = buildSpectrum(gammaCounts, 1024, linPeak);
        int[] cmpChannels = buildSpectrum(gammaCounts, 256, cmpPeak);

        sb.append("  <RadMeasurement id=\"").append(mid).append("\">\n");
        sb.append("    <MeasurementClassCode>").append(classCode).append("</MeasurementClassCode>\n");
        sb.append("    <StartDateTime>").append(startIso).append("</StartDateTime>\n");
        sb.append("    <RealTimeDuration>PT1.0S</RealTimeDuration>\n");
        sb.append("    <Spectrum id=\"").append(mid).append("Lin\" radDetectorInformationReference=\"DetGamma\" ")
                .append("energyCalibrationReference=\"LinEnCal\">\n");
        sb.append("      <LiveTimeDuration>PT1.0S</LiveTimeDuration>\n");
        sb.append("      <ChannelData>");
        appendSpaceSeparatedInts(sb, linChannels);
        sb.append("</ChannelData>\n");
        sb.append("    </Spectrum>\n");
        sb.append("    <Spectrum id=\"").append(mid).append("Cmp\" radDetectorInformationReference=\"DetGamma\" ")
                .append("energyCalibrationReference=\"CmpEnCal\">\n");
        sb.append("      <LiveTimeDuration>PT1.0S</LiveTimeDuration>\n");
        sb.append("      <ChannelData>");
        appendSpaceSeparatedInts(sb, cmpChannels);
        sb.append("</ChannelData>\n");
        sb.append("    </Spectrum>\n");
        sb.append("    <GrossCounts id=\"").append(mid).append("Gamma\" radDetectorInformationReference=\"DetGamma\">\n");
        sb.append("      <LiveTimeDuration>PT1.0S</LiveTimeDuration>\n");
        sb.append("      <CountData>").append(gammaCounts).append("</CountData>\n");
        sb.append("    </GrossCounts>\n");
        sb.append("    <GrossCounts id=\"").append(mid).append("Neutron\" radDetectorInformationReference=\"DetNeutron\">\n");
        sb.append("      <LiveTimeDuration>PT1.0S</LiveTimeDuration>\n");
        sb.append("      <CountData>").append(neutronCounts).append("</CountData>\n");
        sb.append("    </GrossCounts>\n");

        if (doseUsvh != null) {
            sb.append("    <DoseRate id=\"").append(mid).append("Dose\" radDetectorInformationReference=\"DetGamma\">\n");
            sb.append("      <DoseRateValue>").append(String.format(Locale.ROOT, "%.4f", doseUsvh)).append("</DoseRateValue>\n");
            sb.append("    </DoseRate>\n");
        }
        if (gps != null && gps.length >= 3) {
            sb.append("    <RadInstrumentState radInstrumentInformationReference=\"InstInfo\">\n");
            sb.append("      <StateVector>\n");
            sb.append("        <GeographicPoint>\n");
            sb.append("          <LatitudeValue>").append(String.format(Locale.ROOT, "%.6f", gps[0])).append("</LatitudeValue>\n");
            sb.append("          <LongitudeValue>").append(String.format(Locale.ROOT, "%.6f", gps[1])).append("</LongitudeValue>\n");
            sb.append("          <ElevationValue>").append(String.format(Locale.ROOT, "%.1f", gps[2])).append("</ElevationValue>\n");
            sb.append("        </GeographicPoint>\n");
            sb.append("      </StateVector>\n");
            sb.append("    </RadInstrumentState>\n");
        }
        sb.append("  </RadMeasurement>\n");
    }

    private static void appendSpaceSeparatedInts(StringBuilder sb, int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(values[i]);
        }
    }

    static int[] buildSpectrum(int totalCounts, int channelCount, Integer peakChannel) {
        double decay = channelCount / 6.0;
        double[] weights = new double[channelCount];
        double weightSum = 0.0;
        for (int i = 0; i < channelCount; i++) {
            weights[i] = Math.exp(-i / decay);
        }
        if (peakChannel != null && peakChannel > 0 && peakChannel < channelCount) {
            int start = Math.max(0, peakChannel - 6);
            int end = Math.min(channelCount, peakChannel + 7);
            for (int i = start; i < end; i++) {
                double diff = i - peakChannel;
                weights[i] += 4.0 * Math.exp(-(diff * diff) / 8.0);
            }
        }
        for (double w : weights) {
            weightSum += w;
        }
        int[] counts = new int[channelCount];
        if (weightSum <= 0.0 || totalCounts <= 0) {
            return counts;
        }
        for (int i = 0; i < channelCount; i++) {
            counts[i] = (int) (totalCounts * weights[i] / weightSum);
        }
        return counts;
    }

    static double expectedGammaCps(double lat, double lon) {
        double dist = distanceMeters(lat, lon, HOTSPOT_LAT, HOTSPOT_LON);
        return BACKGROUND_GAMMA_CPS + (HOTSPOT_INTENSITY_CPS_AT_1M / Math.max(dist * dist, 1.0));
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * METERS_PER_DEG_LAT;
        double dx = (lon2 - lon1) * METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat1));
        return Math.hypot(dx, dy);
    }

    private static int samplePoisson(double lambda) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (lambda <= 0.0) {
            return 0;
        }
        if (lambda > 50.0) {
            return Math.max(0, (int) Math.round(lambda + Math.sqrt(lambda) * rng.nextGaussian()));
        }
        double limit = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    private void advanceWalkerLocked(double dtSeconds) {
        double remaining = WALKER_SPEED_MPS * dtSeconds;
        int n = WAYPOINT_LOOP.length;
        while (remaining > 0.0) {
            double[] a = WAYPOINT_LOOP[segmentIndex];
            double[] b = WAYPOINT_LOOP[(segmentIndex + 1) % n];
            double segLen = Math.max(distanceMeters(a[0], a[1], b[0], b[1]), 0.01);
            if (segmentPositionMeters + remaining < segLen) {
                segmentPositionMeters += remaining;
                remaining = 0.0;
            } else {
                remaining -= (segLen - segmentPositionMeters);
                segmentIndex = (segmentIndex + 1) % n;
                segmentPositionMeters = 0.0;
            }
        }
    }

    private double[] currentPositionLocked() {
        int n = WAYPOINT_LOOP.length;
        double[] a = WAYPOINT_LOOP[segmentIndex];
        double[] b = WAYPOINT_LOOP[(segmentIndex + 1) % n];
        double segLen = Math.max(distanceMeters(a[0], a[1], b[0], b[1]), 0.01);
        double f = Math.min(segmentPositionMeters / segLen, 1.0);
        return new double[]{
                a[0] + (b[0] - a[0]) * f,
                a[1] + (b[1] - a[1]) * f,
        };
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (tickScheduler != null) {
            tickScheduler.shutdownNow();
            tickScheduler = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (ClientSession session : clients) {
            session.close();
        }
        clients.clear();
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
            acceptExecutor = null;
        }
    }
}
