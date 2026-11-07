package com.botts.impl.service.oscar.dailyfile;

import com.botts.api.service.bucket.IBucketStore;
import com.botts.api.service.bucket.IObjectHandler;
import com.botts.impl.service.bucket.util.RequestContext;
import com.botts.impl.service.bucket.util.ServiceErrors;
import com.botts.impl.service.oscar.purge.DatabasePurger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.impl.utils.rad.dailyfile.IDailyFileJournalService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Authenticated, read-only bounded slice API over immutable published daily-file CSVs.
 *
 * <p>Serves {@code GET /buckets/dailyfiles/dailyfile-slice} for historical and midnight-straddling
 * occupancy inspections after {@link DatabasePurger} removes completed-day compatibility rows from
 * PostgreSQL.</p>
 */
public final class DailyFileSliceHandler implements IObjectHandler {
    public static final String OBJECT_KEY = "dailyfile-slice";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int DEFAULT_LIMIT = 1_000;
    public static final int MAX_LIMIT = 1_000;
    public static final int MAX_JSON_RESPONSE_BYTES = 512 * 1024;
    static final int MAX_CONCURRENT_SLICES = 8;
    static final long ROUNDING_TOLERANCE_MILLIS = 5L;
    static final Duration MAX_SLICE_WINDOW = Duration.ofHours(24);

    private static final long PERMIT_ACQUIRE_TIMEOUT_SECONDS = 5L;
    private static final int MAX_IDENTIFIER_BYTES = 256;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long BINARY_SEARCH_FILE_THRESHOLD_BYTES = 128L * 1024L;
    private static final long BINARY_SEARCH_MIN_SPAN_BYTES = 32L * 1024L;
    private static final long BINARY_SEARCH_BACKOFF_BYTES = 64L * 1024L;
    private static final long JITTER_GUARD_SECONDS = 5L;
    private static final int SCAN_CHUNK_BYTES = 32 * 1024;
    private static final int INITIAL_SCAN_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_SINGLE_RECORD_BYTES = 4 * 1024 * 1024 + 64;
    private static final long MAX_LEGACY_CSV_BYTES = 256L * 1024L * 1024L;
    private static final int LEGACY_TRAILER_ASCII_BYTES = 26; // ,HH-mm-ss.SSS,HH-mm-ss.SSS
    private static final String BUCKET_PATH_ANCHOR = ".oscar-dailyfiles-path-anchor";
    private static final String MANIFEST_SUFFIX = ".manifest.v1.json";
    private static final String MANIFEST_FORMAT = "OSCAR_LEGACY_DAILY_CSV";
    private static final String LANE_SYSTEM_UID_PREFIX = "urn:osh:system:lane:";
    private static final String STANDALONE_LANE_UID_PREFIX = "urn:oscar:lane:";
    private static final Pattern OBJECT_PATTERN = Pattern.compile("dailyfile-slice(?:/.*)?");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path dailyFilesRoot;
    private final Supplier<IObsSystemDatabase> databaseSupplier;
    private final Supplier<IDailyFileJournalService> journalServiceSupplier;
    private final Semaphore slicePermits = new Semaphore(MAX_CONCURRENT_SLICES, true);

    public DailyFileSliceHandler(IBucketStore bucketStore) throws DataStoreException {
        this(bucketStore, () -> null, () -> null);
    }

    public DailyFileSliceHandler(IBucketStore bucketStore,
                                 Supplier<IObsSystemDatabase> databaseSupplier,
                                 Supplier<IDailyFileJournalService> journalServiceSupplier)
            throws DataStoreException {
        Objects.requireNonNull(bucketStore, "bucketStore");
        this.databaseSupplier = databaseSupplier != null ? databaseSupplier : () -> null;
        this.journalServiceSupplier = journalServiceSupplier != null ? journalServiceSupplier : () -> null;

        if (!bucketStore.bucketExists(DatabasePurger.DAILY_FILES_BUCKET))
            bucketStore.createBucket(DatabasePurger.DAILY_FILES_BUCKET);
        try {
            Path anchor = Path.of(bucketStore.getWritableResourceURI(
                            DatabasePurger.DAILY_FILES_BUCKET, BUCKET_PATH_ANCHOR))
                    .toAbsolutePath().normalize();
            Path root = anchor.getParent();
            if (root == null
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(root)) {
                throw new DataStoreException("Daily files bucket is not a regular directory");
            }
            this.dailyFilesRoot = root.toRealPath();
        } catch (IOException | IllegalArgumentException e) {
            throw new DataStoreException("Unable to resolve the dailyfiles bucket path", e);
        }
    }

    @Override
    public boolean canHandleRequest(HttpServletRequest request) {
        if (request == null || request.getPathInfo() == null)
            return false;
        String pathInfo = request.getPathInfo().replace('\\', '/');
        String prefix = "/" + DatabasePurger.DAILY_FILES_BUCKET + "/" + OBJECT_KEY;
        return prefix.equals(pathInfo) || pathInfo.startsWith(prefix + "/");
    }

    @Override
    public String getObjectPattern() {
        return OBJECT_PATTERN.pattern();
    }

    @Override
    public void doGet(RequestContext ctx) throws IOException, SecurityException {
        var sec = ctx.getSecurityHandler();
        var perms = sec.getBucketPermission(ctx.getBucketName());
        if (perms == null)
            throw ServiceErrors.forbidden(ctx.getBucketName());
        sec.checkPermission(perms.get);

        if (!DatabasePurger.DAILY_FILES_BUCKET.equals(ctx.getBucketName()))
            throw ServiceErrors.notFound(ctx.getObjectKey());

        String key = ctx.getObjectKey().replace('\\', '/');
        if (!OBJECT_KEY.equals(key))
            throw ServiceErrors.notFound(key);

        HttpServletRequest req = ctx.getRequest();
        Instant startTime = parseRequiredInstant(req.getParameter("startTime"), "startTime");
        Instant endTime = parseRequiredInstant(req.getParameter("endTime"), "endTime");
        if (endTime.isBefore(startTime))
            throw ServiceErrors.badRequest("endTime must be greater than or equal to startTime");
        if (Duration.between(startTime, endTime).compareTo(MAX_SLICE_WINDOW) > 0)
            throw ServiceErrors.badRequest("Requested slice window exceeds the 24-hour maximum");

        int limit = parseLimit(req.getParameter("limit"));
        String rawLaneUid = trimToNull(req.getParameter("laneUid"));
        String rawLaneName = trimToNull(req.getParameter("laneName"));
        if (rawLaneUid == null && rawLaneName == null)
            throw ServiceErrors.badRequest("laneUid or laneName is required");

        String primaryLaneUid;
        if (rawLaneUid != null) {
            primaryLaneUid = validateIdentifier(rawLaneUid, "laneUid");
        } else {
            String validLaneName = validateIdentifier(rawLaneName, "laneName");
            primaryLaneUid = resolveLaneUidFromName(validLaneName);
        }
        String validatedLaneName = rawLaneName != null
                ? validateIdentifier(rawLaneName, "laneName") : null;

        boolean acquired;
        try {
            acquired = slicePermits.tryAcquire(PERMIT_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ServiceErrors.requestRejected("Interrupted while waiting for a daily-file slice slot");
        }
        if (!acquired)
            throw ServiceErrors.requestRejected("Daily-file slice concurrency limit reached");

        try {
            SliceResponse payload = executeSlice(primaryLaneUid, validatedLaneName,
                    startTime, endTime, limit);
            writeBoundedJsonResponse(ctx, payload);
        } finally {
            slicePermits.release();
        }
    }

    @Override
    public void doPost(RequestContext ctx) throws IOException {
        throw ServiceErrors.unsupportedOperation("The daily-file slice API is read-only");
    }

    @Override
    public void doPut(RequestContext ctx) throws IOException {
        throw ServiceErrors.unsupportedOperation("The daily-file slice API is read-only");
    }

    @Override
    public void doDelete(RequestContext ctx) throws IOException {
        throw ServiceErrors.unsupportedOperation("The daily-file slice API is read-only");
    }

    private SliceResponse executeSlice(String primaryLaneUid,
                                       String optionalLaneName,
                                       Instant startTime,
                                       Instant endTime,
                                       int limit) throws IOException {
        Instant effectiveStart = minusMillisClamped(startTime, ROUNDING_TOLERANCE_MILLIS);
        Instant effectiveEnd = plusMillisClamped(endTime, ROUNDING_TOLERANCE_MILLIS);

        ZoneId activeZone = resolveJournalZoneId();
        LocalDate startDay = LocalDate.ofInstant(effectiveStart, activeZone).minusDays(1);
        LocalDate endDay = LocalDate.ofInstant(effectiveEnd, activeZone).plusDays(1);

        Set<String> candidateUids = new LinkedHashSet<>();
        candidateUids.add(primaryLaneUid);
        if (optionalLaneName != null) {
            candidateUids.add(LANE_SYSTEM_UID_PREFIX + optionalLaneName);
            candidateUids.add(STANDALONE_LANE_UID_PREFIX + optionalLaneName);
            candidateUids.add(optionalLaneName);
        }

        String matchedUid = primaryLaneUid;
        List<MatchedRecord> collected = new ArrayList<>();
        boolean earlyCapReached = false;
        // Collect at most limit + 1 matching items when rows are already chronological so a dense
        // day never buffers tens of thousands of records in memory. Because up to ~5s of segment
        // jitter is allowed, allow a modest headroom before stopping early.
        int collectionCeiling = limit + 256;

        for (String candidateUid : candidateUids) {
            boolean foundAnyArtifactForUid = false;
            for (LocalDate day = startDay; !day.isAfter(endDay); day = day.plusDays(1)) {
                DayReadResult dayResult = readDaySlice(candidateUid, day,
                        effectiveStart, effectiveEnd, collectionCeiling - collected.size());
                if (dayResult.artifactPresent)
                    foundAnyArtifactForUid = true;
                collected.addAll(dayResult.records);
                if (dayResult.hitCeiling || collected.size() >= collectionCeiling) {
                    earlyCapReached = true;
                    break;
                }
            }
            if (foundAnyArtifactForUid) {
                matchedUid = candidateUid;
                break;
            }
        }

        collected.sort(Comparator.comparing(MatchedRecord::instant));

        return buildBoundedSliceResponse(matchedUid, startTime, endTime, limit,
                collected, earlyCapReached);
    }

    private DayReadResult readDaySlice(String laneUid,
                                       LocalDate day,
                                       Instant effectiveStart,
                                       Instant effectiveEnd,
                                       int remainingCeiling) throws IOException {
        if (remainingCeiling <= 0)
            return new DayReadResult(true, List.of(), true);

        String journalCsvName = publicationFileId(laneUid) + "_" + day + ".csv";
        String manifestName = journalCsvName + MANIFEST_SUFFIX;
        Path manifestPath = resolveConfinedExistingFile(manifestName);
        if (manifestPath != null) {
            ParsedManifest manifest = readVerifiedManifest(manifestPath, laneUid, journalCsvName);
            if (!windowsOverlap(effectiveStart, effectiveEnd,
                    manifest.windowStartInclusive, manifest.windowEndExclusive)) {
                return new DayReadResult(true, List.of(), false);
            }
            if (manifest.recordCount == 0 || manifest.byteCount == 0)
                return new DayReadResult(true, List.of(), false);

            Path csvPath = resolveConfinedExistingFile(manifest.dataFile);
            if (csvPath == null)
                throw ServiceErrors.notFound("Published daily CSV is missing for " + day);

            return sliceJournalLegacyCsv(csvPath, manifest, effectiveStart, effectiveEnd, remainingCeiling);
        }

        String legacyCsvName = legacyFileId(laneUid) + "_" + day + ".csv";
        Path legacyCsvPath = resolveConfinedExistingFile(legacyCsvName);
        if (legacyCsvPath != null)
            return sliceExportedHeaderCsv(legacyCsvPath, effectiveStart, effectiveEnd, remainingCeiling);

        return new DayReadResult(false, List.of(), false);
    }

    private DayReadResult sliceJournalLegacyCsv(Path csvPath,
                                                ParsedManifest manifest,
                                                Instant effectiveStart,
                                                Instant effectiveEnd,
                                                int remainingCeiling) throws IOException {
        List<MatchedRecord> matches = new ArrayList<>();
        boolean hitCeiling = false;

        try (FileChannel channel = FileChannel.open(csvPath, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long fileSize = channel.size();
            if (fileSize != manifest.byteCount) {
                throw ServiceErrors.requestRejected(
                        "Published daily CSV size does not match its manifest");
            }
            if (fileSize == 0L)
                return new DayReadResult(true, List.of(), false);

            Instant searchTarget = minusSecondsClamped(effectiveStart, JITTER_GUARD_SECONDS);
            Instant stopAfter = plusSecondsClamped(effectiveEnd, JITTER_GUARD_SECONDS);

            long startOffset = 0L;
            if (fileSize > BINARY_SEARCH_FILE_THRESHOLD_BYTES)
                startOffset = findBinarySearchStartOffset(channel, fileSize, manifest, searchTarget);

            channel.position(startOffset);
            byte[] buf = new byte[INITIAL_SCAN_BUFFER_BYTES];
            int filled = 0;
            boolean eof = false;

            while (!eof || filled > 0) {
                if (!eof && filled < buf.length) {
                    int maxRead = Math.min(SCAN_CHUNK_BYTES, buf.length - filled);
                    int n = channel.read(ByteBuffer.wrap(buf, filled, maxRead));
                    if (n < 0)
                        eof = true;
                    else
                        filled += n;
                }

                int recordStart = 0;
                boolean stopScanning = false;
                for (int i = 0; i < filled; i++) {
                    if (buf[i] != '\n')
                        continue;
                    int endExclusive = i + 1;
                    int recordLen = endExclusive - recordStart;
                    TrailerMatch trailer = matchTrailer(buf, recordStart, endExclusive);
                    if (trailer == null) {
                        if (recordLen > MAX_SINGLE_RECORD_BYTES)
                            throw ServiceErrors.requestRejected("Daily CSV record exceeds maximum byte length");
                        continue;
                    }

                    Instant recordInstant = reconstructInstant(trailer, manifest);
                    if (recordInstant != null) {
                        if (!recordInstant.isBefore(effectiveStart)
                                && !recordInstant.isAfter(effectiveEnd)) {
                            String rawLine = new String(buf, recordStart,
                                    trailer.trailerStartOffset - recordStart, StandardCharsets.UTF_8);
                            matches.add(new MatchedRecord(recordInstant, rawLine));
                            if (matches.size() >= remainingCeiling) {
                                hitCeiling = true;
                                stopScanning = true;
                                recordStart = endExclusive;
                                break;
                            }
                        } else if (recordInstant.isAfter(stopAfter)) {
                            stopScanning = true;
                            recordStart = endExclusive;
                            break;
                        }
                    }
                    recordStart = endExclusive;
                }

                if (stopScanning)
                    break;

                int unread = filled - recordStart;
                if (unread > 0 && recordStart > 0)
                    System.arraycopy(buf, recordStart, buf, 0, unread);
                filled = unread;

                if (eof) {
                    // Any trailing incomplete or unparseable bytes at EOF cannot form a valid record.
                    break;
                }
                if (filled == buf.length) {
                    if (buf.length >= MAX_SINGLE_RECORD_BYTES)
                        throw ServiceErrors.requestRejected("Daily CSV record exceeds maximum byte length");
                    int nextCap = (int) Math.min((long) buf.length * 2L, (long) MAX_SINGLE_RECORD_BYTES);
                    byte[] grown = new byte[nextCap];
                    System.arraycopy(buf, 0, grown, 0, filled);
                    buf = grown;
                }
            }
        }

        return new DayReadResult(true, matches, hitCeiling);
    }

    private long findBinarySearchStartOffset(FileChannel channel,
                                             long fileSize,
                                             ParsedManifest manifest,
                                             Instant searchTarget) throws IOException {
        long low = 0L;
        long high = fileSize;

        while (high - low > BINARY_SEARCH_MIN_SPAN_BYTES) {
            long mid = (low + high) >>> 1;
            ProbeRecord probe = readFirstCompleteRecordAfter(channel, mid, fileSize, manifest);
            if (probe == null || probe.instant == null) {
                high = mid;
            } else if (probe.instant.isBefore(searchTarget)) {
                low = probe.recordStartOffset;
            } else {
                high = mid;
            }
        }

        long backoff = Math.max(0L, low - BINARY_SEARCH_BACKOFF_BYTES);
        if (backoff == 0L)
            return 0L;
        long aligned = findNextRecordBoundary(channel, backoff, fileSize);
        return aligned >= 0L ? aligned : 0L;
    }

    private ProbeRecord readFirstCompleteRecordAfter(FileChannel channel,
                                                     long probeOffset,
                                                     long fileSize,
                                                     ParsedManifest manifest) throws IOException {
        long firstBoundary = probeOffset == 0L
                ? 0L : findNextRecordBoundary(channel, probeOffset, fileSize);
        if (firstBoundary < 0L || firstBoundary >= fileSize)
            return null;

        channel.position(firstBoundary);
        byte[] buf = new byte[Math.min(INITIAL_SCAN_BUFFER_BYTES,
                (int) Math.min(fileSize - firstBoundary, (long) INITIAL_SCAN_BUFFER_BYTES))];
        int filled = 0;
        while (firstBoundary + filled < fileSize) {
            if (filled == buf.length) {
                if (buf.length >= MAX_SINGLE_RECORD_BYTES)
                    return null;
                int nextCap = (int) Math.min((long) buf.length * 2L,
                        Math.min(fileSize - firstBoundary, (long) MAX_SINGLE_RECORD_BYTES));
                if (nextCap <= buf.length)
                    break;
                byte[] grown = new byte[nextCap];
                System.arraycopy(buf, 0, grown, 0, filled);
                buf = grown;
            }
            int readStart = filled;
            int n = channel.read(ByteBuffer.wrap(buf, filled, buf.length - filled));
            if (n <= 0)
                break;
            filled += n;
            for (int i = readStart; i < filled; i++) {
                if (buf[i] != '\n')
                    continue;
                TrailerMatch trailer = matchTrailer(buf, 0, i + 1);
                if (trailer != null) {
                    Instant instant = reconstructInstant(trailer, manifest);
                    return new ProbeRecord(firstBoundary, instant);
                }
            }
        }
        return null;
    }

    private long findNextRecordBoundary(FileChannel channel,
                                        long minNewlineOffset,
                                        long fileSize) throws IOException {
        long lookbackStart = Math.max(0L, minNewlineOffset - (LEGACY_TRAILER_ASCII_BYTES + 2));
        int skipPrefix = (int) (minNewlineOffset - lookbackStart);
        channel.position(lookbackStart);

        byte[] buf = new byte[SCAN_CHUNK_BYTES];
        int filled = 0;
        long bufferBaseOffset = lookbackStart;
        int searchIndex = skipPrefix;

        while (bufferBaseOffset + filled < fileSize) {
            int n = channel.read(ByteBuffer.wrap(buf, filled, buf.length - filled));
            if (n <= 0)
                break;
            filled += n;
            for (int i = searchIndex; i < filled; i++) {
                if (buf[i] != '\n')
                    continue;
                TrailerMatch match = matchTrailer(buf, 0, i + 1);
                if (match != null)
                    return bufferBaseOffset + i + 1L;
            }
            if (bufferBaseOffset - lookbackStart > MAX_SINGLE_RECORD_BYTES)
                return -1L;
            int keep = Math.min(filled, LEGACY_TRAILER_ASCII_BYTES + 2);
            int drop = filled - keep;
            if (drop > 0) {
                System.arraycopy(buf, drop, buf, 0, keep);
                bufferBaseOffset += drop;
                filled = keep;
                searchIndex = keep;
            } else {
                searchIndex = filled;
            }
        }
        return -1L;
    }

    private DayReadResult sliceExportedHeaderCsv(Path csvPath,
                                                 Instant effectiveStart,
                                                 Instant effectiveEnd,
                                                 int remainingCeiling) throws IOException {
        long size = Files.size(csvPath);
        if (size == 0L)
            return new DayReadResult(true, List.of(), false);
        if (size > MAX_LEGACY_CSV_BYTES)
            throw ServiceErrors.requestRejected("Legacy daily CSV exceeds safety bounds");

        List<MatchedRecord> matches = new ArrayList<>();
        boolean hitCeiling = false;
        Instant stopAfter = plusSecondsClamped(effectiveEnd, JITTER_GUARD_SECONDS);

        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null)
                return new DayReadResult(true, List.of(), false);
            if (!"message,timestamp".equals(firstLine.trim())) {
                ParsedHeaderRow maybeFirst = parseHeaderCsvRow(firstLine);
                if (maybeFirst != null
                        && !maybeFirst.instant.isBefore(effectiveStart)
                        && !maybeFirst.instant.isAfter(effectiveEnd)) {
                    matches.add(new MatchedRecord(maybeFirst.instant, maybeFirst.message));
                }
            }
            String line;
            while (matches.size() < remainingCeiling && (line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;
                ParsedHeaderRow row = parseHeaderCsvRow(line);
                if (row == null)
                    continue;
                if (!row.instant.isBefore(effectiveStart) && !row.instant.isAfter(effectiveEnd)) {
                    matches.add(new MatchedRecord(row.instant, row.message));
                    if (matches.size() >= remainingCeiling) {
                        hitCeiling = true;
                        break;
                    }
                } else if (row.instant.isAfter(stopAfter)) {
                    break;
                }
            }
        }
        return new DayReadResult(true, matches, hitCeiling);
    }

    private static ParsedHeaderRow parseHeaderCsvRow(String line) {
        int lastComma = line.lastIndexOf(',');
        if (lastComma < 0 || lastComma + 1 >= line.length())
            return null;
        String tsPart = line.substring(lastComma + 1).trim();
        Instant instant;
        try {
            instant = Instant.parse(tsPart);
        } catch (DateTimeParseException e) {
            return null;
        }
        String rawMessage = line.substring(0, lastComma);
        if (rawMessage.length() >= 2 && rawMessage.startsWith("\"") && rawMessage.endsWith("\"")) {
            rawMessage = rawMessage.substring(1, rawMessage.length() - 1).replace("\"\"", "\"");
        }
        return new ParsedHeaderRow(instant, rawMessage);
    }

    private static TrailerMatch matchTrailer(byte[] buf, int recordStart, int endExclusive) {
        if (endExclusive <= recordStart || buf[endExclusive - 1] != '\n')
            return null;
        int trailerEnd = (endExclusive - 2 >= recordStart && buf[endExclusive - 2] == '\r')
                ? endExclusive - 2 : endExclusive - 1;
        int trailerStart = trailerEnd - LEGACY_TRAILER_ASCII_BYTES;
        if (trailerStart < recordStart)
            return null;
        if (buf[trailerStart] != ',' || buf[trailerStart + 13] != ',')
            return null;

        int localOffset = trailerStart + 1;
        int utcOffset = trailerStart + 14;
        if (!hasTimeSeparators(buf, localOffset) || !hasTimeSeparators(buf, utcOffset))
            return null;

        int localHour = parseTwoDigits(buf, localOffset);
        int localMinute = parseTwoDigits(buf, localOffset + 3);
        int localSecond = parseTwoDigits(buf, localOffset + 6);
        int localMillis = parseThreeDigits(buf, localOffset + 9);

        int utcHour = parseTwoDigits(buf, utcOffset);
        int utcMinute = parseTwoDigits(buf, utcOffset + 3);
        int utcSecond = parseTwoDigits(buf, utcOffset + 6);
        int utcMillis = parseThreeDigits(buf, utcOffset + 9);

        if (!isValidClockTime(localHour, localMinute, localSecond, localMillis)
                || !isValidClockTime(utcHour, utcMinute, utcSecond, utcMillis)) {
            return null;
        }

        return new TrailerMatch(trailerStart,
                localHour, localMinute, localSecond, localMillis,
                utcHour, utcMinute, utcSecond, utcMillis);
    }

    private static boolean hasTimeSeparators(byte[] buf, int offset) {
        return buf[offset + 2] == '-' && buf[offset + 5] == '-' && buf[offset + 8] == '.';
    }

    private static int parseTwoDigits(byte[] buf, int offset) {
        int d0 = buf[offset] - '0';
        int d1 = buf[offset + 1] - '0';
        if ((d0 | d1) < 0 || d0 > 9 || d1 > 9)
            return -1;
        return d0 * 10 + d1;
    }

    private static int parseThreeDigits(byte[] buf, int offset) {
        int d0 = buf[offset] - '0';
        int d1 = buf[offset + 1] - '0';
        int d2 = buf[offset + 2] - '0';
        if ((d0 | d1 | d2) < 0 || d0 > 9 || d1 > 9 || d2 > 9)
            return -1;
        return d0 * 100 + d1 * 10 + d2;
    }

    private static boolean isValidClockTime(int hour, int minute, int second, int millis) {
        return hour >= 0 && hour <= 23
                && minute >= 0 && minute <= 59
                && second >= 0 && second <= 59
                && millis >= 0 && millis <= 999;
    }

    private static Instant reconstructInstant(TrailerMatch trailer, ParsedManifest manifest) {
        LocalTime utcClock = LocalTime.of(trailer.utcHour, trailer.utcMinute,
                trailer.utcSecond, trailer.utcMillis * 1_000_000);
        for (LocalDate utcDate = manifest.startUtcDate;
             !utcDate.isAfter(manifest.endUtcDate);
             utcDate = utcDate.plusDays(1)) {
            Instant candidate = ZonedDateTime.of(utcDate, utcClock, ZoneOffset.UTC).toInstant();
            if (candidate.isBefore(manifest.windowStartInclusive)
                    || !candidate.isBefore(manifest.windowEndExclusive)) {
                continue;
            }
            ZonedDateTime localZdt = candidate.atZone(manifest.zoneId);
            if (localZdt.getHour() == trailer.localHour
                    && localZdt.getMinute() == trailer.localMinute
                    && localZdt.getSecond() == trailer.localSecond
                    && (localZdt.getNano() / 1_000_000) == trailer.localMillis) {
                return candidate;
            }
        }
        return null;
    }

    private ParsedManifest readVerifiedManifest(Path manifestPath,
                                                String expectedLaneUid,
                                                String expectedCsvName) throws IOException {
        long size = Files.size(manifestPath);
        if (size <= 0L || size > MAX_MANIFEST_BYTES)
            throw ServiceErrors.requestRejected("Invalid daily-file manifest size");

        String rawJson = Files.readString(manifestPath, StandardCharsets.UTF_8);
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (!parsed.isJsonObject())
                throw ServiceErrors.requestRejected("Malformed daily-file manifest");
            obj = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw ServiceErrors.requestRejected("Malformed daily-file manifest");
        }

        long schemaVersion = requiredLong(obj, "schemaVersion");
        String format = requiredString(obj, "format");
        String laneId = requiredString(obj, "laneId");
        String zoneStr = requiredString(obj, "zoneId");
        String startStr = requiredString(obj, "windowStartInclusive");
        String endStr = requiredString(obj, "windowEndExclusive");
        long recordCount = requiredLong(obj, "recordCount");
        long byteCount = requiredLong(obj, "byteCount");
        String dataFile = requiredString(obj, "dataFile");

        if (schemaVersion != 1L || !MANIFEST_FORMAT.equals(format))
            throw ServiceErrors.requestRejected("Unsupported daily-file manifest format");
        if (!expectedLaneUid.equals(laneId))
            throw ServiceErrors.requestRejected("Daily-file manifest lane identity mismatch");
        if (!expectedCsvName.equals(dataFile)
                || dataFile.indexOf('/') >= 0 || dataFile.indexOf('\\') >= 0) {
            throw ServiceErrors.requestRejected("Daily-file manifest dataFile mismatch");
        }
        if (recordCount < 0L || byteCount < 0L)
            throw ServiceErrors.requestRejected("Daily-file manifest counts must be non-negative");

        ZoneId zoneId;
        Instant windowStart;
        Instant windowEnd;
        try {
            zoneId = ZoneId.of(zoneStr);
            windowStart = Instant.parse(startStr);
            windowEnd = Instant.parse(endStr);
        } catch (DateTimeException e) {
            throw ServiceErrors.requestRejected("Invalid daily-file manifest window metadata");
        }
        if (!windowStart.isBefore(windowEnd))
            throw ServiceErrors.requestRejected("Invalid daily-file manifest window bounds");

        LocalDate startUtcDate = LocalDate.ofInstant(windowStart, ZoneOffset.UTC);
        LocalDate endUtcDate = LocalDate.ofInstant(windowEnd.minusNanos(1), ZoneOffset.UTC);
        return new ParsedManifest(zoneId, windowStart, windowEnd, startUtcDate, endUtcDate,
                recordCount, byteCount, dataFile);
    }

    private Path resolveConfinedExistingFile(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()
                || fileName.equals(".") || fileName.equals("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.contains(".partial")
                || fileName.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        Path candidate = dailyFilesRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(dailyFilesRoot) || !dailyFilesRoot.equals(candidate.getParent()))
            return null;
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!dailyFilesRoot.equals(real.getParent()))
            return null;
        return real;
    }

    private SliceResponse buildBoundedSliceResponse(String laneUid,
                                                    Instant startTime,
                                                    Instant endTime,
                                                    int limit,
                                                    List<MatchedRecord> sortedRecords,
                                                    boolean alreadyCapped) {
        SliceResponse emptyTrueProbe = new SliceResponse(CURRENT_SCHEMA_VERSION, laneUid,
                startTime.toString(), endTime.toString(), true, List.of());
        int envelopeBytes = GSON.toJson(emptyTrueProbe).getBytes(StandardCharsets.UTF_8).length;
        int maxItemsBytes = MAX_JSON_RESPONSE_BYTES - envelopeBytes;

        List<SliceItem> selected = new ArrayList<>(Math.min(limit, sortedRecords.size()));
        int usedItemBytes = 0;
        boolean truncated = alreadyCapped;

        for (MatchedRecord record : sortedRecords) {
            if (selected.size() >= limit) {
                truncated = true;
                break;
            }
            SliceItem item = new SliceItem(record.message, record.instant.toString());
            int itemBytes = GSON.toJson(item).getBytes(StandardCharsets.UTF_8).length
                    + (selected.isEmpty() ? 0 : 1); // comma separator
            if (usedItemBytes + itemBytes > maxItemsBytes) {
                truncated = true;
                break;
            }
            selected.add(item);
            usedItemBytes += itemBytes;
        }

        return new SliceResponse(CURRENT_SCHEMA_VERSION, laneUid,
                startTime.toString(), endTime.toString(), truncated, List.copyOf(selected));
    }

    private static void writeBoundedJsonResponse(RequestContext ctx, SliceResponse payload)
            throws IOException {
        byte[] encoded = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_JSON_RESPONSE_BYTES)
            throw ServiceErrors.requestRejected("Daily-file slice response exceeds the JSON byte limit");

        HttpServletResponse response = ctx.getResponse();
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(encoded.length);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, no-store");
        response.getOutputStream().write(encoded);
    }

    private ZoneId resolveJournalZoneId() {
        try {
            IDailyFileJournalService service = journalServiceSupplier.get();
            if (service != null && service.getJournalZoneId() != null)
                return service.getJournalZoneId();
        } catch (RuntimeException ignored) {
            // Fall back to UTC when the journal service is absent or stopping.
        }
        return ZoneOffset.UTC;
    }

    private String resolveLaneUidFromName(String laneName) {
        IObsSystemDatabase db = databaseSupplier.get();
        if (db != null && db.getSystemDescStore() != null) {
            String exactLaneUid = LANE_SYSTEM_UID_PREFIX + laneName;
            String lanePrefixMatch = null;
            String fallbackSystemMatch = null;
            var store = db.getSystemDescStore();
            try (var systems = store.select(store.selectAllFilter())) {
                var iter = systems.iterator();
                while (iter.hasNext()) {
                    var sys = iter.next();
                    String uid = sys.getUniqueIdentifier();
                    if (uid == null || uid.isBlank())
                        continue;
                    if (exactLaneUid.equals(uid))
                        return uid;
                    String sysName = sys.getName();
                    if (uid.startsWith(LANE_SYSTEM_UID_PREFIX)
                            && (laneName.equals(sysName) || uid.endsWith(":" + laneName))) {
                        lanePrefixMatch = uid;
                    } else if (fallbackSystemMatch == null
                            && (laneName.equals(sysName)
                            || (STANDALONE_LANE_UID_PREFIX + laneName).equals(uid)
                            || laneName.equals(uid))) {
                        fallbackSystemMatch = uid;
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall back to deterministic lane UID prefix below.
            }
            if (lanePrefixMatch != null)
                return lanePrefixMatch;
            if (fallbackSystemMatch != null)
                return fallbackSystemMatch;
        }
        return LANE_SYSTEM_UID_PREFIX + laneName;
    }

    static String publicationFileId(String laneId) {
        String suffix = laneId.contains(":")
                ? laneId.substring(laneId.lastIndexOf(':') + 1) : laneId;
        String safe = suffix.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank())
            safe = "lane";
        if (safe.length() > 120)
            safe = safe.substring(0, 120);
        String shortHash = HexFormat.of().formatHex(
                sha256Digest().digest(laneId.getBytes(StandardCharsets.UTF_8)), 0, 6);
        return safe + "-" + shortHash;
    }

    private static String legacyFileId(String laneId) {
        String suffix = laneId.contains(":")
                ? laneId.substring(laneId.lastIndexOf(':') + 1) : laneId;
        return suffix.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static Instant parseRequiredInstant(String raw, String paramName) throws IOException {
        String trimmed = trimToNull(raw);
        if (trimmed == null)
            throw ServiceErrors.badRequest(paramName + " is required");
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw ServiceErrors.badRequest("Invalid ISO-8601 timestamp for " + paramName);
        }
    }

    private static int parseLimit(String raw) throws IOException {
        String trimmed = trimToNull(raw);
        if (trimmed == null)
            return DEFAULT_LIMIT;
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed < 1 || parsed > MAX_LIMIT)
                throw ServiceErrors.badRequest("limit must be between 1 and " + MAX_LIMIT);
            return parsed;
        } catch (NumberFormatException e) {
            throw ServiceErrors.badRequest("limit must be an integer between 1 and " + MAX_LIMIT);
        }
    }

    private static String validateIdentifier(String value, String paramName) throws IOException {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.chars().anyMatch(Character::isISOControl)) {
            throw ServiceErrors.badRequest("Invalid " + paramName);
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES)
            throw ServiceErrors.badRequest(paramName + " exceeds maximum length");
        return value;
    }

    private static String requiredString(JsonObject obj, String field) throws IOException {
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
            throw ServiceErrors.requestRejected("Daily-file manifest missing string field " + field);
        return el.getAsString();
    }

    private static long requiredLong(JsonObject obj, String field) throws IOException {
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
            throw ServiceErrors.requestRejected("Daily-file manifest missing numeric field " + field);
        try {
            return el.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw ServiceErrors.requestRejected("Invalid numeric field " + field + " in manifest");
        }
    }

    private static boolean windowsOverlap(Instant startInclusive,
                                          Instant endInclusive,
                                          Instant windowStartInclusive,
                                          Instant windowEndExclusive) {
        return !endInclusive.isBefore(windowStartInclusive)
                && startInclusive.isBefore(windowEndExclusive);
    }

    private static Instant minusMillisClamped(Instant instant, long millis) {
        try {
            return instant.minusMillis(millis);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MIN;
        }
    }

    private static Instant plusMillisClamped(Instant instant, long millis) {
        try {
            return instant.plusMillis(millis);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MAX;
        }
    }

    private static Instant minusSecondsClamped(Instant instant, long seconds) {
        try {
            return instant.minusSeconds(seconds);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MIN;
        }
    }

    private static Instant plusSecondsClamped(Instant instant, long seconds) {
        try {
            return instant.plusSeconds(seconds);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MAX;
        }
    }

    private static String trimToNull(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ParsedManifest(ZoneId zoneId,
                                  Instant windowStartInclusive,
                                  Instant windowEndExclusive,
                                  LocalDate startUtcDate,
                                  LocalDate endUtcDate,
                                  long recordCount,
                                  long byteCount,
                                  String dataFile) {}

    private record TrailerMatch(int trailerStartOffset,
                                int localHour,
                                int localMinute,
                                int localSecond,
                                int localMillis,
                                int utcHour,
                                int utcMinute,
                                int utcSecond,
                                int utcMillis) {}

    private record ProbeRecord(long recordStartOffset, Instant instant) {}

    private record ParsedHeaderRow(Instant instant, String message) {}

    private record MatchedRecord(Instant instant, String message) {}

    private record DayReadResult(boolean artifactPresent,
                                 List<MatchedRecord> records,
                                 boolean hitCeiling) {}

    public record SliceItem(String message, String timestamp) {}

    public record SliceResponse(int schemaVersion,
                                String laneUid,
                                String startTime,
                                String endTime,
                                boolean truncated,
                                List<SliceItem> items) {}
}
