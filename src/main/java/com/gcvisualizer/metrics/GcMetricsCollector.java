package com.gcvisualizer.metrics;

import org.springframework.stereotype.Service;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

/**
 * GcMetricsCollector reads live GC data directly from the JVM via JMX.
 *
 * Key metrics we expose:
 * - Heap used / max
 * - GC pause count and cumulative time (per collector)
 * - Which GC is active (G1GC vs ZGC)
 * - Derived: average pause time, pause rate
 * - CPU usage (process + system) — shows ZGC's concurrent overhead
 * - Thread count — shows GC thread competition
 * - GC overhead % — time spent in GC vs total uptime
 *
 * This data is pushed to the frontend every second via SSE.
 */
@Service
public class GcMetricsCollector {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    // Cast to com.sun API for CPU metrics — available on all major JVMs
    private final com.sun.management.OperatingSystemMXBean osBean =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // Snapshot previous counts to compute deltas
    private long prevGcCount = 0;
    private long prevGcTimeMs = 0;

    // For GC overhead % calculation
    private long prevUptimeMs = 0;
    private long prevGcCpuTimeMs = 0;

    // ─── Public API ────────────────────────────────────────────────────────────

    public GcSnapshot snapshot() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();

        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        List<String> collectorNames = new ArrayList<>();

        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            if (count >= 0) totalGcCount += count;
            if (time  >= 0) totalGcTimeMs += time;
            collectorNames.add(gc.getName());
        }

        // Delta since last snapshot (for "pauses in last second" metric)
        long deltaCount  = totalGcCount  - prevGcCount;
        long deltaTimeMs = totalGcTimeMs - prevGcTimeMs;
        prevGcCount  = totalGcCount;
        prevGcTimeMs = totalGcTimeMs;

        double avgPauseMs = (totalGcCount > 0)
            ? (double) totalGcTimeMs / totalGcCount
            : 0.0;

        double recentAvgPauseMs = (deltaCount > 0)
            ? (double) deltaTimeMs / deltaCount
            : 0.0;

        // CPU metrics
        // processCpuLoad: 0.0–1.0 fraction of CPU used by this JVM process
        double processCpuPercent = Math.max(0, osBean.getProcessCpuLoad() * 100.0);

        // systemCpuLoad: overall machine CPU usage
        double systemCpuPercent = Math.max(0, osBean.getCpuLoad() * 100.0);

        // GC overhead: what % of elapsed time was spent doing GC in the last interval
        long uptimeMs = runtimeBean.getUptime();
        long deltaUptimeMs = uptimeMs - prevUptimeMs;
        long deltaGcCpuMs  = totalGcTimeMs - prevGcCpuTimeMs;
        double gcOverheadPercent = (deltaUptimeMs > 0)
            ? Math.min(100.0, (deltaGcCpuMs * 100.0) / deltaUptimeMs)
            : 0.0;
        prevUptimeMs    = uptimeMs;
        prevGcCpuTimeMs = totalGcTimeMs;

        // Thread count — ZGC spins up more concurrent GC threads
        int threadCount = threadBean.getThreadCount();

        return new GcSnapshot(
            heap.getUsed(),
            heap.getCommitted(),
            heap.getMax(),
            totalGcCount,
            totalGcTimeMs,
            deltaCount,
            deltaTimeMs,
            avgPauseMs,
            recentAvgPauseMs,
            collectorNames,
            detectGcType(collectorNames),
            uptimeMs,
            processCpuPercent,
            systemCpuPercent,
            gcOverheadPercent,
            threadCount
        );
    }

    public String getActiveGcName() {
        List<String> names = gcBeans.stream()
            .map(GarbageCollectorMXBean::getName)
            .toList();
        return detectGcType(names);
    }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private String detectGcType(List<String> names) {
        String joined = String.join(" ", names).toLowerCase();
        if (joined.contains("zgc"))   return "ZGC";
        if (joined.contains("g1"))    return "G1GC";
        if (joined.contains("shenandoah")) return "Shenandoah";
        if (joined.contains("parallel"))   return "Parallel GC";
        if (joined.contains("serial"))     return "Serial GC";
        return "Unknown GC";
    }

    // ─── Snapshot record ───────────────────────────────────────────────────────

    public record GcSnapshot(
        long   heapUsedBytes,
        long   heapCommittedBytes,
        long   heapMaxBytes,
        long   totalGcCount,
        long   totalGcTimeMs,
        long   deltaGcCount,
        long   deltaGcTimeMs,
        double avgPauseMs,
        double recentAvgPauseMs,
        List<String> collectorNames,
        String gcType,
        long   uptimeMs,
        double processCpuPercent,   // CPU used by this JVM process
        double systemCpuPercent,    // overall machine CPU
        double gcOverheadPercent,   // % of time spent in GC — key ZGC cost metric
        int    threadCount          // JVM thread count — rises with ZGC concurrent threads
    ) {
        public double heapUsedMb()      { return heapUsedBytes      / (1024.0 * 1024.0); }
        public double heapCommittedMb() { return heapCommittedBytes / (1024.0 * 1024.0); }
        public double heapMaxMb()       { return heapMaxBytes       / (1024.0 * 1024.0); }
        public double heapUsedPercent() {
            if (heapMaxBytes <= 0) return 0;
            return (heapUsedBytes * 100.0) / heapMaxBytes;
        }
    }
}
