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

package com.botts.impl.service.oscar.reports.helpers;

import java.util.Locale;

public enum ReportFormat {
    PDF(".pdf", "application/pdf"),
    CSV(".csv", "text/csv"),
    JSON(".json", "application/json"),
    XLSX(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String extension;
    private final String mimeType;

    ReportFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public static ReportFormat parse(String raw) {
        if (raw == null || raw.isBlank() || "NONE".equalsIgnoreCase(raw.trim())) {
            return PDF;
        }
        try {
            return ReportFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PDF;
        }
    }

    public ReportFormat effectiveFor(ReportCmdType cmdType) {
        if (cmdType != null && cmdType.isAdjudicationDetails()) {
            return this;
        }
        return PDF;
    }
}
