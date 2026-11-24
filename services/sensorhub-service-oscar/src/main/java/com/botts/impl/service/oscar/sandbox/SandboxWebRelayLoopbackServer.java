/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-Java HTTP/1.1 loopback micro-emulator for ControlByWeb 4-relay modules
 * ({@code WebRelay-Quad} {@code /stateFull.xml} and {@code /state.xml}) bound
 * strictly to {@code 127.0.0.1} inside the ephemeral Hands-On Training Sandbox.
 *
 * <p>Allows administrators in Lab {@code 3.5} to execute {@code Test Connection},
 * toggle {@code AUTO} / {@code PASS_THROUGH} / {@code MUTED} / {@code MANUAL}
 * operating modes, and trigger timed {@code R3} (Gamma + Sounder) and {@code R4}
 * (Neutron) pulses with zero physical relay hardware.</p>
 */
public final class SandboxWebRelayLoopbackServer implements Closeable {

    private final int requestedPort;
    private final String label;
    private final Logger log;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int[] relays = new int[]{0, 0, 0, 0};
    private final ScheduledFuture<?>[] pulseTimers = new ScheduledFuture<?>[4];

    private HttpServer server;
    private ScheduledExecutorService pulseExecutor;

    public SandboxWebRelayLoopbackServer(int port, String label, Logger log) {
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
            this.server = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 8);
        } catch (IOException bindErr) {
            if (requestedPort == 0) {
                throw bindErr;
            }
            this.server = HttpServer.create(new InetSocketAddress(loopback, 0), 8);
            if (log != null) {
                log.warn("Sandbox WebRelay port {} busy for {}; bound fallback 127.0.0.1:{}",
                        requestedPort, label, this.server.getAddress().getPort());
            }
        }

        this.pulseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oscar-sandbox-webrelay-" + label);
            t.setDaemon(true);
            return t;
        });

        this.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "oscar-sandbox-webrelay-http-" + label);
            t.setDaemon(true);
            return t;
        }));
        this.server.createContext("/stateFull.xml", this::handleStateXml);
        this.server.createContext("/state.xml", this::handleStateXml);
        this.server.start();
        running.set(true);
    }

    public int getListeningPort() {
        HttpServer s = this.server;
        return s != null ? s.getAddress().getPort() : requestedPort;
    }

    public synchronized int[] snapshotRelays() {
        return relays.clone();
    }

    private void handleStateXml(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String rawQuery = exchange.getRequestURI().getRawQuery();
            byte[] xmlBytes;
            synchronized (this) {
                if (rawQuery != null && !rawQuery.isBlank()) {
                    applyQueryMutations(rawQuery);
                }
                String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<datavalues>\n"
                        + "  <relay1state>" + relays[0] + "</relay1state>\n"
                        + "  <relay2state>" + relays[1] + "</relay2state>\n"
                        + "  <relay3state>" + relays[2] + "</relay3state>\n"
                        + "  <relay4state>" + relays[3] + "</relay4state>\n"
                        + "</datavalues>\n";
                xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, xmlBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(xmlBytes);
            }
        } finally {
            exchange.close();
        }
    }

    private void applyQueryMutations(String rawQuery) {
        int[] pulseSecs = new int[]{5, 5, 5, 5};
        Integer[] requestedStates = new Integer[4];

        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = pair.substring(eq + 1).trim();
            try {
                int num = Integer.parseInt(v);
                switch (k) {
                    case "relay1state" -> requestedStates[0] = num;
                    case "relay2state" -> requestedStates[1] = num;
                    case "relay3state" -> requestedStates[2] = num;
                    case "relay4state" -> requestedStates[3] = num;
                    case "pulsetime1" -> pulseSecs[0] = Math.max(1, Math.min(300, num));
                    case "pulsetime2" -> pulseSecs[1] = Math.max(1, Math.min(300, num));
                    case "pulsetime3" -> pulseSecs[2] = Math.max(1, Math.min(300, num));
                    case "pulsetime4" -> pulseSecs[3] = Math.max(1, Math.min(300, num));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        for (int idx = 0; idx < 4; idx++) {
            Integer req = requestedStates[idx];
            if (req == null) {
                continue;
            }
            cancelPulseTimer(idx);
            if (req == 0) {
                relays[idx] = 0;
            } else if (req == 1) {
                relays[idx] = 1;
            } else if (req == 2) {
                relays[idx] = 1;
                final int relayIdx = idx;
                ScheduledExecutorService exec = this.pulseExecutor;
                if (exec != null && running.get()) {
                    pulseTimers[idx] = exec.schedule(() -> {
                        synchronized (SandboxWebRelayLoopbackServer.this) {
                            relays[relayIdx] = 0;
                            pulseTimers[relayIdx] = null;
                        }
                    }, pulseSecs[idx], TimeUnit.SECONDS);
                }
            }
        }
    }

    private void cancelPulseTimer(int idx) {
        ScheduledFuture<?> prev = pulseTimers[idx];
        if (prev != null) {
            prev.cancel(false);
            pulseTimers[idx] = null;
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            cancelPulseTimer(i);
        }
        if (pulseExecutor != null) {
            pulseExecutor.shutdownNow();
            pulseExecutor = null;
        }
        if (server != null) {
            var httpExec = server.getExecutor();
            server.stop(0);
            if (httpExec instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
            server = null;
        }
    }
}
