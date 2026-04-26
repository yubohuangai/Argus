# PTP Feasibility for RecSync on 11 Pixel 7 over Wi-Fi

## Verdict: low expected return for high implementation cost

You should **not** invest in PTP for this fleet right now.

## Why PTP is a poor fit here

1. **No hardware timestamping on Pixel 7 Wi-Fi.** PTP's accuracy edge
   over SNTP comes from timestamps captured by the NIC at the MAC/PHY
   layer, just before the packet leaves or after it arrives. Pixel 7's
   Wi-Fi chipset does not expose a usable PHC (PTP hardware clock) or
   `SOF_TIMESTAMPING_*HARDWARE` socket option through stock firmware.
   Without that, PTP collapses to *software* timestamps - taken in the
   kernel/socket layer - and that is the same regime the current SNTP
   min-latency filter already operates in.

2. **Privilege barrier on Android.** A real PTP daemon (`ptpd`,
   `linuxptp/ptp4l`) needs root, raw sockets, and the ability to
   discipline the system clock. None of that is available to a
   non-rooted Android app. Rooting 11 production phones is
   operationally costly.

3. **Wi-Fi air-time variability dominates.** PTP's sub-microsecond
   results in the literature are on Ethernet with switches that support
   transparent-clock corrections, or with Intel TimeSync-class Wi-Fi
   NICs. Phone Wi-Fi over a hotspot has retries, aggregation, and
   queueing that produce tens of microseconds to milliseconds of
   asymmetric jitter. The math
   `theta = ((t1 - t0) + (t2 - t3)) / 2 - (d1 - d2) / 2` keeps the same
   `(d1 - d2) / 2` residual whether you call it SNTP or PTP.

4. **The clock-domain mismatch is a hidden tax.** RecSync uses
   `SystemClock.elapsedRealtimeNanos()` (`CLOCK_BOOTTIME`). PTP servos
   `CLOCK_REALTIME`. Even if PTP did discipline the system clock, you
   would need a bridge to translate that into the `BOOTTIME` domain
   that camera sensor timestamps live in - extra moving parts that
   themselves introduce error.

5. **The current numbers are already in the structurally good range.**
   Max under 17 ms at 9 minutes on 11 phones is well inside the
   33.3 ms frame period at 30 fps, so cross-device frame matching with
   a 16 ms threshold is robust. PTP's typical software-timestamp gain
   on Wi-Fi (a few hundred microseconds to low ms) is unlikely to
   materially change the user-visible result.

## Where the real headroom is (cheaper, no PTP)

If you want to push tighter than 17 ms, these are the low-cost levers.
None requires changing protocol.

- **Robust offset estimator.** The current code keeps the single
  sample with minimum round-trip latency. Replace with median or mean
  of the best-K (e.g. K=10) by latency. Wi-Fi outliers are heavy-tailed,
  so trimmed averages move closer to the symmetric truth than a single
  minimum sample.

- **Periodic re-sync during long captures.** SNTP currently runs at
  handshake time and `STALE_OFFSET_TIME_NS` is 3 hours. Add a
  low-frequency re-sync (e.g. once per minute) during recording to
  absorb thermal / oscillator drift on long takes - exactly the regime
  where 9-minute drift would otherwise grow.

- **Parallelize SNTP across clients.** The current SNTP executor is
  single-threaded, so 11 clients serialise. Parallelising shortens
  convergence and allows more rounds per client without dragging out
  warmup.

- **Hotspot hygiene.** Lock the 5 GHz channel manually, keep phones
  close to the leader, kill background traffic. Wi-Fi `(d1 - d2)`
  asymmetry shrinks roughly proportionally to median latency.

## When PTP would become worth it

- A fleet that uses a Wi-Fi chipset that exposes hardware timestamps
  (Intel AX201/AX210 in laptops/SBCs, certain Qualcomm SoCs with
  TimeSync), or
- A move from Wi-Fi to wired Ethernet with PTP-aware switches, or
- A downstream task that cannot tolerate residuals above ~1 ms (for
  example, sub-frame triangulation at high frame rates), where the
  SNTP-side improvements above have already been exhausted.

## Recommendation

Stay on the current SNTP design. If tighter sync is needed later, the
robust offset estimator looks like the highest-impact follow-up for
9-plus-minute recordings.
