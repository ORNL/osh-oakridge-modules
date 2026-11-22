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

/**
 * Operating mode for optional per-lane ControlByWeb WebRelay-Quad physical RPM
 * lights and sounder control.
 */
public enum WebRelayOperatingMode {
    /**
     * Energizes intercept relays (Relay 1 &amp; Relay 2) while idle so raw gross-count
     * threshold crossings do not fire pillar lights/sounders prematurely, and triggers
     * hardware-timed pulses on Relay 3 (Gamma Light &amp; Sounder) and/or Relay 4
     * (Neutron Light) only when an occupancy completes with an actionable alarm.
     */
    AUTO,

    /**
     * Releases all four relays (Relays 1–4 = OFF) so normally-closed contacts pass
     * native RPM hardware signals directly to the pillar lights, sounder, and
     * auxiliary dry-contact outputs.
     */
    PASS_THROUGH,

    /**
     * Holds intercept relays energized (Relays 1 &amp; 2 = ON, Relays 3 &amp; 4 = OFF) to
     * silence automatic occupancy lights and sounders while still allowing explicit
     * administrator test pulses from the control panel.
     */
    MUTED
}
