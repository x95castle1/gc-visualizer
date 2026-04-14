package com.gcvisualizer.controller;

import com.gcvisualizer.workload.WorkloadEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * StressController exposes endpoints to control the workload engine.
 *
 * POST /api/stress/start?profile=LOW|MEDIUM|HIGH  → start allocation workload
 * POST /api/stress/stop                            → stop workload, clear retained objects
 * POST /api/stress/spike                           → instant 50MB burst (triggers GC immediately)
 * GET  /api/stress/status                          → current state
 */
@RestController
@RequestMapping("/api/stress")
@CrossOrigin(origins = "*")
public class StressController {

    private final WorkloadEngine workloadEngine;

    public StressController(WorkloadEngine workloadEngine) {
        this.workloadEngine = workloadEngine;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(defaultValue = "MEDIUM") String profile) {
        try {
            WorkloadEngine.WorkloadProfile p = WorkloadEngine.WorkloadProfile.valueOf(profile.toUpperCase());
            workloadEngine.start(p);
            return ResponseEntity.ok(Map.of(
                "status",  "started",
                "profile", p.name(),
                "message", describeProfile(p)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid profile. Use LOW, MEDIUM, or HIGH"
            ));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        workloadEngine.stop();
        return ResponseEntity.ok(Map.of(
            "status",  "stopped",
            "message", "Workload stopped. Long-lived objects released."
        ));
    }

    @PostMapping("/spike")
    public ResponseEntity<Map<String, Object>> spike() {
        new Thread(workloadEngine::triggerSpike).start(); // async so HTTP returns immediately
        return ResponseEntity.ok(Map.of(
            "status",  "spike_triggered",
            "message", "Allocating 50MB burst — watch the pause spike on the dashboard"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "running", workloadEngine.isRunning(),
            "profile", workloadEngine.getCurrentProfile().name(),
            "taskCount", workloadEngine.getTaskCount(),
            "avgLatencyMs", workloadEngine.getAverageLatencyMs()
        ));
    }

    private String describeProfile(WorkloadEngine.WorkloadProfile p) {
        return switch (p) {
            case LOW    -> "Light load: small short-lived objects. GC difference minimal.";
            case MEDIUM -> "Medium load: mixed object lifetimes. Differences start appearing.";
            case HIGH   -> "Heavy load: large retained set. Dramatic GC pause differences!";
        };
    }
}
