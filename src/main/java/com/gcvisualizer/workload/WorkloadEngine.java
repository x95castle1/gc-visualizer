package com.gcvisualizer.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WorkloadEngine simulates realistic heap pressure to make GC behaviour visible.
 *
 * Three scenarios:
 *  - LOW   : small allocations, gentle churn  → G1GC and ZGC look similar
 *  - MEDIUM: mixed short/long-lived objects   → differences start showing
 *  - HIGH  : heavy allocation + retained data → dramatic pause difference
 *
 * We also track latency ourselves — every allocation task measures how long
 * it was delayed vs when it was scheduled. GC pauses show up as latency spikes
 * without needing any external tool.
 */
@Service
public class WorkloadEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkloadEngine.class);

    // Long-lived objects that survive GC cycles (simulate cache / session data)
    private final List<byte[]> longLivedObjects = new ArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong taskCounter = new AtomicLong(0);
    private final AtomicLong totalLatencyNs = new AtomicLong(0);
    private final AtomicLong latencySamples = new AtomicLong(0);

    // Tracks last recorded pause for SSE push
    private volatile double lastPauseMs = 0;
    private volatile long lastAllocatedBytes = 0;

    private ScheduledFuture<?> workloadTask;
    private volatile WorkloadProfile currentProfile = WorkloadProfile.MEDIUM;

    private final Random random = new Random();

    public enum WorkloadProfile {
        LOW, MEDIUM, HIGH
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    public void start(WorkloadProfile profile) {
        if (running.compareAndSet(false, true)) {
            this.currentProfile = profile;
            longLivedObjects.clear();
            taskCounter.set(0);
            totalLatencyNs.set(0);
            latencySamples.set(0);

            int intervalMs = switch (profile) {
                case LOW    -> 100;
                case MEDIUM -> 50;
                case HIGH   -> 10;
            };

            workloadTask = scheduler.scheduleAtFixedRate(
                this::runAllocationCycle,
                0, intervalMs, TimeUnit.MILLISECONDS
            );
            log.info("Workload started with profile: {}", profile);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (workloadTask != null) {
                workloadTask.cancel(false);
            }
            longLivedObjects.clear();
            log.info("Workload stopped");
        }
    }

    public void triggerSpike() {
        // Allocate a burst of large objects synchronously — guaranteed pause trigger
        log.info("Spike triggered — allocating large object graph");
        List<byte[]> spike = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            spike.add(new byte[1024 * 1024]); // 50 x 1MB = 50MB burst
        }
        // Hold briefly so GC has to work to collect them
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        spike.clear();
        log.info("Spike released");
    }

    public boolean isRunning() { return running.get(); }

    public WorkloadProfile getCurrentProfile() { return currentProfile; }

    public double getLastPauseMs() { return lastPauseMs; }

    public long getLastAllocatedBytes() { return lastAllocatedBytes; }

    public double getAverageLatencyMs() {
        long samples = latencySamples.get();
        if (samples == 0) return 0;
        return (totalLatencyNs.get() / (double) samples) / 1_000_000.0;
    }

    public long getTaskCount() { return taskCounter.get(); }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private void runAllocationCycle() {
        long scheduledAt = System.nanoTime();

        try {
            switch (currentProfile) {
                case LOW    -> runLowPressure();
                case MEDIUM -> runMediumPressure();
                case HIGH   -> runHighPressure();
            }
        } catch (Exception e) {
            log.warn("Allocation cycle error: {}", e.getMessage());
        }

        // Measure scheduling latency — this is how GC pauses show up
        long elapsed = System.nanoTime() - scheduledAt;
        lastPauseMs = elapsed / 1_000_000.0;
        totalLatencyNs.addAndGet(elapsed);
        latencySamples.incrementAndGet();
        taskCounter.incrementAndGet();
    }

    /**
     * LOW: Small short-lived objects only.
     * G1GC and ZGC behave similarly — minor GC handles this well.
     */
    private void runLowPressure() {
        List<byte[]> shortLived = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shortLived.add(new byte[10 * 1024]); // 10KB each = 100KB total
        }
        lastAllocatedBytes = 100 * 1024L;
        // shortLived goes out of scope — collected in next minor GC
    }

    /**
     * MEDIUM: Mix of short-lived + some objects promoted to old gen.
     * Differences start to appear — G1GC mixed GC kicks in.
     */
    private void runMediumPressure() {
        // Short-lived
        List<byte[]> shortLived = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shortLived.add(new byte[50 * 1024]); // 50KB each
        }

        // Some survive (simulate user sessions, caches)
        if (longLivedObjects.size() < 100) {
            longLivedObjects.add(new byte[100 * 1024]); // 100KB retained
        } else {
            // Evict oldest, add new — simulates cache churn
            longLivedObjects.remove(0);
            longLivedObjects.add(new byte[100 * 1024]);
        }

        lastAllocatedBytes = (20L * 50 * 1024) + (100 * 1024);
    }

    /**
     * HIGH: Heavy allocation + large retained set.
     * G1GC pauses become very visible. ZGC stays consistent.
     * This is the "money shot" for the article.
     */
    private void runHighPressure() {
        // Large short-lived allocations
        List<byte[]> shortLived = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shortLived.add(new byte[500 * 1024]); // 500KB each = 5MB burst
        }

        // Grow long-lived set aggressively (fills old gen in G1GC → triggers Full GC)
        if (longLivedObjects.size() < 200) {
            longLivedObjects.add(new byte[500 * 1024]); // 500KB retained
        } else {
            int evictCount = random.nextInt(10) + 1;
            for (int i = 0; i < evictCount && !longLivedObjects.isEmpty(); i++) {
                longLivedObjects.remove(0);
            }
            longLivedObjects.add(new byte[500 * 1024]);
        }

        lastAllocatedBytes = (10L * 500 * 1024) + (500 * 1024);
    }
}
