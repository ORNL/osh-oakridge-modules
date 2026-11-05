package org.sensorhub.impl.utils.rad.eml;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class EmlArchiveExtractor {
    private static final int MAX_ENTRIES = 4_096;
    private static final long MAX_UNPACKED_BYTES = 512L * 1024L * 1024L;
    private static final int TAR_BLOCK_SIZE = 512;

    public record ExtractedBundle(Path rootDir, Path configDir, List<Path> jarFiles) {}

    private EmlArchiveExtractor() {}

    public static boolean isSupportedArchiveName(String fileName) {
        if (fileName == null)
            return false;
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar")
                || lower.endsWith(".zip")
                || lower.endsWith(".tar.gz")
                || lower.endsWith(".tgz")
                || lower.endsWith(".tar");
    }

    public static ExtractedBundle extract(Path archivePath, String originalFileName, Path targetDir) throws IOException {
        ensurePrivateDirectory(targetDir);
        String lower = (originalFileName != null ? originalFileName : archivePath.getFileName().toString())
                .toLowerCase(Locale.ROOT);

        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            try (InputStream fis = Files.newInputStream(archivePath);
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 GZIPInputStream gis = new GZIPInputStream(bis)) {
                extractTar(gis, targetDir);
            }
        } else if (lower.endsWith(".tar")) {
            try (InputStream fis = Files.newInputStream(archivePath);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                extractTar(bis, targetDir);
            }
        } else if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            try (InputStream fis = Files.newInputStream(archivePath);
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 ZipInputStream zis = new ZipInputStream(bis, StandardCharsets.UTF_8)) {
                extractZip(zis, targetDir);
            }
        } else {
            throw new IOException("Unsupported EML archive format: " + originalFileName);
        }

        Path analysisXml = findAnalysisXml(targetDir);
        if (analysisXml == null) {
            throw new IOException("EML archive is missing required config/vm250Analysis.xml");
        }
        Path configDir = analysisXml.getParent();
        Path bundleRoot = configDir.getParent() != null ? configDir.getParent() : targetDir;

        List<Path> jars = collectRuntimeJars(bundleRoot, targetDir);
        if (jars.isEmpty() && lower.endsWith(".jar")) {
            // Support single shaded/uber JAR containing classes directly alongside config/
            Path selfCopy = targetDir.resolve("eml-self-bundle.jar");
            Files.copy(archivePath, selfCopy, StandardCopyOption.REPLACE_EXISTING);
            jars = List.of(selfCopy);
        }
        if (jars.isEmpty()) {
            throw new IOException("EML archive contains no runtime JAR files");
        }
        return new ExtractedBundle(targetDir, configDir, jars);
    }

    private static Path findAnalysisXml(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> "vm250Analysis.xml".equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static List<Path> collectRuntimeJars(Path bundleRoot, Path extractRoot) throws IOException {
        List<Path> jars = new ArrayList<>();
        Path jarsDir = bundleRoot.resolve("jars");
        Path searchRoot = Files.isDirectory(jarsDir) ? jarsDir : extractRoot;
        try (Stream<Path> walk = Files.walk(searchRoot, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jars::add);
        }
        return jars;
    }

    private static void extractZip(ZipInputStream zis, Path targetRoot) throws IOException {
        int entries = 0;
        long totalBytes = 0L;
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (++entries > MAX_ENTRIES)
                throw new IOException("EML archive exceeds maximum entry limit (" + MAX_ENTRIES + ")");
            String rawName = entry.getName();
            if (shouldSkipEntry(rawName)) {
                zis.closeEntry();
                continue;
            }
            Path dest = resolveSafeTarget(targetRoot, rawName);
            if (entry.isDirectory()) {
                Files.createDirectories(dest);
            } else {
                Files.createDirectories(dest.getParent());
                totalBytes += copyBounded(zis, dest, MAX_UNPACKED_BYTES - totalBytes);
            }
            zis.closeEntry();
        }
    }

    private static void extractTar(InputStream in, Path targetRoot) throws IOException {
        int entries = 0;
        long totalBytes = 0L;
        byte[] header = new byte[TAR_BLOCK_SIZE];
        String pendingLongName = null;

        while (true) {
            int read = readFullyOrEof(in, header);
            if (read == 0 || isZeroBlock(header))
                break;
            if (read < TAR_BLOCK_SIZE)
                throw new EOFException("Truncated TAR header in EML archive");
            if (++entries > MAX_ENTRIES)
                throw new IOException("EML archive exceeds maximum entry limit (" + MAX_ENTRIES + ")");

            String name = parseTarName(header);
            long size = parseTarOctalOrBinary(header, 124, 12);
            byte typeFlag = header[156];

            if (size < 0 || size > MAX_UNPACKED_BYTES)
                throw new IOException("Invalid TAR entry size in EML archive: " + size);

            if (typeFlag == 'L') {
                byte[] nameBytes = readExactBytes(in, (int) size);
                skipTarPadding(in, size);
                int len = nameBytes.length;
                while (len > 0 && nameBytes[len - 1] == 0)
                    len--;
                pendingLongName = new String(nameBytes, 0, len, StandardCharsets.UTF_8);
                continue;
            }
            if (typeFlag == 'x' || typeFlag == 'g') {
                byte[] paxBytes = readExactBytes(in, (int) size);
                skipTarPadding(in, size);
                if (typeFlag == 'x') {
                    String paxPath = parsePaxPath(paxBytes);
                    if (paxPath != null)
                        pendingLongName = paxPath;
                }
                continue;
            }

            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }

            if (typeFlag == '1' || typeFlag == '2') {
                throw new IOException("Symbolic and hard links are not permitted in EML archives: " + name);
            }

            boolean isDir = (typeFlag == '5') || name.endsWith("/");
            boolean isRegular = (typeFlag == 0 || typeFlag == '0');

            if (shouldSkipEntry(name) || (!isDir && !isRegular)) {
                skipExact(in, size);
                skipTarPadding(in, size);
                continue;
            }

            Path dest = resolveSafeTarget(targetRoot, name);
            if (isDir) {
                Files.createDirectories(dest);
            } else {
                if (totalBytes + size > MAX_UNPACKED_BYTES)
                    throw new IOException("EML archive exceeds maximum unpacked size");
                Files.createDirectories(dest.getParent());
                copyExactBounded(in, dest, size);
                totalBytes += size;
                skipTarPadding(in, size);
            }
        }
    }

    private static boolean shouldSkipEntry(String entryName) {
        if (entryName == null || entryName.isBlank())
            return true;
        String norm = entryName.replace('\\', '/');
        if (norm.contains("/daily_file_examples/")
                || norm.startsWith("daily_file_examples/")
                || norm.contains("/__pycache__/")
                || norm.contains("/src/gov.")
                || norm.startsWith("src/")
                || norm.contains("/py/")
                || norm.startsWith("py/")
                || norm.endsWith("-sources.jar")
                || norm.endsWith("-javadoc.jar")) {
            return true;
        }
        return false;
    }

    static Path resolveSafeTarget(Path root, String entryName) throws IOException {
        String sanitized = entryName.replace('\\', '/');
        while (sanitized.startsWith("/")) {
            throw new IOException("Absolute archive entry path is forbidden: " + entryName);
        }
        if (sanitized.indexOf('\0') >= 0)
            throw new IOException("NUL byte in archive entry path");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(sanitized).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Archive path traversal rejected: " + entryName);
        }
        return resolved;
    }

    private static long copyBounded(InputStream in, Path dest, long maxRemaining) throws IOException {
        long written = 0L;
        byte[] buf = new byte[16_384];
        try (var out = Files.newOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (written > maxRemaining)
                    throw new IOException("EML archive exceeds maximum unpacked size");
                out.write(buf, 0, n);
            }
        }
        return written;
    }

    private static void copyExactBounded(InputStream in, Path dest, long exactBytes) throws IOException {
        long remaining = exactBytes;
        byte[] buf = new byte[16_384];
        try (var out = Files.newOutputStream(dest)) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, toRead);
                if (n < 0)
                    throw new EOFException("Unexpected EOF while extracting EML TAR entry");
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    private static int readFullyOrEof(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0)
                return total;
            total += n;
        }
        return total;
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buf = new byte[Math.min(8192, Math.max(1, length))];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0)
                throw new EOFException("Unexpected EOF in TAR metadata block");
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static void skipExact(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else {
                int n = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                if (n < 0)
                    throw new EOFException("Unexpected EOF while skipping TAR entry");
                remaining -= n;
            }
        }
    }

    private static void skipTarPadding(InputStream in, long size) throws IOException {
        long mod = size % TAR_BLOCK_SIZE;
        if (mod != 0)
            skipExact(in, TAR_BLOCK_SIZE - mod);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0)
                return false;
        }
        return true;
    }

    private static String parseTarName(byte[] header) {
        String name = parseTarString(header, 0, 100);
        String magic = parseTarString(header, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = parseTarString(header, 345, 155);
            if (!prefix.isEmpty())
                return prefix + "/" + name;
        }
        return name;
    }

    private static String parseTarString(byte[] buf, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && buf[end] != 0)
            end++;
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseTarOctalOrBinary(byte[] buf, int offset, int length) {
        if ((buf[offset] & 0x80) != 0) {
            long val = 0L;
            for (int i = offset + 1; i < offset + length; i++) {
                val = (val << 8) | (buf[i] & 0xFFL);
            }
            return val;
        }
        String s = parseTarString(buf, offset, length).trim();
        if (s.isEmpty())
            return 0L;
        return Long.parseLong(s, 8);
    }

    private static String parsePaxPath(byte[] paxBytes) {
        String text = new String(paxBytes, StandardCharsets.UTF_8);
        int idx = 0;
        while (idx < text.length()) {
            int space = text.indexOf(' ', idx);
            if (space < 0)
                break;
            int recordLen;
            try {
                recordLen = Integer.parseInt(text.substring(idx, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (recordLen <= 0 || idx + recordLen > text.length())
                break;
            String record = text.substring(space + 1, idx + recordLen - 1);
            if (record.startsWith("path="))
                return record.substring("path=".length());
            idx += recordLen;
        }
        return null;
    }

    public static void ensurePrivateDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g., Windows local dev)
        }
    }

    public static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir))
            return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
