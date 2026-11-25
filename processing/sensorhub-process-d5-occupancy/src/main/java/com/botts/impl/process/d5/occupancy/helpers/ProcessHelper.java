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

import net.opengis.gml.v32.impl.ReferenceImpl;
import net.opengis.sensorml.v20.IOPropertyList;
import net.opengis.sensorml.v20.impl.SettingsImpl;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import org.vast.process.ExecutableProcessImpl;
import org.vast.process.ProcessException;
import org.vast.sensorML.AggregateProcessImpl;
import org.vast.sensorML.LinkImpl;
import org.vast.sensorML.SMLStaxBindings;
import org.vast.sensorML.SMLUtils;
import org.vast.sensorML.SimpleProcessImpl;
import org.vast.xml.XMLWriterException;

import java.io.OutputStream;

public class ProcessHelper extends SMLUtils {

    AggregateProcessImpl aggregateProcess;
    ReferenceImpl controlType;
    ReferenceImpl sourceType;

    public ProcessHelper() {
        super(V2_0);
        controlType = new ReferenceImpl("urn:osh:process:datasink:commandstream");
        sourceType = new ReferenceImpl("urn:osh:process:datasource:stream");
        aggregateProcess = new AggregateProcessImpl();
    }

    public ProcessHelper(SMLStaxBindings staxBindings) {
        super(staxBindings);
    }

    public void writeXML(OutputStream outputStream) throws XMLWriterException {
        writeProcess(outputStream, aggregateProcess, true);
    }

    public void addOutput(DataRecord output) {
        aggregateProcess.addOutput(output.getName(), output);
    }

    public void addOutput(String name, DataRecord output) {
        aggregateProcess.addOutput(name, output);
    }

    public void addOutputList(IOPropertyList outputs) {
        for (AbstractSWEIdentifiable output : outputs) {
            DataComponent outputData = (DataComponent) output;
            aggregateProcess.addOutput(outputData.getName(), outputData);
        }
    }

    public void addInput(DataRecord input) {
        aggregateProcess.addInput(input.getName(), input);
    }

    public void addInput(String name, DataRecord input) {
        aggregateProcess.addInput(name, input);
    }

    public void addProcess(String name, ExecutableProcessImpl process) throws ProcessException {
        process.init();
        SimpleProcessImpl execProcess = new SimpleProcessImpl();
        execProcess.setExecutableImpl(process);
        aggregateProcess.addComponent(name, execProcess);
    }

    public void addDataSource(String name, String systemUID) {
        SimpleProcessImpl source = new SimpleProcessImpl();
        source.setTypeOf(sourceType);
        SettingsImpl settings = new SettingsImpl();
        settings.addSetValue("parameters/producerURI", systemUID);
        source.setConfiguration(settings);
        aggregateProcess.addComponent(name, source);
    }

    public void addControlStream(String name, String systemUID, String inputName) {
        SimpleProcessImpl control = new SimpleProcessImpl();
        control.setTypeOf(controlType);
        SettingsImpl settings = new SettingsImpl();
        settings.addSetValue("parameters/systemUID", systemUID);
        settings.addSetValue("parameters/inputName", inputName);
        control.setConfiguration(settings);
        aggregateProcess.addComponent(name, control);
    }

    public void addConnection(String source, String destination) {
        aggregateProcess.addConnection(new LinkImpl(source, destination));
    }

    public AggregateProcessImpl getAggregateProcess() {
        return aggregateProcess;
    }
}
