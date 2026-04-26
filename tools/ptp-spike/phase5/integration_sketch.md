# Phase 5 - Integration Sketch (apply only if Go)

This phase is **conditional** on Phase 4 returning Go. Until then, do
not apply these changes to the app.

## Why the integration is small

RecSync already routes every camera timestamp through a single field:

```119:122:app/src/main/java/com/googleresearch/capturesync/softwaresync/SoftwareSyncBase.java
  @Override
  public long leaderTimeForLocalTimeNs(long localTimeNs) {
    return localTimeNs - leaderFromLocalNs;
  }
```

So replacing the offset source means: **stop letting `METHOD_OFFSET_UPDATE`
write `leaderFromLocalNs` on clients, and let a PTP-driven helper write
it instead**. Everything downstream (phase alignment, CSV logging,
encoder timestamps) stays unchanged.

## Important clock-domain note

RecSync timestamps come from `SystemClock.elapsedRealtimeNanos()` which
is Android's `CLOCK_BOOTTIME`. ptpd typically disciplines
`CLOCK_REALTIME`, which leaves `CLOCK_BOOTTIME` untouched. So we cannot
just enable `clock:no_adjust = N` and expect timestamps to "just work".

Two viable patterns:

1. **Offset bridging (preferred for the spike)**. Keep
   `clock:no_adjust = Y`. Have a tiny privileged helper continuously
   read ptpd's reported offset between local clock and master, project
   it into `BOOTTIME` units, and write the resulting
   `leaderFromLocalNs` value to a local socket the app reads.
2. **Custom servo on BOOTTIME**. Replace ptpd's clock discipline with a
   bespoke servo that updates a per-device offset rather than touching
   any system clock. Higher effort; only if Pattern 1 is too noisy.

## Code changes (Pattern 1)

### 1. `SoftwareSyncBase` - widen visibility

Change

```144:147:app/src/main/java/com/googleresearch/capturesync/softwaresync/SoftwareSyncBase.java
  void setLeaderFromLocalNs(long value) {
    leaderFromLocalNs = value;
  }
```

to `public void setLeaderFromLocalNs(long value)` so an external
`PtpOffsetSource` (added below) can push values in.

### 2. `SoftwareSyncClient` - gate the SNTP update

In the `METHOD_OFFSET_UPDATE` handler:

```93:103:app/src/main/java/com/googleresearch/capturesync/softwaresync/SoftwareSyncClient.java
    rpcMap.put(
        SyncConstants.METHOD_OFFSET_UPDATE,
        payload -> {
          lastLeaderOffsetResponseTimeNs = localClock.read();
          ...
          setLeaderFromLocalNs(Long.parseLong(payload));
          ...
        });
```

Wrap the `setLeaderFromLocalNs(...)` call in a flag:

```java
if (!Constants.USE_PTP_OFFSET_SOURCE) {
  setLeaderFromLocalNs(Long.parseLong(payload));
}
```

When the flag is on, the SNTP exchange still runs (kept as a heartbeat
and visibility into asymmetry) but no longer writes the offset.

### 3. New helper class `PtpOffsetSource`

A reference stub lives at
[tools/ptp-spike/phase5/java/PtpOffsetSource.java](java/PtpOffsetSource.java).
It opens an abstract Unix domain socket (`@recsync-ptp`), reads
8-byte big-endian `int64` offset values, and calls
`base.setLeaderFromLocalNs(value)` on each update.

Wire it in `SoftwareSyncController` next to the existing softwaresync
construction; start it after `SoftwareSyncClient` is built and stop it
in `close()`.

### 4. New flag

Add `public static final boolean USE_PTP_OFFSET_SOURCE = false;` to
`Constants.java`. Flip to `true` only on builds for the rooted spike
fleet.

## Privileged helper (root daemon)

Outline at [tools/ptp-spike/phase5/native/recsync_ptp_helper.c](native/recsync_ptp_helper.c).

Responsibilities:

1. Read ptpd's status file (e.g. `/data/local/tmp/ptpd-client.status`)
   or attach to ptpd's management socket to retrieve current offset and
   the timestamp it was sampled at.
2. Convert the offset into `leaderFromLocalNs` units (matching the
   existing convention `leader_time = local_elapsed_time - leader_from_local`).
3. Bind an abstract Unix socket `@recsync-ptp` and stream the latest
   value on every change (or every 1 s, whichever is sooner).
4. Run as root via Magisk service or `init.rc` add-on.

## Rollout

1. Land flag + stub behind `USE_PTP_OFFSET_SOURCE = false` first.
2. Run the rooted-spike build with the flag on, the rest of the fleet
   unchanged. Compare two captures to validate.
3. Only flip the flag default to `true` once Phase 4 numbers, repeated
   on the full 11-phone fleet, beat the current SNTP baseline.

## If No-go

Document the numbers in [REPORT.md](../../../REPORT.md) section 8 and
do not apply any of the above. The investment then shifts to SNTP
robustness (parallel sync, robust filter, periodic re-sync during long
captures) which is independent of this branch.
