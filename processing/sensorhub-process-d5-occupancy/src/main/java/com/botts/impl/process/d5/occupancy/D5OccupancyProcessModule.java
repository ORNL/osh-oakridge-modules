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

package com.botts.impl.process.d5.occupancy;

import com.botts.impl.process.d5.occupancy.helpers.DataOutputInterface;
import com.botts.impl.process.d5.occupancy.helpers.OccupancyProcessInterface;
import com.botts.impl.process.d5.occupancy.helpers.ProcessHelper;
import net.opengis.OgcPropertyList;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.processing.OSHProcessInfo;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.processing.AbstractProcessModule;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.process.ProcessException;
import org.vast.sensorML.AggregateProcessImpl;
import org.vast.sensorML.SMLException;
import org.vast.sensorML.SMLHelper;
import org.vast.sensorML.SMLUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight Kromek D5 occupancy process module matching {@code Rs350OccupancyProcessModule}.
 * Lifecycle coordination and recovery are owned by the enclosing {@code LaneSystem} /
 * {@code MobileDetectorSystem} ({@code OccupancyProcessStarter} and {@code RpmRecoverySupervisor}).
 */
public class D5OccupancyProcessModule extends AbstractProcessModule<D5OccupancyProcessConfig> {

    protected SMLUtils smlUtils;
    public AggregateProcessImpl wrapperProcess;
    protected boolean useThreads = true;
    String processUniqueID;
    D5OutputToOccupancy alarmProcess;
    String parentSystemUID = null;

    public D5OccupancyProcessModule() {
        wrapperProcess = new AggregateProcessImpl();
        wrapperProcess.setUniqueIdentifier(UUID.randomUUID().toString());
        initAsync = true;
    }

    @Override
    public void setParentHub(ISensorHub hub) {
        super.setParentHub(hub);
        smlUtils = new SMLUtils(SMLUtils.V2_0);
        smlUtils.setProcessFactory(hub.getProcessingManager());
    }

    @Override
    protected void doInit() throws SensorHubException {
        parentSystemUID = config.systemUID;
        if (parentSystemUID == null || parentSystemUID.isBlank()) {
            if (getParentSystem() == null || getParentSystemUID() == null || getParentSystemUID().isBlank()) {
                throw new SensorHubException("Please specify a system UID, or put process under parent system.");
            }
            parentSystemUID = getParentSystemUID();
        }

        try {
            processUniqueID = OSHProcessInfo.URI_PREFIX + "d5-occupancy:" + config.serialNumber;
            OshAsserts.checkValidUID(processUniqueID);

            processDescription = buildProcess();
            if (processDescription.getName() == null) {
                processDescription.setName(this.getName());
            }

            initChain();
        } catch (ProcessException e) {
            throw new ProcessingException("Processing error", e);
        }
    }

    public AggregateProcessImpl buildProcess() throws ProcessException, SensorHubException {
        ProcessHelper processHelper = new ProcessHelper();
        processHelper.getAggregateProcess().setUniqueIdentifier(processUniqueID);

        var dataStreams = findMatchingDs(D5OutputToOccupancy.CHAIN_INPUTS.toArray(new String[0]));

        this.alarmProcess = new D5OutputToOccupancy();

        for (int i = 0; i < dataStreams.size(); i++) {
            String dsID = dataStreams.get(i).getSystemID().getUniqueID();
            OshAsserts.checkValidUID(dsID);
            processHelper.addDataSource("source" + i, dsID);
        }

        alarmProcess.getParameterList().getComponent(D5OutputToOccupancy.SYSTEM_INPUT_PARAM).getData()
                .setStringValue(parentSystemUID);
        alarmProcess.setParentHub(getParentHub());
        alarmProcess.notifyParamChange();

        processHelper.addOutputList(alarmProcess.getOutputList());
        processHelper.addProcess("process0", alarmProcess);

        for (int i = 0; i < dataStreams.size(); i++) {
            processHelper.addConnection(
                    "components/source" + i + "/outputs/" + dataStreams.get(i).getOutputName(),
                    "components/process0/inputs/" + dataStreams.get(i).getOutputName());
        }

        for (AbstractSWEIdentifiable systemOutput : alarmProcess.getOutputList()) {
            DataComponent systemComponent = (DataComponent) systemOutput;
            if (systemComponent.getComponentCount() > 0) {
                processHelper.addConnection(
                        "components/process0/outputs/" + systemComponent.getName(),
                        "outputs/" + systemComponent.getName());
            }
        }

        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            smlUtils.writeProcess(os, processHelper.getAggregateProcess(), true);
            InputStream is = new ByteArrayInputStream(os.toByteArray());
            return (AggregateProcessImpl) smlUtils.readProcess(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<IDataStreamInfo> findMatchingDs(String... dataStreamNames) throws SensorHubException {
        List<IDataStreamInfo> dataStreams = new LinkedList<>();

        var sysFilter = new SystemFilter.Builder()
                .withUniqueIDs(parentSystemUID)
                .includeMembers(true)
                .build();

        var db = getParentHub().getDatabaseRegistry().getFederatedDatabase();

        for (String dsName : dataStreamNames) {
            List<IDataStreamInfo> dsList;
            try (var matchingDs = db.getDataStreamStore().select(new DataStreamFilter.Builder()
                    .withSystems(sysFilter)
                    .withCurrentVersion()
                    .withOutputNames(dsName)
                    .withLimit(1)
                    .build())) {
                dsList = matchingDs.toList();
            }
            if (dsList.size() != 1) {
                throw new SensorHubException("Unable to find D5 datastream " + dsName + " in system");
            }
            dataStreams.add(dsList.get(0));
        }

        return dataStreams;
    }

    protected void initChain() throws SensorHubException {
        try {
            wrapperProcess = (AggregateProcessImpl) smlUtils.getExecutableInstance(
                    (AggregateProcessImpl) processDescription, useThreads);
            processDescription = wrapperProcess;
            wrapperProcess.setInstanceName("chain");
            wrapperProcess.setParentLogger(getLogger());
            wrapperProcess.init();
        } catch (SMLException e) {
            throw new ProcessingException("Cannot prepare process chain for execution", e);
        } catch (ProcessException e) {
            throw new ProcessingException(e.getMessage(), e.getCause());
        }

        refreshIOList(processDescription.getOutputList(), outputs, alarmProcess.getOutputEncodingMap());
        setState(ModuleEvent.ModuleState.INITIALIZED);
    }

    protected void refreshIOList(
            OgcPropertyList<AbstractSWEIdentifiable> ioList,
            Map<String, DataComponent> ioMap,
            Map<String, DataEncoding> encodingMap) throws ProcessingException {
        ioMap.clear();
        if (ioMap == inputs) {
            controlInterfaces.clear();
        } else if (ioMap == outputs) {
            outputInterfaces.clear();
        }

        int numSignals = ioList.size();
        for (int i = 0; i < numSignals; i++) {
            String ioName = ioList.getProperty(i).getName();
            AbstractSWEIdentifiable ioDesc = ioList.get(i);

            DataComponent ioComponent = SMLHelper.getIOComponent(ioDesc);
            DataEncoding ioEncoding = encodingMap.get(ioName);
            ioMap.put(ioName, ioComponent);

            if (ioMap == outputs) {
                if (ioName.equals(OccupancyOutput.NAME)) {
                    outputInterfaces.put(ioName, new OccupancyProcessInterface(this, ioDesc, ioEncoding));
                } else {
                    outputInterfaces.put(ioName, new DataOutputInterface(this, ioDesc, ioEncoding));
                }
            }
        }
    }

    @Override
    protected void doStart() throws SensorHubException {
        if (wrapperProcess == null) {
            throw new ProcessingException("No valid processing chain provided");
        }

        if (useThreads) {
            try {
                wrapperProcess.start(e -> reportError("Error while executing process chain", e));
            } catch (ProcessException e) {
                throw new ProcessingException("Cannot start process chain thread", e);
            }
        }
    }

    @Override
    protected void doStop() {
        if (wrapperProcess != null && wrapperProcess.isExecutable()) {
            wrapperProcess.stop();
        }
    }
}
