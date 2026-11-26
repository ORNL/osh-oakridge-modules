/*******************************************************************************

  The contents of this file are subject to the Mozilla Public License, v. 2.0.
  If a copy of the MPL was not distributed with this file, You can obtain one
  at http://mozilla.org/MPL/2.0/.

  Software distributed under the License is distributed on an "AS IS" basis,
  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
  for the specific language governing rights and limitations under the License.

  The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
  Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.process.rs350.occupancy;

import com.botts.impl.process.rs350.occupancy.helpers.OccupancyProcessInterface;
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
import com.botts.impl.process.rs350.occupancy.helpers.ProcessHelper;
import com.botts.impl.process.rs350.occupancy.helpers.DataOutputInterface;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.process.ProcessException;
import org.vast.sensorML.*;

import java.io.*;
import java.util.*;

// Based on SMLProcessImpl
public class Rs350OccupancyProcessModule extends AbstractProcessModule<Rs350OccupancyProcessConfig> {

    protected SMLUtils smlUtils;
    public AggregateProcessImpl wrapperProcess;
    protected boolean useThreads = true;
    String processUniqueID;
    Rs350OutputToOccupancy alarmProcess;
    String parentSystemUID = null;

    /**
     * Standalone processing module to be recognized by OSH module provider, accessible in the OSH Admin UI.
     */
    public Rs350OccupancyProcessModule()
    {
        wrapperProcess = new AggregateProcessImpl();
        wrapperProcess.setUniqueIdentifier(UUID.randomUUID().toString());
        initAsync = true;
    }


    @Override
    public void setParentHub(ISensorHub hub)
    {
        super.setParentHub(hub);
        smlUtils = new SMLUtils(SMLUtils.V2_0);
        smlUtils.setProcessFactory(hub.getProcessingManager());
    }


    @Override
    protected void doInit() throws SensorHubException {
        parentSystemUID = config.systemUID;
        if (parentSystemUID == null || parentSystemUID.isBlank()) {
            if(getParentSystem() == null || getParentSystemUID() == null || getParentSystemUID().isBlank())
                throw new SensorHubException("Please specify a system UID, or put process under parent system.");
            parentSystemUID = getParentSystemUID();
        }

        try {
            processUniqueID = OSHProcessInfo.URI_PREFIX +  "rs350-occupancy:" + config.serialNumber;
            OshAsserts.checkValidUID(processUniqueID);

            processDescription = buildProcess();

            if(processDescription.getName() == null) {
                processDescription.setName(this.getName());
            }

            initChain();
        } catch (ProcessException e) {
            throw new ProcessingException("Processing error", e);
        }
    }

    /**
     * Build SensorML process description via ProcessHelper. // TODO Change to use ProcessHelper in sensorhub-process-helpers
     * @return process chain implementation to be executed
     * @throws ProcessException if not able to read process
     * @throws SensorHubException if no RPM data streams
     */
    public AggregateProcessImpl buildProcess() throws ProcessException, SensorHubException {

        ProcessHelper processHelper = new ProcessHelper();
        processHelper.getAggregateProcess().setUniqueIdentifier(processUniqueID);

        var dataStreams = findMatchingDs(Rs350OutputToOccupancy.RS350_OUTPUTS.toArray(new String[0]));

        this.alarmProcess = new Rs350OutputToOccupancy();

        for (int i = 0; i < dataStreams.size(); i++) {
            String dsID = dataStreams.get(i).getSystemID().getUniqueID();
            OshAsserts.checkValidUID(dsID);
            processHelper.addDataSource("source" + i, dsID);
        }

        alarmProcess.getParameterList().getComponent(Rs350OutputToOccupancy.SYSTEM_INPUT_PARAM).getData().setStringValue(parentSystemUID);
        alarmProcess.setParentHub(getParentHub());
        alarmProcess.notifyParamChange();

        processHelper.addOutputList(alarmProcess.getOutputList());
        processHelper.addProcess("process0", alarmProcess);

        for (int i = 0; i < dataStreams.size(); i++) {
            processHelper.addConnection("components/source" + i + "/outputs/" + dataStreams.get(i).getOutputName()
                    , "components/process0/inputs/" + dataStreams.get(i).getOutputName());
        }

        for(AbstractSWEIdentifiable systemOutput : alarmProcess.getOutputList()) {
            DataComponent systemComponent = (DataComponent) systemOutput;
            if(systemComponent.getComponentCount() > 0)
                processHelper.addConnection("components/process0/outputs/" + systemComponent.getName(),
                        "outputs/" + systemComponent.getName());
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
            if (dsList.size() != 1)
                throw new SensorHubException("Unable to find RPM datastream " + dsName + " in system");
            dataStreams.add(dsList.get(0));
        }

        return dataStreams;
    }

    protected void  initChain() throws SensorHubException {
        // make process executable
        try {
            //smlUtils.makeProcessExecutable(wrapperProcess, true);
            wrapperProcess = (AggregateProcessImpl)smlUtils.getExecutableInstance((AggregateProcessImpl)processDescription, useThreads);
            processDescription = wrapperProcess;
            wrapperProcess.setInstanceName("chain");
            wrapperProcess.setParentLogger(getLogger());
            wrapperProcess.init();
        } catch (SMLException e) {
            throw new ProcessingException("Cannot prepare process chain for execution", e);
        } catch (ProcessException e) {
            throw new ProcessingException(e.getMessage(), e.getCause());
        }

        // advertise process inputs and outputs
        refreshIOList(processDescription.getOutputList(), outputs, alarmProcess.getOutputEncodingMap());

        setState(ModuleEvent.ModuleState.INITIALIZED);
    }


    /**
     * Add output interface connections
     * @param ioList list of all process components
     * @param ioMap map of all inputs/outputs
     * @param encodingMap map of data encodings
     * @throws ProcessingException if unable to get IO component
     */
    protected void refreshIOList(OgcPropertyList<AbstractSWEIdentifiable> ioList, Map<String, DataComponent> ioMap, Map<String, DataEncoding> encodingMap) throws ProcessingException {
        ioMap.clear();
        if (ioMap == inputs)
            controlInterfaces.clear();
        else if (ioMap == outputs)
            outputInterfaces.clear();

        int numSignals = ioList.size();
        for (int i=0; i<numSignals; i++)
        {
            String ioName = ioList.getProperty(i).getName();
            AbstractSWEIdentifiable ioDesc = ioList.get(i);

            DataComponent ioComponent = SMLHelper.getIOComponent(ioDesc);
            DataEncoding ioEncoding = encodingMap.get(ioName);
            ioMap.put(ioName, ioComponent);

            if(ioMap == inputs) {
                // TODO set control interface
            } else if(ioMap == parameters) {
                // TODO set control interfaces
            } else if(ioMap == outputs) {
                if (ioName.equals(OccupancyOutput.NAME)) {
                    outputInterfaces.put(ioName, new OccupancyProcessInterface(this, ioDesc, ioEncoding));
                } else {
                    outputInterfaces.put(ioName, new DataOutputInterface(this, ioDesc, ioEncoding));
                }
            }
        }
    }

    /**
     * Begin processing chain
     * @throws SensorHubException if unable to start process or invalid process
     */
    @Override
    protected void doStart() throws SensorHubException
    {
        if (wrapperProcess == null)
            throw new ProcessingException("No valid processing chain provided");

        // start processing thread
        if (useThreads)
        {
            try
            {
                wrapperProcess.start(e-> {
                    reportError("Error while executing process chain", e);
                });
            }
            catch (ProcessException e)
            {
                throw new ProcessingException("Cannot start process chain thread", e);
            }
        }
    }


    @Override
    protected void doStop()
    {
        if (wrapperProcess != null && wrapperProcess.isExecutable())
            wrapperProcess.stop();
    }
}
