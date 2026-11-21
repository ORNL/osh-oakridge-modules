/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java wire-compatible Rapiscan ASCII TCP loopback micro-emulator bound strictly
 * to {@code 127.0.0.1} inside the ephemeral Hands-On Training Sandbox.
 *
 * <p>Emits authentic daily setup records ({@code SG1}–{@code SG3}, {@code SN1}–{@code SN2},
 * {@code TC}), steady 1 Hz {@code GB}/{@code NB} background counts, and deterministic
 * on-demand occupancy, alarm, NORM ({@code Code 9999}), extended-occupancy ({@code Code 9998}),
 * and fault/tamper scenarios.</p>
 */
public final class SandboxRapiscanLoopbackServer implements Closeable {

    public static final Set<String> SUPPORTED_SCENARIOS = Set.of(
            "clean_pass",
            "gamma_alarm",
            "neutron_alarm",
            "gamma_neutron_alarm",
            "norm_eml",
            "extended_occupancy",
            "fault_gamma_high",
            "fault_tamper",
            "clear_faults"
    );

    private final int requestedPort;
    private final String label;
    private final Logger log;
    private final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger occupancySeq = new AtomicInteger(100);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean busyOccupancy = new AtomicBoolean(false);
    private volatile String activeFaultCode = null; // "GH" or null
    private volatile boolean tamperActive = false;

    private ServerSocket serverSocket;
    private ExecutorService acceptAndInjectExecutor;
    private ScheduledExecutorService tickScheduler;

    private static final class ClientSession implements Closeable {
        final Socket socket;
        final BufferedWriter writer;

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setTcpNoDelay(true);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        }

        synchronized void sendLine(String line) throws IOException {
            writer.write(line);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public synchronized void close() {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public SandboxRapiscanLoopbackServer(int port, String label, Logger log) {
        this.requestedPort = port;
        this.label = label;
        this.log = log;
    }

    public synchronized void start() throws IOException {
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
            // Fall back to an ephemeral loopback port only if requestedPort is already bound in tests
            this.serverSocket = new ServerSocket(0, 8, loopback);
            if (log != null) {
                log.warn("Sandbox Rapiscan port {} busy for {}; bound fallback 127.0.0.1:{}",
                        requestedPort, label, this.serverSocket.getLocalPort());
            }
        }
        running.set(true);

        this.acceptAndInjectExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "oscar-sandbox-rpm-" + label);
            t.setDaemon(true);
            return t;
        });
        this.tickScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oscar-sandbox-rpm-tick-" + label);
            t.setDaemon(true);
            return t;
        });

        acceptAndInjectExecutor.submit(this::acceptLoop);
        tickScheduler.scheduleAtFixedRate(this::emitBackgroundTickSafely, 350L, 1_000L, TimeUnit.MILLISECONDS);
    }

    public int getListeningPort() {
        ServerSocket ss = this.serverSocket;
        return ss != null ? ss.getLocalPort() : requestedPort;
    }

    public int getConnectedClientCount() {
        return clients.size();
    }

    public boolean isBusyOccupancy() {
        return busyOccupancy.get();
    }

    public boolean hasActiveFaultOrTamper() {
        return activeFaultCode != null || tamperActive;
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
                sendInitialHandshake(session);
            } catch (IOException e) {
                if (running.get() && log != null) {
                    log.debug("Sandbox Rapiscan accept loop ({}) closed: {}", label, e.getMessage());
                }
                break;
            }
        }
    }

    private void sendInitialHandshake(ClientSession session) {
        try {
            session.sendLine("SG1,50000,50,5,1,6.0");
            session.sendLine("SG2,4,0.05,3.00,1,NSIGMA,4.2.0");
            session.sendLine("SG3,0.05,3.00,30,1.0,2.1.4");
            session.sendLine("SN1,500,5,0.001,4.0,2,30");
            session.sendLine("SN2,0.10,3.00,0.10,3.00");
            session.sendLine(tamperActive ? "TT" : "TC");
            session.sendLine("GB,186,191,188,185");
            session.sendLine("NB,1,0,1,0");
        } catch (IOException e) {
            clients.remove(session);
            session.close();
        }
    }

    private void emitBackgroundTickSafely() {
        if (!running.get() || busyOccupancy.get() || clients.isEmpty()) {
            return;
        }
        String fault = this.activeFaultCode;
        if ("GH".equals(fault)) {
            broadcast("GH,68500,192,188,186");
            broadcast("NB,1,0,1,0");
            return;
        }
        int jitter = (int) ((System.currentTimeMillis() / 1000L) % 5L) - 2;
        broadcast(String.format(Locale.ROOT, "GB,%d,%d,%d,%d",
                186 + jitter, 190 - jitter, 188 + jitter, 186 - jitter));
        broadcast("NB,1,0,1,0");
    }

    public String injectScenarioAsync(String rawScenario) {
        String scenario = rawScenario == null ? "" : rawScenario.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCENARIOS.contains(scenario)) {
            throw new IllegalArgumentException("Unsupported sandbox scenario: " + rawScenario);
        }

        switch (scenario) {
            case "fault_gamma_high" -> {
                this.activeFaultCode = "GH";
                broadcast("GH,68500,192,188,186");
                return "Injected Fault - Gamma High (GH) on " + label;
            }
            case "fault_tamper" -> {
                this.tamperActive = true;
                broadcast("TT");
                return "Injected Tamper Alarm (TT) on " + label;
            }
            case "clear_faults" -> {
                this.activeFaultCode = null;
                this.tamperActive = false;
                broadcast("TC");
                broadcast("GB,186,190,188,186");
                broadcast("NB,1,0,1,0");
                return "Cleared faults and tamper state on " + label;
            }
            default -> {
                if (!busyOccupancy.compareAndSet(false, true)) {
                    throw new IllegalStateException("An occupancy pass is already in progress on " + label + "; wait a few seconds and retry.");
                }
                int occNum = occupancySeq.incrementAndGet();
                acceptAndInjectExecutor.submit(() -> {
                    try {
                        runOccupancyScenario(scenario, occNum);
                    } finally {
                        busyOccupancy.set(false);
                    }
                });
                return "Started practice scenario '" + scenario + "' (Occupancy #" + occNum + ") on " + label;
            }
        }
    }

    private void runOccupancyScenario(String scenario, int occNum) {
        try {
            // Ensure fresh background baseline right before entry
            broadcast("GB,186,190,188,186");
            broadcast("NB,1,0,1,0");
            sleepQuietly(120L);

            switch (scenario) {
                case "clean_pass" -> {
                    broadcast("SP,0.45,11.2,18.0");
                    for (int i = 0; i < 12; i++) {
                        broadcast("GS,37,38,38,37");
                        if (i % 4 == 0) {
                            broadcast("NS,0,1,0,0");
                        }
                        sleepQuietly(180L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
                case "gamma_alarm" -> {
                    // Localized high-intensity point-source spike on detector 1 -> active Gamma Alarm
                    broadcast("SP,0.52,8.4,13.5");
                    for (int i = 0; i < 4; i++) {
                        broadcast("GS,38,39,37,38");
                        sleepQuietly(180L);
                    }
                    broadcast("NS,1,0,0,1");
                    for (int i = 0; i < 8; i++) {
                        int peak = (i >= 2 && i <= 5) ? 640 : 280;
                        broadcast("GA," + peak + ",46,43,41");
                        sleepQuietly(180L);
                    }
                    for (int i = 0; i < 3; i++) {
                        broadcast("GS,39,38,37,38");
                        sleepQuietly(180L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
                case "neutron_alarm" -> {
                    broadcast("SP,0.58,7.6,12.2");
                    for (int i = 0; i < 5; i++) {
                        broadcast("GS,40,41,39,40");
                        sleepQuietly(180L);
                    }
                    for (int i = 0; i < 4; i++) {
                        broadcast("NA,18,16,19,15");
                        broadcast("GS,42,41,40,41");
                        sleepQuietly(200L);
                    }
                    for (int i = 0; i < 3; i++) {
                        broadcast("GS,38,39,38,37");
                        sleepQuietly(180L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
                case "gamma_neutron_alarm" -> {
                    broadcast("SP,0.61,6.9,11.1");
                    for (int i = 0; i < 3; i++) {
                        broadcast("GS,39,40,38,39");
                        sleepQuietly(180L);
                    }
                    for (int i = 0; i < 7; i++) {
                        broadcast("GA,710,55,48,44");
                        if (i % 2 == 0) {
                            broadcast("NA,22,19,21,18");
                        }
                        sleepQuietly(180L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
                case "norm_eml" -> {
                    // Spatially uniform 4-panel distributed cargo elevation (e.g. K-40 fertilizer) -> Code 9999
                    broadcast("SP,0.55,8.1,13.0");
                    for (int i = 0; i < 3; i++) {
                        broadcast("GS,39,40,39,38");
                        sleepQuietly(160L);
                    }
                    broadcast("NS,0,1,0,0");
                    for (int i = 0; i < 10; i++) {
                        broadcast("GA,128,132,130,126");
                        sleepQuietly(160L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
                case "extended_occupancy" -> {
                    // Holds lane occupied beyond sandbox extended-occupancy threshold (default 8s in sandbox)
                    // with gamma-only alarm so Fault - Extended Occupancy + Code 9998 fire authentically
                    int holdSeconds = Math.max(3, Integer.getInteger("oscar.sandbox.extendedOccupancySeconds", 8)) + 2;
                    int totalSlices = holdSeconds * 5;
                    broadcast("SP,1.20,1.4,2.3");
                    for (int i = 0; i < totalSlices && running.get(); i++) {
                        if (i >= 5 && i <= 15) {
                            broadcast("GA,390,48,44,42");
                        } else {
                            broadcast("GS,41,40,39,40");
                        }
                        if (i % 10 == 0) {
                            broadcast("NS,0,1,0,0");
                        }
                        sleepQuietly(200L);
                    }
                    broadcast("GX," + occNum + ",1000");
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warn("Sandbox scenario '{}' interrupted on {}: {}", scenario, label, e.getMessage());
            }
        }
    }

    private void broadcast(String line) {
        List<ClientSession> snapshot = List.copyOf(clients);
        for (ClientSession session : snapshot) {
            try {
                session.sendLine(line);
            } catch (IOException e) {
                clients.remove(session);
                session.close();
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
        if (acceptAndInjectExecutor != null) {
            acceptAndInjectExecutor.shutdownNow();
            acceptAndInjectExecutor = null;
        }
    }
}
