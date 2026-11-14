package com.botts.impl.service.oscar.alarms;

import com.botts.impl.service.bucket.util.InvalidRequestException;
import com.botts.impl.service.bucket.util.ServiceErrors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Low-overhead, site-shared per-lane Dashboard alarm read-state store.
 *
 * <p>Reads serve a pre-serialized immutable UTF-8 snapshot and ETag directly from volatile memory
 * without acquiring locks or touching disk. Mutations merge per-lane {@code acknowledgedThroughMs}
 * watermarks and bounded {@code readKeys} sets monotonically, persisting atomically via
 * sibling {@code .partial-} rename so operational views (e.g. {@code north-gate} vs.
 * {@code south-gate}) stay isolated across browsers and workstations.</p>
 */
public final class DashboardAlarmReadStateStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_STORED_LANES = 256;
    public static final int MAX_LANES_PER_REQUEST = 256;
    public static final int MAX_READ_KEYS_PER_LANE = 500;
    public static final int MAX_LANE_ID_LENGTH = 128;
    public static final int MAX_READ_KEY_LENGTH = 256;
    public static final long MAX_FUTURE_CLOCK_SKEW_MS = 300_000L;
    public static final long MAX_PERSISTED_FILE_BYTES = 512L * 1024L;
    static final String STATE_FILE_NAME = ".dashboard-read-state.v1.json";

    private static final Set<PosixFilePermission> OWNER_RW_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path stateDirectory;
    private final Path stateFile;
    private final Clock clock;
    private final Logger logger;
    private final ReentrantLock writeLock = new ReentrantLock(true);

    private long revision;
    private String updatedAt;
    private final LinkedHashMap<String, MutableLaneState> lanes = new LinkedHashMap<>();
    private volatile CachedSnapshot currentSnapshot;

    public record LaneStateView(long acknowledgedThroughMs, List<String> readKeys) {}

    public record StatePayload(
            int schemaVersion,
            long revision,
            String updatedAt,
            Map<String, LaneStateView> lanes
    ) {}

    public record CachedSnapshot(long revision, String etag, byte[] jsonBytes, StatePayload payload) {}

    public record LaneUpdate(Boolean acknowledgeAll, Long acknowledgedThroughMs, List<String> readKeys) {}

    private static final class MutableLaneState {
        long acknowledgedThroughMs;
        final LinkedHashSet<String> readKeys = new LinkedHashSet<>();

        MutableLaneState(long acknowledgedThroughMs, Iterable<String> initialKeys) {
            this.acknowledgedThroughMs = Math.max(0L, acknowledgedThroughMs);
            if (initialKeys != null) {
                for (String key : initialKeys) {
                    if (key != null && !key.isBlank()) {
                        addBoundedKey(key);
                    }
                }
            }
        }

        boolean addBoundedKey(String key) {
            boolean existed = readKeys.remove(key);
            readKeys.add(key);
            boolean evicted = false;
            while (readKeys.size() > MAX_READ_KEYS_PER_LANE) {
                var iterator = readKeys.iterator();
                if (!iterator.hasNext())
                    break;
                iterator.next();
                iterator.remove();
                evicted = true;
            }
            return !existed || evicted;
        }

        LaneStateView toImmutableView() {
            return new LaneStateView(
                    acknowledgedThroughMs,
                    Collections.unmodifiableList(new ArrayList<>(readKeys)));
        }
    }

    public DashboardAlarmReadStateStore(Path stateDirectory) throws IOException {
        this(stateDirectory, Clock.systemUTC(), LoggerFactory.getLogger(DashboardAlarmReadStateStore.class));
    }

    public DashboardAlarmReadStateStore(Path stateDirectory, Clock clock, Logger logger) throws IOException {
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = logger != null ? logger : LoggerFactory.getLogger(DashboardAlarmReadStateStore.class);

        Path normalizedDir = stateDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedDir)) {
            throw new IOException("Dashboard alarm read-state directory is not a regular directory");
        }
        this.stateDirectory = normalizedDir.toRealPath();
        this.stateFile = this.stateDirectory.resolve(STATE_FILE_NAME);
        cleanupAbandonedPartials();
        loadInitialState();
    }

    public CachedSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public CachedSnapshot applyJsonUpdate(String rawJson) throws IOException {
        Map<String, LaneUpdate> parsedUpdates = parseAndValidateRequest(rawJson);
        return applyUpdates(parsedUpdates);
    }

    public CachedSnapshot applyUpdates(Map<String, LaneUpdate> updates) throws IOException {
        Objects.requireNonNull(updates, "updates");
        if (updates.size() > MAX_LANES_PER_REQUEST) {
            throw ServiceErrors.badRequest("Too many lanes in dashboard read-state update");
        }

        // Pre-validate every lane identifier and read key before acquiring the write lock.
        Map<String, ValidatedLaneUpdate> validated = new LinkedHashMap<>(updates.size());
        for (Map.Entry<String, LaneUpdate> entry : updates.entrySet()) {
            String laneId = validateLaneId(entry.getKey());
            LaneUpdate raw = entry.getValue();
            if (raw == null) {
                throw ServiceErrors.badRequest("Lane update entry must be an object: " + laneId);
            }
            boolean ackAll = Boolean.TRUE.equals(raw.acknowledgeAll());
            Long ackThrough = raw.acknowledgedThroughMs();
            if (ackThrough != null && ackThrough < 0L) {
                throw ServiceErrors.badRequest("acknowledgedThroughMs must be non-negative");
            }
            List<String> keys = new ArrayList<>();
            if (raw.readKeys() != null) {
                if (raw.readKeys().size() > MAX_READ_KEYS_PER_LANE) {
                    throw ServiceErrors.badRequest(
                            "readKeys per lane must not exceed " + MAX_READ_KEYS_PER_LANE);
                }
                for (String key : raw.readKeys()) {
                    keys.add(validateReadKey(key));
                }
            }
            validated.put(laneId, new ValidatedLaneUpdate(ackAll, ackThrough, keys));
        }

        if (validated.isEmpty()) {
            return currentSnapshot;
        }

        writeLock.lock();
        try {
            long nowMs = Math.max(0L, clock.millis());
            long maxAllowedWatermark = nowMs + MAX_FUTURE_CLOCK_SKEW_MS;
            boolean mutated = false;

            for (Map.Entry<String, ValidatedLaneUpdate> entry : validated.entrySet()) {
                String laneId = entry.getKey();
                ValidatedLaneUpdate update = entry.getValue();

                MutableLaneState state = lanes.remove(laneId);
                boolean isNewLane = (state == null);
                if (isNewLane) {
                    state = new MutableLaneState(0L, List.of());
                }

                long targetAckMs = state.acknowledgedThroughMs;
                if (update.acknowledgeAll) {
                    long candidate = nowMs;
                    if (update.acknowledgedThroughMs != null) {
                        candidate = Math.max(candidate,
                                Math.min(update.acknowledgedThroughMs, maxAllowedWatermark));
                    }
                    targetAckMs = Math.max(targetAckMs, candidate);
                } else if (update.acknowledgedThroughMs != null) {
                    long clamped = Math.min(update.acknowledgedThroughMs, maxAllowedWatermark);
                    targetAckMs = Math.max(targetAckMs, clamped);
                }

                if (targetAckMs > state.acknowledgedThroughMs) {
                    state.acknowledgedThroughMs = targetAckMs;
                    mutated = true;
                }

                if (update.acknowledgeAll && !state.readKeys.isEmpty()) {
                    // Advancing a lane's acknowledge-all watermark covers all prior alarms on that
                    // lane; prune older per-event keys while retaining any keys explicitly supplied
                    // in this request so payload size stays bounded across multi-week uptime.
                    state.readKeys.clear();
                    mutated = true;
                }

                for (String key : update.readKeys) {
                    if (state.addBoundedKey(key)) {
                        mutated = true;
                    }
                }

                if (!isNewLane || state.acknowledgedThroughMs > 0L || !state.readKeys.isEmpty()) {
                    lanes.put(laneId, state);
                }

                while (lanes.size() > MAX_STORED_LANES) {
                    var oldest = lanes.entrySet().iterator();
                    if (!oldest.hasNext())
                        break;
                    oldest.next();
                    oldest.remove();
                    mutated = true;
                }
            }

            if (!mutated) {
                return currentSnapshot;
            }

            long nextRevision = revision + 1L;
            String nextUpdatedAt = Instant.ofEpochMilli(nowMs).toString();
            CachedSnapshot nextSnapshot = buildSnapshot(nextRevision, nextUpdatedAt, lanes);
            persistAtomically(nextSnapshot.jsonBytes());
            this.revision = nextRevision;
            this.updatedAt = nextUpdatedAt;
            this.currentSnapshot = nextSnapshot;
            return nextSnapshot;
        } finally {
            writeLock.unlock();
        }
    }

    private record ValidatedLaneUpdate(
            boolean acknowledgeAll,
            Long acknowledgedThroughMs,
            List<String> readKeys
    ) {}

    private static Map<String, LaneUpdate> parseAndValidateRequest(String rawJson) throws InvalidRequestException {
        if (rawJson == null || rawJson.isBlank()) {
            throw ServiceErrors.badRequest("Request body must be a non-empty JSON object");
        }
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(rawJson);
        } catch (JsonSyntaxException e) {
            throw ServiceErrors.invalidPayload("Malformed JSON in dashboard read-state update");
        }
        if (rootElement == null || !rootElement.isJsonObject()) {
            throw ServiceErrors.badRequest("Dashboard read-state update must be a JSON object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        JsonElement lanesElement = root.get("lanes");
        if (lanesElement == null || !lanesElement.isJsonObject()) {
            throw ServiceErrors.badRequest("Dashboard read-state update requires a 'lanes' object");
        }

        JsonObject lanesObject = lanesElement.getAsJsonObject();
        if (lanesObject.size() > MAX_LANES_PER_REQUEST) {
            throw ServiceErrors.badRequest("Too many lanes in dashboard read-state update");
        }

        Map<String, LaneUpdate> result = new LinkedHashMap<>(lanesObject.size());
        for (Map.Entry<String, JsonElement> entry : lanesObject.entrySet()) {
            String laneId = entry.getKey();
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                throw ServiceErrors.badRequest("Lane update must be an object for lane: " + laneId);
            }
            JsonObject laneObj = value.getAsJsonObject();

            Boolean ackAll = null;
            if (laneObj.has("acknowledgeAll") && !laneObj.get("acknowledgeAll").isJsonNull()) {
                JsonElement el = laneObj.get("acknowledgeAll");
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
                    throw ServiceErrors.badRequest("acknowledgeAll must be a boolean");
                }
                ackAll = el.getAsBoolean();
            }

            Long ackThrough = null;
            if (laneObj.has("acknowledgedThroughMs") && !laneObj.get("acknowledgedThroughMs").isJsonNull()) {
                ackThrough = parseEpochMillisField(laneObj.get("acknowledgedThroughMs"), "acknowledgedThroughMs");
            }

            List<String> readKeys = null;
            if (laneObj.has("readKeys") && !laneObj.get("readKeys").isJsonNull()) {
                JsonElement el = laneObj.get("readKeys");
                if (!el.isJsonArray()) {
                    throw ServiceErrors.badRequest("readKeys must be an array of strings");
                }
                var arr = el.getAsJsonArray();
                if (arr.size() > MAX_READ_KEYS_PER_LANE) {
                    throw ServiceErrors.badRequest(
                            "readKeys per lane must not exceed " + MAX_READ_KEYS_PER_LANE);
                }
                readKeys = new ArrayList<>(arr.size());
                for (JsonElement item : arr) {
                    if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                        throw ServiceErrors.badRequest("Each readKeys entry must be a string");
                    }
                    readKeys.add(item.getAsString());
                }
            }

            result.put(laneId, new LaneUpdate(ackAll, ackThrough, readKeys));
        }
        return result;
    }

    private static long parseEpochMillisField(JsonElement element, String fieldName) throws InvalidRequestException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw ServiceErrors.badRequest(fieldName + " must be a finite integer");
        }
        double asDouble = element.getAsDouble();
        if (!Double.isFinite(asDouble) || asDouble < 0d || asDouble > 4_102_444_800_000d
                || Math.floor(asDouble) != asDouble) {
            throw ServiceErrors.badRequest(fieldName + " must be a valid epoch millisecond timestamp");
        }
        return (long) asDouble;
    }

    static String validateLaneId(String raw) throws InvalidRequestException {
        if (raw == null) {
            throw ServiceErrors.badRequest("Lane identifier is required");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LANE_ID_LENGTH) {
            throw ServiceErrors.badRequest("Lane identifier must be 1.." + MAX_LANE_ID_LENGTH + " characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch)) {
                throw ServiceErrors.badRequest("Lane identifier contains invalid control characters");
            }
        }
        return trimmed;
    }

    static String validateReadKey(String raw) throws InvalidRequestException {
        if (raw == null) {
            throw ServiceErrors.badRequest("Read key must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_READ_KEY_LENGTH) {
            throw ServiceErrors.badRequest("Read key must be 1.." + MAX_READ_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch)) {
                throw ServiceErrors.badRequest("Read key contains invalid control characters");
            }
        }
        return trimmed;
    }

    private void cleanupAbandonedPartials() {
        try (var stream = Files.list(stateDirectory)) {
            stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(STATE_FILE_NAME + ".partial-");
            }).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.debug("Could not remove stale dashboard read-state temp file {}", path, e);
                }
            });
        } catch (IOException e) {
            logger.debug("Could not scan dashboard read-state directory for stale temp files", e);
        }
    }

    private void loadInitialState() {
        this.revision = 0L;
        this.updatedAt = Instant.EPOCH.toString();
        this.lanes.clear();

        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.isSymbolicLink(stateFile)
                        || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                    logger.warn("Ignoring non-regular dashboard alarm read-state file {}", stateFile);
                } else {
                    long size = Files.size(stateFile);
                    if (size > MAX_PERSISTED_FILE_BYTES) {
                        logger.warn("Ignoring oversized dashboard alarm read-state file ({} bytes)", size);
                    } else {
                        String raw = Files.readString(stateFile, StandardCharsets.UTF_8);
                        restoreFromPersistedJson(raw);
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not load persisted dashboard alarm read state; starting clean: {}",
                        e.getMessage());
                this.revision = 0L;
                this.updatedAt = Instant.EPOCH.toString();
                this.lanes.clear();
            }
        }

        this.currentSnapshot = buildSnapshot(this.revision, this.updatedAt, this.lanes);
    }

    private void restoreFromPersistedJson(String raw) throws InvalidRequestException {
        JsonElement element = JsonParser.parseString(raw);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Persisted state is not a JSON object");
        }
        JsonObject root = element.getAsJsonObject();
        int schema = root.has("schemaVersion") && root.get("schemaVersion").isJsonPrimitive()
                ? root.get("schemaVersion").getAsInt() : 0;
        if (schema != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + schema);
        }
        long loadedRevision = root.has("revision") && root.get("revision").isJsonPrimitive()
                ? Math.max(0L, root.get("revision").getAsLong()) : 0L;
        String loadedUpdatedAt = root.has("updatedAt") && root.get("updatedAt").isJsonPrimitive()
                ? root.get("updatedAt").getAsString() : Instant.EPOCH.toString();

        LinkedHashMap<String, MutableLaneState> loadedLanes = new LinkedHashMap<>();
        if (root.has("lanes") && root.get("lanes").isJsonObject()) {
            JsonObject lanesObj = root.get("lanes").getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : lanesObj.entrySet()) {
                if (loadedLanes.size() >= MAX_STORED_LANES)
                    break;
                String laneId = validateLaneId(entry.getKey());
                if (entry.getValue() == null || !entry.getValue().isJsonObject())
                    continue;
                JsonObject laneObj = entry.getValue().getAsJsonObject();
                long ackMs = 0L;
                if (laneObj.has("acknowledgedThroughMs") && laneObj.get("acknowledgedThroughMs").isJsonPrimitive()) {
                    ackMs = Math.max(0L, laneObj.get("acknowledgedThroughMs").getAsLong());
                }
                List<String> keys = new ArrayList<>();
                if (laneObj.has("readKeys") && laneObj.get("readKeys").isJsonArray()) {
                    for (JsonElement item : laneObj.get("readKeys").getAsJsonArray()) {
                        if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                            String candidate = item.getAsString().trim();
                            if (!candidate.isEmpty()
                                    && candidate.length() <= MAX_READ_KEY_LENGTH
                                    && candidate.chars().noneMatch(Character::isISOControl)) {
                                keys.add(candidate);
                            }
                        }
                    }
                }
                loadedLanes.put(laneId, new MutableLaneState(ackMs, keys));
            }
        }

        this.revision = loadedRevision;
        this.updatedAt = loadedUpdatedAt;
        this.lanes.putAll(loadedLanes);
    }

    private static CachedSnapshot buildSnapshot(long revision,
                                                String updatedAt,
                                                Map<String, MutableLaneState> sourceLanes) {
        Map<String, LaneStateView> immutableLanes = new LinkedHashMap<>(sourceLanes.size());
        for (Map.Entry<String, MutableLaneState> entry : sourceLanes.entrySet()) {
            immutableLanes.put(entry.getKey(), entry.getValue().toImmutableView());
        }
        StatePayload payload = new StatePayload(
                CURRENT_SCHEMA_VERSION,
                revision,
                updatedAt,
                Collections.unmodifiableMap(immutableLanes));
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        String etag = "\"rev-" + revision + "\"";
        return new CachedSnapshot(revision, etag, bytes, payload);
    }

    private void persistAtomically(byte[] jsonBytes) throws IOException {
        Path tempFile = stateDirectory.resolve(
                STATE_FILE_NAME + ".partial-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(
                    tempFile,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                setOwnerReadWritePermissions(tempFile);
                ByteBuffer buffer = ByteBuffer.wrap(jsonBytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tempFile, stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerReadWritePermissions(stateFile);
            forceParentDirectory();
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best-effort cleanup if move failed before replacing tempFile.
            }
        }
    }

    private static void setOwnerReadWritePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems (e.g., Windows NTFS) manage ACLs separately.
        }
    }

    private void forceParentDirectory() {
        try (FileChannel dirChannel = FileChannel.open(stateDirectory, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and certain virtual filesystems do not support opening directories for fsync.
        }
    }
}
