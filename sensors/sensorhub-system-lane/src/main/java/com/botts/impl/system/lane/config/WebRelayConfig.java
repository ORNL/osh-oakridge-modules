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

package com.botts.impl.system.lane.config;

import org.sensorhub.api.config.DisplayInfo;

/**
 * Optional per-lane ControlByWeb WebRelay-Quad ({@code X-WR-4R3-E} / {@code X-WR-4R3-I})
 * configuration for controlling physical RPM pillar lights, audible sounders, and
 * optional auxiliary dry-contact outputs.
 *
 * <p>Strictly opt-in: when {@link #enabled} is {@code false} (the default), the lane
 * creates no background polling threads, opens no HTTP sockets, and displays no relay
 * indicators on operator views.</p>
 */
public class WebRelayConfig {

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_ALARM_PULSE_SECONDS = 5;
    public static final int MIN_ALARM_PULSE_SECONDS = 1;
    public static final int MAX_ALARM_PULSE_SECONDS = 300;
    public static final int DEFAULT_POLL_INTERVAL_SECONDS = 10;
    public static final int MIN_POLL_INTERVAL_SECONDS = 2;
    public static final int MAX_POLL_INTERVAL_SECONDS = 300;

    @DisplayInfo(
            label = "Enable WebRelay Lights & Sound",
            desc = "Master switch. When enabled, OSCAR connects to the lane's ControlByWeb WebRelay-Quad module(s) to manage physical RPM pillar lights and sounder."
    )
    public boolean enabled = false;

    @DisplayInfo(
            label = "Operating Mode",
            desc = "AUTO intercepts raw hardware signals and pulses lights/sounder only on actionable alarms; PASS_THROUGH leaves native RPM hardware relays active; MUTED silences automatic alarms."
    )
    public WebRelayOperatingMode mode = WebRelayOperatingMode.AUTO;

    @DisplayInfo(
            label = "Primary WebRelay Host",
            desc = "IPv4 address or hostname of the Primary WebRelay-Quad controlling RPM pillar lights and sounder."
    )
    public String primaryHost = "";

    @DisplayInfo(
            label = "Primary WebRelay Port",
            desc = "HTTP port of the Primary WebRelay-Quad (default: 80)."
    )
    public int primaryPort = DEFAULT_PORT;

    @DisplayInfo(
            label = "Enable Ancillary WebRelay",
            desc = "Enable when a second WebRelay-Quad is installed on the lane for external dry-contact alarm panels or gate-arm interlocks."
    )
    public boolean ancillaryEnabled = false;

    @DisplayInfo(
            label = "Ancillary WebRelay Host",
            desc = "IPv4 address or hostname of the optional Ancillary WebRelay-Quad."
    )
    public String ancillaryHost = "";

    @DisplayInfo(
            label = "Ancillary WebRelay Port",
            desc = "HTTP port of the optional Ancillary WebRelay-Quad (default: 80)."
    )
    public int ancillaryPort = DEFAULT_PORT;

    @DisplayInfo(
            label = "HTTP Control Username",
            desc = "Optional HTTP Basic username when Control Password protection is enabled on the WebRelay (default: admin or blank)."
    )
    public String username = "";

    @DisplayInfo(
            label = "HTTP Control Password",
            desc = "Optional HTTP Basic control password for WebRelay state requests (leave blank when Control Password is disabled on the module)."
    )
    @DisplayInfo.FieldType(DisplayInfo.FieldType.Type.PASSWORD)
    public String password = "";

    @DisplayInfo(
            label = "Alarm Pulse Duration (s)",
            desc = "Hardware-timed pulse duration in seconds (1-300) sent to Relay 3 / Relay 4 on actionable occupancy alarms (default: 5 seconds)."
    )
    public int alarmPulseSeconds = DEFAULT_ALARM_PULSE_SECONDS;

    @DisplayInfo(
            label = "Suppress on Code 9998 (Extended Occupancy Gamma)",
            desc = "When enabled (default), gamma-only extended occupancy alarms auto-adjudicated as Code 9998 do not trigger physical RPM lights or sounder."
    )
    public boolean suppressExtendedOccupancyGamma = true;

    @DisplayInfo(
            label = "Sound Siren on Neutron-Only Alarm",
            desc = "When enabled, neutron-only alarms pulse both Channel 2 (Neutron Blue Light) and Channel 1 (Gamma Red Light + Audible Sounder); disable to pulse Channel 2 only."
    )
    public boolean soundOnNeutronAlarm = false;

    @DisplayInfo(
            label = "Health Poll Interval (s)",
            desc = "Interval in seconds (2-300) for polling WebRelay XML state and reconciling armed coil state after power blips (default: 10 seconds)."
    )
    public int pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;

    public WebRelayConfig copy() {
        WebRelayConfig out = new WebRelayConfig();
        out.enabled = this.enabled;
        out.mode = this.mode != null ? this.mode : WebRelayOperatingMode.AUTO;
        out.primaryHost = this.primaryHost != null ? this.primaryHost.trim() : "";
        out.primaryPort = normalizePort(this.primaryPort);
        out.ancillaryEnabled = this.ancillaryEnabled;
        out.ancillaryHost = this.ancillaryHost != null ? this.ancillaryHost.trim() : "";
        out.ancillaryPort = normalizePort(this.ancillaryPort);
        out.username = this.username != null ? this.username.trim() : "";
        out.password = this.password != null ? this.password : "";
        out.alarmPulseSeconds = clamp(this.alarmPulseSeconds, MIN_ALARM_PULSE_SECONDS, MAX_ALARM_PULSE_SECONDS, DEFAULT_ALARM_PULSE_SECONDS);
        out.suppressExtendedOccupancyGamma = this.suppressExtendedOccupancyGamma;
        out.soundOnNeutronAlarm = this.soundOnNeutronAlarm;
        out.pollIntervalSeconds = clamp(this.pollIntervalSeconds, MIN_POLL_INTERVAL_SECONDS, MAX_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS);
        return out;
    }

    public static int normalizePort(int port) {
        return (port >= 1 && port <= 65535) ? port : DEFAULT_PORT;
    }

    public static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
