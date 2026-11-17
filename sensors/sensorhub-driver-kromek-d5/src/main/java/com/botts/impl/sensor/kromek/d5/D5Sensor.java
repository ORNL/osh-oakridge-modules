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
 * initialization and shutdown of the driver and outputs.
 *
 * @author Michael Elmore
 * @since Oct. 2023
 */
public class D5Sensor extends AbstractSensorModule<D5Config> {
    private static final Logger logger = LoggerFactory.getLogger(D5Sensor.class);

    // Map of report classes to their associated output instances
    HashMap<Class<?>, D5Output> outputs;
    D5LocationOutput locationOutput;
    volatile ICommProvider<?> commProvider;
    volatile Boolean processLock;
    D5MessageRouter messageRouter;

    static final long RECONNECT_INITIAL_DELAY_MS = 1_000;
    static final long RECONNECT_MAX_DELAY_MS = 30_000;
    private final Object connectionLock = new Object();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean stopping;
    private volatile Thread reconnectThread;

    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        // Generate identifiers
        generateUniqueID("urn:osh:sensor:kromek:d5:", config.serialNumber);
        generateXmlID("kromek_d5_", config.serialNumber);

        // Create and initialize output(s)
        outputs = new HashMap<>();
        createOutputs();
    }

    @Override
    public void doStart() throws SensorHubException {
        stopping = false;
        processLock = false;
        ICommProvider<?> provider = openCommProvider();
        synchronized (connectionLock) {
            installConnection(provider);
        }
    }

    @Override
    public void doStop() throws SensorHubException {
        logger.info("Stopping sensor");
        stopping = true;
        processLock = true;

        for (D5Output output : outputs.values()) {
            output.doStop();
        }

        synchronized (connectionLock) {
            closeConnection();
        }
        Thread reconnect = reconnectThread;
        if (reconnect != null)
            reconnect.interrupt();
    }

    @Override
    public boolean isConnected() {
        // Determine if the sensor is connected
        ICommProvider<?> provider = commProvider;
        return provider != null && provider.isInitialized();
    }

    /**
     * Called by the message router once the device has stopped answering for several consecutive
     * polling cycles (a dropped TCP link, a power-cycled unit). Without this the router kept writing
     * to the dead socket, logging two errors a second, and the walker never recovered short of a
     * manual module restart. Recovery runs on its own thread because the router's scheduler is torn
     * down as part of it.
     */
    void onCommunicationLost(Exception cause) {
        if (stopping || !reconnecting.compareAndSet(false, true))
            return;
        logger.warn("D5 {} stopped responding ({}); re-establishing the connection",
                config.serialNumber, cause == null ? "no response" : cause.getMessage());
        Thread thread = new Thread(this::reestablishConnection, "D5 reconnect " + config.serialNumber);
        thread.setDaemon(true);
        reconnectThread = thread;
        thread.start();
    }

    private void reestablishConnection() {
        long delayMillis = RECONNECT_INITIAL_DELAY_MS;
        try {
            synchronized (connectionLock) {
                closeConnection();
            }
            while (!stopping) {
                Thread.sleep(delayMillis);
                if (stopping)
                    return;
                ICommProvider<?> provider;
                try {
                    // Opened outside the lock: a robust TCP provider can block for its whole retry
                    // window, and doStop must never wait on that.
                    provider = openCommProvider();
                } catch (SensorHubException e) {
                    logger.warn("D5 {} reconnect failed ({}); retrying in {} ms",
                            config.serialNumber, e.getMessage(), Math.min(delayMillis * 2, RECONNECT_MAX_DELAY_MS));
                    delayMillis = Math.min(delayMillis * 2, RECONNECT_MAX_DELAY_MS);
                    continue;
                }
                synchronized (connectionLock) {
                    if (stopping) {
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
        // the comm provider is recreated on every (re)connect because it can be changed by the UI
        if (config.commSettings == null)
            throw new SensorHubException("No communication settings specified");
        ICommProvider<?> provider = null;
        try {
            var moduleReg = getParentHub().getModuleRegistry();
            provider = (ICommProvider<?>) moduleReg.loadSubModule(config.commSettings, true);
            provider.start();
            // AbstractModule.start() reports a connect failure as module state rather than
            // throwing, leaving the provider with null streams.
            if (!provider.isStarted() || provider.getInputStream() == null || provider.getOutputStream() == null)
                throw new SensorException("D5 device is not reachable",
                        provider.getCurrentError());
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
        if (provider == null)
            return;
        try {
            provider.stop();
        } catch (Exception e) {
            logger.debug("Error stopping D5 comm provider", e);
        }
    }

    void createOutputs() {
        // Create and initialize outputs.
        // In each block the report is constructed BEFORE getReportName() is read:
        // SerialReport's name/label/definition are STATIC fields set in its
        // constructor, so the static getters only return this report's values
        // after an instance exists (otherwise every output gets the previous
        // block's name).
        if (config.outputs.enableKromekDetectorRadiometricsV1Report) {
            var report = new KromekDetectorRadiometricsV1Report();
            D5Output output = new D5Output(KromekDetectorRadiometricsV1Report.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekDetectorRadiometricsV1Report.class, output);
        }
        if (config.outputs.enableKromekSerialRadiometricStatusReport) {
            var report = new KromekSerialRadiometricStatusReport();
            D5Output output = new D5Output(KromekSerialRadiometricStatusReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialRadiometricStatusReport.class, output);
        }
        if (config.outputs.enableKromekSerialCompressionEnabledReport) {
            var report = new KromekSerialCompressionEnabledReport();
            D5Output output = new D5Output(KromekSerialCompressionEnabledReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialCompressionEnabledReport.class, output);
        }
        if (config.outputs.enableKromekSerialEthernetConfigReport) {
            var report = new KromekSerialEthernetConfigReport();
            D5Output output = new D5Output(KromekSerialEthernetConfigReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialEthernetConfigReport.class, output);
        }
        if (config.outputs.enableKromekSerialStatusReport) {
            var report = new KromekSerialStatusReport();
            D5Output output = new D5Output(KromekSerialStatusReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialStatusReport.class, output);
        }
        if (config.outputs.enableKromekSerialUnitIDReport) {
            var report = new KromekSerialUnitIDReport();
            D5Output output = new D5Output(KromekSerialUnitIDReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialUnitIDReport.class, output);
        }
        if (config.outputs.enableKromekSerialDoseInfoReport) {
            var report = new KromekSerialDoseInfoReport();
            D5Output output = new D5Output(KromekSerialDoseInfoReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialDoseInfoReport.class, output);
        }
        if (config.outputs.enableKromekSerialRemoteIsotopeConfirmationReport) {
            var report = new KromekSerialRemoteIsotopeConfirmationReport();
            D5Output output = new D5Output(KromekSerialRemoteIsotopeConfirmationReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialRemoteIsotopeConfirmationReport.class, output);
        }
        if (config.outputs.enableKromekSerialRemoteIsotopeConfirmationStatusReport) {
            var report = new KromekSerialRemoteIsotopeConfirmationStatusReport();
            D5Output output = new D5Output(KromekSerialRemoteIsotopeConfirmationStatusReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialRemoteIsotopeConfirmationStatusReport.class, output);
        }
        if (config.outputs.enableKromekSerialUTCReport) {
            var report = new KromekSerialUTCReport();
            D5Output output = new D5Output(KromekSerialUTCReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialUTCReport.class, output);
        }
        if (config.outputs.enableKromekSerialRemoteBackgroundStatusReport) {
            var report = new KromekSerialRemoteBackgroundStatusReport();
            D5Output output = new D5Output(KromekSerialRemoteBackgroundStatusReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialRemoteBackgroundStatusReport.class, output);
        }
        if (config.outputs.enableKromekSerialRemoteExtendedIsotopeConfirmationStatusReport) {
            var report = new KromekSerialRemoteExtendedIsotopeConfirmationStatusReport();
            D5Output output = new D5Output(KromekSerialRemoteExtendedIsotopeConfirmationStatusReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialRemoteExtendedIsotopeConfirmationStatusReport.class, output);
        }
        if (config.outputs.enableKromekSerialUIRadiationThresholdsReport) {
            var report = new KromekSerialUIRadiationThresholdsReport();
            D5Output output = new D5Output(KromekSerialUIRadiationThresholdsReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialUIRadiationThresholdsReport.class, output);
        }
        if (config.outputs.enableKromekSerialAboutReport) {
            var report = new KromekSerialAboutReport();
            D5Output output = new D5Output(KromekSerialAboutReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialAboutReport.class, output);
        }
        if (config.outputs.enableKromekSerialOTGReport) {
            var report = new KromekSerialOTGReport();
            D5Output output = new D5Output(KromekSerialOTGReport.getReportName(), this);
            addOutput(output, false);
            output.doInit(report);
            outputs.put(KromekSerialOTGReport.class, output);
        }
        if (config.outputs.enableLocationOutput && config.outputs.enableKromekSerialRadiometricStatusReport) {
            locationOutput = new D5LocationOutput(this);
            addOutput(locationOutput, false);
            locationOutput.doInit();
        } else {
            locationOutput = null;
        }
    }

    /**
     * Called by the message router whenever a RadiometricStatus report arrives,
     * so the GPS fix it carries is republished as a sensorLocation datastream.
     */
    void publishLocation(KromekSerialRadiometricStatusReport report) {
        if (locationOutput != null)
            locationOutput.onNewFix(report.getLatitude(), report.getLongitude());
    }
}
