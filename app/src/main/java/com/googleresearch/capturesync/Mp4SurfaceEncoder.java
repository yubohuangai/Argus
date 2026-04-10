/*
 * Copyright 2021 Mobile Robotics Lab. at Skoltech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.googleresearch.capturesync;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.googleresearch.capturesync.softwaresync.CSVLogger;
import com.googleresearch.capturesync.softwaresync.TimeDomainConverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H.264 encoder + MP4 muxer using a persistent input {@link Surface}. Writes one CSV line per muxed
 * video sample by looking up the full-precision leader-time timestamp from a
 * {@link ConcurrentMap} keyed by {@code presentationTimeUs}. The map is populated from the Camera2
 * capture callback; the encoder consumes entries here. Because the CSV row is written in the same
 * callback that muxes the frame, {@code csv_count == frame_count} is guaranteed by construction.
 */
public final class Mp4SurfaceEncoder {
    private static final String TAG = "Mp4SurfaceEncoder";
    private static final int STOP_TIMEOUT_SEC = 60;

    private final HandlerThread encoderThread;
    private final Handler encoderHandler;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted;
    private Runnable pendingStopDone;
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(true);

    public Mp4SurfaceEncoder() {
        encoderThread = new HandlerThread("Mp4SurfaceEncoder");
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());
    }

    public void startEncoding(
            Surface persistentInputSurface,
            String outputPath,
            int width,
            int height,
            int bitRate,
            int frameRate,
            CSVLogger csvLogger,
            ConcurrentMap<Long, Long> timestampLookup,
            TimeDomainConverter timeDomainConverter,
            long suspendOffsetNs)
            throws InterruptedException, IOException {
        CountDownLatch started = new CountDownLatch(1);
        IOException[] holder = new IOException[1];
        encoderHandler.post(
                () -> {
                    try {
                        internalStart(
                                persistentInputSurface,
                                outputPath,
                                width,
                                height,
                                bitRate,
                                frameRate,
                                csvLogger,
                                timestampLookup,
                                timeDomainConverter,
                                suspendOffsetNs);
                    } catch (IOException e) {
                        holder[0] = e;
                    } finally {
                        started.countDown();
                    }
                });
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Encoder start timed out");
        }
        if (holder[0] != null) {
            throw holder[0];
        }
    }

    private void internalStart(
            Surface persistentInputSurface,
            String outputPath,
            int width,
            int height,
            int bitRate,
            int frameRate,
            CSVLogger csvLogger,
            ConcurrentMap<Long, Long> timestampLookup,
            TimeDomainConverter timeDomainConverter,
            long suspendOffsetNs)
            throws IOException {
        resourcesReleased.set(false);
        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        MediaFormat format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(
                MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.setInputSurface(persistentInputSurface);

        codec.setCallback(
                new MediaCodec.Callback() {
                    @Override
                    public void onInputBufferAvailable(MediaCodec mc, int index) {}

                    @Override
                    public void onOutputBufferAvailable(
                            MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        try {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                return;
                            }
                            if (muxerStarted && videoTrackIndex >= 0 && info.size > 0) {
                                ByteBuffer encoded = mc.getOutputBuffer(index);
                                if (encoded != null) {
                                    encoded.position(info.offset);
                                    encoded.limit(info.offset + info.size);
                                    muxer.writeSampleData(videoTrackIndex, encoded, info);
                                }
                                if (csvLogger != null && !csvLogger.isClosed()) {
                                    // Try full-precision lookup first; fall back to
                                    // presentationTimeUs conversion (µs precision).
                                    Long leaderTsNs = timestampLookup.remove(
                                            info.presentationTimeUs);
                                    if (leaderTsNs == null) {
                                        // presentationTimeUs is MONOTONIC; converter expects
                                        // BOOTTIME. Add the suspend offset back.
                                        long boottimeNs =
                                                info.presentationTimeUs * 1000L + suspendOffsetNs;
                                        leaderTsNs = timeDomainConverter
                                                .leaderTimeForLocalTimeNs(boottimeNs);
                                        Log.d(TAG, "Lookup miss for ptsUs="
                                                + info.presentationTimeUs
                                                + "; map size=" + timestampLookup.size()
                                                + "; using converter fallback");
                                    }
                                    try {
                                        csvLogger.logLine(Long.toString(leaderTsNs));
                                    } catch (IOException e) {
                                        Log.e(TAG, "CSV log failed", e);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "onOutputBufferAvailable", e);
                        } finally {
                            try {
                                mc.releaseOutputBuffer(index, false);
                            } catch (Exception e) {
                                Log.e(TAG, "releaseOutputBuffer", e);
                            }
                        }
                        if (eos) {
                            releaseAllAndRunPending();
                        }
                    }

                    @Override
                    public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                        Log.e(TAG, "MediaCodec error", e);
                    }

                    @Override
                    public void onOutputFormatChanged(MediaCodec mc, MediaFormat newFormat) {
                        try {
                            if (muxerStarted) {
                                return;
                            }
                            videoTrackIndex = muxer.addTrack(newFormat);
                            muxer.start();
                            muxerStarted = true;
                        } catch (Exception e) {
                            Log.e(TAG, "muxer addTrack/start", e);
                        }
                    }
                },
                encoderHandler);

        codec.start();
        Log.i(TAG, "Encoder started " + width + "x" + height + " @" + frameRate + "fps");
    }

    private synchronized void releaseAllAndRunPending() {
        if (resourcesReleased.getAndSet(true)) {
            return;
        }
        try {
            if (muxerStarted && muxer != null) {
                muxer.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "muxer.stop", e);
        }
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "muxer.release", e);
        }
        muxer = null;
        muxerStarted = false;
        videoTrackIndex = -1;
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "codec stop/release", e);
        }
        codec = null;
        Runnable done = pendingStopDone;
        pendingStopDone = null;
        if (done != null) {
            done.run();
        }
    }

    public void stopAndRelease(Runnable whenFullyStopped) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Runnable wrap =
                () -> {
                    try {
                        whenFullyStopped.run();
                    } finally {
                        latch.countDown();
                    }
                };
        encoderHandler.post(
                () -> {
                    if (resourcesReleased.get() || codec == null) {
                        wrap.run();
                        return;
                    }
                    pendingStopDone = wrap;
                    try {
                        codec.signalEndOfInputStream();
                    } catch (Exception e) {
                        Log.e(TAG, "signalEndOfInputStream", e);
                        pendingStopDone = null;
                        releaseAllAndRunPending();
                        wrap.run();
                    }
                });
        if (!latch.await(STOP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.e(TAG, "stopAndRelease timed out");
        }
    }

    public void releaseQuietly() {
        encoderHandler.post(
                () -> {
                    pendingStopDone = null;
                    if (!resourcesReleased.get()) {
                        releaseAllAndRunPending();
                    }
                });
    }

    public void quitSafely() {
        releaseQuietly();
        encoderThread.quitSafely();
    }
}
