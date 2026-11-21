/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Hardened random-access ZIP Central Directory reader for WCO {@code .uff} packages.
 *
 * <p>Inspects only the trailing End-of-Central-Directory (EOCD / ZIP64 EOCD) and Central Directory
 * table, allowing metadata indexing over SMBv2/SMBv3 in ~1-2 small reads instead of streaming
 * 10-50 MiB radiograph archives sequentially.</p>
 */
public final class UffZipReader {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;

    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    private static final int MAX_EOCD_SEARCH = 65_557;
    private static final int MAX_CEN_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2_048;

    public record ZipEntryMeta(
            String name,
            int compressionMethod,
            long compressedSize,
            long uncompressedSize,
            long localHeaderOffset,
            int crc32,
            boolean directory
    ) {}

    private final UffSeekableSource source;
    private final Map<String, ZipEntryMeta> entries;

    private UffZipReader(UffSeekableSource source, Map<String, ZipEntryMeta> entries) {
        this.source = source;
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static UffZipReader open(UffSeekableSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        long totalSize = source.size();
        if (totalSize < 22) {
            throw new IOException("Invalid UFF archive: file size (" + totalSize + " B) is smaller than ZIP EOCD");
        }

        int tailLen = (int) Math.min(totalSize, MAX_EOCD_SEARCH);
        long tailOffset = totalSize - tailLen;
        byte[] tail = new byte[tailLen];
        source.readFullyAt(tailOffset, tail, 0, tailLen);

        int eocdPos = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (getIntLE(tail, i) == EOCD_SIG) {
                int commentLen = getUShortLE(tail, i + 20);
                if (i + 22 + commentLen <= tailLen) {
                    eocdPos = i;
                    break;
                }
            }
        }
        if (eocdPos < 0) {
            throw new IOException("Invalid UFF archive: ZIP End-of-Central-Directory signature not found");
        }

        long cdSize = getUIntLE(tail, eocdPos + 12);
        long cdOffset = getUIntLE(tail, eocdPos + 16);

        // Handle ZIP64 archives cleanly (WCO UFF 3.0 specifies ZIP 6.3+ support)
        if ((cdSize == 0xFFFF_FFFFL || cdOffset == 0xFFFF_FFFFL) && eocdPos >= 20) {
            int locPos = eocdPos - 20;
            if (getIntLE(tail, locPos) == ZIP64_LOCATOR_SIG) {
                long zip64EocdOffset = getLongLE(tail, locPos + 8);
                byte[] z64 = new byte[56];
                source.readFullyAt(zip64EocdOffset, z64, 0, 56);
                if (getIntLE(z64, 0) == ZIP64_EOCD_SIG) {
                    cdSize = getLongLE(z64, 40);
                    cdOffset = getLongLE(z64, 48);
                }
            }
        }

        if (cdSize < 0 || cdSize > MAX_CEN_BYTES || cdOffset < 0 || cdOffset + cdSize > totalSize) {
            throw new IOException("Invalid or oversized UFF Central Directory (size=" + cdSize + ", offset=" + cdOffset + ")");
        }

        byte[] cen = new byte[(int) cdSize];
        source.readFullyAt(cdOffset, cen, 0, cen.length);

        Map<String, ZipEntryMeta> map = new LinkedHashMap<>();
        int pos = 0;
        int count = 0;
        while (pos + 46 <= cen.length) {
            if (getIntLE(cen, pos) != CEN_SIG) {
                break;
            }
            if (++count > MAX_ENTRIES) {
                throw new IOException("UFF archive exceeds maximum permitted entry count (" + MAX_ENTRIES + ")");
            }
            int method = getUShortLE(cen, pos + 10);
            int crc = getIntLE(cen, pos + 16);
            long compSize = getUIntLE(cen, pos + 20);
            long uncompSize = getUIntLE(cen, pos + 24);
            int nameLen = getUShortLE(cen, pos + 28);
            int extraLen = getUShortLE(cen, pos + 30);
            int commentLen = getUShortLE(cen, pos + 32);
            long locOffset = getUIntLE(cen, pos + 42);

            if (pos + 46 + nameLen + extraLen + commentLen > cen.length) {
                throw new IOException("Truncated UFF Central Directory entry");
            }

            String rawName = new String(cen, pos + 46, nameLen, StandardCharsets.UTF_8);
            String safeName = sanitizeEntryName(rawName);

            // Parse ZIP64 extended info extra field (0x0001) if present
            if (extraLen >= 4 && (uncompSize == 0xFFFF_FFFFL || compSize == 0xFFFF_FFFFL || locOffset == 0xFFFF_FFFFL)) {
                int ep = pos + 46 + nameLen;
                int eEnd = ep + extraLen;
                while (ep + 4 <= eEnd) {
                    int headerId = getUShortLE(cen, ep);
                    int dataSize = getUShortLE(cen, ep + 2);
                    int dp = ep + 4;
                    if (headerId == 0x0001 && dp + dataSize <= eEnd) {
                        if (uncompSize == 0xFFFF_FFFFL && dp + 8 <= ep + 4 + dataSize) {
                            uncompSize = getLongLE(cen, dp);
                            dp += 8;
                        }
                        if (compSize == 0xFFFF_FFFFL && dp + 8 <= ep + 4 + dataSize) {
                            compSize = getLongLE(cen, dp);
                            dp += 8;
                        }
                        if (locOffset == 0xFFFF_FFFFL && dp + 8 <= ep + 4 + dataSize) {
                            locOffset = getLongLE(cen, dp);
                        }
                        break;
                    }
                    ep += 4 + dataSize;
                }
            }

            boolean isDir = rawName.endsWith("/") || rawName.endsWith("\\");
            if (!safeName.isEmpty()) {
                map.put(safeName, new ZipEntryMeta(safeName, method, compSize, uncompSize, locOffset, crc, isDir));
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }

        return new UffZipReader(source, map);
    }

    public Map<String, ZipEntryMeta> entries() {
        return entries;
    }

    public ZipEntryMeta getEntry(String normalizedName) {
        if (normalizedName == null) {
            return null;
        }
        return entries.get(sanitizeEntryName(normalizedName));
    }

    public byte[] readEntryBytesBounded(String entryName, int maxBytes) throws IOException {
        ZipEntryMeta meta = getEntry(entryName);
        if (meta == null) {
            throw new IOException("UFF entry not found: " + entryName);
        }
        if (meta.uncompressedSize() > maxBytes) {
            throw new IOException("UFF entry '" + entryName + "' (" + meta.uncompressedSize()
                    + " B) exceeds limit (" + maxBytes + " B)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(Math.max(meta.uncompressedSize(), 256L), maxBytes));
        streamEntry(meta, out, maxBytes);
        return out.toByteArray();
    }

    public void streamEntry(String entryName, OutputStream out, long maxBytes) throws IOException {
        ZipEntryMeta meta = getEntry(entryName);
        if (meta == null) {
            throw new IOException("UFF entry not found: " + entryName);
        }
        streamEntry(meta, out, maxBytes);
    }

    public void streamEntry(ZipEntryMeta meta, OutputStream out, long maxBytes) throws IOException {
        if (meta.directory()) {
            throw new IOException("Cannot stream directory entry: " + meta.name());
        }
        if (meta.uncompressedSize() > maxBytes) {
            throw new IOException("UFF entry '" + meta.name() + "' (" + meta.uncompressedSize()
                    + " B) exceeds maximum permitted stream size (" + maxBytes + " B)");
        }
        long locPos = meta.localHeaderOffset();
        byte[] loc = new byte[30];
        source.readFullyAt(locPos, loc, 0, 30);
        if (getIntLE(loc, 0) != LOC_SIG) {
            throw new IOException("Invalid ZIP local file header for entry: " + meta.name());
        }
        int nameLen = getUShortLE(loc, 26);
        int extraLen = getUShortLE(loc, 28);
        long dataOffset = locPos + 30L + nameLen + extraLen;
        long compSize = meta.compressedSize();
        if (dataOffset < 0 || dataOffset + compSize > source.size()) {
            throw new IOException("Out-of-bounds UFF entry payload: " + meta.name());
        }

        InputStream rawIn = new SeekableSliceInputStream(source, dataOffset, compSize);
        if (meta.compressionMethod() == METHOD_STORED) {
            copyBounded(rawIn, out, maxBytes, meta.name());
        } else if (meta.compressionMethod() == METHOD_DEFLATED) {
            Inflater inflater = new Inflater(true);
            try (InflaterInputStream infIn = new InflaterInputStream(rawIn, inflater, 32_768)) {
                copyBounded(infIn, out, maxBytes, meta.name());
            } finally {
                inflater.end();
            }
        } else {
            throw new IOException("Unsupported ZIP compression method " + meta.compressionMethod()
                    + " on UFF entry: " + meta.name());
        }
    }

    static String sanitizeEntryName(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.indexOf('\0') >= 0) {
            throw new SecurityException("Null byte in UFF ZIP entry name");
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
            throw new SecurityException("Drive-qualified path rejected in UFF ZIP entry: " + raw);
        }
        for (String seg : norm.split("/")) {
            if ("..".equals(seg) || ".".equals(seg)) {
                throw new SecurityException("Unsafe relative path segment rejected in UFF ZIP entry: " + raw);
            }
        }
        return norm;
    }

    private static void copyBounded(InputStream in, OutputStream out, long maxBytes, String label) throws IOException {
        byte[] buf = new byte[65_536];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            total += n;
            if (total > maxBytes) {
                throw new IOException("Decompressed UFF entry '" + label + "' exceeded maximum bound (" + maxBytes + " B)");
            }
            out.write(buf, 0, n);
        }
    }

    private static int getUShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int getIntLE(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long getUIntLE(byte[] b, int off) {
        return Integer.toUnsignedLong(getIntLE(b, off));
    }

    private static long getLongLE(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static final class SeekableSliceInputStream extends InputStream {
        private final UffSeekableSource src;
        private long pos;
        private long remaining;

        SeekableSliceInputStream(UffSeekableSource src, long start, long length) {
            this.src = src;
            this.pos = start;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = src.readAt(pos, b, off, toRead);
            if (n <= 0) {
                return -1;
            }
            pos += n;
            remaining -= n;
            return n;
        }
    }
}
