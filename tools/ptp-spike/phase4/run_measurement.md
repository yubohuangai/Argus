# Phase 4 - A/B Measurement

The objective is two back-to-back 9-minute recordings on the same 3
phones, identical phase alignment, identical scene, then compare
pairwise frame-time spreads.

## Run A - current SNTP

1. Build the unmodified RecSync APK from `fix-sync-accuracy`.
2. Install on leader + 2 clients.
3. Connect clients to leader hotspot, wait for sync to settle.
4. Run **Align Phases**, then **Record** for 9 minutes.
5. Pull CSVs:
   ```sh
   mkdir -p recordings/run-A-sntp
   for s in <leader-serial> <client1-serial> <client2-serial>; do
     adb -s "$s" pull /sdcard/RecSync/$(adb -s "$s" shell ls -t /sdcard/RecSync | grep .csv | head -n1) \
       recordings/run-A-sntp/$s.csv
   done
   ```
6. Analyse:
   ```sh
   python tools/ptp-spike/phase4/compare_pairwise.py \
     --csv-dir recordings/run-A-sntp --label SNTP
   ```

## Run B - PTP discipline (no app changes)

For the spike, do **not** modify the RecSync app. Instead:

1. On the leader phone, leave RecSync running (its SNTP exchange will
   keep happening too; that is fine, ptpd is observe-only with
   `clock:no_adjust = Y`).
2. Start `ptpd2` (Phase 3) on all 3 phones in parallel with RecSync.
3. After it has been in SLAVE state for 60 seconds on both clients,
   start the same 9-minute recording.
4. Pull both the RecSync CSVs and the ptpd stats:
   ```sh
   mkdir -p recordings/run-B-ptp
   for s in <leader-serial> <client1-serial> <client2-serial>; do
     adb -s "$s" pull /sdcard/RecSync/...csv recordings/run-B-ptp/$s.csv
     adb -s "$s" pull /data/local/tmp/ptpd-client.stats.log recordings/run-B-ptp/$s.ptpstats
   done
   ```
5. Analyse:
   ```sh
   python tools/ptp-spike/phase4/compare_pairwise.py \
     --csv-dir recordings/run-B-ptp --label PTP-csv
   for f in recordings/run-B-ptp/*.ptpstats; do
     python tools/ptp-spike/phase4/parse_ptpd_stats.py --stats "$f" --label "$(basename $f)"
   done
   ```

## Decision

Take the *PTP* `compare_pairwise.py` output and the worst per-client
ptpd `offset_p95_us`. Apply:

- **Go**: SNTP baseline `p95_ms` is materially worse (>= 2x) than the
  PTP run, AND `offset_p95_us < 1000` on every client.
- **No-go**: Either condition fails. Document numbers and stop.

Append the resulting tables to [REPORT.md](../../../REPORT.md) under
section 8 ("The fix-sync-accuracy Branch").
