/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.pipeline;

import com.botts.impl.process.ocr.domain.CandidateAggregator;
import com.botts.impl.process.ocr.engine.OcrEngine;
import com.botts.impl.process.ocr.engine.OcrRead;
import com.botts.impl.process.ocr.engine.OnnxOcrEngine;
import com.botts.impl.process.ocr.engine.OnnxOcrEngineTest;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.PointerPointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameSamplerTest {

    @Test
    void samplesFramesDownscalesAbove1080pAndRecognizesContainerEndToEnd(@TempDir Path tempDir) throws Exception {
        Path mp4 = tempDir.resolve("container-qhd.mp4");
        BufferedImage qhdPlacard = OnnxOcrEngineTest.renderContainerFrame(2560, 1440, "CSQU 305438 3");
        writeSyntheticMp4(mp4, qhdPlacard, 15, 10);

        FrameSampler sampler = new FrameSampler();
        List<FrameSampler.SampledFrame> sampled = sampler.sampleFrames(mp4, 4);

        assertEquals(4, sampled.size());
        for (FrameSampler.SampledFrame sf : sampled) {
            assertNotNull(sf.image());
            assertEquals(1920, sf.image().getWidth(), "Expected automatic downscale from 2560x1440 to 1920x1080");
            assertEquals(1080, sf.image().getHeight());
        }

        try (OcrEngine engine = OnnxOcrEngine.acquireShared(OnnxOcrEngineTest.locateBundledModelDir())) {
            List<CandidateAggregator.FrameInput> inputs = new ArrayList<>();
            for (int i = 0; i < sampled.size(); i++) {
                BufferedImage img = sampled.get(i).image();
                List<OcrRead> reads = engine.recognize(img);
                inputs.add(new CandidateAggregator.FrameInput(i, img.getWidth(), img.getHeight(), reads));
            }
            var aggregated = new CandidateAggregator(0.50).aggregate(inputs);
            assertFalse(aggregated.isEmpty());
            assertEquals("CSQU3054383", aggregated.get(0).normalizedValue());
            assertTrue(aggregated.get(0).checksumValid());
        }
    }

    @Test
    void returnsEmptyListWithoutThrowingOnMissingTinyOrCorruptMp4(@TempDir Path tempDir) throws Exception {
        FrameSampler sampler = new FrameSampler();

        assertTrue(sampler.sampleFrames(tempDir.resolve("does-not-exist.mp4"), 4).isEmpty());

        Path tiny = tempDir.resolve("tiny.mp4");
        Files.writeString(tiny, "partial", StandardCharsets.UTF_8);
        assertTrue(sampler.sampleFrames(tiny, 4).isEmpty());

        Path corrupt = tempDir.resolve("corrupt.mp4");
        byte[] garbage = new byte[4096];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) ((i * 37) ^ 0x5A);
        }
        Files.write(corrupt, garbage);
        assertTrue(sampler.sampleFrames(corrupt, 4).isEmpty());
    }

    static void writeSyntheticMp4(Path dest, BufferedImage bgrImage, int frameCount, int fps) throws IOException {
        int width = bgrImage.getWidth();
        int height = bgrImage.getHeight();
        byte[] srcPixels = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();

        AVFormatContext fmt = new AVFormatContext(null);
        AVCodecContext encCtx = null;
        AVFrame yuvFrame = null;
        AVFrame bgrFrame = null;
        AVPacket pkt = null;
        SwsContext sws = null;
        boolean ioOpened = false;

        try {
            if (avformat_alloc_output_context2(fmt, null, "mp4", dest.toString()) < 0 || fmt.isNull())
                throw new IOException("avformat_alloc_output_context2 failed");

            AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
            if (codec == null || codec.isNull())
                codec = avcodec_find_encoder(AV_CODEC_ID_H264);
            if (codec == null || codec.isNull())
                throw new IOException("No MPEG4/H264 encoder available");

            AVStream stream = avformat_new_stream(fmt, null);
            encCtx = avcodec_alloc_context3(codec);
            encCtx.codec_id(codec.id());
            encCtx.codec_type(AVMEDIA_TYPE_VIDEO);
            encCtx.width(width);
            encCtx.height(height);
            encCtx.pix_fmt(AV_PIX_FMT_YUV420P);
            encCtx.bit_rate(4_000_000L);
            AVRational tb = new AVRational();
            tb.num(1);
            tb.den(fps);
            encCtx.time_base(tb);
            stream.time_base(tb);
            if ((fmt.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
                encCtx.flags(encCtx.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
            }

            if (avcodec_open2(encCtx, codec, (PointerPointer<?>) null) < 0)
                throw new IOException("avcodec_open2 encoder failed");
            if (avcodec_parameters_from_context(stream.codecpar(), encCtx) < 0)
                throw new IOException("avcodec_parameters_from_context failed");

            AVIOContext pb = new AVIOContext(null);
            if (avio_open(pb, dest.toString(), AVIO_FLAG_WRITE) < 0)
                throw new IOException("avio_open failed");
            fmt.pb(pb);
            ioOpened = true;

            if (avformat_write_header(fmt, (PointerPointer<?>) null) < 0)
                throw new IOException("avformat_write_header failed");

            bgrFrame = av_frame_alloc();
            bgrFrame.format(AV_PIX_FMT_BGR24);
            bgrFrame.width(width);
            bgrFrame.height(height);
            av_frame_get_buffer(bgrFrame, 32);
            int rowBytes = width * 3;
            int stride = bgrFrame.linesize(0);
            for (int r = 0; r < height; r++) {
                bgrFrame.data(0).position((long) r * stride).put(srcPixels, r * rowBytes, rowBytes);
            }
            bgrFrame.data(0).position(0);

            yuvFrame = av_frame_alloc();
            yuvFrame.format(AV_PIX_FMT_YUV420P);
            yuvFrame.width(width);
            yuvFrame.height(height);
            av_frame_get_buffer(yuvFrame, 32);

            sws = sws_getContext(width, height, AV_PIX_FMT_BGR24,
                    width, height, AV_PIX_FMT_YUV420P,
                    SWS_BILINEAR, null, null, (double[]) null);
            sws_scale(sws, bgrFrame.data(), bgrFrame.linesize(), 0, height, yuvFrame.data(), yuvFrame.linesize());

            pkt = av_packet_alloc();
            for (int i = 0; i < frameCount; i++) {
                av_frame_make_writable(yuvFrame);
                yuvFrame.pts(i);
                if (avcodec_send_frame(encCtx, yuvFrame) >= 0) {
                    drainPackets(fmt, encCtx, stream, pkt);
                }
            }
            avcodec_send_frame(encCtx, null);
            drainPackets(fmt, encCtx, stream, pkt);
            av_write_trailer(fmt);
        } finally {
            if (pkt != null && !pkt.isNull())
                av_packet_free(pkt);
            if (sws != null && !sws.isNull())
                sws_freeContext(sws);
            if (yuvFrame != null && !yuvFrame.isNull())
                av_frame_free(yuvFrame);
            if (bgrFrame != null && !bgrFrame.isNull())
                av_frame_free(bgrFrame);
            if (encCtx != null && !encCtx.isNull())
                avcodec_free_context(encCtx);
            if (ioOpened && fmt.pb() != null && !fmt.pb().isNull())
                avio_closep(fmt.pb());
            if (!fmt.isNull())
                avformat_free_context(fmt);
        }
    }

    private static void drainPackets(AVFormatContext fmt, AVCodecContext encCtx, AVStream stream, AVPacket pkt) {
        while (avcodec_receive_packet(encCtx, pkt) >= 0) {
            try {
                av_packet_rescale_ts(pkt, encCtx.time_base(), stream.time_base());
                pkt.stream_index(stream.index());
                av_interleaved_write_frame(fmt, pkt);
            } finally {
                av_packet_unref(pkt);
            }
        }
    }
}
