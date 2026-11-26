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

import com.botts.impl.sensor.kromek.d5.reports.*;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sensor driver for the Kromek D5 providing sensor description, output registration,
 * initialization, automatic communication recovery, and shutdown of the driver and outputs.
 *
 * @author Michael Elmore
 * @since Oct. 2023
 */
public class D5Sensor extends AbstractSensorModule<D5Config> {
    private static final Logger logger = LoggerFactory.getLogger(D5Sensor.class);

    HashMap<Class<?>, D5Output> outputs;
    D5LocationOutput locationOutput;
    volatile ICommProvider<?> commProvider;
    volatile boolean processLock = false;
    D5MessageRouter messageRouter;

    static final long RECONNECT_INITIAL_DELAY_MS = 1_000L;
    static final long RECONNECT_MAX_DELAY_MS = 30_000L;
    static final long DEFAULT_ROUTER_POLL_PERIOD_MS = 1_000L;

    private final Object connectionLock = new Object();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean stopping;
    private volatile Thread reconnectThread;

    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        generateUniqueID("urn:osh:sensor:kromek:d5:", config.serialNumber);
        generateXmlID("KROMEK_D5_", config.serialNumber);

        outputs = new HashMap<>();
        createOutputs();
    }

    @Override
    public void doStart() throws SensorHubException {
        stopping = false;
        processLock = false;
        ICommProvider<?> provider = openCommProvider();
        synchronized (connectionLock) {
            if (stopping) {
                stopQuietly(provider);
                return;
            }
            installConnection(provider);
        }
    }

    @Override
    public void doStop() throws SensorHubException {
        logger.info("Stopping sensor");
        stopping = true;
        processLock = true;

        synchronized (connectionLock) {
            closeConnection();
        }
        Thread reconnect = reconnectThread;
        if (reconnect != null) {
            reconnect.interrupt();
        }
        if (outputs != null) {
            for (D5Output output : outputs.values()) {
                output.doStop();
            }
        }
    }

    @Override
    public boolean isConnected() {
        ICommProvider<?> provider = commProvider;
        return provider != null && provider.isInitialized();
    }

    long reconnectInitialDelayMs() {
        return RECONNECT_INITIAL_DELAY_MS;
    }

    long routerPollPeriodMs() {
        return DEFAULT_ROUTER_POLL_PERIOD_MS;
    }

    void onCommunicationLost(Exception cause) {
        if (stopping || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        logger.warn("D5 {} stopped responding ({}); re-establishing the connection",
                config.serialNumber, cause == null ? "no response" : cause.getMessage());
        Thread thread = new Thread(this::reestablishConnection, "D5 reconnect " + config.serialNumber);
        thread.setDaemon(true);
        reconnectThread = thread;
        thread.start();
    }

    private void reestablishConnection() {
        long delayMillis = Math.max(10L, reconnectInitialDelayMs());
        try {
            synchronized (connectionLock) {
                closeConnection();
            }
            while (!stopping && isStarted()) {
                Thread.sleep(delayMillis);
                if (stopping || !isStarted()) {
                    return;
                }
                ICommProvider<?> provider;
                try {
                    // Open outside connectionLock so blocking TCP connect timeouts never stall doStop().
                    provider = openCommProvider();
                } catch (SensorHubException e) {
                    long nextDelay = Math.min(delayMillis * 2, RECONNECT_MAX_DELAY_MS);
                    logger.warn("D5 {} reconnect failed ({}); retrying in {} ms",
                            config.serialNumber, e.getMessage(), nextDelay);
                    delayMillis = nextDelay;
                    continue;
                }
                synchronized (connectionLock) {
                    if (stopping || !isStarted()) {
                        stopQuietly(provider);
                        return;
                    }
                    installConnection(provider);
                }
                logger.info("D5 {} connection re-established", config.serialNumber);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SensorHubException e) {
            logger.error("D5 {} could not restart its message router", config.serialNumber, e);
        } finally {
            reconnectThread = null;
            reconnecting.set(false);
        }
    }

    private ICommProvider<?> openCommProvider() throws SensorHubException {
        if (config.commSettings == null) {
            throw new SensorHubException("No communication settings specified");
        }
        ICommProvider<?> provider = null;
        try {
            var moduleReg = getParentHub().getModuleRegistry();
            provider = (ICommProvider<?>) moduleReg.loadSubModule(config.commSettings, true);
            provider.start();
            if (!provider.isStarted() || provider.getInputStream() == null || provider.getOutputStream() == null) {
                throw new SensorException("D5 device is not reachable", provider.getCurrentError());
            }
            return provider;
        } catch (Exception e) {
            stopQuietly(provider);
            throw new SensorException("Error while initializing communications ", e);
        }
    }

    /** Caller holds {@link #connectionLock}. */
    private void installConnection(ICommProvider<?> provider) throws SensorHubException {
        try {
            commProvider = provider;
            messageRouter = new D5MessageRouter(this, provider.getInputStream(), provider.getOutputStream());
            messageRouter.start();
        } catch (Exception e) {
            closeConnection();
            throw new SensorException("Error while initializing communications ", e);
        }
    }

    /** Caller holds {@link #connectionLock}. */
    private void closeConnection() {
        if (messageRouter != null) {
            messageRouter.stop();
            messageRouter = null;
        }
        if (commProvider != null) {
            stopQuietly(commProvider);
            commProvider = null;
        }
    }

    private void stopQuietly(ICommProvider<?> provider) {
        if (provider == null) {
            return;
        }
        try {
            provider.stop();
        } catch (Exception e) {
            logger.debug("Error stopping D5 comm provider", e);
        }
    }

    private void registerReportOutput(SerialReport report) {
        D5Output output = new D5Output(report.getReportName(), this);
        addOutput(output, false);
        output.doInit(report);
        outputs.put(report.getClass(), output);
    }

    void createOutputs() {
        if (config.outputs.enableKromekDetectorRadiometricsV1Report) {
            registerReportOutput(new KromekDetectorRadiometricsV1Report());
        }
        if (config.outputs.enableKromekSerialRadiometricStatusReport) {
            registerReportOutput(new KromekSerialRadiometricStatusReport());
        }
        if (config.outputs.enableKromekSerialCompressionEnabledReport) {
            registerReportOutput(new KromekSerialCompressionEnabledReport());
        }
        if (config.outputs.enableKromekSerialEthernetConfigReport) {
            registerReportOutput(new KromekSerialEthernetConfigReport());
        }
        if (config.outputs.enableKromekSerialStatusReport) {
            registerReportOutput(new KromekSerialStatusReport());
        }
        if (config.outputs.enableKromekSerialUnitIDReport) {
            registerReportOutput(new KromekSerialUnitIDReport());
        }
        if (config.outputs.enableKromekSerialDoseInfoReport) {
            registerReportOutput(new KromekSerialDoseInfoReport());
        }
        if (config.outputs.enableKromekSerialRemoteIsotopeConfirmationReport) {
            registerReportOutput(new KromekSerialRemoteIsotopeConfirmationReport());
        }
        if (config.outputs.enableKromekSerialRemoteIsotopeConfirmationStatusReport) {
            registerReportOutput(new KromekSerialRemoteIsotopeConfirmationStatusReport());
        }
        if (config.outputs.enableKromekSerialUTCReport) {
            registerReportOutput(new KromekSerialUTCReport());
        }
        if (config.outputs.enableKromekSerialRemoteBackgroundStatusReport) {
            registerReportOutput(new KromekSerialRemoteBackgroundStatusReport());
        }
        if (config.outputs.enableKromekSerialRemoteExtendedIsotopeConfirmationStatusReport) {
            registerReportOutput(new KromekSerialRemoteExtendedIsotopeConfirmationStatusReport());
        }
        if (config.outputs.enableKromekSerialUIRadiationThresholdsReport) {
            registerReportOutput(new KromekSerialUIRadiationThresholdsReport());
        }
        if (config.outputs.enableKromekSerialAboutReport) {
            registerReportOutput(new KromekSerialAboutReport());
        }
        if (config.outputs.enableKromekSerialOTGReport) {
            registerReportOutput(new KromekSerialOTGReport());
        }
        if (config.outputs.enableLocationOutput && config.outputs.enableKromekSerialRadiometricStatusReport) {
            locationOutput = new D5LocationOutput(this);
            addOutput(locationOutput, false);
            locationOutput.doInit();
        } else {
            locationOutput = null;
        }
    }

    void publishLocation(KromekSerialRadiometricStatusReport report) {
        if (locationOutput != null && report != null) {
            locationOutput.onNewFix(report.getLatitude(), report.getLongitude());
        }
    }
}
