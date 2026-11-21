/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.uff;

import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Bounded random-access read abstraction for WCO {@code .uff} ZIP archives stored either on a
 * local filesystem / {@code IBucketStore} path or on a remote SMBv2/SMBv3 network share.
 *
 * <p>Reading via offset-based random access allows {@link UffZipReader} to inspect the trailing
 * ZIP Central Directory ({@code <= 64 KiB}), {@code version}, {@code <uuid>.xml}, and
 * {@code preview.jpg} without streaming multi-megabyte 16-bit X-ray TIFF payloads over the network
 * until an operator explicitly opens a cargo radiograph.</p>
 */
public interface UffSeekableSource extends AutoCloseable {

    long size() throws IOException;

    int readAt(long position, byte[] buffer, int offset, int length) throws IOException;

    default void readFullyAt(long position, byte[] buffer, int offset, int length) throws IOException {
        int remaining = length;
        int curOff = offset;
        long curPos = position;
        while (remaining > 0) {
            int n = readAt(curPos, buffer, curOff, remaining);
            if (n <= 0) {
                throw new IOException("Unexpected EOF while reading UFF archive at offset " + curPos);
            }
            curPos += n;
            curOff += n;
            remaining -= n;
        }
    }

    @Override
    void close() throws IOException;

    static UffSeekableSource fromLocalPath(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        long fileSize = channel.size();
        return new UffSeekableSource() {
            @Override
            public long size() {
                return fileSize;
            }

            @Override
            public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (position < 0 || position >= fileSize) {
                    return -1;
                }
                ByteBuffer bb = ByteBuffer.wrap(buffer, offset, length);
                return channel.read(bb, position);
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    static UffSeekableSource fromSmbFile(File smbFile, long knownSize, AutoCloseable parentCloseable) {
        Objects.requireNonNull(smbFile, "smbFile");
        return new UffSeekableSource() {
            @Override
            public long size() {
                return knownSize;
            }

            @Override
            public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (position < 0 || position >= knownSize) {
                    return -1;
                }
                int capped = (int) Math.min(length, knownSize - position);
                try {
                    int n = smbFile.read(buffer, position, offset, capped);
                    return n <= 0 ? -1 : n;
                } catch (Exception e) {
                    throw new IOException("SMB read failed at offset " + position + ": " + e.getMessage(), e);
                }
            }

            @Override
            public void close() throws IOException {
                IOException first = null;
                try {
                    smbFile.close();
                } catch (Exception e) {
                    first = new IOException("Failed closing SMB file: " + e.getMessage(), e);
                }
                if (parentCloseable != null) {
                    try {
                        parentCloseable.close();
                    } catch (Exception e) {
                        if (first == null) {
                            first = new IOException("Failed closing SMB session: " + e.getMessage(), e);
                        }
                    }
                }
                if (first != null) {
                    throw first;
                }
            }
        };
    }
}
