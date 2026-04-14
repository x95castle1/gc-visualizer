package com.gcvisualizer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcvisualizer.metrics.GcMetricsCollector;
import com.gcvisualizer.workload.WorkloadEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MetricsStreamController pushes GC + workload metrics to all connected browsers
 * every second using Server-Sent Events (SSE).
 *
 * SSE is simpler than WebSocket for one-directional push — perfect here.
 * The browser just does: new EventSource('/api/metrics/stream')
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsStreamController {

    private static final Logger log = LoggerFactory.getLogger(MetricsStreamController.class);

    private final GcMetricsCollector metricsCollector;
    private final WorkloadEngine workloadEngine;
    private final ObjectMapper objectMapper;

    // Thread-safe list of active SSE connections
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // For computing tasks/second throughput delta
    private long lastTaskCount    = 0;
    private long lastSnapshotTime = 0;

    public MetricsStreamController(GcMetricsCollector metricsCollector,
                                   WorkloadEngine workloadEngine,
                                   ObjectMapper objectMapper) {
        this.metricsCollector = metricsCollector;
        this.workloadEngine   = workloadEngine;
        this.objectMapper     = objectMapper;
    }

    /**
     * Browser connects here once and receives a continuous stream of JSON events.
     */
    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // never timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e  ->  emitters.remove(emitter));

        log.info("New SSE client connected. Total: {}", emitters.size());
        return emitter;
    }

    /**
     * Snapshot endpoint — single pull (for initial page load)
     */
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        return buildPayload();
    }

    /**
     * Scheduled push — fires every second, broadcasts to all connected clients.
     */
    @Scheduled(fixedRate = 1000)
    public void pushMetrics() {
        if (emitters.isEmpty()) return;

        Map<String, Object> payload = buildPayload();
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize metrics", e);
            return;
        }

        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildPayload() {
        GcMetricsCollector.GcSnapshot snap = metricsCollector.snapshot();

        // Tasks per second — key throughput metric showing G1GC advantage
        long taskCount = workloadEngine.getTaskCount();
        long now = System.currentTimeMillis();
        double tasksPerSecond = 0;
        if (lastTaskCount > 0 && lastSnapshotTime > 0) {
            long deltaMs    = now - lastSnapshotTime;
            long deltaTasks = taskCount - lastTaskCount;
            tasksPerSecond  = deltaMs > 0 ? (deltaTasks * 1000.0) / deltaMs : 0;
        }
        lastTaskCount    = taskCount;
        lastSnapshotTime = now;

        return Map.ofEntries(
            Map.entry("timestamp",          System.currentTimeMillis()),
            Map.entry("gcType",             snap.gcType()),
            Map.entry("collectorNames",     snap.collectorNames()),

            // Heap
            Map.entry("heapUsedMb",         round(snap.heapUsedMb())),
            Map.entry("heapCommittedMb",    round(snap.heapCommittedMb())),
            Map.entry("heapMaxMb",          round(snap.heapMaxMb())),
            Map.entry("heapUsedPercent",    round(snap.heapUsedPercent())),

            // GC pauses
            Map.entry("totalGcCount",       snap.totalGcCount()),
            Map.entry("totalGcTimeMs",      snap.totalGcTimeMs()),
            Map.entry("avgPauseMs",         round(snap.avgPauseMs())),
            Map.entry("recentAvgPauseMs",   round(snap.recentAvgPauseMs())),
            Map.entry("deltaGcCount",       snap.deltaGcCount()),

            // CPU — shows ZGC's concurrent overhead cost
            Map.entry("processCpuPercent",  round(snap.processCpuPercent())),
            Map.entry("systemCpuPercent",   round(snap.systemCpuPercent())),
            Map.entry("gcOverheadPercent",  round(snap.gcOverheadPercent())),
            Map.entry("threadCount",        snap.threadCount()),

            // Workload
            Map.entry("workloadRunning",    workloadEngine.isRunning()),
            Map.entry("workloadProfile",    workloadEngine.getCurrentProfile().name()),
            Map.entry("taskLatencyMs",      round(workloadEngine.getLastPauseMs())),
            Map.entry("avgTaskLatencyMs",   round(workloadEngine.getAverageLatencyMs())),
            Map.entry("taskCount",          taskCount),

            // Throughput — shows G1GC advantage on CPU-constrained workloads
            Map.entry("tasksPerSecond",     round(tasksPerSecond)),

            // Uptime
            Map.entry("uptimeMs",           snap.uptimeMs())
        );
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
