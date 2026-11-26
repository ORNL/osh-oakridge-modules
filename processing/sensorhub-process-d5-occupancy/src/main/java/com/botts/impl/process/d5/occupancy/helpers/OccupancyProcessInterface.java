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

package com.botts.impl.process.d5.occupancy.helpers;

import com.botts.impl.process.d5.occupancy.D5OccupancyProcessModule;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IDataProducer;
import org.sensorhub.api.event.IEventHandler;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.processing.IProcessModule;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.impl.event.BasicEventHandler;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.impl.utils.rad.model.OccupancyExtended;
import org.sensorhub.impl.utils.rad.output.OccupancyOutput;
import org.vast.process.DataQueue;
import org.vast.process.ProcessException;
import org.vast.sensorML.SMLHelper;

public class OccupancyProcessInterface extends OccupancyOutput<IDataProducer> {
    final IProcessModule<?> parentProcess;
    final IEventHandler eventHandler;
    static final String OUTPUT_NAME = OccupancyOutput.NAME;
    DataComponent outputDef;
    DataEncoding outputEncoding;
    DataBlock lastRecord;
    Occupancy lastOccupancy;
    long lastRecordTime = Long.MIN_VALUE;
    double avgSamplingPeriod = 1.0;
    volatile boolean doPublish = false;

    public void doPublish() {
        doPublish = true;
    }

    protected DataQueue outputQueue = new DataQueue() {
        @Override
        public synchronized void publishData() {
            if (!sourceComponent.hasData()) {
                return;
            }
            DataBlock data = sourceComponent.getData();
            OccupancyExtended occupancy = OccupancyExtended.toOccupancy(data);

            if (lastOccupancy == null || lastOccupancy.getSamplingTime() != occupancy.getSamplingTime()) {
                lastOccupancy = occupancy;
                setData(occupancy);
                doPublish = false;
            }
        }
    };

    public OccupancyProcessInterface(
            IProcessModule<?> parentProcess,
            AbstractSWEIdentifiable outputDescriptor,
            DataEncoding encoding) throws ProcessingException {
        super(parentProcess);
        this.dataStruct = OccupancyExtended.createExtendedRecordStructure();
        this.parentProcess = parentProcess;
        this.eventHandler = new BasicEventHandler();

        if (outputDescriptor != null) {
            this.outputDef = SMLHelper.getIOComponent(outputDescriptor);
            lastOccupancy = OccupancyExtended.toOccupancy(outputDef.createDataBlock());
            if (encoding != null) {
                this.outputEncoding = encoding;
            } else {
                this.outputEncoding = SMLHelper.getIOEncoding(outputDescriptor);
            }

            try {
                if (parentProcess instanceof D5OccupancyProcessModule d5Module) {
                    DataComponent execOutput = d5Module.wrapperProcess.getOutputComponent(outputDef.getName());
                    d5Module.wrapperProcess.connect(execOutput, outputQueue);
                }
            } catch (ProcessException e) {
                throw new ProcessingException("Error while connecting output " + outputDef.getName(), e);
            }
        }
    }

    @Override
    protected void augmentPublish(Occupancy occupancy) {
        augmentOccupancy(occupancy);

        if (occupancy instanceof OccupancyExtended extended) {
            dataBlock = OccupancyExtended.fromOccupancy(extended);
        } else {
            OccupancyExtended wrapper = OccupancyExtended.wrapAsExtended(occupancy, "");
            dataBlock = OccupancyExtended.fromOccupancy(wrapper);
        }

        dataStruct.setData(dataBlock);
        latestRecord = dataBlock;
        lastRecord = dataBlock;

        long now = System.currentTimeMillis();
        updateSamplingPeriod(now);
        latestRecordTime = now;
        lastRecordTime = now;

        eventHandler.publish(new DataEvent(now, this, dataBlock));
    }

    @Override
    public IDataProducer getParentProducer() {
        return parentProcess;
    }

    @Override
    public String getName() {
        return OUTPUT_NAME;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public DataComponent getRecordDescription() {
        return outputDef;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return outputEncoding;
    }

    @Override
    public DataBlock getLatestRecord() {
        return lastRecord;
    }

    @Override
    public long getLatestRecordTime() {
        return lastRecordTime;
    }

    @Override
    public double getAverageSamplingPeriod() {
        return avgSamplingPeriod;
    }

    @Override
    public void registerListener(IEventListener listener) {
        eventHandler.registerListener(listener);
    }

    @Override
    public void unregisterListener(IEventListener listener) {
        eventHandler.unregisterListener(listener);
    }
}
