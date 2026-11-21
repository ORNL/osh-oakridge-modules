/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * User-space read-only connector for commercial SMBv2/SMBv3 NII scanner shares and
 * local/mounted NAS directories.
 *
 * <p>Avoids OS-level kernel CIFS mounts so an offline or congested port X-ray NAS can never
 * stall OSCAR's real-time radiation/video ingestion threads; every SMB socket operation is
 * strictly bounded by {@code 3-5s} timeouts.</p>
 */
public final class UffSmbConnector {

    private static final int MAX_SCAN_DEPTH = 6;
    private static final int MAX_DISCOVERED_FILES = 10_000;

    private static final Set<String> FORBIDDEN_LOCAL_PREFIXES = Set.of(
            "/etc", "/proc", "/sys", "/dev", "/boot", "/root", "/run/secrets"
    );

    public record RemoteUffFileEntry(
            String fileName,
            String relativePath,
            long sizeBytes,
            long mtimeMillis
    ) {}

    public record ParsedSmbEndpoint(
            String host,
            int port,
            String shareName,
            String subPath
    ) {
        public String displayShareUrl() {
            String base = "\\\\" + host + (port != 445 ? ":" + port : "") + "\\" + shareName;
            if (subPath != null && !subPath.isEmpty()) {
                return base + "\\" + subPath.replace('/', '\\');
            }
            return base;
        }
    }

    private UffSmbConnector() {}

    /**
     * Parses UNC paths ({@code \\192.168.0.60\oscar-uff\smb-date-archive}), POSIX SMB URLs
     * ({@code smb://192.168.0.60/oscar-uff/smb-date-archive}), or slash-prefixed shares
     * ({@code //192.168.0.60/oscar-uff}).
     */
    public static ParsedSmbEndpoint parseSmbTarget(String rawUrl, String explicitSubPath) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("SMB share URL or UNC path is required (for example: \\\\nas.port.local\\oscar-uff or smb://192.168.0.60/oscar-uff).");
        }
        String trimmed = rawUrl.trim();
        String host;
        int port = 445;
        String remainder;

        if (trimmed.regionMatches(true, 0, "smb://", 0, 6)) {
            URI uri = URI.create(trimmed.replace('\\', '/'));
            host = uri.getHost();
            if (uri.getPort() > 0) {
                port = uri.getPort();
            }
            remainder = uri.getPath() != null ? uri.getPath() : "";
        } else {
            String norm = trimmed.replace('\\', '/');
            while (norm.startsWith("/")) {
                norm = norm.substring(1);
            }
            int firstSlash = norm.indexOf('/');
            if (firstSlash <= 0) {
                throw new IllegalArgumentException("SMB path must include both host and share name (for example: \\\\192.168.0.60\\oscar-uff).");
            }
            String hostPort = norm.substring(0, firstSlash);
            remainder = norm.substring(firstSlash + 1);
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && !hostPort.contains("]")) {
                host = hostPort.substring(0, colon);
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } else {
                host = hostPort;
            }
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SMB host cannot be empty.");
        }
        String cleanRem = sanitizeRelativeSubPath(remainder);
        if (cleanRem.isEmpty()) {
            throw new IllegalArgumentException("SMB share name is missing from '" + rawUrl + "'.");
        }
        String shareName;
        String urlSub = "";
        int slash = cleanRem.indexOf('/');
        if (slash < 0) {
            shareName = cleanRem;
        } else {
            shareName = cleanRem.substring(0, slash);
            urlSub = cleanRem.substring(slash + 1);
        }

        String extraSub = sanitizeRelativeSubPath(explicitSubPath);
        String combinedSub;
        if (!urlSub.isEmpty() && !extraSub.isEmpty()) {
            combinedSub = urlSub + "/" + extraSub;
        } else if (!urlSub.isEmpty()) {
            combinedSub = urlSub;
        } else {
            combinedSub = extraSub;
        }

        return new ParsedSmbEndpoint(host.trim(), port, shareName, combinedSub);
    }

    public static SmbSessionHandle connectSmb(
            ParsedSmbEndpoint endpoint,
            String domain,
            String username,
            String password
    ) throws IOException {
        SmbConfig smbConfig = SmbConfig.builder()
                .withTimeout(4, TimeUnit.SECONDS)
                .withTransactTimeout(5, TimeUnit.SECONDS)
                .withSoTimeout(6, TimeUnit.SECONDS)
                .withDfsEnabled(false)
                .build();

        SMBClient client = new SMBClient(smbConfig);
        Connection connection = null;
        Session session = null;
        DiskShare share = null;
        try {
            connection = client.connect(endpoint.host(), endpoint.port());
            AuthenticationContext auth;
            if (username == null || username.isBlank()) {
                auth = AuthenticationContext.guest();
            } else {
                char[] passChars = password != null ? password.toCharArray() : new char[0];
                String dom = (domain != null && !domain.isBlank()) ? domain.trim() : "";
                auth = new AuthenticationContext(username.trim(), passChars, dom);
            }
            session = connection.authenticate(auth);
            var rawShare = session.connectShare(endpoint.shareName());
            if (!(rawShare instanceof DiskShare diskShare)) {
                throw new IOException("SMB share '" + endpoint.shareName() + "' is not a disk share.");
            }
            share = diskShare;
            return new SmbSessionHandle(client, connection, session, share, endpoint);
        } catch (Exception e) {
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
            throw new IOException("Unable to connect to SMB share " + endpoint.displayShareUrl() + ": " + e.getMessage(), e);
        }
    }

    public static List<RemoteUffFileEntry> listSmbUffFiles(SmbSessionHandle handle) throws IOException {
        List<RemoteUffFileEntry> results = new ArrayList<>();
        String baseSub = handle.endpoint().subPath();
        String startSmbDir = baseSub.isEmpty() ? "" : baseSub.replace('/', '\\');
        try {
            if (!startSmbDir.isEmpty() && !handle.share().folderExists(startSmbDir)) {
                throw new IOException("SMB subfolder does not exist on share '"
                        + handle.endpoint().shareName() + "': " + baseSub);
            }
            walkSmbFolder(handle.share(), startSmbDir, "", 0, results);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Failed listing SMB directory " + handle.endpoint().displayShareUrl() + ": " + e.getMessage(), e);
        }
        return results;
    }

    private static void walkSmbFolder(
            DiskShare share,
            String smbFolderPath,
            String relPrefix,
            int depth,
            List<RemoteUffFileEntry> out
    ) {
        if (depth > MAX_SCAN_DEPTH || out.size() >= MAX_DISCOVERED_FILES) {
            return;
        }
        for (FileIdBothDirectoryInformation item : share.list(smbFolderPath)) {
            String name = item.getFileName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name) || name.startsWith(".")) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".tmp") || lower.endsWith(".partial") || lower.contains(".partial.")) {
                continue;
            }
            long attrs = item.getFileAttributes();
            boolean isDir = (attrs & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0L;
            String childRel = relPrefix.isEmpty() ? name : relPrefix + "/" + name;
            String childSmb = smbFolderPath.isEmpty() ? name : smbFolderPath + "\\" + name;

            if (isDir) {
                walkSmbFolder(share, childSmb, childRel, depth + 1, out);
            } else if (lower.endsWith(".uff") && item.getEndOfFile() >= 22L) {
                long mtimeMs = item.getLastWriteTime() != null ? item.getLastWriteTime().toEpochMillis() : 0L;
                out.add(new RemoteUffFileEntry(name, childRel, item.getEndOfFile(), mtimeMs));
                if (out.size() >= MAX_DISCOVERED_FILES) {
                    return;
                }
            }
        }
    }

    public static UffSeekableSource openSmbUffFile(
            SmbSessionHandle handle,
            String relativePath,
            long expectedSize,
            boolean closeSessionWithFile
    ) throws IOException {
        String cleanRel = sanitizeRelativeSubPath(relativePath);
        if (cleanRel.isEmpty()) {
            throw new IllegalArgumentException("Invalid UFF relative path");
        }
        String baseSub = handle.endpoint().subPath();
        String fullRel = baseSub.isEmpty() ? cleanRel : baseSub + "/" + cleanRel;
        String smbPath = fullRel.replace('/', '\\');
        try {
            File file = handle.share().openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
            );
            long size = expectedSize > 0
                    ? expectedSize
                    : file.getFileInformation().getStandardInformation().getEndOfFile();
            return UffSeekableSource.fromSmbFile(file, size, closeSessionWithFile ? handle : null);
        } catch (Exception e) {
            if (closeSessionWithFile) {
                closeQuietly(handle);
            }
            throw new IOException("Failed opening SMB UFF file '" + relativePath + "': " + e.getMessage(), e);
        }
    }

    // --- Local Directory Mode (for mounted NAS / offline bench / automated tests) ---

    public static Path validateSafeLocalRoot(String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Local UFF directory path is required.");
        }
        Path p = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        if (p.getNameCount() < 2) {
            throw new SecurityException("UFF directory path must be a dedicated directory at least two levels below filesystem root: " + p);
        }
        String unixNorm = p.toString().replace('\\', '/');
        for (String forbidden : FORBIDDEN_LOCAL_PREFIXES) {
            if (unixNorm.equals(forbidden) || unixNorm.startsWith(forbidden + "/")) {
                throw new SecurityException("Disallowed system directory for UFF integration: " + p);
            }
        }
        if (unixNorm.contains("/secrets")) {
            throw new SecurityException("UFF directory must not reference a secrets path: " + p);
        }
        if (!Files.isDirectory(p)) {
            throw new IOException("Configured local UFF directory does not exist or is not a directory: " + p);
        }
        return p.toRealPath();
    }

    public static List<RemoteUffFileEntry> listLocalUffFiles(Path rootDir) throws IOException {
        List<RemoteUffFileEntry> results = new ArrayList<>();
        walkLocalFolder(rootDir, rootDir, 0, results);
        return results;
    }

    private static void walkLocalFolder(Path rootDir, Path dir, int depth, List<RemoteUffFileEntry> out) throws IOException {
        if (depth > MAX_SCAN_DEPTH || out.size() >= MAX_DISCOVERED_FILES) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".tmp") || lower.endsWith(".partial") || lower.contains(".partial.")) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                if (attrs.isDirectory()) {
                    walkLocalFolder(rootDir, child, depth + 1, out);
                } else if (attrs.isRegularFile() && lower.endsWith(".uff") && attrs.size() >= 22L) {
                    String rel = rootDir.relativize(child).toString().replace('\\', '/');
                    out.add(new RemoteUffFileEntry(name, rel, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    if (out.size() >= MAX_DISCOVERED_FILES) {
                        return;
                    }
                }
            }
        }
    }

    public static Path resolveContainedLocalUffFile(Path realRoot, String relativePath) throws IOException {
        String cleanRel = sanitizeRelativeSubPath(relativePath);
        if (cleanRel.isEmpty()) {
            throw new SecurityException("Invalid empty UFF relative path");
        }
        Path candidate = realRoot.resolve(cleanRel).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new SecurityException("Rejected UFF path outside configured root: " + relativePath);
        }
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
            throw new IOException("UFF file does not exist: " + relativePath);
        }
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(realRoot)) {
            throw new SecurityException("Rejected symlinked UFF file outside configured root: " + relativePath);
        }
        return realFile;
    }

    public static String sanitizeRelativeSubPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (raw.indexOf('\0') >= 0) {
            throw new SecurityException("Null byte in UFF subpath");
        }
        String norm = raw.replace('\\', '/').trim();
        while (norm.startsWith("/")) {
            norm = norm.substring(1);
        }
        while (norm.endsWith("/")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        if (norm.isEmpty()) {
            return "";
        }
        if (norm.length() >= 2 && Character.isLetter(norm.charAt(0)) && norm.charAt(1) == ':') {
            throw new SecurityException("Drive-letter prefix is not valid in relative subpath: " + raw);
        }
        List<String> cleanSegs = new ArrayList<>();
        for (String seg : norm.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                throw new SecurityException("Parent path traversal '..' is not allowed in UFF path: " + raw);
            }
            cleanSegs.add(seg);
        }
        return String.join("/", cleanSegs);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static final class SmbSessionHandle implements AutoCloseable {
        private final SMBClient client;
        private final Connection connection;
        private final Session session;
        private final DiskShare share;
        private final ParsedSmbEndpoint endpoint;

        SmbSessionHandle(
                SMBClient client,
                Connection connection,
                Session session,
                DiskShare share,
                ParsedSmbEndpoint endpoint
        ) {
            this.client = client;
            this.connection = connection;
            this.session = session;
            this.share = share;
            this.endpoint = endpoint;
        }

        public DiskShare share() {
            return share;
        }

        public ParsedSmbEndpoint endpoint() {
            return endpoint;
        }

        @Override
        public void close() {
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
        }
    }
}
