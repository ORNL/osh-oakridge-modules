package org.sensorhub.impl.utils.rad.eml;

public record EmlLaneParameters(
        String portId,
        int laneId,
        boolean collimated,
        double laneWidth,
        int intervals,
        int occupancyHoldin
) {
    public EmlLaneParameters {
        portId = (portId == null || portId.isBlank()) ? "OSCAR" : portId;
        laneWidth = laneWidth > 0.0 ? laneWidth : 4.82;
        intervals = intervals > 0 ? intervals : 5;
        occupancyHoldin = occupancyHoldin > 0 ? occupancyHoldin : 10;
    }
}
