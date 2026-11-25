/***************************** BEGIN LICENSE BLOCK ***************************
 Copyright (C) 2023 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package com.botts.impl.sensor.rs350;

import com.botts.impl.sensor.rs350.output.*;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.comm.RobustIPConnection;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.utils.rad.dailyfile.DailyFileJournalConnection;
import org.sensorhub.impl.utils.rad.interfaces.IWebIdProvider;
import org.sensorhub.impl.utils.rad.webid.WebIdAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public class RS350Sensor extends AbstractSensorModule<RS350Config> {

    private static final Logger logger = LoggerFactory.getLogger(RS350Sensor.class);

    ICommProvider<?> commProvider;
    StatusOutput statusOutput;
    BackgroundOutput backgroundOutput;
    ForegroundOutput foregroundOutput;
    AlarmOutput alarmOutput;
    LocationOutput locationOutput;
    ConnectionStatusOutput connectionStatusOutput;

    RobustConnection connection;
    // Read and replaced from the heartbeat thread while doStop() may be
    // clearing it, so both need to be visible across threads.
    volatile MessageHandler messageHandler;
    DailyFileJournalConnection dailyFileJournal;
    InputStream msgIn;
    private volatile boolean isRunning;
    private Thread tcpConnectionThread;
    /** Paces connection rebuild attempts while the detector stays silent. */
    private long lastReconnectAttemptMs;
    /** Guards swapping commProvider/messageHandler without holding this monitor across TCP connect timeouts. */
    private final Object connectionSwapLock = new Object();

    @Override
    protected void doInit() throws SensorHubException {
        super.doInit();
        generateUniqueID("urn:osh:sensor:rsi:rs350:", config.serialNumber);
        generateXmlID("rsi_rs350_", config.serialNumber);

        tryConnection();

        createOutputs();
    }

    private void createOutputs(){
        if (config.outputs.enableStatusOutput) {
            statusOutput = new StatusOutput(this);
            addOutput(statusOutput, false);
            statusOutput.init();
        }

        if (config.outputs.enableBackgroundOutput) {
            backgroundOutput = new BackgroundOutput(this);
            addOutput(backgroundOutput, false);
            backgroundOutput.init();
        }

        if (config.outputs.enableForegroundOutput) {
            foregroundOutput = new ForegroundOutput(this);
            addOutput(foregroundOutput, false);
            foregroundOutput.init();
        }

        if (config.outputs.enableAlarmOutput) {
            alarmOutput = new AlarmOutput(this);
            addOutput(alarmOutput, false);
            alarmOutput.init();
        }

        if (config.outputs.enableLocationOutput) {
            locationOutput = new LocationOutput(this);
            addOutput(locationOutput, false);
            locationOutput.init();
        } else {
            locationOutput = null;
        }

        connectionStatusOutput = new ConnectionStatusOutput(this);
        addOutput(connectionStatusOutput, false);
        connectionStatusOutput.init();
    }

    private void tryConnection() throws SensorHubException {
        if (config.commSettings == null) throw new SensorHubException("No communication settings specified");

        connection = new RobustIPConnection(this, config.commSettings.connection, "RS-350") {
            @Override
            public boolean tryConnect() throws IOException {
                ICommProvider<?> candidate = null;
                try {
                    var moduleReg = getParentHub().getModuleRegistry();
                    candidate = (ICommProvider<?>) moduleReg.loadSubModule(config.commSettings, true);
                    candidate.start();
                    if (!candidate.isStarted())
                        throw new SensorHubException("Comm Provider failed to start. Check communication settings.");
                    commProvider = candidate;
                    return true;
                } catch (Exception e) {
                    if (candidate != null) {
                        try {
                            if (candidate.isStarted())
                                candidate.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    reportError("Cannot connect to RS350 ", e , true);
                    return false;
                }
            }
        };
        connection.waitForConnection();
    }
    @Override
    protected void doStart() throws SensorHubException {

        connection.waitForConnection();
        connectDailyFileJournal();
        try {
            msgIn = new BufferedInputStream(commProvider.getInputStream());
        } catch (IOException e) {
            throw new SensorException("Error while initializing communications ", e);
        }

        initMessageHandler();
    }

    /**
     * Build the message handler over the current input stream and wire it to
     * the enabled outputs. Also used when rebuilding a dropped connection, so
     * a reconnected detector feeds the same outputs (and the same daily-file
     * journal) as at startup.
     */
    private void initMessageHandler() {
        WebIdAnalyzer webIdAnalyzer = null;
        try {
            var oscarServiceModule = getParentHub().getModuleRegistry().getModuleByType(IWebIdProvider.class);
            webIdAnalyzer = new WebIdAnalyzer(oscarServiceModule.getWebIdClient(), getParentHub());
        } catch (Exception e) {
            logger.warn("Could not attach WebID Analysis to RS350", e);
        }
        messageHandler = new MessageHandler(webIdAnalyzer, msgIn, "</RadInstrumentData>", this,
                dailyFileJournal);

        if (config.outputs.enableStatusOutput) {
            messageHandler.addStatusListener(statusOutput);
        }

        if (config.outputs.enableBackgroundOutput) {
            messageHandler.addBackgroundListener(backgroundOutput);
        }

        if (config.outputs.enableForegroundOutput) {
            messageHandler.addForegroundListener(foregroundOutput);
        }

        if (config.outputs.enableAlarmOutput) {
            messageHandler.addAlarmListener(alarmOutput);
        }

        if (config.outputs.enableLocationOutput && locationOutput != null) {
            messageHandler.addForegroundListener(locationOutput);
        }
    }

    @Override
    protected void afterStart() {
        // Begin heartbeat check
        tcpConnectionThread = new Thread(this::heartbeat);
        isRunning = true;
        tcpConnectionThread.start();
    }

    @Override
    protected void doStop() throws SensorHubException {
        isRunning = false;
        if (tcpConnectionThread != null) {
            tcpConnectionThread.interrupt();
            tcpConnectionThread = null;
        }
        if (connection != null) {
            try {
                connection.cancel();
            } catch (Exception e) {
                logger.error("Error canceling connection", e);
            }
        }

        MessageHandler activeHandler;
        ICommProvider<?> activeComm;
        synchronized (connectionSwapLock) {
            activeHandler = messageHandler;
            messageHandler = null;
            activeComm = commProvider;
            commProvider = null;
        }
        if (activeHandler != null)
            activeHandler.requestStop();

        if (activeComm != null) {
            try {
                if (activeComm.isStarted()) {
                    activeComm.stop();
                }
            } catch (Exception e) {
                logger.error("Error stopping comm module", e);
            }
        }

        if (activeHandler != null) {
            try {
                activeHandler.awaitSourceStopped();
            } catch (IOException e) {
                throw new SensorHubException(
                        "RS350 source reader did not stop before journal close", e);
            }
        }

        SensorHubException stopFailure = null;
        if (dailyFileJournal != null) {
            try {
                dailyFileJournal.close();
                dailyFileJournal = null;
            } catch (IOException e) {
                stopFailure = new SensorHubException(
                        "Unable to close the RS350 daily-file writer", e);
            }
        }

        if (activeHandler != null) {
            try {
                activeHandler.awaitAncillaryStopped();
                messageHandler = null;
            } catch (IOException e) {
                SensorHubException ancillaryFailure = new SensorHubException(
                        "RS350 ancillary workers did not stop", e);
                if (stopFailure == null)
                    stopFailure = ancillaryFailure;
                else
                    stopFailure.addSuppressed(ancillaryFailure);
            }
        }
        if (stopFailure != null)
            throw stopFailure;
    }

    private void connectDailyFileJournal() throws SensorHubException {
        if (dailyFileJournal != null)
            return;
        String laneId = getParentSystemUID();
        if (laneId == null || laneId.isBlank())
            laneId = getUniqueIdentifier();
        dailyFileJournal = DailyFileJournalConnection.connect(getParentHub(), laneId,
                config.dailyFileJournal, getLogger());
    }

    public ConnectionStatusOutput getConnectionStatusOutput() {
        return connectionStatusOutput;
    }



    @Override
    public boolean isConnected() {
        if(connection == null) return false;

        return connection.isConnected();
    }

    public void checkConnection() {
        // Triggered by DATA silence, never by connection.isConnected(): the
        // RobustConnection flag is set once on the first successful connect
        // and is only cleared by cancel()/reconnect(), so it stays true after
        // the detector disappears and this branch could never fire.
        long now = System.currentTimeMillis();
        if (now - lastReconnectAttemptMs < config.commSettings.connection.reconnectPeriod)
            return;
        lastReconnectAttemptMs = now;

        reportStatus("RS-350 has gone silent, rebuilding the connection...");
        if (reestablishConnection())
            reportStatus("RS-350 connection re-established");
    }

    /**
     * Replace the dead comm provider and message pipeline in place, so a
     * detector that comes back starts feeding the outputs again on its own.
     * <p>
     * The stream and its reader cannot be reused: the reader thread exits the
     * moment the socket reports EOF, and a Java thread cannot be restarted, so
     * both the InputStream and the MessageHandler are rebuilt. This is done
     * without RobustConnection.reconnect(), which stops and starts the whole
     * module and gives up after reconnectAttempts — an outage longer than
     * reconnectAttempts * reconnectPeriod would leave the module in ERROR with
     * no heartbeat left to retry. Rebuilding here keeps the module STARTED and
     * the heartbeat retries indefinitely, once per reconnectPeriod.
     * <p>
     * The daily-file journal is lane-scoped and stays open across rebuilds;
     * only doStop() closes it.
     *
     * @return true when the detector is connected and a new reader is running
     */
    private boolean reestablishConnection() {
        MessageHandler oldHandler;
        ICommProvider<?> oldComm;
        synchronized (connectionSwapLock) {
            if (!isRunning)
                return false;
            oldHandler = messageHandler;
            messageHandler = null;
            oldComm = commProvider;
            commProvider = null;
        }

        if (oldHandler != null)
            oldHandler.requestStop();

        if (oldComm != null) {
            try {
                if (oldComm.isStarted())
                    oldComm.stop();
            } catch (Exception e) {
                logger.debug("Error stopping stale comm provider", e);
            }
        }

        if (oldHandler != null) {
            try {
                oldHandler.awaitSourceStopped();
                oldHandler.awaitAncillaryStopped();
            } catch (IOException e) {
                // keep going: the old workers are interrupted and hold a dead
                // socket; a lingering one must not keep the lane dark
                logger.warn("Stale RS-350 message handler did not stop cleanly", e);
            }
        }

        // Connect outside connectionSwapLock so a TCP SYN timeout on an offline
        // detector never blocks doStop() or Admin UI module stops.
        try {
            if (!isRunning || !connection.tryConnect())
                return false;

            synchronized (connectionSwapLock) {
                if (!isRunning) {
                    ICommProvider<?> lateComm = commProvider;
                    commProvider = null;
                    if (lateComm != null && lateComm.isStarted()) {
                        try {
                            lateComm.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    return false;
                }
                msgIn = new BufferedInputStream(commProvider.getInputStream());
                initMessageHandler();
                return true;
            }
        } catch (Exception e) {
            logger.debug("RS-350 reconnect attempt failed", e);
            return false;
        }
    }


    public void heartbeat() {

        while (isRunning) {
            MessageHandler handler = messageHandler;
            // No handler means a rebuild is in flight: treat that as silence.
            long silentMs = (handler == null) ? Long.MAX_VALUE : handler.getTimeSinceLastMessage();

            if (silentMs < config.commSettings.connection.reconnectPeriod) {

                this.connectionStatusOutput.onNewMessage(true);
            }
            else {
                this.connectionStatusOutput.onNewMessage(false);

                checkConnection();
            }
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                logger.debug("Couldn't sleep");
            }
        }
    }
}
