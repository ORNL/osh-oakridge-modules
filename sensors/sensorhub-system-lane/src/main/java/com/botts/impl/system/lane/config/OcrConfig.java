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

import java.util.ArrayList;
import java.util.List;

/**
 * Per-lane asynchronous vehicle OCR settings. Strictly opt-in: when {@link #enabled} is
 * {@code false} (the default), the lane registers no {@code vehicleOcr} datastream, subscribes
 * to nothing, and loads no ONNX runtime or models.
 */
public class OcrConfig {

    @DisplayInfo(label = "Enable Vehicle OCR", desc = "Master switch. When enabled, camera recordings are asynchronously scanned in the background as system resources allow, and candidate container numbers and license plates are published on the lane's vehicleOcr datastream.")
    public boolean enabled = false;

    @DisplayInfo(label = "Alarms Only", desc = "When enabled (default), run OCR only on occupancies with a gamma or neutron alarm. Disable to run OCR on all recorded occupancies for this lane.")
    public boolean alarmsOnly = true;

    @DisplayInfo(label = "Model Directory", desc = "Directory containing det.onnx, rec.onnx, and dict.txt, relative to the node directory or absolute.")
    public String modelDir = "models/ocr";

    @DisplayInfo(label = "Read Container Numbers", desc = "Recognize and validate ISO 6346 shipping container numbers.")
    public boolean readContainerIds = true;

    @DisplayInfo(label = "Read License Plates", desc = "Recognize license plate numbers.")
    public boolean readPlates = true;

    @DisplayInfo(label = "Camera UIDs", desc = "Optional allowlist of camera system UIDs to scan. Leave empty to scan all cameras in this lane.")
    public List<String> cameraUids = new ArrayList<>();

    @DisplayInfo(label = "Frames Per Clip", desc = "Maximum number of keyframes sampled per recorded camera clip (1-12).")
    public int framesPerVideo = 6;

    @DisplayInfo(label = "Container Min Confidence", desc = "Container reads below this confidence (0.0-1.0) are dropped unless their ISO 6346 check digit validates.")
    public double containerMinConfidence = 0.50;

    @DisplayInfo(label = "Plate Min Confidence", desc = "License plate reads below this confidence (0.0-1.0) are dropped.")
    public double plateMinConfidence = 0.60;

    @DisplayInfo(label = "Ignore Top Band Fraction", desc = "Fraction of frame height (0.0-0.45) at the top edge to ignore so burned-in camera timestamps/OSD are excluded.")
    public double ignoreTopFraction = 0.0;

    @DisplayInfo(label = "Ignore Bottom Band Fraction", desc = "Fraction of frame height (0.0-0.45) at the bottom edge to ignore so burned-in camera captions/OSD are excluded.")
    public double ignoreBottomFraction = 0.0;
}
