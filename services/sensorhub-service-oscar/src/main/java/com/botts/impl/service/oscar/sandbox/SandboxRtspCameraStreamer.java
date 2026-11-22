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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Generates deterministic CC0-1.0 synthetic 1280x720 H.264 tractor-trailer portal
 * transit MP4 clips in-process via JavaCPP JNI ({@code libopenh264} / {@code libavcodec},
 * zero external CLI {@code ffmpeg} or X11 shared libraries required) and streams them
 * into a loopback {@code mediamtx} RTSP server at
 * {@code rtsp://127.0.0.1:8554/tutorial-cam} and {@code /tutorial-cam-2}.
 *
 * <p>Each lane pre-encodes an empty-lane idle loop ({@code 1.0 s}), a full-lane
 * tractor-trailer drive-through ({@code 5.0 s}, entering from off-screen right,
 * passing completely between the foreground/background RPM pillars, and exiting
 * off-screen left), and an extended-occupancy stall clip ({@code 12.0 s}). Occupancy
 * injections immediately trigger a synchronized full-lane truck transit while idle
 * live viewing plays periodic ambient drive-throughs.</p>
 *
 * <p>Active strictly when {@code OSCAR_SANDBOX_MODE=true}.</p>
 */
public final class SandboxRtspCameraStreamer implements Closeable {

    private static final String DEFAULT_MEDIAMTX_BIN = "/usr/local/bin/mediamtx";
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int FPS = 15;
    private static final int GOP_SIZE = 5; // 3 keyframes/sec => <=333ms FileControl lock-on
    private static final int IDLE_FRAMES = 15;            // 1.0s @ 15 fps
    private static final int TRANSIT_FRAMES = 75;         // 5.0s @ 15 fps full portal drive-through
    private static final int EXTENDED_TRANSIT_FRAMES = 180; // 12.0s @ 15 fps stall + exit
    private static final long AMBIENT_TRANSIT_INTERVAL_MS = 14_000L;

    private static final int MODE_NONE = 0;
    private static final int MODE_NORMAL_TRANSIT = 1;
    private static final int MODE_EXTENDED_TRANSIT = 2;

    public static final Color LANE1_CONTAINER_COLOR = new Color(194, 72, 38); // Terracotta SCFU8572724
    public static final Color LANE1_CAB_COLOR = new Color(241, 245, 249);      // White cabover SWT03
    public static final Color LANE2_CONTAINER_COLOR = new Color(30, 84, 158);  // Maritime blue RSTU2402941
    public static final Color LANE2_CAB_COLOR = new Color(24, 42, 86);         // Navy tractor M50M1

    private static final class LaneClipSet {
        final Path idleMp4;
        final Path transitMp4;
        final Path extendedMp4;
        final AtomicInteger pendingMode = new AtomicInteger(MODE_NORMAL_TRANSIT);
        final AtomicLong lastTransitCompletedMs = new AtomicLong(0L);

        LaneClipSet(Path idleMp4, Path transitMp4, Path extendedMp4) {
            this.idleMp4 = idleMp4;
            this.transitMp4 = transitMp4;
            this.extendedMp4 = extendedMp4;
        }
    }

    private final Path workDir;
    private final int rtspPort;
    private final Logger log;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> publisherThreads = new ArrayList<>();

    private LaneClipSet lane1Clips;
    private LaneClipSet lane2Clips;
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

            this.lane1Clips = ensureLaneClipSet(
                    "lane1",
                    TrainingSandboxEnvironment.CONTAINER_1,
                    TrainingSandboxEnvironment.PLATE_1,
                    "DEMOLANE01 · NORTH GATE",
                    LANE1_CONTAINER_COLOR,
                    LANE1_CAB_COLOR);
            this.lane2Clips = ensureLaneClipSet(
                    "lane2",
                    TrainingSandboxEnvironment.CONTAINER_2,
                    TrainingSandboxEnvironment.PLATE_2,
                    "DEMOLANE02 · SOUTH GATE",
                    LANE2_CONTAINER_COLOR,
                    LANE2_CAB_COLOR);

            Path mediamtxBin = resolveMediaMtxBinary();
            if (mediamtxBin != null) {
                launchLoopbackMediaMtxAndPublishers(mediamtxBin);
            } else if (log != null) {
                log.info("Sandbox moving-truck MP4 clips generated via JNI at {} (mediamtx binary not present in test environment).", workDir);
            }
        } catch (Throwable e) {
            if (log != null) {
                log.warn("Failed starting sandbox RTSP camera streamer: {}", e.getMessage());
            }
        }
    }

    /**
     * Immediately cues a full-lane tractor-trailer drive-through on the specified sandbox lane
     * ({@code 1} or {@code 2}) so the RTSP camera feed and retained occupancy MP4 depict the truck
     * entering the portal, traversing between the RPM pillars, and exiting the lane in sync with
     * the radiation occupancy.
     */
    public void triggerLaneTransit(int laneSlot, boolean extendedHold) {
        LaneClipSet target = (laneSlot == 2) ? lane2Clips : lane1Clips;
        if (target != null) {
            target.pendingMode.set(extendedHold ? MODE_EXTENDED_TRANSIT : MODE_NORMAL_TRANSIT);
        }
    }

    private LaneClipSet ensureLaneClipSet(
            String prefix,
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor,
            Color cabColor
    ) throws IOException {
        Path idleMp4 = workDir.resolve(prefix + "-idle-v2.mp4");
        Path transitMp4 = workDir.resolve(prefix + "-transit-v2.mp4");
        Path extendedMp4 = workDir.resolve(prefix + "-extended-v2.mp4");

        if (!Files.isRegularFile(idleMp4) || Files.size(idleMp4) < 1024L) {
            encodeClipMp4(idleMp4, IDLE_FRAMES, f -> renderPortalSceneFrame(
                    f, IDLE_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                    MODE_NONE, false));
        }
        if (!Files.isRegularFile(transitMp4) || Files.size(transitMp4) < 1024L) {
            encodeClipMp4(transitMp4, TRANSIT_FRAMES, f -> renderPortalSceneFrame(
                    f, TRANSIT_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                    MODE_NORMAL_TRANSIT, false));
        }
        if (!Files.isRegularFile(extendedMp4) || Files.size(extendedMp4) < 1024L) {
            encodeClipMp4(extendedMp4, EXTENDED_TRANSIT_FRAMES, f -> renderPortalSceneFrame(
                    f, EXTENDED_TRANSIT_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                    MODE_EXTENDED_TRANSIT, true));
        }
        return new LaneClipSet(idleMp4, transitMp4, extendedMp4);
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
        return lane1Clips != null ? lane1Clips.transitMp4 : null;
    }

    private Path resolveMediaMtxBinary() {
        String envOverride = System.getenv("OSCAR_MEDIAMTX_BIN");
        if (envOverride != null && !envOverride.isBlank() && Files.isExecutable(Paths.get(envOverride.trim()))) {
            return Paths.get(envOverride.trim());
        }
        Path def = Paths.get(DEFAULT_MEDIAMTX_BIN);
        return Files.isExecutable(def) ? def : null;
    }

    @FunctionalInterface
    private interface FrameRenderer {
        BufferedImage render(int frameIndex);
    }

    /**
     * Encodes a complete 5.0-second (75-frame @ 15 fps) full-lane tractor-trailer drive-through MP4
     * in-process via JavaCPP {@code libopenh264}/{@code libavcodec}.
     */
    static void encodeSyntheticContainerMp4(
            Path targetMp4,
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor
    ) throws IOException {
        Color cabColor = TrainingSandboxEnvironment.CONTAINER_2.equals(containerId)
                ? LANE2_CAB_COLOR
                : LANE1_CAB_COLOR;
        encodeClipMp4(targetMp4, TRANSIT_FRAMES, f -> renderPortalSceneFrame(
                f, TRANSIT_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                MODE_NORMAL_TRANSIT, false));
    }

    /**
     * Renders a crisp CC0 optical rear/side container verification JPEG matching the moving
     * sandbox truck for inclusion inside WCO UFF 3.0 {@code Ocr-Rear-1-*.jpg} attachments.
     */
    public static byte[] renderOpticalRearCropJpeg(
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor,
            Color cabColor
    ) throws IOException {
        BufferedImage img = renderPortalSceneFrame(
                40, TRANSIT_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                MODE_NORMAL_TRANSIT, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream(65_536);
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /**
     * Renders a crisp CC0 optical front tractor + license-plate JPEG matching the moving
     * sandbox truck for inclusion inside WCO UFF 3.0 {@code Ocr-Front-1-*.jpg} attachments.
     */
    public static byte[] renderOpticalFrontCropJpeg(
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor,
            Color cabColor
    ) throws IOException {
        BufferedImage img = renderPortalSceneFrame(
                14, TRANSIT_FRAMES, containerId, licensePlate, gateLabel, containerColor, cabColor,
                MODE_NORMAL_TRANSIT, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream(65_536);
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static void encodeClipMp4(
            Path targetMp4,
            int totalFrames,
            FrameRenderer renderer
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
            encCtx.bit_rate(2_200_000L);
            encCtx.gop_size(GOP_SIZE);
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

            for (int f = 0; f < totalFrames; f++) {
                BufferedImage img = renderer.render(f);
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

    /**
     * Computes horizontal tractor nose coordinate {@code cabNoseX} for a full-lane right-to-left
     * drive-through:
     * <ul>
     *   <li>{@code cabNoseX = 1270}: empty lane, tractor bumper about to enter from right</li>
     *   <li>{@code cabNoseX = 240}: 40-ft container centered dead-on between the RPM pillars</li>
     *   <li>{@code cabNoseX = -940}: rear container doors &amp; trailer bumper fully exited off left</li>
     * </ul>
     */
    private static int computeCabNoseX(int frame, int totalFrames, int mode) {
        if (mode == MODE_NONE) {
            return WIDTH + 400; // Off-screen: empty portal lane
        }
        final double startX = 1265.0;
        final double endX = -935.0;
        if (mode == MODE_EXTENDED_TRANSIT) {
            double u = frame / (double) Math.max(1, totalFrames - 1);
            // 0..0.18: drive into portal (1265 -> 255)
            // 0.18..0.80: slow creep/stall inside portal (255 -> 95)
            // 0.80..1.00: accelerate out of portal (95 -> -935)
            if (u <= 0.18) {
                double t = u / 0.18;
                return (int) Math.round(startX + (255.0 - startX) * t);
            } else if (u <= 0.80) {
                double t = (u - 0.18) / 0.62;
                return (int) Math.round(255.0 + (95.0 - 255.0) * t);
            } else {
                double t = (u - 0.80) / 0.20;
                return (int) Math.round(95.0 + (endX - 95.0) * t);
            }
        }
        double u = frame / (double) Math.max(1, totalFrames - 1);
        return (int) Math.round(startX + (endX - startX) * u);
    }

    static BufferedImage renderPortalSceneFrame(
            int frame,
            int totalFrames,
            String containerId,
            String licensePlate,
            String gateLabel,
            Color containerColor,
            Color cabColor,
            int mode,
            boolean hazardBlink
    ) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 1. Sky gradient + distant terminal horizon & canopy
        g.setPaint(new GradientPaint(0, 0, new Color(196, 214, 232), 0, 245, new Color(228, 236, 244)));
        g.fillRect(0, 0, WIDTH, 245);

        // Distant port warehouse & light standards
        g.setColor(new Color(148, 163, 184));
        g.fillRect(140, 178, 390, 67);
        g.fillRect(760, 186, 360, 59);
        g.setColor(new Color(100, 116, 139));
        for (int wx = 165; wx < 510; wx += 48) {
            g.fillRect(wx, 194, 28, 18);
        }
        // Treeline along terminal perimeter
        g.setColor(new Color(78, 108, 86));
        for (int tx = 0; tx < WIDTH; tx += 58) {
            g.fillOval(tx - 16, 206 + ((tx * 7) % 11), 74, 48);
        }

        // 2. Concrete gatehouse pad & asphalt RPM inspection lane
        g.setColor(new Color(164, 172, 180));
        g.fillRect(0, 245, WIDTH, 92); // Far concrete pad
        g.setPaint(new GradientPaint(0, 337, new Color(68, 75, 84), 0, 645, new Color(50, 56, 64)));
        g.fillRect(0, 337, WIDTH, 315); // Active travel lane
        g.setColor(new Color(138, 146, 156));
        g.fillRect(0, 652, WIDTH, HEIGHT - 652); // Near concrete apron

        // Yellow lane boundary stripes & center guide dashes
        g.setColor(new Color(234, 179, 8));
        g.fillRect(0, 340, WIDTH, 6);
        g.fillRect(0, 644, WIDTH, 7);
        g.setColor(new Color(203, 213, 225));
        for (int dx = 30; dx < WIDTH; dx += 110) {
            g.fillRect(dx, 492, 56, 5);
        }

        // 3. Far-side (background) Radiation Portal Monitor tower + yellow safety bollards
        // Drawn BEFORE the truck so the tractor-trailer passes realistically IN FRONT of the far pillar!
        boolean truckInsidePortal = false;
        int cabNoseX = computeCabNoseX(frame, totalFrames, mode);
        int truckTotalLen = 910; // 200px cab + 30px fifth-wheel gap + 680px 40-ft container
        if (mode != MODE_NONE && cabNoseX < 1160 && (cabNoseX + truckTotalLen) > 120) {
            truckInsidePortal = true;
        }
        drawRpmPillarAndBollards(g, 608, 82, 54, 266, truckInsidePortal, (frame % 6) < 3, false);

        // 4. Moving Articulated Tractor-Trailer Truck (drives completely through lane: right -> left)
        if (mode != MODE_NONE && cabNoseX < WIDTH + 40 && (cabNoseX + truckTotalLen) > -40) {
            drawMovingTractorTrailer(
                    g,
                    cabNoseX,
                    frame,
                    containerId,
                    licensePlate,
                    containerColor,
                    cabColor,
                    hazardBlink && ((frame / 4) % 2 == 0));
        }

        // 5. Near-side (foreground) RPM pillars at entry (right) & exit (left) + yellow safety bollards
        // Drawn AFTER the truck so the vehicle enters from behind the right pillar and exits behind the left pillar!
        drawRpmPillarAndBollards(g, 34, 68, 62, 594, truckInsidePortal, (frame % 6) < 3, true);
        drawRpmPillarAndBollards(g, WIDTH - 96, 68, 62, 594, truckInsidePortal, (frame % 6) < 3, true);

        // 6. Top gatehouse camera telemetry bar (clean, zero private IP / zero personal info)
        g.setColor(new Color(15, 23, 42, 220));
        g.fillRect(0, 0, WIDTH, 40);
        g.setColor(new Color(251, 191, 36));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        String stateTag = truckInsidePortal ? "OCCUPIED" : "READY";
        g.drawString(
                String.format("TRAINING SANDBOX CAMERA  |  %s  |  PORTAL: %s  |  CC0 SYNTHETIC FEED", gateLabel, stateTag),
                20,
                26);

        g.dispose();
        return img;
    }

    private static void drawRpmPillarAndBollards(
            Graphics2D g,
            int x,
            int y,
            int w,
            int h,
            boolean occupied,
            boolean blinkOn,
            boolean foreground
    ) {
        // Concrete pedestal base
        g.setColor(new Color(148, 163, 184));
        g.fillRoundRect(x - 10, y + h - 22, w + 20, 24, 6, 6);

        // White Rapiscan-style RPM detector shroud
        g.setColor(new Color(241, 245, 249));
        g.fillRoundRect(x, y, w, h - 18, 8, 8);
        g.setColor(new Color(100, 116, 139));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h - 18, 8, 8);

        // Detector panel seams
        g.setColor(new Color(203, 213, 225));
        g.drawLine(x + 6, y + (h / 3), x + w - 6, y + (h / 3));
        g.drawLine(x + 6, y + (2 * h / 3), x + w - 6, y + (2 * h / 3));

        // Status annunciation beacon atop RPM pillar (green idle, amber occupied)
        Color beacon = occupied
                ? (blinkOn ? new Color(245, 158, 11) : new Color(180, 83, 9))
                : new Color(22, 163, 74);
        g.setColor(beacon);
        g.fillRoundRect(x + (w / 2) - 12, y - 14, 24, 14, 6, 6);

        // Yellow steel protective bollards flanking the RPM shroud
        int bollardH = foreground ? 190 : 108;
        int bollardW = foreground ? 16 : 12;
        int bollardY = y + h - bollardH;
        g.setColor(new Color(234, 179, 8));
        g.fillRoundRect(x - bollardW - 6, bollardY, bollardW, bollardH, 6, 6);
        g.fillRoundRect(x + w + 6, bollardY, bollardW, bollardH, 6, 6);
        g.setColor(new Color(30, 41, 59));
        g.fillRect(x - bollardW - 6, bollardY + 18, bollardW, 10);
        g.fillRect(x + w + 6, bollardY + 18, bollardW, 10);
    }

    private static void drawMovingTractorTrailer(
            Graphics2D g,
            int cabNoseX,
            int frame,
            String containerId,
            String licensePlate,
            Color containerColor,
            Color cabColor,
            boolean hazardLampOn
    ) {
        final int roadContactY = 590;
        final int wheelRadius = 38;
        final int wheelY = roadContactY - (wheelRadius * 2);
        final int chassisY = 486;
        final int cabW = 196;
        final int gapW = 28;
        final int contX = cabNoseX + cabW + gapW;
        final int contW = 686;
        final int contY = 176;
        final int contH = 310;

        // Ground shadow beneath entire tractor-trailer
        g.setColor(new Color(15, 23, 42, 95));
        g.fillRoundRect(cabNoseX + 8, roadContactY - 14, cabW + gapW + contW - 12, 26, 18, 18);

        // Full-length steel gooseneck chassis I-beam & fifth-wheel coupling
        g.setColor(new Color(30, 36, 46));
        g.fillRect(cabNoseX + 18, chassisY, cabW + gapW + contW - 24, 28);
        g.setColor(new Color(71, 85, 105));
        g.fillRect(cabNoseX + cabW - 18, chassisY - 12, gapW + 30, 14); // Fifth wheel plate

        // Cylindrical aluminum diesel saddle tank on tractor frame
        g.setColor(new Color(148, 163, 184));
        g.fillRoundRect(cabNoseX + 92, chassisY - 4, 86, 38, 14, 14);
        g.setColor(new Color(71, 85, 105));
        g.fillRect(cabNoseX + 112, chassisY - 4, 6, 38);
        g.fillRect(cabNoseX + 152, chassisY - 4, 6, 38);

        // Sloped cabover / aerodynamic tractor cab (facing left, driving right -> left)
        Polygon cabPoly = new Polygon(
                new int[]{
                        cabNoseX + 4,
                        cabNoseX + 26,
                        cabNoseX + 92,
                        cabNoseX + cabW,
                        cabNoseX + cabW,
                        cabNoseX + 4
                },
                new int[]{
                        chassisY,
                        338,
                        236,
                        224,
                        chassisY,
                        chassisY
                },
                6);
        g.setColor(cabColor);
        g.fillPolygon(cabPoly);
        g.setColor(new Color(51, 65, 85));
        g.setStroke(new BasicStroke(2.5f));
        g.drawPolygon(cabPoly);

        // Windshield & side driver window
        Polygon glassPoly = new Polygon(
                new int[]{cabNoseX + 24, cabNoseX + 54, cabNoseX + 126, cabNoseX + 126, cabNoseX + 24},
                new int[]{356, 268, 268, 356, 356},
                5);
        g.setColor(new Color(30, 41, 59));
        g.fillPolygon(glassPoly);
        g.setColor(new Color(148, 163, 184, 110));
        g.drawLine(cabNoseX + 46, 348, cabNoseX + 76, 276); // Windshield reflection

        // Side mirror, door seam, chrome grille, headlight & front bumper license plate
        g.setColor(new Color(15, 23, 42));
        g.fillRoundRect(cabNoseX + 18, 294, 12, 46, 4, 4);
        g.setColor(new Color(100, 116, 139));
        g.drawLine(cabNoseX + 132, 266, cabNoseX + 132, chassisY - 8);
        g.fillRoundRect(cabNoseX + 104, 386, 20, 6, 3, 3); // Door handle

        // Front bumper + headlight + front plate (visible as tractor enters portal!)
        g.setColor(new Color(203, 213, 225));
        g.fillRoundRect(cabNoseX - 4, chassisY - 16, 38, 42, 6, 6);
        g.setColor(hazardLampOn ? new Color(245, 158, 11) : new Color(254, 249, 195));
        g.fillRoundRect(cabNoseX + 2, chassisY - 38, 22, 16, 4, 4);

        // Front reflective license plate placard on lower cab panel (unobstructed above steer wheel arch)
        drawLicensePlatePlacard(g, cabNoseX + 34, chassisY - 58, licensePlate);

        // 40-ft Corrugated Corten Intermodal Shipping Container
        g.setColor(containerColor);
        g.fillRoundRect(contX, contY, contW, contH, 8, 8);
        Color ribDark = containerColor.darker();
        Color ribLight = containerColor.brighter();
        for (int rx = contX + 22; rx < contX + contW - 36; rx += 26) {
            g.setColor(ribDark);
            g.fillRect(rx, contY + 12, 7, contH - 24);
            g.setColor(ribLight);
            g.fillRect(rx + 7, contY + 12, 3, contH - 24);
        }

        // ISO corner castings & top/bottom structural rails
        g.setColor(containerColor.darker().darker());
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(contX, contY, contW, contH, 8, 8);
        g.setColor(new Color(226, 232, 240));
        g.fillRect(contX, contY, 18, 14);
        g.fillRect(contX + contW - 18, contY, 18, 14);

        // High-contrast white horizontal ISO 6346 identification placard on container side
        // Sized & contrasted so PP-OCRv3 (det.onnx + rec.onnx) achieves >=0.98 confidence in motion
        int placardX = contX + 96;
        int placardY = contY + 58;
        int placardW = 476;
        int placardH = 104;
        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(placardX, placardY, placardW, placardH, 8, 8);
        g.setColor(new Color(15, 23, 42));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(placardX, placardY, placardW, placardH, 8, 8);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 58));
        g.drawString(containerId, placardX + 26, placardY + 73);

        // Rear container door locking bars & rear chassis license plate
        g.setColor(new Color(148, 163, 184));
        g.fillRect(contX + contW - 26, contY + 8, 5, contH - 16);
        g.fillRect(contX + contW - 14, contY + 8, 5, contH - 16);
        if (hazardLampOn) {
            g.setColor(new Color(245, 158, 11));
            g.fillOval(contX + contW - 12, chassisY + 6, 16, 16);
        }
        drawLicensePlatePlacard(g, contX + 278, chassisY + 4, licensePlate);

        // 5 Rotating Wheel & Tire Assemblies (steer axle, tandem drive axles, tandem trailer axles)
        double wheelAngleRad = -(frame * 0.42);
        int[] axleCentersX = {
                cabNoseX + 56,
                cabNoseX + 168,
                cabNoseX + 244,
                contX + contW - 188,
                contX + contW - 104
        };
        for (int cx : axleCentersX) {
            drawRotatingWheel(g, cx, wheelY + wheelRadius, wheelRadius, wheelAngleRad);
        }
    }

    private static void drawLicensePlatePlacard(Graphics2D g, int x, int y, String plateText) {
        int pw = 148;
        int ph = 44;
        g.setColor(new Color(248, 250, 252));
        g.fillRoundRect(x, y, pw, ph, 6, 6);
        g.setColor(new Color(15, 23, 42));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, pw, ph, 6, 6);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.drawString(plateText, x + 18, y + 32);
    }

    private static void drawRotatingWheel(Graphics2D g, int cx, int cy, int r, double angleRad) {
        // Rubber tire
        g.setColor(new Color(20, 24, 30));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        // Steel rim
        int rimR = (int) Math.round(r * 0.56);
        g.setColor(new Color(148, 163, 184));
        g.fillOval(cx - rimR, cy - rimR, rimR * 2, rimR * 2);
        // Rotating lug spokes so wheel motion is unmistakable in video & frame strips
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(angleRad);
        g.setColor(new Color(30, 41, 59));
        g.setStroke(new BasicStroke(3f));
        for (int i = 0; i < 5; i++) {
            double a = i * (Math.PI * 2.0 / 5.0);
            int lx = (int) Math.round(Math.cos(a) * (rimR - 3));
            int ly = (int) Math.round(Math.sin(a) * (rimR - 3));
            g.drawLine(0, 0, lx, ly);
        }
        g.fillOval(-6, -6, 12, 12);
        g.setTransform(old);
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
            startRtspPublisherThread("oscar-sandbox-rtsp-1", lane1Clips, getPrimaryRtspUrl());
            startRtspPublisherThread("oscar-sandbox-rtsp-2", lane2Clips, getSecondaryRtspUrl());
            this.rtspLive = true;
        }
        if (log != null) {
            log.info("Sandbox loopback RTSP server ready={} at {}", rtspLive, getPrimaryRtspUrl());
        }
    }

    private void startRtspPublisherThread(String threadName, LaneClipSet clips, String rtspUrl) {
        Thread t = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    streamLaneChannelUntilStopped(clips, rtspUrl);
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

    private void streamLaneChannelUntilStopped(LaneClipSet clips, String rtspUrl) throws Exception {
        AVFormatContext outCtx = new AVFormatContext(null);
        AVFormatContext firstIn = new AVFormatContext(null);
        AVPacket pkt = av_packet_alloc();
        try {
            if (avformat_open_input(firstIn, clips.transitMp4.toString(), null, null) < 0) {
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
            outStream.time_base(av_make_q(1, 90_000));
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

            // Compute exact 1/FPS step in the RTSP muxer's negotiated output time_base (e.g. 90kHz -> 6000 ticks @ 15fps).
            // Never rescale a second time from srcStream.time_base(), so RTP timestamps stay strictly 1.0x real-time!
            long cumulativeTicks = 0L;
            long frameStepTicks = Math.max(1L, av_rescale_q(1L, av_make_q(1, FPS), outStream.time_base()));
            long framePeriodNanos = 1_000_000_000L / FPS;
            long nextDeadlineNanos = System.nanoTime();
            clips.lastTransitCompletedMs.set(System.currentTimeMillis());

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int requested = clips.pendingMode.getAndSet(MODE_NONE);
                if (requested == MODE_NONE
                        && (System.currentTimeMillis() - clips.lastTransitCompletedMs.get()) >= AMBIENT_TRANSIT_INTERVAL_MS) {
                    requested = MODE_NORMAL_TRANSIT;
                }

                Path clipToPlay = switch (requested) {
                    case MODE_EXTENDED_TRANSIT -> clips.extendedMp4;
                    case MODE_NORMAL_TRANSIT -> clips.transitMp4;
                    default -> clips.idleMp4;
                };
                boolean interruptibleIdle = (requested == MODE_NONE);

                AVFormatContext loopIn = new AVFormatContext(null);
                try {
                    if (avformat_open_input(loopIn, clipToPlay.toString(), null, null) < 0) {
                        break;
                    }
                    if (avformat_find_stream_info(loopIn, (AVDictionary) null) < 0) {
                        break;
                    }
                    int framesSentInClip = 0;
                    while (running.get() && av_read_frame(loopIn, pkt) >= 0) {
                        if (pkt.stream_index() != videoStreamIdx) {
                            av_packet_unref(pkt);
                            continue;
                        }
                        // If idle and an occupancy transit was just triggered, cut cleanly at a GOP boundary
                        // so the truck enters the lane immediately (<333ms latency)!
                        if (interruptibleIdle
                                && framesSentInClip > 0
                                && (pkt.flags() & AV_PKT_FLAG_KEY) != 0
                                && clips.pendingMode.get() != MODE_NONE) {
                            av_packet_unref(pkt);
                            break;
                        }

                        pkt.pts(cumulativeTicks);
                        pkt.dts(cumulativeTicks);
                        pkt.duration(frameStepTicks);
                        pkt.pos(-1);
                        pkt.stream_index(outStream.index());
                        int writeRc = av_interleaved_write_frame(outCtx, pkt);
                        av_packet_unref(pkt);
                        if (writeRc < 0) {
                            return;
                        }

                        cumulativeTicks += frameStepTicks;
                        framesSentInClip++;
                        nextDeadlineNanos += framePeriodNanos;
                        long sleepNanos = nextDeadlineNanos - System.nanoTime();
                        if (sleepNanos > 1_000_000L) {
                            TimeUnit.NANOSECONDS.sleep(sleepNanos);
                        } else if (sleepNanos < -500_000_000L) {
                            nextDeadlineNanos = System.nanoTime();
                        }
                    }
                } finally {
                    if (!loopIn.isNull()) {
                        avformat_close_input(loopIn);
                    }
                }

                if (!interruptibleIdle) {
                    clips.lastTransitCompletedMs.set(System.currentTimeMillis());
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
