import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const APPS = [
  { name: "G1GC", url: "http://localhost:8081" },
  { name: "ZGC_Gen", url: "http://localhost:8082" },
  { name: "ZGC_NonGen", url: "http://localhost:8083" },
];

const spikesTriggered = new Counter("spikes_triggered");

export const options = {
  scenarios: {
    // Steady request load across all 3 apps
    steady_load: {
      executor: "constant-vus",
      vus: 6,
      duration: "1m",
      exec: "steadyLoad",
    },
    // Periodic spike bursts — same timing on all 3
    spike_bursts: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      startTime: "0s",
      exec: "spikeBursts",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.10"],
  },
};

// Start HIGH workload on all 3 apps
export function setup() {
  console.log("Starting HIGH workload on all 3 GC apps...");
  for (const app of APPS) {
    const res = http.post(`${app.url}/api/stress/start?profile=HIGH`);
    check(res, {
      [`${app.name} started`]: (r) => r.status === 200,
    });
  }
  // Let the workload engines warm up
  sleep(3);
}

// Steady polling — simulates dashboard users hitting the metrics endpoint
// Distributes VUs round-robin across the 3 apps
export function steadyLoad() {
  const app = APPS[__VU % APPS.length];
  const snapshot = http.get(`${app.url}/api/metrics/snapshot`);
  check(snapshot, { "snapshot ok": (r) => r.status === 200 });

  const status = http.get(`${app.url}/api/stress/status`);
  check(status, { "status ok": (r) => r.status === 200 });

  sleep(0.5);
}

// Fire spikes at identical times across all 3 apps
export function spikeBursts() {
  const spikeTimesSeconds = [10, 25, 40, 52];

  for (let i = 0; i < spikeTimesSeconds.length; i++) {
    if (i === 0) {
      sleep(spikeTimesSeconds[0]);
    } else {
      sleep(spikeTimesSeconds[i] - spikeTimesSeconds[i - 1]);
    }

    console.log(`Spike #${i + 1} at ~${spikeTimesSeconds[i]}s`);
    for (const app of APPS) {
      const res = http.post(`${app.url}/api/stress/spike`);
      check(res, {
        [`${app.name} spike ok`]: (r) => r.status === 200,
      });
      spikesTriggered.add(1);
    }
  }
}

// Stop workloads on all 3 apps
export function teardown() {
  console.log("Stopping workloads on all 3 GC apps...");
  for (const app of APPS) {
    const res = http.post(`${app.url}/api/stress/stop`);
    check(res, {
      [`${app.name} stopped`]: (r) => r.status === 200,
    });
  }
}
