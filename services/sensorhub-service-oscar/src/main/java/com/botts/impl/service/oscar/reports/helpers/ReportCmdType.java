package com.botts.impl.service.oscar.reports.helpers;

public enum ReportCmdType {
    RDS_SITE,
    LANE,
    EVENT,
    ADJUDICATION,
    ADJUDICATION_DETAILS,
    ADJUDICATION_DETAILS_PDF,
    ADJUDICATION_DETAILS_CSV,
    ADJUDICATION_DETAILS_JSON,
    ADJUDICATION_DETAILS_XLSX;

    public boolean isAdjudicationDetails() {
        return this == ADJUDICATION_DETAILS
                || this == ADJUDICATION_DETAILS_PDF
                || this == ADJUDICATION_DETAILS_CSV
                || this == ADJUDICATION_DETAILS_JSON
                || this == ADJUDICATION_DETAILS_XLSX;
    }

    public ReportCmdType baseType() {
        return isAdjudicationDetails() ? ADJUDICATION_DETAILS : this;
    }

    public ReportFormat defaultFormat() {
        return switch (this) {
            case ADJUDICATION_DETAILS_CSV -> ReportFormat.CSV;
            case ADJUDICATION_DETAILS_JSON -> ReportFormat.JSON;
            case ADJUDICATION_DETAILS_XLSX -> ReportFormat.XLSX;
            default -> ReportFormat.PDF;
        };
    }
}
