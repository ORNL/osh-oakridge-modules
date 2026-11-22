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

import com.botts.impl.system.lane.config.WebRelayOperatingMode;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController.ControllerStatusSnapshot;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.command.CommandResult;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.IStreamingControlInterfaceWithResult;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.impl.utils.rad.RADHelper;
import org.vast.util.TimeExtent;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * OpenSensorHub command interface ({@code laneRelayControl}) for per-lane physical
 * RPM lights and sounder control via ControlByWeb WebRelay-Quad modules.
 */
public class LaneRelayControl extends AbstractSensorControl<LaneSystem> implements IStreamingControlInterfaceWithResult {

    public static final String NAME = "laneRelayControl";
    public static final String LABEL = "RPM Lights & Sound Relay Control";
    public static final String DESCRIPTION = "Controls per-lane physical RPM pillar lights and sounder via ControlByWeb WebRelay-Quad";

    public static final String ACTION_PULSE_GAMMA_SOUND = "PULSE_GAMMA_SOUND";
    public static final String ACTION_PULSE_NEUTRON_LIGHT = "PULSE_NEUTRON_LIGHT";
    public static final String ACTION_PULSE_ALL = "PULSE_ALL";
    public static final String ACTION_LATCH_GAMMA_SOUND = "LATCH_GAMMA_SOUND";
    public static final String ACTION_LATCH_NEUTRON_LIGHT = "LATCH_NEUTRON_LIGHT";
    public static final String ACTION_LATCH_ALL = "LATCH_ALL";
    public static final String ACTION_SILENCE_ARMED = "SILENCE_ARMED";
    public static final String ACTION_PASS_THROUGH = "PASS_THROUGH";
    public static final String ACTION_SET_MODE_AUTO = "SET_MODE_AUTO";
    public static final String ACTION_SET_MODE_MUTED = "SET_MODE_MUTED";
    public static final String ACTION_SET_MODE_PASS_THROUGH = "SET_MODE_PASS_THROUGH";
    public static final String ACTION_REFRESH_STATUS = "REFRESH_STATUS";

    private final DataComponent commandStructure;
    private final DataComponent resultStructure;

    protected LaneRelayControl(LaneSystem parentSensor) {
        super(NAME, parentSensor);
        RADHelper fac = new RADHelper();

        this.commandStructure = fac.createRecord()
                .name(NAME)
                .label(LABEL)
                .description(DESCRIPTION)
                .definition(RADHelper.getRadUri("LaneRelayControl"))
                .addField("action", fac.createCategory()
                        .label("Action")
                        .description("Relay action to execute on this lane")
                        .addAllowedValues(
                                ACTION_PULSE_GAMMA_SOUND,
                                ACTION_PULSE_NEUTRON_LIGHT,
                                ACTION_PULSE_ALL,
                                ACTION_LATCH_GAMMA_SOUND,
                                ACTION_LATCH_NEUTRON_LIGHT,
                                ACTION_LATCH_ALL,
                                ACTION_SILENCE_ARMED,
                                ACTION_PASS_THROUGH,
                                ACTION_SET_MODE_AUTO,
                                ACTION_SET_MODE_MUTED,
                                ACTION_SET_MODE_PASS_THROUGH,
                                ACTION_REFRESH_STATUS
                        )
                        .build())
                .addField("durationSeconds", fac.createCount()
                        .label("Pulse Duration (s)")
                        .description("Optional pulse duration override in seconds (0 uses the lane default)")
                        .build())
                .build();

        this.resultStructure = fac.createRecord()
                .name("laneRelayResult")
                .label("RPM Lights & Sound Status")
                .definition(RADHelper.getRadUri("LaneRelayStatus"))
                .addField("primaryOnline", fac.createBoolean().label("Primary Online").build())
                .addField("mode", fac.createText().label("Operating Mode").build())
                .addField("relay1", fac.createCount().label("Relay 1 (Gamma Intercept)").build())
                .addField("relay2", fac.createCount().label("Relay 2 (Neutron Intercept)").build())
                .addField("relay3", fac.createCount().label("Relay 3 (Gamma Light & Sound)").build())
                .addField("relay4", fac.createCount().label("Relay 4 (Neutron Light)").build())
                .addField("summary", fac.createText().label("Summary").build())
                .build();
    }

    @Override
    public DataComponent getCommandDescription() {
        return commandStructure;
    }

    @Override
    public DataComponent getResultDescription() {
        return resultStructure;
    }

    @Override
    public CompletableFuture<ICommandStatus> submitCommand(ICommandData command) {
        Instant start = Instant.now();
        DataBlock params = command.getParams();
        return CompletableFuture.supplyAsync(() -> {
            try {
                LaneWebRelayController controller = getParentProducer().getWebRelayController();
                if (controller == null) {
                    return CommandStatus.failed(command.getID(),
                            "WebRelay Lights & Sound control is not enabled on lane " + getParentProducer().getConfiguration().name);
                }

                String rawAction = params != null ? params.getStringValue(0) : ACTION_REFRESH_STATUS;
                int dur = params != null && params.getAtomCount() > 1 ? params.getIntValue(1) : 0;
                Integer overrideSec = dur > 0 ? dur : null;

                String action = rawAction != null ? rawAction.trim().toUpperCase(Locale.ROOT) : ACTION_REFRESH_STATUS;
                ControllerStatusSnapshot snap = switch (action) {
                    case ACTION_PULSE_GAMMA_SOUND -> controller.pulseGammaAndSound(overrideSec);
                    case ACTION_PULSE_NEUTRON_LIGHT -> controller.pulseNeutronLight(overrideSec);
                    case ACTION_PULSE_ALL -> controller.pulseAllIndicators(overrideSec);
                    case ACTION_LATCH_GAMMA_SOUND -> controller.latchIndicators(true, false);
                    case ACTION_LATCH_NEUTRON_LIGHT -> controller.latchIndicators(false, true);
                    case ACTION_LATCH_ALL -> controller.latchIndicators(true, true);
                    case ACTION_SILENCE_ARMED -> controller.silenceAndArm();
                    case ACTION_PASS_THROUGH -> controller.releaseToPassThrough();
                    case ACTION_SET_MODE_AUTO -> controller.setOperatingMode(WebRelayOperatingMode.AUTO);
                    case ACTION_SET_MODE_MUTED -> controller.setOperatingMode(WebRelayOperatingMode.MUTED);
                    case ACTION_SET_MODE_PASS_THROUGH -> controller.setOperatingMode(WebRelayOperatingMode.PASS_THROUGH);
                    case ACTION_REFRESH_STATUS -> controller.pollAndReconcileNow();
                    default -> throw new IllegalArgumentException("Unsupported laneRelayControl action: " + rawAction);
                };

                if (!snap.primaryState().online()) {
                    return CommandStatus.failed(command.getID(),
                            "Primary WebRelay unreachable: " + snap.primaryState().error());
                }

                DataBlock res = resultStructure.createDataBlock();
                res.setBooleanValue(0, snap.primaryState().online());
                res.setStringValue(1, snap.mode().name());
                res.setIntValue(2, snap.primaryState().relay1());
                res.setIntValue(3, snap.primaryState().relay2());
                res.setIntValue(4, snap.primaryState().relay3());
                res.setIntValue(5, snap.primaryState().relay4());
                res.setStringValue(6, snap.lastActionSummary());

                return new CommandStatus.Builder()
                        .withCommand(command.getID())
                        .withStatusCode(ICommandStatus.CommandStatusCode.COMPLETED)
                        .withResult(CommandResult.withData(res))
                        .withExecutionTime(TimeExtent.endNow(start))
                        .build();
            } catch (Exception ex) {
                return CommandStatus.failed(command.getID(), ex.getMessage());
            }
        });
    }
}
