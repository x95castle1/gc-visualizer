# GC Visualizer — G1GC vs ZGC Live Comparison

A **Spring Boot 3 + Java 21** application that lets you **see** the difference between G1GC and ZGC in real time. Watch pause times, heap usage, and task latency live on a dashboard — then decide for yourself if you should switch.

> Built for learning. Designed to be shared. Perfect for teams evaluating a GC migration.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

---

## What You Will See

| Scenario | G1GC | ZGC |
|---|---|---|
| Small heap + low load | Pauses ~5–20ms | Pauses ~0.5ms |
| Large heap + sustained load | Pauses **50–200ms** | Pauses **still ~0.5ms** |
| Throughput benchmark | Slightly higher | Slightly lower |
| Spike (50MB burst) | **Visible stop-the-world** | Nearly invisible |

The dashboard shows this live — no external tools needed.

---

## Prerequisites

- **Java 21** (required — ZGC generational is Java 21+)
- **Maven** (or use the included `./mvnw` wrapper)
- **8 GB RAM minimum** on your machine (16 GB recommended for dramatic results)
- A modern browser (Chrome, Firefox, Edge)

**Check your Java version:**
```bash
java -version
# Should output: openjdk version "21.x.x"
```

---

## Quick Start

### Step 1 — Clone and Build

```bash
git clone https://github.com/YOUR_USERNAME/gc-visualizer.git
cd gc-visualizer
./mvnw clean package -DskipTests
```

Build output: `target/gc-visualizer-1.0.0.jar`

---

### Step 2 — Run with G1GC

```bash
./scripts/run-g1gc.sh medium
```

Open your browser: **http://localhost:8080**

---

### Step 3 — Stress It

On the dashboard:
1. Click **▶ Start** (MEDIUM profile to begin)
2. Watch GC Pause Time and Task Latency charts for 1–2 minutes
3. Click **⚡ Trigger Spike** — watch for the pause spike
4. Take a screenshot

---

### Step 4 — Stop and Switch to ZGC

Press `Ctrl+C` in the terminal, then:

```bash
./scripts/run-zgc.sh medium
```

Repeat the exact same steps — **same profile, same spike** — and compare.

---

## Heap Profiles (Pick Based on Your RAM)

| Profile | Heap | Recommended For |
|---|---|---|
| `low` | 512 MB | 8 GB machine with IDE + browser open |
| `medium` | 1 GB | 8 GB machine, comfortable |
| `high` | 2 GB | 16 GB machine |
| `ultra` | 4 GB | 32 GB machine or EC2 t3.large |

```bash
# Example with explicit profile
./scripts/run-g1gc.sh low      # tight on RAM
./scripts/run-g1gc.sh ultra    # maximum drama for article screenshots
```

---

## Understanding the Three Workload Scenarios

| Profile | What Happens | What It Shows |
|---|---|---|
| **LOW** | Small short-lived allocations only | G1GC and ZGC look similar — no reason to switch at this scale |
| **MEDIUM** | Mixed short + long-lived objects | Differences begin to appear — old gen pressure starts |
| **HIGH** | Heavy allocation + large retained set | **Dramatic difference** — G1GC pauses spike, ZGC stays flat |

Start with MEDIUM, then switch to HIGH for the biggest visual difference.

---

## Reading the Dashboard

### GC Pause Time chart
Shows average milliseconds per GC collection. With G1GC on HIGH profile, expect 50–200ms spikes. With ZGC, this stays near 0–1ms.

### Task Latency chart
Every background task records how long it was actually delayed vs when it was scheduled. **GC Stop-The-World pauses show up here as spikes** — no external profiler needed.

### Heap Usage chart
Watch the sawtooth pattern — heap grows as objects are allocated, drops when GC collects. G1GC drops are sharper (stop-the-world). ZGC drops are smoother (concurrent).

### Trigger Spike button
Allocates 50MB instantly. This is a reliable way to trigger a GC event on demand — great for demonstrating pauses to a team or for article screenshots.

---

## Reading the GC Logs

After running, raw GC logs are saved in `logs/`:

```bash
# View G1GC pauses
grep "Pause" logs/g1gc-medium.log

# View ZGC pauses
grep "Pause" logs/zgc-medium.log
```

You'll see entries like:
```
# G1GC
[2.345s][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 87.234ms

# ZGC
[2.345s][info][gc] GC(3) Pause Mark Start 0.412ms
```

The difference is immediately visible in raw text too.

---

## REST API Reference

Control the workload programmatically:

| Endpoint | Method | Description |
|---|---|---|
| `/api/stress/start?profile=LOW\|MEDIUM\|HIGH` | POST | Start allocation workload |
| `/api/stress/stop` | POST | Stop workload, release retained objects |
| `/api/stress/spike` | POST | Trigger instant 50MB burst |
| `/api/stress/status` | GET | Current workload state |
| `/api/metrics/snapshot` | GET | Single metrics snapshot (JSON) |
| `/api/metrics/stream` | GET | SSE stream (used by dashboard) |

---

## So, Should You Switch to ZGC?

Based on what you just observed:

**Switch to ZGC if:**
- Your heap is larger than 4 GB
- You have latency-sensitive endpoints (APIs, real-time, trading, gaming)
- Your p99 response time matters more than raw throughput
- You are already on Java 21

**Stay on G1GC if:**
- Your heap is under 2 GB — ZGC overhead is not worth it
- Your workload is batch-oriented — pauses during processing do not matter
- You need maximum throughput and CPU is constrained
- You are not yet on Java 21

**Always benchmark your own workload** — these are guidelines, not rules.

---

## Running on AWS EC2 (For Article Screenshots)

For the most dramatic results, run on a t3.large (8 GB RAM, ~$0.08/hr):

### Launch EC2
```
AMI:           Amazon Linux 2023
Instance type: t3.large
Storage:       20 GB gp3
Security group: open port 22 (SSH) and 8080 (HTTP)
```

### Connect and Setup
```bash
ssh -i your-key.pem ec2-user@YOUR_EC2_IP

# Install Java 21
sudo dnf install java-21-amazon-corretto -y
java -version

# Install Maven
sudo dnf install maven -y
```

### Deploy and Run
```bash
# Clone the repo
git clone https://github.com/YOUR_USERNAME/gc-visualizer.git
cd gc-visualizer
./mvnw clean package -DskipTests

# Run G1GC with ultra profile (4GB heap — maximum drama)
./scripts/run-g1gc.sh ultra
```

Open browser: `http://YOUR_EC2_IP:8080`

Run G1GC, take screenshots, then Ctrl+C and run ZGC:
```bash
./scripts/run-zgc.sh ultra
```

**Terminate the instance immediately after** — total cost under $0.25.

---

## Project Structure

```
gc-visualizer/
├── src/main/java/com/gcvisualizer/
│   ├── GcVisualizerApplication.java     # Entry point
│   ├── workload/
│   │   └── WorkloadEngine.java          # Allocation engine (LOW/MEDIUM/HIGH)
│   ├── metrics/
│   │   └── GcMetricsCollector.java      # JMX-based GC metrics
│   └── controller/
│       ├── MetricsStreamController.java # SSE push + snapshot endpoint
│       └── StressController.java        # Workload control REST API
├── src/main/resources/
│   ├── application.properties
│   └── static/
│       └── index.html                   # Live dashboard (single file)
├── scripts/
│   ├── run-g1gc.sh                      # Launch with G1GC
│   └── run-zgc.sh                       # Launch with ZGC (Generational)
├── logs/                                # GC log output (created at runtime)
└── README.md
```

---

## Tech Stack

| Technology | Purpose |
|---|---|
| Java 21 | Virtual threads, Generational ZGC |
| Spring Boot 3.2 | Application framework |
| Server-Sent Events (SSE) | Real-time metrics push to browser |
| Chart.js | Live charts on the dashboard |
| JMX MBeans | GC metrics collection (no agent needed) |
| JVM GC logging (`-Xlog:gc*`) | Raw GC event logs |

---

## Contributing

Pull requests welcome! Ideas for improvement:
- Add Shenandoah GC support
- Export comparison report as PDF
- Add throughput benchmark mode
- Docker Compose for side-by-side comparison

---

## License

MIT — use freely, credit appreciated.

---

*Built to answer the question: "Should I switch to ZGC?" — now you can see for yourself.*
