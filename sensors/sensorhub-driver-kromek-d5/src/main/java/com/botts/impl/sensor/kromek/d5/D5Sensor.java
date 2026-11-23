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
    ICommProvider<?> commProvider;
    volatile boolean processLock = false;
    D5MessageRouter messageRouter;

    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        // Generate identifiers
        generateUniqueID("urn:osh:sensor:kromek:d5:", config.serialNumber);
        generateXmlID("KROMEK_D5_", config.serialNumber);

        // Create and initialize output(s)
        outputs = new HashMap<>();
        createOutputs();
    }

    @Override
    public void doStart() throws SensorHubException {
        if (commProvider == null) {
            // we need to recreate comm provider here because it can be changed by UI
            try {
                if (config.commSettings == null)
                    throw new SensorHubException("No communication settings specified");

                var moduleReg = getParentHub().getModuleRegistry();
                commProvider = (ICommProvider<?>) moduleReg.loadSubModule(config.commSettings, true);
                commProvider.start();
            } catch (Exception e) {
                commProvider = null;
                throw new SensorException("Error while initializing communications ", e);
            }
        }

        // connect to data stream
        try {
            messageRouter = new D5MessageRouter(this, commProvider.getInputStream(), commProvider.getOutputStream());
            messageRouter.start();
        } catch (Exception e) {
            throw new SensorException("Error while initializing communications ", e);
        }

        processLock = false;
    }

    @Override
    public void doStop() throws SensorHubException {
        logger.info("Stopping sensor");
        processLock = true;

        if (messageRouter != null) {
            messageRouter.stop();
            messageRouter = null;
        }
        if (commProvider != null) {
            try {
                commProvider.stop();
            } finally {
                commProvider = null;
            }
        }
        if (outputs != null) {
            for (D5Output output : outputs.values()) {
                output.doStop();
            }
        }
    }

    @Override
    public boolean isConnected() {
        // Determine if the sensor is connected
        return commProvider != null && commProvider.isInitialized();
    }

    private void registerReportOutput(SerialReport report) {
        D5Output output = new D5Output(report.getReportName(), this);
        addOutput(output, false);
        output.doInit(report);
        outputs.put(report.getClass(), output);
    }

    void createOutputs() {
        // Create and initialize outputs
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
    }
}
