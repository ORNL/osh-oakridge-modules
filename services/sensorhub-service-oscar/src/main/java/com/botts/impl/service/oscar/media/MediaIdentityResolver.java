package com.botts.impl.service.oscar.media;

import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.system.SystemFilter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves stable public observation, lane, and camera identities from datastore records. */
public final class MediaIdentityResolver {
    private static final String LANE_UID_PREFIX = "urn:osh:system:lane:";
    private static final List<String> CHILD_DETECTOR_UID_PREFIXES = List.of(
            "urn:osh:sensor:rapiscan:",
            "urn:osh:sensor:aspect:",
            "urn:osh:sensor:rs350:",
            "urn:osh:sensor:kromek:d5:",
            "urn:osh:process:d5-occupancy:",
            "urn:osh:process:kromek:d5:");
    private final ISensorHub hub;
    private volatile Map<String, String> cameraUidsByPathToken = Map.of();

    public MediaIdentityResolver(ISensorHub hub) {
        this.hub = hub;
    }

    public String publicObservationId(BigId observationId) {
        return hub.getIdEncoders().getObsIdEncoder().encodeID(observationId);
    }

    public String laneUid(IObsData observation) {
        var database = hub.getDatabaseRegistry().getFederatedDatabase();
        var dataStream = database.getDataStreamStore().get(new DataStreamKey(observation.getDataStreamID()));
        if (dataStream == null || dataStream.getSystemID() == null)
            throw new IllegalStateException(
                    "Cannot resolve retained-media lane without a datastream system identity");

        BigId systemId = dataStream.getSystemID().getInternalID();
        for (int depth = 0; systemId != null && depth < 32; depth++) {
            var entry = database.getSystemDescStore().getCurrentVersionEntry(systemId);
            if (entry != null && entry.getValue() != null) {
                String uid = entry.getValue().getUniqueIdentifier();
                if (uid != null && uid.startsWith(LANE_UID_PREFIX))
                    return uid;
            } else {
                try (var historical = database.getSystemDescStore().select(
                        new SystemFilter.Builder().withInternalIDs(systemId).withAllVersions().build())) {
                    String uid = historical
                            .map(sys -> sys.getUniqueIdentifier())
                            .filter(u -> u != null && !u.isBlank())
                            .findFirst()
                            .orElse(null);
                    if (uid != null) {
                        if (uid.startsWith(LANE_UID_PREFIX))
                            return uid;
                        String derived = deriveLaneUidFromChildUid(uid);
                        if (derived != null)
                            return derived;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            BigId parent = database.getSystemDescStore().getParent(systemId);
            if (parent == null || parent.equals(systemId) || BigId.NONE.equals(parent))
                break;
            systemId = parent;
        }

        String directUid = dataStream.getSystemID().getUniqueID();
        if (directUid != null && !directUid.isBlank()) {
            if (directUid.startsWith(LANE_UID_PREFIX))
                return directUid;
            String derived = deriveLaneUidFromChildUid(directUid);
            if (derived != null)
                return derived;
        }

        throw new IllegalStateException(
                "Cannot resolve an authoritative OSCAR lane UID for retained media datastream "
                        + observation.getDataStreamID());
    }

    static String deriveLaneUidFromChildUid(String uid) {
        if (uid == null)
            return null;
        for (String prefix : CHILD_DETECTOR_UID_PREFIXES) {
            if (uid.startsWith(prefix) && uid.length() > prefix.length()) {
                String slug = uid.substring(prefix.length());
                int colon = slug.indexOf(':');
                if (colon > 0)
                    slug = slug.substring(0, colon);
                if (!slug.isBlank())
                    return LANE_UID_PREFIX + slug;
            }
        }
        return null;
    }

    public String cameraUid(String legacyVideoPath) {
        String sourceKey = MediaSetStore.stripVideoBucket(legacyVideoPath);
        Path path = Path.of(sourceKey.replace('\\', '/'));
        Path parent = path.getParent();
        String token = parent == null || parent.getFileName() == null
                ? "unknown-camera" : parent.getFileName().toString();
        String match = cameraUidsByPathToken.get(token);
        if (match != null)
            return match;
        try {
            refreshCameraIndex();
        } catch (RuntimeException ignored) {
            // Camera discovery must not make retention fail. The stable path token remains a valid
            // identity and a later retry can enrich it after the system catalog is available.
            return token;
        }
        return cameraUidsByPathToken.getOrDefault(token, token);
    }

    private synchronized void refreshCameraIndex() {
        Map<String, String> refreshed = new HashMap<>();
        var systems = hub.getDatabaseRegistry().getFederatedDatabase().getSystemDescStore();
        try (var stream = systems.select(systems.selectAllFilter())) {
            stream.forEach(system -> {
                String uid = system.getUniqueIdentifier();
                if (uid != null && !uid.isBlank())
                    refreshed.put(uid.replace(':', '-'), uid);
            });
        }
        cameraUidsByPathToken = Map.copyOf(refreshed);
    }
}
