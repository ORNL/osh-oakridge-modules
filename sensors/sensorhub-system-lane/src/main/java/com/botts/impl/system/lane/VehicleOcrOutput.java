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

package com.botts.impl.system.lane;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.VarRateSensorOutput;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.sensorhub.impl.utils.rad.model.VehicleOcrResult;
import org.vast.data.TextEncodingImpl;

import java.time.Instant;

/**
 * Lane output carrying asynchronous OCR candidate vehicle IDs (ISO 6346 container numbers and
 * license plates) read from camera recordings. Registered only when {@code ocrConfig.enabled} is
 * {@code true} on the lane.
 */
public class VehicleOcrOutput extends VarRateSensorOutput<LaneSystem> {

    public static final String NAME = "vehicleOcr";

    private final DataComponent recordStruct;
    private final DataEncoding recordEncoding;

    public VehicleOcrOutput(LaneSystem parentSensor) {
        super(NAME, parentSensor, 0);
        this.recordStruct = new RADHelper().createVehicleOcrRecord();
        this.recordEncoding = new TextEncodingImpl();
    }

    public void publishData(VehicleOcrResult result) {
        if (result == null)
            return;
        DataBlock dataBlock = VehicleOcrResult.createDataBlock(recordStruct, result);
        Instant sampleTs = result.getSampleTime() != null ? result.getSampleTime() : Instant.now();
        latestRecord = dataBlock;
        latestRecordTime = sampleTs.toEpochMilli();
        eventHandler.publish(new DataEvent(latestRecordTime, VehicleOcrOutput.this, dataBlock));
    }

    @Override
    public DataComponent getRecordDescription() {
        return recordStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return recordEncoding;
    }
}
