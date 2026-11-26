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

package com.botts.impl.system.lane.mobile;

import com.botts.impl.sensor.kromek.d5.D5Config;
import com.botts.impl.sensor.rs350.RS350Config;
import com.botts.impl.system.lane.LaneSystem;
import com.botts.impl.system.lane.config.D5RPMConfig;
import com.botts.impl.system.lane.config.LaneOptionsConfig;
import com.botts.impl.system.lane.config.RS350RPMConfig;
import com.botts.impl.system.lane.mobile.config.MobileDetectorConfig;
import net.opengis.sensorml.v20.KeywordList;
import org.sensorhub.api.common.SensorHubException;

/**
 * Lane system specialization for mobile radiation detectors (RS-350 backpack,
 * Kromek D5 walker). Shares the {@link LaneSystem} runtime and system UID scheme
 * ({@code urn:osh:system:lane:*}) so the OSCAR viewer and ConSys APIs treat it
 * as a first-class lane, while enforcing mobile-only constraints, enabling child
 * GPS {@code location} datastreams, disabling portal Extended Occupancy faults,
 * and advertising {@link #MOBILE_DETECTOR_SYSTEM_DEFINITION} and
 * {@link #MOBILE_DETECTOR_KEYWORD} in SensorML.
 */
public class MobileDetectorSystem extends LaneSystem {

    public static final String MOBILE_DETECTOR_SYSTEM_DEFINITION =
            "http://sensorml.com/ont/swe/system/MobileDetectorLane";
    public static final String MOBILE_DETECTOR_KEYWORD = "oscar:mobile-detector:true";

    @Override
    protected void doInit() throws SensorHubException {
        var cfg = getConfiguration();
        if (cfg != null) {
            if (cfg.detectorConfig != null) {
                if (cfg.laneOptionsConfig == null) {
                    cfg.laneOptionsConfig = new LaneOptionsConfig();
                }
                cfg.laneOptionsConfig.rpmConfig = cfg.detectorConfig;
            } else if (cfg.laneOptionsConfig != null && cfg.laneOptionsConfig.rpmConfig != null) {
                cfg.detectorConfig = cfg.laneOptionsConfig.rpmConfig;
            }

            if (cfg.detectorConfig != null
                    && !(cfg.detectorConfig instanceof RS350RPMConfig || cfg.detectorConfig instanceof D5RPMConfig)) {
                throw new SensorHubException("Mobile Detector System supports RS-350 or Kromek D5 detectors only");
            }

            if (cfg.laneOptionsConfig != null) {
                cfg.laneOptionsConfig.ffmpegConfig = null;
            }
            if (cfg.ocrConfig != null) {
                cfg.ocrConfig.enabled = false;
            }
            if (cfg.webRelayConfig != null) {
                cfg.webRelayConfig.enabled = false;
            }

            enableChildLocationOutputs(cfg);
        }

        super.doInit();
    }

    private void enableChildLocationOutputs(MobileDetectorConfig cfg) {
        if (cfg.subsystems != null) {
            for (var sub : cfg.subsystems) {
                if (sub == null || sub.config == null) {
                    continue;
                }
                if (sub.config instanceof RS350Config rs350Cfg && rs350Cfg.outputs != null) {
                    rs350Cfg.outputs.enableLocationOutput = true;
                } else if (sub.config instanceof D5Config d5Cfg && d5Cfg.outputs != null) {
                    d5Cfg.outputs.enableLocationOutput = true;
                }
            }
        }
        for (var member : getMembers().values()) {
            if (member == null || member.getConfiguration() == null) {
                continue;
            }
            if (member.getConfiguration() instanceof RS350Config rs350Cfg && rs350Cfg.outputs != null) {
                rs350Cfg.outputs.enableLocationOutput = true;
            } else if (member.getConfiguration() instanceof D5Config d5Cfg && d5Cfg.outputs != null) {
                d5Cfg.outputs.enableLocationOutput = true;
            }
        }
    }

    @Override
    protected boolean isExtendedOccupancyMonitoringEnabled() {
        return false;
    }

    @Override
    protected String getSystemDefinitionUri() {
        return MOBILE_DETECTOR_SYSTEM_DEFINITION;
    }

    @Override
    protected void contributeAdditionalKeywords(KeywordList keywords) {
        keywords.addKeyword(MOBILE_DETECTOR_KEYWORD);
    }

    @Override
    public MobileDetectorConfig getConfiguration() {
        return (MobileDetectorConfig) this.config;
    }
}
