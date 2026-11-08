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

import com.botts.impl.service.oscar.OSCARServiceModule;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public class Utils {

    public static String gammaNeutronAlarmCQL = "gammaAlarm = true AND neutronAlarm = true";
    public static String gammaAlarmCQL = "gammaAlarm = true AND neutronAlarm = false";
    public static String neutronAlarmCQL = "gammaAlarm = false AND neutronAlarm = true";
    public static String anyOccupancyAlarmCQL = "gammaAlarm = true OR neutronAlarm = true";
    public static String faultCQL = "alarmState = 'Fault - Neutron High' OR alarmState = 'Fault - Gamma Low' OR alarmState = 'Fault - Gamma High' OR alarmState = 'Fault - Extended Occupancy'";
    public static String gammaFaultCQL = "alarmState = 'Fault - Gamma Low' OR alarmState = 'Fault - Gamma High'";
    public static String gammaHighFaultCQL = "alarmState = 'Fault - Gamma High'";
    public static String gammaLowFaultCQL = "alarmState = 'Fault - Gamma Low'";
    public static String neutronFaultCQL = "alarmState = 'Fault - Neutron High'";
    public static String extendedOccupancyFaultCQL = "alarmState = 'Fault - Extended Occupancy'";
    public static String tamperCQL = "tamperStatus = true";
    public static String emlSuppressedCQL = "result = 'RELEASE' OR result = 1";

    public static long calcEMLAlarmRate(long emlSuppCount, long alarmCount) {
        if (emlSuppCount + alarmCount == 0) {
            return 0;
        }
        return Math.round(calcEMLAlarmRatePct(emlSuppCount, alarmCount));
    }

    public static long calculateAlarmingOccRate(long alarmCount, long occupancyCount) {
        if (occupancyCount == 0) {
            return 0;
        }
        return Math.round(calculateAlarmingOccRatePct(alarmCount, occupancyCount));
    }

    public static double calcEMLAlarmRatePct(long emlSuppCount, long alarmCount) {
        if (emlSuppCount <= 0) {
            return 0.0;
        }
        long denom = Math.max(alarmCount, emlSuppCount);
        if (denom <= 0) {
            return 0.0;
        }
        return (emlSuppCount * 100.0) / denom;
    }

    public static double calculateAlarmingOccRatePct(long alarmCount, long occupancyCount) {
        if (occupancyCount <= 0) {
            return 0.0;
        }
        return (alarmCount * 100.0) / occupancyCount;
    }

    public static String formatPercentage(double percentage) {
        if (!Double.isFinite(percentage) || percentage <= 0.0) {
            return "0.00%";
        }
        return String.format(Locale.US, "%.2f%%", percentage);
    }

    public static long countObservations(OSCARServiceModule module, String cqlValue, Instant begin, Instant end, String... observedProperties) {
        return countObservationsForLanes(module, null, cqlValue, begin, end, observedProperties);
    }

    public static long countObservationsForLanes(OSCARServiceModule module, String laneUIDs, String cqlValue,
                                                 Instant begin, Instant end, String... observedProperties) {
        DataStreamFilter.Builder dataStreamFilter = new DataStreamFilter.Builder()
                .withObservedProperties(observedProperties);

        String[] scopedLaneUIDs = parseLaneUIDs(laneUIDs);
        if (scopedLaneUIDs.length > 0) {
            dataStreamFilter.withSystems()
                    .withUniqueIDs(scopedLaneUIDs)
                    .includeMembers(true)
                    .done();
        }

        ObsFilter.Builder builder = new ObsFilter.Builder()
                .withResultTimeDuring(begin, end)
                .withDataStreams(dataStreamFilter.build());

        if (cqlValue != null && !cqlValue.isBlank()) {
            builder.withCQLFilter(cqlValue);
        }

        return module.getParentHub().getDatabaseRegistry().getFederatedDatabase().getObservationStore().countMatchingEntries(builder.build());
    }

    public static long countStatusResults(String laneUID, OSCARServiceModule module, Predicate<ICommandStatus> valuePredicate, Instant begin, Instant end) {
        return module.getParentHub().getDatabaseRegistry().getFederatedDatabase().getCommandStatusStore().select(new CommandStatusFilter.Builder()
                        .withStatus(ICommandStatus.CommandStatusCode.COMPLETED)
                        .withReportTimeDuring(begin, end)
                        .withValuePredicate(valuePredicate)
                        .withCommands(new CommandFilter.Builder().withSystems().withUniqueIDs(laneUID).done().build())
                        .build())
                .count();
    }

    // suppressed : RPM Gamma Alert = true but the EML Gamma Alert = false (release)
    // so count how many times the RPM had a true alarm but the EML decided it was false

    public static long countObservationsFromLane(String laneUID, OSCARServiceModule module, String cqlValue, Instant begin, Instant end, String... observedProperties) {
        ObsFilter.Builder builder = new ObsFilter.Builder()
                .withResultTimeDuring(begin, end)
                .withDataStreams(new DataStreamFilter.Builder()
                        .withSystems().withUniqueIDs(laneUID).includeMembers(true).done()
                        .withObservedProperties(observedProperties)
                        .build());

        if (cqlValue != null && !cqlValue.isBlank()) {
            builder.withCQLFilter(cqlValue);
        }

        return module.getParentHub().getDatabaseRegistry().getFederatedDatabase().getObservationStore().countMatchingEntries(builder.build());
    }

    public static Map<Instant, Long> countObservationsByDay(OSCARServiceModule module, String cqlValue, Instant startDate, Instant endDate, String... observedProperties) {
        return countObservationsByDayForLanes(module, null, cqlValue, startDate, endDate, observedProperties);
    }

    public static Map<Instant, Long> countObservationsByDayForLanes(OSCARServiceModule module, String laneUIDs,
                                                                    String cqlValue, Instant startDate,
                                                                    Instant endDate, String... observedProperties) {
        Map<Instant, Long> result = new LinkedHashMap<>();

        var start = startDate;
        var end = endDate;

        while (start.isBefore(end)) {
            Instant currentDay = start;
            Instant endOfCurrentDay = currentDay.plus(1, ChronoUnit.DAYS);

            if (endOfCurrentDay.isAfter(end)) {
                endOfCurrentDay = end;
            }

            long count = countObservationsForLanes(
                    module, laneUIDs, cqlValue, currentDay, endOfCurrentDay, observedProperties);

            result.put(currentDay, count);

            start = endOfCurrentDay;
        }

        return result;
    }

    public static String[] parseLaneUIDs(String laneUIDs) {
        if (laneUIDs == null || laneUIDs.isBlank() || laneUIDs.equals("NONE")) {
            return new String[0];
        }

        return Arrays.stream(laneUIDs.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank() && !value.equalsIgnoreCase("NONE"))
                .distinct()
                .toArray(String[]::new);
    }

    public static Iterator<ICommandStatus> queryCommandStatus(OSCARServiceModule module, String laneUID, Instant begin, Instant end) {
        return module.getParentHub().getDatabaseRegistry().getFederatedDatabase().getCommandStatusStore().select(new CommandStatusFilter.Builder()
                .withStatus(ICommandStatus.CommandStatusCode.COMPLETED)
                .withReportTimeDuring(begin, end)
                .withCommands(new CommandFilter.Builder().withSystems().withUniqueIDs(laneUID).done()
                        .build()).build()).iterator();
    }
}
