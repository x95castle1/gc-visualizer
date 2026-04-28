import { startWorkloads, stopWorkloads, steadyLoad as _steadyLoad, fireSpikes } from "./common.js";

export const options = {
  scenarios: {
    steady_load: {
      executor: "constant-vus",
      vus: 6,
      duration: "10m",
      exec: "steadyLoad",
    },
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

export function setup() { startWorkloads(); }
export function teardown() { stopWorkloads(); }
export function steadyLoad() { _steadyLoad(); }

// Spikes every ~45-60s across the 10m run
export function spikeBursts() {
  fireSpikes([30, 75, 120, 180, 240, 300, 345, 390, 450, 510, 560]);
}
