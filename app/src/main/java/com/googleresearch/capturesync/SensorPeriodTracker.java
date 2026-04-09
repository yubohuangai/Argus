/*
 * Copyright 2026 RecSync-Mod contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googleresearch.capturesync;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks {@code CaptureResult.SENSOR_FRAME_DURATION} values across the first N frames of a
 * session and produces a single stable canonical period.
 *
 * <p>Why this exists: {@code PeriodCalculator} derives the frame period from synchronized
 * capture timestamps and is noise-dominated — the same device can measure 33,336,589 ns on
 * one run and 33,331,423 ns on the next. Camera2 already reports the sensor's own frame
 * duration per frame via {@code SENSOR_FRAME_DURATION}, which is effectively noise-free on
 * Pixel 7 class hardware. This class collects those values, picks the mode (with median
 * fallback), and exposes the result exactly once.
 */
public class SensorPeriodTracker {

    /** Number of non-null samples required before the tracker is considered stable. */
    private static final int REQUIRED_SAMPLES = 60; // ~2 seconds at 30 fps.

    private final long[] samples = new long[REQUIRED_SAMPLES];
    private int count = 0;
    private boolean finalized = false;
    private long stablePeriodNs = 0;

    /** Feed one frame's SENSOR_FRAME_DURATION value. Null values are ignored. */
    public synchronized void onFrameDuration(Long frameDurationNs) {
        if (finalized || frameDurationNs == null || frameDurationNs <= 0) {
            return;
        }
        samples[count++] = frameDurationNs;
        if (count >= REQUIRED_SAMPLES) {
            stablePeriodNs = computeModeOrMedian();
            finalized = true;
        }
    }

    public synchronized boolean isStable() {
        return finalized;
    }

    /** Returns the stable period in nanoseconds, or 0 if not yet stable. */
    public synchronized long getStablePeriodNs() {
        return stablePeriodNs;
    }

    /**
     * Returns the mode of the collected samples. If no value has a strict majority of
     * occurrences, falls back to the median.
     */
    private long computeModeOrMedian() {
        Map<Long, Integer> histogram = new HashMap<>();
        long bestValue = samples[0];
        int bestCount = 0;
        boolean ambiguous = false;
        for (int i = 0; i < count; i++) {
            int c = histogram.getOrDefault(samples[i], 0) + 1;
            histogram.put(samples[i], c);
            if (c > bestCount) {
                bestCount = c;
                bestValue = samples[i];
                ambiguous = false;
            } else if (c == bestCount && samples[i] != bestValue) {
                ambiguous = true;
            }
        }
        if (!ambiguous && bestCount * 2 >= count) {
            return bestValue;
        }
        // Fallback: median.
        Long[] sorted = new Long[count];
        for (int i = 0; i < count; i++) {
            sorted[i] = samples[i];
        }
        java.util.Arrays.sort(sorted);
        return sorted[count / 2];
    }
}
