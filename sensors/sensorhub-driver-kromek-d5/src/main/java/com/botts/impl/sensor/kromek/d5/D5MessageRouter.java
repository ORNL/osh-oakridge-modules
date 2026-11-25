/*
 * The contents of this file are subject to the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one
 * at http://mozilla.org/MPL/2.0/.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the License.
 *
 * Copyright (c) 2023 Botts Innovative Research, Inc. All Rights Reserved.
 */

package com.botts.impl.sensor.kromek.d5;

import com.botts.impl.sensor.kromek.d5.reports.KromekSerialRadiometricStatusReport;
import com.botts.impl.sensor.kromek.d5.reports.SerialReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.botts.impl.sensor.kromek.d5.Shared.sendRequest;

/**
 * Sends SLIP-framed requests and processes responses from the Kromek D5 every
 * polling period (1 Hz by default), triggering automatic transport recovery
 * when consecutive polling cycles fail completely.
 *
 * @author Michael Elmore
 * @since Oct. 2023
 */
public class D5MessageRouter implements Runnable {
    Thread worker;
    D5Sensor sensor;
    D5Config config;
    InputStream inputStream;
    OutputStream outputStream;

    private static final Logger logger = LoggerFactory.getLogger(D5Sensor.class);
    private static final long STOP_TIMEOUT_MILLIS = 2_000L;
    static final int FAILED_CYCLES_BEFORE_RECONNECT = 3;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kromek-d5-router");
        t.setDaemon(true);
        return t;
    });
    private int count = 0;
    private int consecutiveFailedCycles = 0;
    private volatile boolean connectionLost = false;

    public D5MessageRouter(D5Sensor sensor, InputStream inputStream, OutputStream outputStream) {
        this.sensor = sensor;
        this.inputStream = inputStream;
        this.outputStream = outputStream;

        config = sensor.getConfiguration();
        worker = new Thread(this, "Message Router");
    }

    public void start() {
        long periodMillis = sensor.routerPollPeriodMs();
        scheduler.scheduleAtFixedRate(this, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isStopped() {
        return scheduler.isShutdown() && scheduler.isTerminated();
    }

    @Override
    public synchronized void run() {
        if (sensor.processLock || connectionLost) {
            return;
        }

        int attempted = 0;
        int failed = 0;
        Exception lastError = null;

        for (Map.Entry<Class<?>, D5Output> entry : sensor.outputs.entrySet()) {
            Class<?> reportClass = entry.getKey();
            D5Output output = entry.getValue();

            try {
                var report = (SerialReport) reportClass.getDeclaredConstructor().newInstance();

                if (count != 0 && report.getPollingRate() == 0) {
                    continue;
                } else if (count != 0 && count % report.getPollingRate() != 0) {
                    continue;
                }

                attempted++;
                report = sendRequest(report, inputStream, outputStream);

                if (report != null) {
                    output.setData(report);

                    if (report instanceof KromekSerialRadiometricStatusReport statusReport) {
                        sensor.publishLocation(statusReport);
                    }
                }
            } catch (Exception e) {
                failed++;
                lastError = e;
                if (consecutiveFailedCycles == 0 && failed == 1) {
                    logger.error("D5 {} request failed", config.serialNumber, e);
                } else {
                    logger.debug("D5 {} request failed", config.serialNumber, e);
                }
            }
        }
        count++;

        if (attempted > 0 && failed == attempted) {
            if (++consecutiveFailedCycles >= FAILED_CYCLES_BEFORE_RECONNECT) {
                connectionLost = true;
                sensor.onCommunicationLost(lastError);
            }
        } else if (attempted > 0) {
            if (consecutiveFailedCycles > 0) {
                logger.info("D5 {} responding again", config.serialNumber);
            }
            consecutiveFailedCycles = 0;
        }
    }
}
