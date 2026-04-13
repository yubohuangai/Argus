# RecSync: Multi-Device Synchronized Camera Recording

## Comprehensive Technical Report

---

## 1. Overview

RecSync is an Android research application that enables **sub-millisecond synchronized video recording** across multiple smartphones (tested with up to 11 Pixel phones). Each device simultaneously records H.264 MP4 video with a companion CSV file containing per-frame timestamps in a shared clock domain, enabling frame-accurate post-processing alignment for motion capture and multi-view 3D reconstruction.

The system runs on a local WiFi hotspot with no internet dependency. One phone acts as the **leader** (the hotspot host), and all others are **clients**. Synchronization is achieved in three layers:

1. **Clock Synchronization** (SNTP) — aligns each device's monotonic elapsed time into a shared leader time domain
2. **Phase Alignment** — aligns the *moment within each frame period* when shutters fire
3. **Coordinated Recording** — starts/stops video capture atomically across the fleet

---

## 2. Architecture

### 2.1 Project Structure

```
app/src/main/java/com/googleresearch/capturesync/
  |-- MainActivity.java             # Primary UI and lifecycle orchestrator (~65 KB)
  |-- CameraController.java         # Camera2 device lifecycle, frame callbacks
  |-- CaptureRequestFactory.java    # Builds Camera2 CaptureRequests
  |-- ImageMetadataSynchronizer.java# Matches Images to CaptureResults by timestamp
  |-- ResultProcessor.java          # Saves still frames (NV21/JPEG)
  |-- PhaseAlignController.java     # Phase alignment feedback loop
  |-- SensorPeriodTracker.java      # Measures frame period from SENSOR_FRAME_DURATION
  |-- Mp4SurfaceEncoder.java        # H.264 encoding via MediaCodec + MediaMuxer
  |-- Constants.java                # Global flags (SAVE_YUV, SAVE_JPG_FROM_YUV, etc.)
  |
  |-- softwaresync/
  |     |-- SoftwareSyncBase.java       # UDP socket infrastructure, RPC listener
  |     |-- SoftwareSyncLeader.java     # Leader: client tracking, broadcast RPCs
  |     |-- SoftwareSyncClient.java     # Client: heartbeat, state machine
  |     |-- SoftwareSyncController.java # App-level integration of network + camera
  |     |-- SimpleNetworkTimeProtocol.java  # Leader-side SNTP (naive PTP)
  |     |-- SntpListener.java           # Client-side SNTP responder
  |     |-- SyncConstants.java          # Ports, method IDs, timing constants
  |     |-- NetworkHelpers.java         # WiFi hotspot IP discovery
  |     |-- ClientInfo.java             # Per-client metadata (name, IP, offset)
  |     |-- TimeDomainConverter.java    # Interface: local time -> leader time
  |     |-- CSVLogger.java              # Writes per-frame timestamps to CSV
  |     |
  |     |-- phasealign/
  |           |-- PhaseAligner.java      # Core modular-arithmetic phase computation
  |           |-- PeriodCalculator.java  # Estimates frame period from timestamp deltas
  |           |-- PhaseConfig.java       # Parameters: periodNs, goalPhaseNs, thresholds
  |           |-- PhaseResponse.java     # Per-frame alignment result
  |
  |-- external/                          # Post-processing Python scripts (submodule)
```

### 2.2 Threading Model

| Thread | Role |
|--------|------|
| Main (UI) | Activity lifecycle, button handlers, phase alignment |
| cameraThread | Opens camera, creates capture session, submits requests |
| imageThread | ImageReader callbacks (acquires decoded frames) |
| syncThread | Delivers synchronized (Image + CaptureResult) pairs |
| encoderThread | MediaCodec callbacks, MP4 muxing, CSV writing |
| ExecutorService | SNTP rounds, network RPC sends |

### 2.3 Build Configuration

- **Compile SDK:** 36, **Target SDK:** 29, **Min SDK:** 28
- **Language:** Java 21, Kotlin BOM 2.1.0
- **App ID:** `com.googleresearch.capturesync`
- **Permissions:** CAMERA, INTERNET, ACCESS_WIFI_STATE, READ/WRITE_EXTERNAL_STORAGE

---

## 3. Network Protocol

### 3.1 Device Discovery

RecSync does **not** use mDNS or Android NSD. Instead, discovery is implicit via the WiFi hotspot topology:

1. The leader device creates a WiFi hotspot.
2. Client devices connect to that hotspot.
3. Each client queries `WifiManager.getDhcpInfo().serverAddress` to find the leader's IP.
4. If a device's own IP equals the DHCP server address (or `0.0.0.0`), it is the leader.

This is simple and reliable, but tightly coupled to the hotspot model.

### 3.2 Transport

All communication uses **UDP datagrams** over two ports:

| Port | Purpose |
|------|---------|
| **8244** | RPC messages (commands, heartbeats, status) |
| **9428** | SNTP time synchronization |

Message format (RPC port): **4-byte big-endian method ID** + **variable-length UTF-8 string payload**, up to 1024 bytes total.

### 3.3 RPC Method Table

| Method ID | Name | Direction | Payload |
|-----------|------|-----------|---------|
| 1 | `HEARTBEAT` | Client -> Leader | `"name,ip,syncState"` |
| 2 | `HEARTBEAT_ACK` | Leader -> Client | same |
| 3 | `OFFSET_UPDATE` | Leader -> Client | SNTP offset (ns, string) |
| 200,000 | `SET_TRIGGER_TIME` | Leader -> All | nanosecond timestamp |
| 200,001 | `DO_PHASE_ALIGN` | Leader -> All | — |
| 200,002 | `SET_2A` | Leader -> All | exposure + sensitivity |
| 200,003 | `START_RECORDING` | Leader -> All | — |
| 200,004 | `STOP_RECORDING` | Leader -> All | — |
| 200,005 | `RESET_ALL` | Leader -> All | — |
| 200,006 | `BROADCAST_PERIOD` | Leader -> All | canonical periodNs |
| 200,007 | `REPORT_ALIGNMENT_STATUS` | Client -> Leader | `"name,state,diffMs"` |

### 3.4 Heartbeat and Client Lifecycle

- Clients send `HEARTBEAT` every **1 second**.
- Leader responds with `HEARTBEAT_ACK` and tracks each client's liveness.
- If no heartbeat is received for **2 seconds**, the leader drops the client.
- Client state machine: `WAITING_FOR_LEADER` -> (heartbeat ACK + offset received) -> `SYNCED`.

---

## 4. Clock Synchronization (SNTP)

### 4.1 Clock Domain

All devices measure time using `SystemClock.elapsedRealtimeNanos()` — a monotonic clock counting nanoseconds since boot. Unlike the system real-time clock (which can jump due to NTP or user changes), this clock only moves forward and is never adjusted, making it safe for interval measurement.

### 4.2 Protocol (Naive PTP)

The SNTP exchange is a 4-timestamp round-trip:

```
Leader                          Client
  |                               |
  |--- t0 (leader sends) ------->|
  |                               | t1 = client receive time
  |                               | t2 = client send time
  |<-- [t0, t1, t2] -------------|
  |                               |
  t3 = leader receive time
```

**Clock offset** = `((t1 - t0) + (t2 - t3)) / 2`
**Round-trip latency** = `(t3 - t0) - (t2 - t1)`

#### Derivation of the clock offset formula

Define **θ** as the clock offset (client time − leader time at the same physical instant), **d₁** as the propagation delay leader→client, and **d₂** as the propagation delay client→leader.

From the two legs:

```
Leader→client:  t1 = t0 + d₁ + θ   →   t1 - t0 = d₁ + θ        … (i)
Client→leader:  t3 = (t2 - θ) + d₂  →   t2 - t3 = θ - d₂        … (ii)
```

Adding (i) and (ii) and dividing by 2:

```
θ = [(t1 - t0) + (t2 - t3)] / 2   −   (d₁ - d₂) / 2
                                        ──────────────
                                          residual error
```

The formula is **not** assuming zero latency — it assumes **symmetric** latency (d₁ = d₂). When the two directions take the same time the residual error term vanishes and the formula is exact regardless of how large the latency actually is. When paths are asymmetric, the irreducible error is `(d₁ − d₂) / 2` — unavoidable in software-only time sync.

The round-trip latency formula subtracts the client hold time `(t2 - t1)` from the total elapsed time `(t3 - t0)`, leaving only the two network transit times:

```
(t3 - t0) - (t2 - t1) = (t1 - t0) + (t3 - t2) = d₁ + d₂
```

#### Why the min-filter helps

Selecting the cycle with the **minimum round-trip** `(d₁ + d₂)` is a proxy for the cycle where both legs were short and therefore most likely equal. It does not eliminate asymmetry error but minimises it in practice.

The leader runs up to **300 cycles** and selects the measurement with the **minimum round-trip latency** (min-filter), stopping early if latency drops below **1 ms**. The resulting offset is sent to the client via `METHOD_OFFSET_UPDATE`.

### 4.3 Timestamp Conversion

Every frame timestamp is converted from local to leader time:

```
leaderTimestampNs = localTimestampNs - leaderFromLocalNs
```

This conversion is applied to every `CaptureResult.SENSOR_TIMESTAMP` before it is used for phase alignment, CSV logging, or file naming.

---

## 5. Phase Alignment

### 5.1 The Problem

Even after clock synchronization, each camera sensor's shutter fires at an arbitrary offset within its frame period. Device A might capture at t=0.00, 33.33, 66.67 ms... while Device B captures at t=5.12, 38.45, 71.78 ms... — same frame rate, different phase, producing a fixed ~5 ms inter-device offset.

### 5.2 Core Algorithm

Phase alignment exploits a key property: **changing a single frame's exposure time shifts subsequent frames' phase** by approximately half the exposure change.

For each frame timestamp (in leader time):

```
1.  phaseNs = synchronizedTimestampNs % periodNs
2.  error   = goalPhaseNs - phaseNs
3.  if |error| < alignThresholdNs:
        -> ALIGNED (done)
4.  if error < 0:
        error += periodNs            // can only shift forward
5.  frameDurationToShift = error / 2 + periodNs
6.  exposureToShift = max(minExposure, frameDurationToShift - overheadNs)
7.  Inject one capture request at exposureToShift
8.  Wait 400 ms for sensor to settle
9.  Repeat (up to 50 iterations)
```

### 5.3 Key Parameters (from PhaseConfig)

| Parameter | Typical Value (30 fps) | Meaning |
|-----------|----------------------|---------|
| `periodNs` | 33,333,333 ns | Frame period (~33.3 ms) |
| `goalPhaseNs` | 7,000,000 ns | Target phase within period |
| `alignThresholdNs` | 100,000 ns (100 us) | Convergence tolerance |
| `overheadNs` | ~196,499 ns | Gap between frame duration and exposure |
| `minExposureNs` | ~16,530,105 ns | Minimum effective exposure |

Convergence typically takes **3-10 iterations** (~1-4 seconds).

### 5.4 Period Measurement: The Critical Input

Phase alignment depends on `periodNs` for its modular arithmetic (`timestamp % periodNs`). If two devices use different `periodNs` values, they live in **different modular arithmetic spaces** — both can report "aligned" locally while their actual capture timestamps are offset by a constant.

**PeriodCalculator (legacy):** Measures frame period by collecting inter-frame timestamp deltas over 10 seconds and clustering them. This approach is **noise-dominated** — empirically, back-to-back runs on the same Pixel 7 measured 33,336,589 ns vs 33,331,423 ns (a 5,166 ns variance, ~155x the alignment threshold).

**SensorPeriodTracker (new):** Reads `CaptureResult.SENSOR_FRAME_DURATION` from the Camera2 API for the first 60 frames and takes the mode. On Pixel 7, this returns a perfectly stable **33,333,333 ns** on every frame.

**Caveat:** The Pixel 7's `SENSOR_FRAME_DURATION` reports the *requested* period (exactly 1/30 s), not the *achieved* physical period (~33,336,500 ns). This means the displayed phase error drifts upward at ~0.097 ms/sec after alignment — a cosmetic artifact. However, since all Pixel 7 devices return the same constant, cross-phone alignment is correct (all devices share one modular space).

---

## 6. Recording Pipeline

### 6.1 Video Encoding

RecSync uses a **persistent-surface MediaCodec encoder** (not MediaRecorder):

```
Camera2 → CameraCaptureSession → persistent Surface → MediaCodec (H.264/AVC) → MediaMuxer → .mp4
```

Key encoder settings:
- Codec: H.264/AVC (`video/avc`)
- Color format: `COLOR_FormatSurface` (GPU zero-copy)
- Bitrate mode: VBR (variable)
- I-frame interval: 1 second
- Resolution: 1080p or 2160p (selectable)

### 6.2 CSV Timestamp Generation

For every muxed video sample, the encoder callback writes a corresponding timestamp to a CSV file:

```
CaptureResult.SENSOR_TIMESTAMP
  → timeDomainConverter.leaderTimeForLocalTimeNs()
  → videoCsvTimestampQueue (LinkedBlockingQueue)
  → Mp4SurfaceEncoder.onOutputBufferAvailable() polls queue
  → csvLogger.logLine(leaderTimestampNs)
```

The CSV file contains one nanosecond timestamp per line, one line per MP4 frame. Post-processing scripts use these timestamps to pair frames across devices.

### 6.3 Frame Metadata Synchronization

`ImageMetadataSynchronizer` enforces the invariant that each `TotalCaptureResult` is matched to its corresponding `Image` by `SENSOR_TIMESTAMP` before being delivered to callbacks. It maintains separate queues for capture results and images, sweeping them in lockstep. Unmatched entries (dropped by HAL under load) are logged and discarded.

### 6.4 Output Files

Per device, per recording session:

| File | Content |
|------|---------|
| `RecSync/VID/VID_yyyyMMdd_HHmmss.mp4` | H.264 video |
| `RecSync/yyyyMMdd_HHmmss.csv` | One nanosecond leader-domain timestamp per frame |

---

## 7. Typical Workflow

```
1. Leader phone creates WiFi hotspot
2. 2-10 client phones connect to hotspot
3. All phones launch RecSync
4. Clock sync settles automatically (SNTP, ~seconds)
5. SensorPeriodTracker measures frame period (~2 sec)
6. Leader broadcasts canonical periodNs to all clients
7. User taps "Align Phases" on leader
   -> Leader broadcasts METHOD_DO_PHASE_ALIGN
   -> All devices run PhaseAligner feedback loop
   -> Clients report ALIGNED/FAILED status to leader
8. User adjusts exposure/sensitivity sliders (broadcast to all)
9. User taps "Record" on leader
   -> Leader broadcasts METHOD_START_RECORDING
   -> All devices start MP4 encoding + CSV logging
10. User taps "Stop" on leader
    -> Leader broadcasts METHOD_STOP_RECORDING
    -> All devices finalize MP4 + CSV
11. Transfer files to PC for post-processing
```

---

## 8. The `fix-sync-accuracy` Branch

### 8.1 Problem Statement

Field testing with 11 Pixel phones revealed **20-50 ms pairwise sync errors** that were originally attributed to clock drift or SNTP inaccuracy. Analysis of ground-truth CSV data (`matched_full.csv`) showed the errors were actually **bimodal**: 6 phones had sub-0.1 ms residuals, while 4 phones were stuck at stable constant offsets of -8.01 ms and +13.89 ms. These offsets were stable to within 80 ns across frames — no drift, no noise, just wrong by a constant.

**Root cause:** Each device independently ran `PeriodCalculator` and got a slightly different `periodNs` estimate. Since `PhaseAligner` computes `phase = timestamp % periodNs`, devices with different periods live in different modular arithmetic spaces. Both can report "Aligned: 0.04 ms" locally while their actual capture timestamps disagree by a frame-period-dependent constant.

A secondary problem: under 4K recording load, Camera2 occasionally drops `TotalCaptureResult` callbacks while the encoder still muxes the frame, causing CSV row counts to diverge from MP4 frame counts.

### 8.2 Changes Introduced (28 commits)

#### Fix 1: Canonical Period Broadcast

**New file: `SensorPeriodTracker.java`**

Instead of each device independently running the noisy `PeriodCalculator`, the leader now:
1. Collects `SENSOR_FRAME_DURATION` from Camera2 for the first 60 frames
2. Computes the mode (most frequent value)
3. Broadcasts this canonical `periodNs` to all clients via new RPC `METHOD_BROADCAST_PERIOD`

All devices use the **same** period for phase alignment math, eliminating the bimodal failure mode.

#### Fix 2: Per-Device Alignment Status Reporting

**New RPC: `METHOD_REPORT_ALIGNMENT_STATUS`**

After phase alignment, each client reports its alignment state (ALIGNED/FAILED/ALIGNING) and final phase error back to the leader. The leader aggregates and displays per-device status in its UI, making it possible to diagnose which phones failed alignment without physically checking each screen.

#### Fix 3: Replace MediaRecorder with MediaCodec

**New file: `Mp4SurfaceEncoder.java`**

Replaced the Android MediaRecorder with a direct MediaCodec + MediaMuxer pipeline. The key architectural change: CSV timestamps are now written **from the same callback that muxes frames** (`onOutputBufferAvailable`), making `csv_count == frame_count` guaranteed by construction.

Previous architecture had two independent pipelines (Camera2 callbacks -> queue, Encoder polling queue) that could disagree when the HAL dropped a `TotalCaptureResult`.

#### Fix 4: BOOTTIME to MONOTONIC Conversion

Camera sensors report `SENSOR_TIMESTAMP` in `CLOCK_BOOTTIME` (includes device suspend time), but MediaCodec's `presentationTimeUs` uses `CLOCK_MONOTONIC`. On devices with significant cumulative suspend time, the nanosecond values differ by >150 billion ns, causing timestamp lookup misses. The fix computes the suspend offset once at startup and subtracts it when converting.

#### Fix 5: Reduce Memory/CPU Load

- Disabled YUV ImageReader (`SAVE_YUV = false`) — not needed during video-only recording
- Disabled JPEG encoding from YUV (`SAVE_JPG_FROM_YUV = false`)
- Reduces ISP bandwidth and memory allocation under 4K sustained recording

#### Supporting Changes

- Java 17 -> 21, Kotlin BOM 1.8.22 -> 2.1.0
- Build script for timestamped APK filenames
- Fix for `NetworkOnMainThreadException` when reporting alignment status
- CI fixes for NixOS self-hosted runner (unzip installation)

### 8.3 Impact

| Metric | Before | After |
|--------|--------|-------|
| Cross-phone sync | Bimodal: 6 good, 4 off by 8-14 ms | All phones in same modular space |
| CSV/frame count match | Occasional mismatches under 4K | Guaranteed by construction |
| Alignment visibility | Check each phone screen | Leader shows all device statuses |
| Period measurement noise | +/- 5,166 ns between runs | Stable 33,333,333 ns (from sensor API) |

### 8.4 Known Limitation

`SENSOR_FRAME_DURATION` on Pixel 7 reports the requested period (33,333,333 ns) rather than the achieved physical period (~33,336,500 ns). This causes the displayed phase error to drift upward at ~0.097 ms/sec after alignment. This is a **cosmetic artifact** — physical cross-phone sync is correct because all devices share the same constant. A future fix could measure the true period from timestamp deltas, but it is not required for sync accuracy.

---

## 9. Post-Processing

The `external/` submodule contains Python scripts (notably `sync.py`) that:
1. Read CSV files from all devices
2. Match frames across devices using the leader-domain timestamps
3. Apply a **16 ms threshold** (half of 33.3 ms frame period at 30 fps) — this is structural, not a quality knob
4. Output `matched_full.csv` with pairwise timestamp differences for sync quality analysis

Sync quality is assessed by examining pairwise timestamp diffs on matched frames, not the in-app Phase Error readout.
