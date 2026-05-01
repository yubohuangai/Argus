# Switching SNTP → PTP or Gyroscope Sync on 11 Pixel 7

## Verdict: don't switch — neither helps the actual bottleneck

The end-to-end synchronization error on the current 11-Pixel-7 setup
is bounded at **under 17 ms over 9-minute recordings**, measured
optically (see [REPORT.md §10](REPORT.md#10-synchronization-accuracy-validation)).
That ceiling is dominated by the camera/optical pipeline — sensor
exposure window, ISP latency, encoder lag, and the monitor-refresh
quantum used for measurement (16.67 ms at 60 Hz). The clock-sync
residual is a small slice of that budget. Replacing SNTP with PTP or
gyroscope cross-correlation chases microseconds in a system whose
floor is milliseconds.

## Why not PTP

The full memo is in [ptp_feasibility.md](ptp_feasibility.md). Summary:

1. **No Wi-Fi hardware timestamping on Pixel 7.** The chipset and
   stock firmware do not expose a PTP hardware clock or
   `SOF_TIMESTAMPING_*HARDWARE` socket option. Without that, PTP
   collapses to software timestamps — the same regime SNTP already
   operates in.

2. **Privilege barrier.** A real PTP daemon (`ptp4l`, `ptpd`) needs
   root, raw sockets, and the right to discipline the system clock.
   Non-rooted Android cannot provide any of that. Rooting 11 production
   phones is operationally costly.

3. **Wi-Fi air-time asymmetry dominates.** The residual
   `(d₁ − d₂) / 2` is the same whether you call it SNTP or PTP. PTP's
   sub-microsecond results come from Ethernet with PTP-aware switches,
   not consumer Wi-Fi hotspots.

4. **Clock-domain mismatch.** RecSync lives in `CLOCK_BOOTTIME` because
   that is the domain Camera2 sensor timestamps come in. PTP services
   `CLOCK_REALTIME`. Bridging the two domains adds moving parts that
   themselves introduce error.

## Why not gyroscope sync (twist-n-sync style)

The reference is *Twist-n-Sync: Software Clock Synchronization with
Microseconds Accuracy Using MEMS-Gyroscopes* (Faizullin et al.,
*Sensors* 2021).

1. **Incompatible with multi-view geometry.** The algorithm requires
   the phones to be **rigidly bolted together** so they share one
   motion signal that cross-correlates. Eleven phones distributed
   around a subject — the entire reason for the rig — cannot share
   motion. There is no path to "11 phones on a stick" that is also
   doing multi-view capture.

2. **Validated only for two devices.** Section 7 of the paper
   explicitly states the Android implementation supports only two
   smartphones and lists multi-device extension as future work. Going
   to 11 is not a port; it is original engineering on top of an
   unvalidated extension.

3. **One-shot offset, but recordings are continuous.** Clock drift is
   ~9.5 ppm, so the paper recommends re-twisting every ~2 minutes to
   stay sub-millisecond. The 9-minute recording workflow has no
   opportunity to pause, dismount, twist, and remount.

4. **Integration cost is real.** Brings in Eigen, an FFT module,
   Scapix JNI bindings, and the NDK 22.1 native toolchain. Substantial
   build-system and packaging work for a fork that is currently pure
   Java/Kotlin.

## Where the real headroom is (cheaper, no protocol change)

If tighter sync is needed later, these are the highest-leverage
follow-ups, none of which touches the protocol:

- **Median-of-best-K offset estimator.** Replace the current single
  minimum-latency sample with the median of the best K (e.g. K = 10)
  samples by round-trip latency. Wi-Fi residuals are heavy-tailed, so
  a trimmed average tracks the symmetric truth more closely than a
  single minimum.
- **Periodic SNTP re-sync during recording.** `STALE_OFFSET_TIME_NS`
  is currently 3 hours. A re-sync once per minute during long takes
  would absorb thermal and oscillator drift exactly where 9-minute
  drift would otherwise grow.
- **Parallelize SNTP across clients.** The current SNTP executor is
  single-threaded, so 11 clients serialize. Parallelising shortens
  warmup and allows more rounds per client without dragging out
  startup.
- **Higher-refresh validation monitor.** 120 Hz or 240 Hz with a
  matched-FPS reference video tightens the optical quantum below
  16.67 ms — needed even to *see* improvements past the current floor.

## When either alternative would become worth revisiting

- **PTP** would become worth it on a fleet whose Wi-Fi chipset
  exposes hardware timestamps (Intel AX201/AX210, certain
  TimeSync-capable Qualcomm SoCs), or after a move from Wi-Fi to wired
  Ethernet with PTP-aware switches.
- **Gyroscope sync** would become worth it for a use case where all
  phones are physically clamped together (single-rig stereo or very
  short-baseline arrays) and the take is short enough that
  re-twisting every ~2 minutes is operationally acceptable.

Neither describes the current 11-Pixel-7 multi-view setup, so the
recommendation is to stay on SNTP and, if more headroom is needed,
spend the engineering budget on the items in the section above.
