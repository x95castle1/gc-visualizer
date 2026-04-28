package com.gcvisualizer.metrics;

import org.springframework.stereotype.Service;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class GcMetricsCollector {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<MemoryPoolMXBean> poolBeans = ManagementFactory.getMemoryPoolMXBeans();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private final com.sun.management.OperatingSystemMXBean osBean =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private long prevGcCount = 0;
    private long prevGcTimeMs = 0;
    private long prevUptimeMs = 0;
    private long prevGcCpuTimeMs = 0;

    public GcSnapshot snapshot() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();

        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        List<String> collectorNames = new ArrayList<>();

        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            collectorNames.add(gc.getName());

            String nameLower = gc.getName().toLowerCase();
            // Skip concurrent cycle time to keep pause metrics accurate
            boolean isZgcConcurrent = nameLower.contains("zgc") && nameLower.contains("cycle");
            if (isZgcConcurrent) continue;

            if (count >= 0) totalGcCount += count;
            if (time  >= 0) totalGcTimeMs += time;
        }

        long deltaCount  = totalGcCount  - prevGcCount;
        long deltaTimeMs = totalGcTimeMs - prevGcTimeMs;
        prevGcCount  = totalGcCount;
        prevGcTimeMs = totalGcTimeMs;

        double avgPauseMs = (totalGcCount > 0) ? (double) totalGcTimeMs / totalGcCount : 0.0;
        double recentAvgPauseMs = (deltaCount > 0) ? (double) deltaTimeMs / deltaCount : 0.0;

        double processCpuPercent = Math.max(0, osBean.getProcessCpuLoad() * 100.0);
        double systemCpuPercent = Math.max(0, osBean.getCpuLoad() * 100.0);

        long uptimeMs = runtimeBean.getUptime();
        long deltaUptimeMs = uptimeMs - prevUptimeMs;
        long deltaGcCpuMs  = totalGcTimeMs - prevGcCpuTimeMs;
        double gcOverheadPercent = (deltaUptimeMs > 0)
                ? Math.min(100.0, (deltaGcCpuMs * 100.0) / deltaUptimeMs)
                : 0.0;
        prevUptimeMs    = uptimeMs;
        prevGcCpuTimeMs = totalGcTimeMs;

        int threadCount = threadBean.getThreadCount();

        // Get pool names for detection
        List<String> poolNames = poolBeans.stream().map(MemoryPoolMXBean::getName).toList();

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
                detectGcType(collectorNames, poolNames), // Pass pools here
                uptimeMs,
                processCpuPercent,
                systemCpuPercent,
                gcOverheadPercent,
                threadCount
        );
    }

    public String getActiveGcName() {
        List<String> names = gcBeans.stream().map(GarbageCollectorMXBean::getName).toList();
        List<String> pools = poolBeans.stream().map(MemoryPoolMXBean::getName).toList();
        return detectGcType(names, pools);
    }

    private String detectGcType(List<String> collectorNames, List<String> poolNames) {
        String collectors = String.join(" ", collectorNames).toLowerCase();
        String pools = String.join(" ", poolNames).toLowerCase();

        if (collectors.contains("zgc")) {
            // Generational ZGC creates specific "Young" and "Old" pools.
            // Non-generational ZGC uses a single "ZGC Heap" pool.
            if (pools.contains("young") || pools.contains("old")) {
                return "ZGC (Generational)";
            }
            return "ZGC (Non-Generational)";
        }

        if (collectors.contains("g1"))    return "G1GC";
        if (collectors.contains("shenandoah")) return "Shenandoah";
        if (collectors.contains("parallel"))   return "Parallel GC";
        if (collectors.contains("serial"))     return "Serial GC";
        return "Unknown GC";
    }

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
            double processCpuPercent,
            double systemCpuPercent,
            double gcOverheadPercent,
            int    threadCount
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