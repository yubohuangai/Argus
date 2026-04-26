# PTP Feasibility Spike Kit

Runnable artifacts for the spike described in
`.cursor/plans/ptp_feasibility_spike_*.plan.md`.

This kit answers: **can full IEEE 1588 PTP (ptpd / linuxptp) on rooted
Pixel 7 over Wi-Fi do meaningfully better than RecSync's current SNTP?**

## Layout

```
tools/ptp-spike/
  phase1/   Capability inventory (no root needed)
  phase2/   Cross-compile ptpd for aarch64 Android (rooted host required)
  phase3/   Run leader + 2 slaves on Wi-Fi hotspot, capture stats
  phase4/   A/B measurement: SNTP vs PTP, pairwise frame-time diffs
  phase5/   Integration sketch (only if Go) into SoftwareSyncBase
```

## Decision criteria (recap)

- **Go**: PTP slave offset to GM stays under ~1 ms (95th percentile) on
  Wi-Fi with the RecSync hotspot, AND there is a credible path to
  expose that offset to `TimeDomainConverter`.
- **No-go**: Wi-Fi NIC lacks usable timestamping, ptpd cannot run on
  rooted Pixel 7 in our network mode, or measured offset is not
  materially better than current SNTP min-latency filter.

## Prerequisites

- `adb` on PATH (Phase 1+).
- Linux or WSL2 host with autotools and Android NDK r26+ (Phase 2).
- 1 spare Pixel 7 you are willing to root via Magisk (Phase 2/3).
- Existing RecSync APK installed on 3 Pixel 7s for Phase 4 measurements.
- Python 3.10+ with `numpy`, `pandas`, `matplotlib` (Phase 4 analysis).

## Quick start

```sh
# Phase 1 - run on any host with adb + connected Pixel 7
bash tools/ptp-spike/phase1/check_capabilities.sh

# Phase 2 - on Linux/WSL host, point at NDK
NDK=/opt/android-ndk-r26d \
PTPD_SRC=/c/Users/yuboh/GitHub/ptpd \
bash tools/ptp-spike/phase2/build_ptpd_android.sh

# Phase 3 - push to rooted device, run on hotspot
adb push out/ptpd2 /data/local/tmp/
adb push tools/ptp-spike/phase3/leader.conf  /data/local/tmp/
adb push tools/ptp-spike/phase3/client.conf  /data/local/tmp/
adb shell su -c '/data/local/tmp/ptpd2 -c /data/local/tmp/leader.conf'

# Phase 4 - after RecSync recordings, pull CSVs and analyse
python tools/ptp-spike/phase4/compare_pairwise.py \
  --csv-dir ./recordings/run-A-sntp/ \
  --label   SNTP
python tools/ptp-spike/phase4/compare_pairwise.py \
  --csv-dir ./recordings/run-B-ptp/  \
  --label   PTP
```

See each phase's `README.md` for details and stop conditions.
