/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.extensions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Pure-Java binary parser and validator for the Protomaps PMTiles v3 archive header (127 bytes).
 *
 * <p>Spec reference: PMTiles v3 binary layout (little-endian):
 * <ul>
 *   <li>{@code 0..6}: ASCII {@code "PMTiles"}</li>
 *   <li>{@code 7}: spec version ({@code 3})</li>
 *   <li>{@code 8..71}: 8 uint64 offsets/lengths (root dir, json metadata, leaf dirs, tile data)</li>
 *   <li>{@code 72..95}: 3 uint64 tile counts (addressed, entries, contents)</li>
 *   <li>{@code 96}: clustered flag</li>
 *   <li>{@code 97}: internal compression</li>
 *   <li>{@code 98}: tile compression ({@code 1=none, 2=gzip, 3=brotli, 4=zstd})</li>
 *   <li>{@code 99}: tile type ({@code 1=mvt, 2=png, 3=jpeg, 4=webp, 5=avif})</li>
 *   <li>{@code 100}: minZoom, {@code 101}: maxZoom</li>
 *   <li>{@code 102..117}: int32 minLonE7, minLatE7, maxLonE7, maxLatE7</li>
 *   <li>{@code 118}: centerZoom, {@code 119..126}: int32 centerLonE7, centerLatE7</li>
 * </ul>
 */
public record PmTilesHeader(
        int specVersion,
        String tileType,
        int tileTypeCode,
        String tileCompression,
        int minZoom,
        int maxZoom,
        double minLon,
        double minLat,
        double maxLon,
        double maxLat,
        int centerZoom,
        double centerLon,
        double centerLat,
        long addressedTilesCount,
        long tileEntriesCount
) {
    public static final int HEADER_SIZE_BYTES = 127;
    private static final byte[] MAGIC_PREFIX = "PMTiles".getBytes(StandardCharsets.US_ASCII);

    public static PmTilesHeader readAndValidate(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < HEADER_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        "Invalid .pmtiles archive: file size (" + fileSize + " bytes) is smaller than the 127-byte PMTiles v3 header");
            }
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE_BYTES);
            while (buf.hasRemaining()) {
                int r = ch.read(buf);
                if (r < 0) {
                    break;
                }
            }
            if (buf.position() < HEADER_SIZE_BYTES) {
                throw new IllegalArgumentException("Unable to read full 127-byte PMTiles header");
            }
            return parseHeaderBytes(buf.array(), fileSize);
        }
    }

    public static PmTilesHeader parseHeaderBytes(byte[] header, long totalFileSize) {
        if (header == null || header.length < HEADER_SIZE_BYTES) {
            throw new IllegalArgumentException("Invalid PMTiles header buffer");
        }
        for (int i = 0; i < MAGIC_PREFIX.length; i++) {
            if (header[i] != MAGIC_PREFIX[i]) {
                throw new IllegalArgumentException("File does not start with 'PMTiles' magic signature");
            }
        }
        int version = Byte.toUnsignedInt(header[7]);
        if (version != 3) {
            throw new IllegalArgumentException("Unsupported PMTiles version " + version + " (expected PMTiles v3)");
        }

        ByteBuffer le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long rootDirOffset = le.getLong(8);
        long rootDirBytes = le.getLong(16);
        if (rootDirOffset < HEADER_SIZE_BYTES || rootDirBytes < 0 || rootDirOffset + rootDirBytes > totalFileSize) {
            throw new IllegalArgumentException("Corrupt PMTiles v3 root directory bounds");
        }

        long addressedTiles = Math.max(0L, le.getLong(72));
        long tileEntries = Math.max(0L, le.getLong(80));
        int tileCompCode = Byte.toUnsignedInt(header[98]);
        int tileTypeCode = Byte.toUnsignedInt(header[99]);
        if (tileTypeCode < 1 || tileTypeCode > 5) {
            throw new IllegalArgumentException("Unsupported PMTiles tileType code: " + tileTypeCode);
        }

        int minZoom = Byte.toUnsignedInt(header[100]);
        int maxZoom = Byte.toUnsignedInt(header[101]);
        if (maxZoom < minZoom || maxZoom > 30) {
            throw new IllegalArgumentException("Invalid PMTiles zoom range (" + minZoom + ".." + maxZoom + ")");
        }

        double minLon = clamp(le.getInt(102) / 1e7, -180.0, 180.0);
        double minLat = clamp(le.getInt(106) / 1e7, -90.0, 90.0);
        double maxLon = clamp(le.getInt(110) / 1e7, -180.0, 180.0);
        double maxLat = clamp(le.getInt(114) / 1e7, -90.0, 90.0);
        if (minLon > maxLon || minLat > maxLat) {
            throw new IllegalArgumentException("Invalid PMTiles geographic bounding box");
        }

        int centerZoom = Byte.toUnsignedInt(header[118]);
        double centerLon = clamp(le.getInt(119) / 1e7, -180.0, 180.0);
        double centerLat = clamp(le.getInt(123) / 1e7, -90.0, 90.0);

        return new PmTilesHeader(
                version,
                mapTileType(tileTypeCode),
                tileTypeCode,
                mapCompression(tileCompCode),
                minZoom,
                maxZoom,
                minLon,
                minLat,
                maxLon,
                maxLat,
                centerZoom,
                centerLon,
                centerLat,
                addressedTiles,
                tileEntries
        );
    }

    private static String mapTileType(int code) {
        return switch (code) {
            case 1 -> "mvt";
            case 2 -> "png";
            case 3 -> "jpeg";
            case 4 -> "webp";
            case 5 -> "avif";
            default -> "unknown";
        };
    }

    private static String mapCompression(int code) {
        return switch (code) {
            case 1 -> "none";
            case 2 -> "gzip";
            case 3 -> "brotli";
            case 4 -> "zstd";
            default -> "unknown";
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
