/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.process.ocr.pipeline;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Adaptive CPU and JVM heap resource governor ensuring background OCR runs strictly
 * <em>as system resources allow</em> without starving live RPM telemetry, HLS streaming,
 * PostgreSQL/Parquet archiving, or Viewer HTTP/MQTT traffic.
 */
public class OcrResourceGovernor {

    public enum Decision {
        /** Sufficient CPU and heap headroom: run at normal configured sampling rate. */
        PROCEED,
        /** System CPU or heap is transiently elevated: defer non-blockingly on the timer wheel. */
        DEFER,
        /** Heap is critically tight even after deferral window: shed job safely to prevent OOM. */
        SHED_HEAP_PRESSURE
    }

    public interface TelemetrySource {
        /** System-wide CPU load in {@code [0.0, 1.0]}, or {@code < 0} when unavailable. */
        double systemCpuLoad();
        /** JVM process CPU load in {@code [0.0, 1.0]}, or {@code < 0} when unavailable. */
        double processCpuLoad();
        /** Fraction of {@code Runtime.maxMemory()} currently occupied in {@code [0.0, 1.0]}. */
        double heapUsedRatio();
        /** Estimated allocatable free heap bytes ({@code maxMemory - (totalMemory - freeMemory)}). */
        long freeAllocatableBytes();
    }

    public static final double DEFAULT_MAX_SYSTEM_CPU = 0.85;
    public static final double DEFAULT_MAX_PROCESS_CPU = 0.80;
    public static final double DEFAULT_MAX_HEAP_USED_RATIO = 0.85;
    public static final long DEFAULT_MIN_FREE_HEAP_BYTES = 96L * 1024L * 1024L;
    public static final long CRITICAL_MIN_FREE_HEAP_BYTES = 48L * 1024L * 1024L;

    private final TelemetrySource telemetry;
    private final double maxSystemCpu;
    private final double maxProcessCpu;
    private final double maxHeapUsedRatio;
    private final long minFreeHeapBytes;

    public OcrResourceGovernor() {
        this(new JvmTelemetrySource(),
                DEFAULT_MAX_SYSTEM_CPU,
                DEFAULT_MAX_PROCESS_CPU,
                DEFAULT_MAX_HEAP_USED_RATIO,
                DEFAULT_MIN_FREE_HEAP_BYTES);
    }

    public OcrResourceGovernor(TelemetrySource telemetry,
                               double maxSystemCpu,
                               double maxProcessCpu,
                               double maxHeapUsedRatio,
                               long minFreeHeapBytes) {
        this.telemetry = telemetry;
        this.maxSystemCpu = maxSystemCpu;
        this.maxProcessCpu = maxProcessCpu;
        this.maxHeapUsedRatio = maxHeapUsedRatio;
        this.minFreeHeapBytes = minFreeHeapBytes;
    }

    /**
     * Evaluates whether an OCR job with {@code deferCount} prior resource deferrals should run now,
     * be deferred onto the non-blocking timer wheel, or be shed due to critical heap pressure.
     */
    public Decision evaluateBeforeJob(int deferCount, int maxDeferrals) {
        long freeBytes = telemetry.freeAllocatableBytes();
        double heapRatio = telemetry.heapUsedRatio();
        boolean heapTight = freeBytes < minFreeHeapBytes || heapRatio >= maxHeapUsedRatio;
        boolean heapCritical = freeBytes < CRITICAL_MIN_FREE_HEAP_BYTES || heapRatio >= 0.92;

        double sysCpu = telemetry.systemCpuLoad();
        double procCpu = telemetry.processCpuLoad();
        boolean cpuBusy = (sysCpu >= 0.0 && sysCpu >= maxSystemCpu)
                || (procCpu >= 0.0 && procCpu >= maxProcessCpu);

        if (!heapTight && !cpuBusy) {
            return Decision.PROCEED;
        }

        if (deferCount < maxDeferrals) {
            return Decision.DEFER;
        }

        if (heapCritical) {
            return Decision.SHED_HEAP_PRESSURE;
        }

        // After exhausting bounded CPU deferrals with safe heap headroom, proceed in eco mode
        return Decision.PROCEED;
    }

    /** Returns {@code true} when CPU or heap is currently under elevated load. */
    public boolean isElevatedLoad() {
        long freeBytes = telemetry.freeAllocatableBytes();
        double heapRatio = telemetry.heapUsedRatio();
        if (freeBytes < minFreeHeapBytes || heapRatio >= maxHeapUsedRatio)
            return true;
        double sysCpu = telemetry.systemCpuLoad();
        double procCpu = telemetry.processCpuLoad();
        return (sysCpu >= 0.0 && sysCpu >= maxSystemCpu)
                || (procCpu >= 0.0 && procCpu >= maxProcessCpu);
    }

    /** Returns {@code true} when JVM heap is critically low and further frame decoding must stop. */
    public boolean isCriticalHeapPressure() {
        return telemetry.freeAllocatableBytes() < CRITICAL_MIN_FREE_HEAP_BYTES
                || telemetry.heapUsedRatio() >= 0.92;
    }

    private static final class JvmTelemetrySource implements TelemetrySource {
        private final com.sun.management.OperatingSystemMXBean sunOsBean;

        JvmTelemetrySource() {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            this.sunOsBean = (bean instanceof com.sun.management.OperatingSystemMXBean s) ? s : null;
        }

        @Override
        public double systemCpuLoad() {
            if (sunOsBean == null)
                return -1.0;
            try {
                return sunOsBean.getCpuLoad();
            } catch (Throwable t) {
                return -1.0;
            }
        }

        @Override
        public double processCpuLoad() {
            if (sunOsBean == null)
                return -1.0;
            try {
                return sunOsBean.getProcessCpuLoad();
            } catch (Throwable t) {
                return -1.0;
            }
        }

        @Override
        public double heapUsedRatio() {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            if (max <= 0 || max == Long.MAX_VALUE)
                return 0.0;
            long used = Math.max(0L, rt.totalMemory() - rt.freeMemory());
            return used / (double) max;
        }

        @Override
        public long freeAllocatableBytes() {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            if (max <= 0 || max == Long.MAX_VALUE)
                return Long.MAX_VALUE;
            long used = Math.max(0L, rt.totalMemory() - rt.freeMemory());
            return Math.max(0L, max - used);
        }
    }
}
