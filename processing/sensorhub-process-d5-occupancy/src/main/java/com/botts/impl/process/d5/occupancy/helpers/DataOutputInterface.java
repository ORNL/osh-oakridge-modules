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
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.event.IEventHandler;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.processing.IProcessModule;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.impl.event.BasicEventHandler;
import org.vast.process.DataQueue;
import org.vast.process.ProcessException;
import org.vast.sensorML.SMLHelper;

public class DataOutputInterface implements IStreamingDataInterface {
    final IProcessModule<?> parentProcess;
    final IEventHandler eventHandler;
    DataComponent outputDef;
    DataEncoding outputEncoding;
    DataBlock lastRecord;
    long lastRecordTime = Long.MIN_VALUE;
    double avgSamplingPeriod = 1.0;
    int avgSampleCount = 0;

    protected DataQueue outputQueue = new DataQueue() {
        @Override
        public synchronized void publishData() {
            if (!sourceComponent.hasData()) {
                return;
            }
            DataBlock data = sourceComponent.getData();

            long now = System.currentTimeMillis();
            if (lastRecordTime != Long.MIN_VALUE) {
                double dT = (now - lastRecordTime) / 1000.0;
                avgSampleCount++;
                if (avgSampleCount == 1) {
                    avgSamplingPeriod = dT;
                } else {
                    avgSamplingPeriod += (dT - avgSamplingPeriod) / avgSampleCount;
                }
            }

            lastRecord = data;
            lastRecordTime = now;
            eventHandler.publish(new DataEvent(now, DataOutputInterface.this, data));
        }
    };

    public DataOutputInterface(
            D5OccupancyProcessModule parentProcess,
            AbstractSWEIdentifiable outputDescriptor,
            DataEncoding encoding) throws ProcessingException {
        this.parentProcess = parentProcess;
        this.outputDef = SMLHelper.getIOComponent(outputDescriptor);
        if (encoding != null) {
            this.outputEncoding = encoding;
        } else {
            this.outputEncoding = SMLHelper.getIOEncoding(outputDescriptor);
        }

        this.eventHandler = new BasicEventHandler();

        try {
            DataComponent execOutput = parentProcess.wrapperProcess.getOutputComponent(outputDef.getName());
            parentProcess.wrapperProcess.connect(execOutput, outputQueue);
        } catch (ProcessException e) {
            throw new ProcessingException("Error while connecting output " + outputDef.getName(), e);
        }
    }

    @Override
    public IDataProducer getParentProducer() {
        return parentProcess;
    }

    @Override
    public String getName() {
        return outputDef.getName();
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
