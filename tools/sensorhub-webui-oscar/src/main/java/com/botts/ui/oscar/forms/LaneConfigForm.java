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

package com.botts.ui.oscar.forms;

import static org.sensorhub.ui.AdminI18n.trConfig;

import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.LaneConfig;
import com.botts.impl.system.lane.config.WebRelayConfig;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController;
import com.botts.impl.system.lane.helpers.webrelay.LaneWebRelayController.ControllerStatusSnapshot;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.EndpointSpec;
import com.botts.impl.system.lane.helpers.webrelay.WebRelayQuadClient.RelayStateSnapshot;
import com.vaadin.ui.Button;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Panel;
import com.vaadin.ui.VerticalLayout;
import org.sensorhub.ui.DisplayUtils;
import org.sensorhub.ui.GenericConfigForm;
import org.sensorhub.ui.api.IModuleConfigForm;
import org.sensorhub.ui.data.BaseProperty;
import org.sensorhub.ui.data.MyBeanItem;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom SensorHub Admin form for {@link LaneConfig} and nested {@link WebRelayConfig}
 * providing localized RPM/camera type pickers and live per-lane physical RPM lights &amp;
 * sounder controls via ControlByWeb WebRelay-Quad.
 *
 * @author Alex Almanza
 * @author Kalyn Stricklin
 * @since May 13 2025
 */
@SuppressWarnings({"serial", "deprecation"})
public class LaneConfigForm extends GenericConfigForm {

    private static final String LANE_CONFIG_PACKAGE = "com.botts.impl.system.lane.config.";
    private static final String PROP_RPM = "laneOptionsConfig.rpmConfig";
    private static final String PROP_CAMERA = "laneOptionsConfig.ffmpegConfig";

    @Override
    public void build(String title, String popupText, MyBeanItem<Object> beanItem, boolean includeSubForms) {
        super.build(title, popupText, beanItem, includeSubForms);
        if (beanItem != null && beanItem.getBean() instanceof WebRelayConfig webRelayBean) {
            addComponent(buildWebRelayLiveControlPanel(webRelayBean));
        }
    }

    private Panel buildWebRelayLiveControlPanel(WebRelayConfig editedRelayBean) {
        Panel panel = new Panel(trConfig(
                LaneConfigForm.class,
                "ui.webrelay.panelTitle",
                "Live RPM Lights & Sound Control (WebRelay-Quad)"));
        panel.setWidth(100.0f, Unit.PERCENTAGE);

        VerticalLayout content = new VerticalLayout();
        content.setMargin(true);
        content.setSpacing(true);

        Label statusLabel = new Label(formatInitialStatus(editedRelayBean));
        statusLabel.setWidth(100.0f, Unit.PERCENTAGE);
        content.addComponent(statusLabel);

        HorizontalLayout buttonRow = new HorizontalLayout();
        buttonRow.setSpacing(true);

        Button testBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.testConnection", "Test / Refresh"));
        Button pulseGammaBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.pulseGamma", "Pulse Gamma Light & Sound"));
        Button pulseNeutronBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.pulseNeutron", "Pulse Neutron Light"));
        Button pulseAllBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.pulseAll", "Pulse All"));
        Button silenceBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.silence", "Silence (Armed)"));
        Button passThroughBtn = new Button(trConfig(
                LaneConfigForm.class, "ui.webrelay.passThrough", "Hardware Pass-Through"));

        testBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "test", statusLabel));
        pulseGammaBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "pulse-gamma", statusLabel));
        pulseNeutronBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "pulse-neutron", statusLabel));
        pulseAllBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "pulse-all", statusLabel));
        silenceBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "silence", statusLabel));
        passThroughBtn.addClickListener(e -> executeAdminRelayAction(editedRelayBean, "pass-through", statusLabel));

        buttonRow.addComponents(testBtn, pulseGammaBtn, pulseNeutronBtn, pulseAllBtn, silenceBtn, passThroughBtn);
        content.addComponent(buttonRow);
        panel.setContent(content);
        return panel;
    }

    private void executeAdminRelayAction(WebRelayConfig editedBean, String action, Label statusLabel) {
        try {
            if (fieldGroup != null) {
                fieldGroup.commit();
            }
            LaneSystem lane = resolveEnclosingLaneSystem();
            LaneWebRelayController ctrl = lane != null ? lane.getWebRelayController() : null;

            if (ctrl != null && !"test".equals(action)) {
                ControllerStatusSnapshot snap = switch (action) {
                    case "pulse-gamma" -> ctrl.pulseGammaAndSound(null);
                    case "pulse-neutron" -> ctrl.pulseNeutronLight(null);
                    case "pulse-all" -> ctrl.pulseAllIndicators(null);
                    case "silence" -> ctrl.silenceAndArm();
                    case "pass-through" -> ctrl.releaseToPassThrough();
                    default -> ctrl.pollAndReconcileNow();
                };
                statusLabel.setValue(formatSnapshotText(snap.primaryState(), snap.lastActionSummary()));
                return;
            }

            // Direct ad-hoc execution against the form's Primary WebRelay settings (works even before Apply)
            EndpointSpec ep = new EndpointSpec(
                    editedBean.primaryHost,
                    WebRelayConfig.normalizePort(editedBean.primaryPort),
                    editedBean.username,
                    editedBean.password
            );
            WebRelayQuadClient client = new WebRelayQuadClient(Duration.ofMillis(2500), Duration.ofMillis(3500));
            int pulseSec = WebRelayConfig.clamp(
                    editedBean.alarmPulseSeconds,
                    WebRelayConfig.MIN_ALARM_PULSE_SECONDS,
                    WebRelayConfig.MAX_ALARM_PULSE_SECONDS,
                    WebRelayConfig.DEFAULT_ALARM_PULSE_SECONDS
            );
            RelayStateSnapshot snap = switch (action) {
                case "pulse-gamma" -> client.pulseIndicators(ep, true, false, pulseSec);
                case "pulse-neutron" -> client.pulseIndicators(ep, false, true, pulseSec);
                case "pulse-all" -> client.pulseIndicators(ep, true, true, pulseSec);
                case "silence" -> client.setArmedIntercept(ep);
                case "pass-through" -> client.setPassThrough(ep);
                default -> client.readState(ep);
            };
            statusLabel.setValue(formatSnapshotText(snap, action));
        } catch (Exception ex) {
            DisplayUtils.showErrorPopup(trConfig(
                    LaneConfigForm.class,
                    "ui.webrelay.actionFailed",
                    "WebRelay command failed"), ex);
        }
    }

    private String formatInitialStatus(WebRelayConfig bean) {
        LaneSystem lane = resolveEnclosingLaneSystem();
        if (lane != null && lane.getWebRelayController() != null) {
            ControllerStatusSnapshot snap = lane.getWebRelayController().getStatusSnapshot();
            return formatSnapshotText(snap.primaryState(), snap.lastActionSummary());
        }
        if (bean.primaryHost == null || bean.primaryHost.isBlank()) {
            return trConfig(
                    LaneConfigForm.class,
                    "ui.webrelay.notConfigured",
                    "Enter Primary WebRelay Host above and click Test / Refresh to verify connectivity.");
        }
        return bean.primaryHost + ":" + WebRelayConfig.normalizePort(bean.primaryPort);
    }

    private static String formatSnapshotText(RelayStateSnapshot s, String note) {
        if (s == null || !s.online()) {
            return "Offline — " + (s != null ? s.error() : "No response");
        }
        return String.format(
                "Online (%d ms, %s) · R1:%s R2:%s R3(Gamma+Sound):%s R4(Neutron):%s%s",
                s.latencyMs(),
                s.endpointPath(),
                s.relay1() == 1 ? "ON" : "OFF",
                s.relay2() == 1 ? "ON" : "OFF",
                s.relay3() == 1 ? "ON" : "OFF",
                s.relay4() == 1 ? "ON" : "OFF",
                (note != null && !note.isBlank()) ? " — " + note : ""
        );
    }

    private LaneSystem resolveEnclosingLaneSystem() {
        try {
            var hub = getParentHub();
            if (hub != null && hub.getModuleRegistry() != null) {
                for (LaneSystem lane : hub.getModuleRegistry().getLoadedModules(LaneSystem.class)) {
                    if (lane.getConfiguration() != null
                            && this.fieldGroup != null
                            && this.fieldGroup.getItemDataSource() instanceof MyBeanItem<?> bi
                            && lane.getConfiguration().webRelayConfig == bi.getBean()) {
                        return lane;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public Map<String, Class<?>> getPossibleTypes(String propId, BaseProperty<?> prop) {
        if (propId.equals(PROP_RPM)) {
            Map<String, Class<?>> classList = new LinkedHashMap<>();
            try {
                addTranslatedType(classList, "AspectRPMConfig", "Aspect");
                addTranslatedType(classList, "RapiscanRPMConfig", "Rapiscan");
                addTranslatedType(classList, "RS350RPMConfig", "RS350");
                addTranslatedType(classList, "D5RPMConfig", "Kromek D5");
            } catch (ClassNotFoundException e) {
                getOshLogger().error("Cannot find RPM class", e);
            }
            return classList;
        } else if (propId.equals(PROP_CAMERA)) {
            Map<String, Class<?>> classList = new LinkedHashMap<>();
            try {
                addTranslatedType(classList, "SonyCameraConfig", "Sony");
                addTranslatedType(classList, "AxisCameraConfig", "Axis");
                addTranslatedType(classList, "CustomCameraConfig", "Custom");
            } catch (ClassNotFoundException e) {
                getOshLogger().error("Cannot find Camera class", e);
            }
            return classList;
        }

        return super.getPossibleTypes(propId, prop);
    }

    private void addTranslatedType(Map<String, Class<?>> classList, String className, String fallback)
            throws ClassNotFoundException {
        Class<?> configClass = Class.forName(LANE_CONFIG_PACKAGE + className);
        classList.put(trConfig(configClass, "type.name", fallback), configClass);
    }
}
