/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.slf4j.Logger;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Generates deterministic CC0-1.0 synthetic 1280x720 H.264 shipping-container MP4
 * clips in-process via JavaCPP JNI ({@code libopenh264} / {@code libavcodec}, zero
 * external CLI {@code ffmpeg} or X11 shared libraries required) and streams them
 * continuously into a loopback {@code mediamtx} RTSP server at
 * {@code rtsp://127.0.0.1:8554/tutorial-cam} and {@code /tutorial-cam-2}.
 *
 * <p>Active strictly when {@code OSCAR_SANDBOX_MODE=true}.</p>
 */
public final class SandboxRtspCameraStreamer implements Closeable {

    private static final String DEFAULT_MEDIAMTX_BIN = "/usr/local/bin/mediamtx";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 10;
    private static final int TOTAL_FRAMES = 40; // 4 seconds @ 10 fps, packet-looped seamlessly

    private final Path workDir;
    private final int rtspPort;
    private final Logger log;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> publisherThreads = new ArrayList<>();

    private Path clip1Mp4;
    private Path clip2Mp4;
    private Process mediamtxProcess;
    private volatile boolean rtspLive = false;

    public SandboxRtspCameraStreamer(Path workDir, int rtspPort, Logger log) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.rtspPort = rtspPort;
        this.log = log;
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        try {
            av_log_set_level(AV_LOG_ERROR);
            avformat_network_init();
            Files.createDirectories(workDir);

            this.clip1Mp4 = workDir.resolve("tutorial-cam-1.mp4");
            this.clip2Mp4 = workDir.resolve("tutorial-cam-2.mp4");
            if (!Files.isRegularFile(clip1Mp4) || Files.size(clip1Mp4) < 1024L) {
                encodeSyntheticContainerMp4(
                        clip1Mp4,
                        TrainingSandboxEnvironment.CONTAINER_1,
                        TrainingSandboxEnvironment.PLATE_1,
                        "DEMOLANE01 · NORTH GATE",
                        new Color(20, 48, 92));
            }
            if (!Files.isRegularFile(clip2Mp4) || Files.size(clip2Mp4) < 1024L) {
                encodeSyntheticContainerMp4(
                        clip2Mp4,
                        TrainingSandboxEnvironment.CONTAINER_2,
                        TrainingSandboxEnvironment.PLATE_2,
                        "DEMOLANE02 · SOUTH GATE",
                        new Color(115, 42, 28));
            }

            Path mediamtxBin = resolveMediaMtxBinary();
            if (mediamtxBin != null) {
                launchLoopbackMediaMtxAndPublishers(mediamtxBin);
            } else if (log != null) {
                log.info("Sandbox MP4 clips generated via JNI at {} (mediamtx binary not present in test environment).", workDir);
            }
        } catch (Throwable e) {
            if (log != null) {
                log.warn("Failed starting sandbox RTSP camera streamer: {}", e.getMessage());
            }
        }
    }

    public boolean isRtspLive() {
        return rtspLive && mediamtxProcess != null && mediamtxProcess.isAlive();
    }

    public int getRtspPort() {
        return rtspPort;
    }

    public String getPrimaryRtspUrl() {
        return "rtsp://127.0.0.1:" + rtspPort + "/tutorial-cam";
    }

    public String getSecondaryRtspUrl() {
        return "rtsp://127.0.0.1:" + rtspPort + "/tutorial-cam-2";
    }

    public Path getPrimaryMp4Path() {
        return clip1Mp4;
    }

    private Path resolveMediaMtxBinary() {
        String envOverride = System.getenv("OSCAR_MEDIAMTX_BIN");
        if (envOverride != null && !envOverride.isBlank() && Files.isExecutable(Paths.get(envOverride.trim()))) {
            return Paths.get(envOverride.trim());
        }
        Path def = Paths.get(DEFAULT_MEDIAMTX_BIN);
        return Files.isExecutable(def) ? def : null;
    }

    static void encodeSyntheticContainerMp4(
            Path targetMp4,
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor
    ) throws IOException {
        AVCodec codec = avcodec_find_encoder_by_name("libopenh264");
        if (codec == null) {
            codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        }
        if (codec == null) {
            codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
        }
        if (codec == null) {
            throw new IOException("No H.264/MPEG4 encoder available in JavaCPP libavcodec");
        }

        AVFormatContext fmtCtx = new AVFormatContext(null);
        AVCodecContext encCtx = null;
        AVFrame yuvFrame = null;
        AVFrame rgbFrame = null;
        SwsContext swsCtx = null;
        AVPacket pkt = null;

        try {
            if (avformat_alloc_output_context2(fmtCtx, null, "mp4", targetMp4.toString()) < 0 || fmtCtx.isNull()) {
                throw new IOException("avformat_alloc_output_context2 failed for " + targetMp4);
            }
            AVStream stream = avformat_new_stream(fmtCtx, null);
            if (stream == null || stream.isNull()) {
                throw new IOException("avformat_new_stream failed");
            }

            encCtx = avcodec_alloc_context3(codec);
            encCtx.codec_id(codec.id());
            encCtx.codec_type(AVMEDIA_TYPE_VIDEO);
            encCtx.pix_fmt(AV_PIX_FMT_YUV420P);
            encCtx.width(WIDTH);
            encCtx.height(HEIGHT);
            AVRational tb = av_make_q(1, FPS);
            AVRational fr = av_make_q(FPS, 1);
            encCtx.time_base(tb);
            encCtx.framerate(fr);
            stream.time_base(tb);
            encCtx.bit_rate(1_500_000L);
            encCtx.gop_size(FPS);
            encCtx.max_b_frames(0);

            if ((fmtCtx.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
                encCtx.flags(encCtx.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
            }

            AVDictionary codecOpts = new AVDictionary(null);
            int openRc = avcodec_open2(encCtx, codec, codecOpts);
            av_dict_free(codecOpts);
            if (openRc < 0) {
                throw new IOException("avcodec_open2 failed: " + openRc);
            }
            if (avcodec_parameters_from_context(stream.codecpar(), encCtx) < 0) {
                throw new IOException("avcodec_parameters_from_context failed");
            }

            if ((fmtCtx.oformat().flags() & AVFMT_NOFILE) == 0) {
                AVIOContext pb = new AVIOContext(null);
                if (avio_open(pb, targetMp4.toString(), AVIO_FLAG_WRITE) < 0) {
                    throw new IOException("avio_open failed for " + targetMp4);
                }
                fmtCtx.pb(pb);
            }

            if (avformat_write_header(fmtCtx, (AVDictionary) null) < 0) {
                throw new IOException("avformat_write_header failed");
            }

            yuvFrame = av_frame_alloc();
            yuvFrame.format(AV_PIX_FMT_YUV420P);
            yuvFrame.width(WIDTH);
            yuvFrame.height(HEIGHT);
            if (av_frame_get_buffer(yuvFrame, 32) < 0) {
                throw new IOException("av_frame_get_buffer(yuv) failed");
            }

            rgbFrame = av_frame_alloc();
            rgbFrame.format(AV_PIX_FMT_RGB24);
            rgbFrame.width(WIDTH);
            rgbFrame.height(HEIGHT);
            if (av_frame_get_buffer(rgbFrame, 32) < 0) {
                throw new IOException("av_frame_get_buffer(rgb) failed");
            }

            swsCtx = sws_getContext(
                    WIDTH, HEIGHT, AV_PIX_FMT_RGB24,
                    WIDTH, HEIGHT, AV_PIX_FMT_YUV420P,
                    SWS_BILINEAR, null, null, (DoublePointer) null);
            if (swsCtx == null || swsCtx.isNull()) {
                throw new IOException("sws_getContext failed");
            }

            pkt = av_packet_alloc();
            int[] argbRow = new int[WIDTH];
            byte[] rgbRow = new byte[WIDTH * 3];
            int rgbStride = rgbFrame.linesize(0);
            BytePointer rgbPtr = rgbFrame.data(0);

            for (int f = 0; f < TOTAL_FRAMES; f++) {
                BufferedImage img = renderGatehouseFrame(f, containerId, licensePlate, gateLabel, containerColor);
                av_frame_make_writable(rgbFrame);
                for (int y = 0; y < HEIGHT; y++) {
                    img.getRGB(0, y, WIDTH, 1, argbRow, 0, WIDTH);
                    int bi = 0;
                    for (int x = 0; x < WIDTH; x++) {
                        int px = argbRow[x];
                        rgbRow[bi++] = (byte) ((px >> 16) & 0xFF);
                        rgbRow[bi++] = (byte) ((px >> 8) & 0xFF);
                        rgbRow[bi++] = (byte) (px & 0xFF);
                    }
                    rgbPtr.position((long) y * rgbStride).put(rgbRow, 0, rgbRow.length);
                }
                rgbPtr.position(0);

                av_frame_make_writable(yuvFrame);
                sws_scale(swsCtx, rgbFrame.data(), rgbFrame.linesize(), 0, HEIGHT, yuvFrame.data(), yuvFrame.linesize());
                yuvFrame.pts(f);

                drainEncoder(encCtx, fmtCtx, stream, yuvFrame, pkt);
            }
            drainEncoder(encCtx, fmtCtx, stream, null, pkt);
            av_write_trailer(fmtCtx);
        } finally {
            if (pkt != null && !pkt.isNull()) {
                av_packet_free(pkt);
            }
            if (swsCtx != null && !swsCtx.isNull()) {
                sws_freeContext(swsCtx);
            }
            if (rgbFrame != null && !rgbFrame.isNull()) {
                av_frame_free(rgbFrame);
            }
            if (yuvFrame != null && !yuvFrame.isNull()) {
                av_frame_free(yuvFrame);
            }
            if (encCtx != null && !encCtx.isNull()) {
                avcodec_free_context(encCtx);
            }
            if (fmtCtx != null && !fmtCtx.isNull()) {
                if (fmtCtx.pb() != null && !fmtCtx.pb().isNull()) {
                    avio_closep(fmtCtx.pb());
                }
                avformat_free_context(fmtCtx);
            }
        }
    }

    private static void drainEncoder(
            AVCodecContext encCtx,
            AVFormatContext fmtCtx,
            AVStream stream,
            AVFrame frame,
            AVPacket pkt
    ) throws IOException {
        int sendRc = avcodec_send_frame(encCtx, frame);
        if (sendRc < 0) {
            throw new IOException("avcodec_send_frame failed: " + sendRc);
        }
        while (true) {
            int recvRc = avcodec_receive_packet(encCtx, pkt);
            if (recvRc == AVERROR_EAGAIN() || recvRc == AVERROR_EOF) {
                break;
            }
            if (recvRc < 0) {
                throw new IOException("avcodec_receive_packet failed: " + recvRc);
            }
            av_packet_rescale_ts(pkt, encCtx.time_base(), stream.time_base());
            pkt.stream_index(stream.index());
            av_interleaved_write_frame(fmtCtx, pkt);
            av_packet_unref(pkt);
        }
    }

    private static BufferedImage renderGatehouseFrame(
            int f,
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor
    ) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Sky & asphalt roadway
        g.setColor(new Color(214, 224, 235));
        g.fillRect(0, 0, WIDTH, 250);
        g.setColor(new Color(52, 58, 66));
        g.fillRect(0, 250, WIDTH, HEIGHT - 250);

        // Radiation portal monitor pillars (left & right)
        g.setColor(new Color(235, 178, 28));
        g.fillRoundRect(54, 90, 44, 520, 8, 8);
        g.fillRoundRect(WIDTH - 98, 90, 44, 520, 8, 8);
        g.setColor(new Color(30, 41, 59));
        g.fillRect(62, 120, 28, 380);
        g.fillRect(WIDTH - 90, 120, 28, 380);

        // Yellow safety lane stripes
        g.setColor(new Color(250, 204, 21));
        g.setStroke(new BasicStroke(6f));
        g.drawLine(120, 650, 260, 420);
        g.drawLine(WIDTH - 120, 650, WIDTH - 260, 420);

        int shiftX = (int) Math.round(Math.sin((f / (double) TOTAL_FRAMES) * Math.PI * 2.0) * 18.0);
        int boxX = 240 + shiftX;
        int boxY = 155;
        int boxW = 800;
        int boxH = 340;

        // Intermodal container body + vertical corrugation ribs
        g.setColor(containerColor);
        g.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10);
        g.setColor(containerColor.darker());
        for (int rx = boxX + 28; rx < boxX + boxW - 24; rx += 34) {
            g.fillRect(rx, boxY + 12, 8, boxH - 24);
        }

        // High-contrast white ISO 6346 identification placard for reliable PP-OCRv3 recognition
        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(boxX + 130, boxY + 78, 540, 118, 8, 8);
        g.setColor(new Color(15, 23, 42));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 66));
        g.drawString(containerId, boxX + 162, boxY + 158);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.setColor(new Color(226, 232, 240));
        g.drawString("45G1   MAX GROSS 32,500 KG", boxX + 140, boxY + 242);

        // Chassis beam, wheels & license plate
        g.setColor(new Color(25, 30, 38));
        g.fillRect(boxX + 20, boxY + boxH, boxW - 40, 38);
        g.fillOval(boxX + 90, boxY + boxH + 12, 74, 74);
        g.fillOval(boxX + 180, boxY + boxH + 12, 74, 74);
        g.fillOval(boxX + boxW - 254, boxY + boxH + 12, 74, 74);
        g.fillOval(boxX + boxW - 164, boxY + boxH + 12, 74, 74);

        g.setColor(Color.WHITE);
        g.fillRoundRect(boxX + 320, boxY + boxH + 4, 175, 48, 6, 6);
        g.setColor(new Color(15, 23, 42));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(boxX + 320, boxY + boxH + 4, 175, 48, 6, 6);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString(licensePlate, boxX + 334, boxY + boxH + 38);

        g.setColor(new Color(15, 23, 42, 215));
        g.fillRect(0, 0, WIDTH, 42);
        g.setColor(new Color(251, 191, 36));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        g.drawString("TRAINING SANDBOX CAMERA  |  " + gateLabel + "  |  SYNTHETIC CC0 FEED", 20, 27);

        g.dispose();
        return img;
    }

    private void launchLoopbackMediaMtxAndPublishers(Path mediamtxBin) throws IOException {
        Path cfg = workDir.resolve("mediamtx-sandbox.yml");
        String yaml = """
                logLevel: warn
                logDestinations: [stdout]
                api: no
                metrics: no
                pprof: no
                playback: no
                rtmp: no
                hls: no
                webrtc: no
                srt: no
                rtsp: yes
                rtspTransports: [tcp]
                rtspAddress: 127.0.0.1:%d
                paths:
                  tutorial-cam: {}
                  tutorial-cam-2: {}
                """.formatted(rtspPort);

        Files.writeString(cfg, yaml, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(mediamtxBin.toString(), cfg.toString());
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        this.mediamtxProcess = pb.start();

        long deadline = System.currentTimeMillis() + 5_000L;
        boolean listenerUp = false;
        while (System.currentTimeMillis() < deadline) {
            if (!mediamtxProcess.isAlive()) {
                break;
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", rtspPort), 250);
                listenerUp = true;
                break;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (listenerUp) {
            startRtspPublisherThread("oscar-sandbox-rtsp-1", clip1Mp4, getPrimaryRtspUrl());
            startRtspPublisherThread("oscar-sandbox-rtsp-2", clip2Mp4, getSecondaryRtspUrl());
            this.rtspLive = true;
        }
        if (log != null) {
            log.info("Sandbox loopback RTSP server ready={} at {}", rtspLive, getPrimaryRtspUrl());
        }
    }

    private void startRtspPublisherThread(String threadName, Path mp4File, String rtspUrl) {
        Thread t = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    streamMp4OverRtspUntilStopped(mp4File, rtspUrl);
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
        publisherThreads.add(t);
    }

    private void streamMp4OverRtspUntilStopped(Path mp4File, String rtspUrl) throws Exception {
        AVFormatContext outCtx = new AVFormatContext(null);
        AVFormatContext firstIn = new AVFormatContext(null);
        AVPacket pkt = av_packet_alloc();
        try {
            if (avformat_open_input(firstIn, mp4File.toString(), null, null) < 0) {
                return;
            }
            if (avformat_find_stream_info(firstIn, (AVDictionary) null) < 0) {
                return;
            }
            int videoStreamIdx = -1;
            for (int i = 0; i < firstIn.nb_streams(); i++) {
                if (firstIn.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    videoStreamIdx = i;
                    break;
                }
            }
            if (videoStreamIdx < 0) {
                return;
            }

            if (avformat_alloc_output_context2(outCtx, null, "rtsp", rtspUrl) < 0 || outCtx.isNull()) {
                return;
            }
            AVStream inStream = firstIn.streams(videoStreamIdx);
            AVStream outStream = avformat_new_stream(outCtx, null);
            avcodec_parameters_copy(outStream.codecpar(), inStream.codecpar());
            outStream.codecpar().codec_tag(0);
            outStream.time_base(inStream.time_base());
            avformat_close_input(firstIn);
            firstIn = null;

            AVDictionary rtspOpts = new AVDictionary(null);
            av_dict_set(rtspOpts, "rtsp_transport", "tcp", 0);
            av_dict_set(rtspOpts, "stimeout", "3000000", 0);
            int hdrRc = avformat_write_header(outCtx, rtspOpts);
            av_dict_free(rtspOpts);
            if (hdrRc < 0) {
                return;
            }

            long cumulativeTicks = 0L;
            long frameStepTicks = Math.max(1L, av_rescale_q(1L, av_make_q(1, FPS), outStream.time_base()));
            long frameSleepMs = 1000L / FPS;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                AVFormatContext loopIn = new AVFormatContext(null);
                try {
                    if (avformat_open_input(loopIn, mp4File.toString(), null, null) < 0) {
                        break;
                    }
                    if (avformat_find_stream_info(loopIn, (AVDictionary) null) < 0) {
                        break;
                    }
                    AVStream srcStream = loopIn.streams(videoStreamIdx);
                    while (running.get() && av_read_frame(loopIn, pkt) >= 0) {
                        if (pkt.stream_index() != videoStreamIdx) {
                            av_packet_unref(pkt);
                            continue;
                        }
                        pkt.pts(cumulativeTicks);
                        pkt.dts(cumulativeTicks);
                        pkt.duration(frameStepTicks);
                        pkt.pos(-1);
                        av_packet_rescale_ts(pkt, srcStream.time_base(), outStream.time_base());
                        pkt.stream_index(outStream.index());
                        int writeRc = av_interleaved_write_frame(outCtx, pkt);
                        av_packet_unref(pkt);
                        if (writeRc < 0) {
                            return;
                        }
                        cumulativeTicks += frameStepTicks;
                        Thread.sleep(frameSleepMs);
                    }
                } finally {
                    if (!loopIn.isNull()) {
                        avformat_close_input(loopIn);
                    }
                }
            }
            av_write_trailer(outCtx);
        } finally {
            if (pkt != null && !pkt.isNull()) {
                av_packet_free(pkt);
            }
            if (firstIn != null && !firstIn.isNull()) {
                avformat_close_input(firstIn);
            }
            if (outCtx != null && !outCtx.isNull()) {
                avformat_free_context(outCtx);
            }
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        rtspLive = false;
        for (Thread t : publisherThreads) {
            t.interrupt();
        }
        publisherThreads.clear();
        Process proc = this.mediamtxProcess;
        this.mediamtxProcess = null;
        if (proc != null) {
            proc.destroy();
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
