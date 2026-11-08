package org.sensorhub.impl.utils.rad.eml;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates pre-publication EML Non-Threat occupancy classifications between
 * the Rapiscan RPM driver and the enclosing LaneSystem so an automated
 * Adjudication command is persisted immediately once the Occupancy observation
 * receives its database ID.
 */
public final class PendingEmlAutoAdjudicationRegistry {
    private static final long STALE_ENTRY_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_ENTRIES_PER_PRODUCER = 256;

    public record Key(String producerUid, long samplingEpochMillis, int occupancyCount) {}

    public record Entry(EmlEvaluationResult result, long registeredAtMillis) {}

    private static final ConcurrentHashMap<Key, Entry> PENDING = new ConcurrentHashMap<>();

    private PendingEmlAutoAdjudicationRegistry() {}

    public static void register(String producerUid, double samplingTimeSeconds, int occupancyCount, EmlEvaluationResult result) {
        if (producerUid == null || producerUid.isBlank() || result == null)
            return;
        evictExpired();
        long samplingMillis = Math.round(samplingTimeSeconds * 1000.0);
        PENDING.put(new Key(producerUid, samplingMillis, occupancyCount),
                new Entry(result, System.currentTimeMillis()));
    }

    public static EmlEvaluationResult claim(String producerUid, double samplingTimeSeconds, int occupancyCount) {
        if (producerUid == null || producerUid.isBlank())
            return null;
        long samplingMillis = Math.round(samplingTimeSeconds * 1000.0);
        Entry exact = PENDING.remove(new Key(producerUid, samplingMillis, occupancyCount));
        if (exact != null)
            return exact.result();

        // Guard against +/-1ms floating-point rounding on samplingTimeSeconds serialization
        for (long delta : new long[]{-1L, 1L}) {
            Entry near = PENDING.remove(new Key(producerUid, samplingMillis + delta, occupancyCount));
            if (near != null)
                return near.result();
        }
        return null;
    }

    public static void remove(String producerUid, double samplingTimeSeconds, int occupancyCount) {
        claim(producerUid, samplingTimeSeconds, occupancyCount);
    }

    public static void clearProducer(String producerUid) {
        if (producerUid == null)
            return;
        PENDING.keySet().removeIf(k -> producerUid.equals(k.producerUid()));
    }

    private static void evictExpired() {
        if (PENDING.size() < MAX_ENTRIES_PER_PRODUCER)
            return;
        long cutoff = System.currentTimeMillis() - STALE_ENTRY_MILLIS;
        Iterator<Map.Entry<Key, Entry>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> entry = it.next();
            if (entry.getValue().registeredAtMillis() < cutoff)
                it.remove();
        }
    }
}
