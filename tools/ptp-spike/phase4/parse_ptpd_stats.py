#!/usr/bin/env python3
"""Parse ptpd2 stats.log and summarise offset/delay distributions.

The ptpd column order matches ptpd's tools/ptplib/R/ptplib.R:
  timestamp, state, clockID, delay, offset, slave.to.master,
  master.to.slave, drift, packet, sequence, one.way.delay.mean,
  one.way.delay.dev, offset.mean, offset.dev, drift.mean, drift.dev,
  delay.ms, delay.sm

We care primarily about the 'offset' (slave -> master offset, seconds)
and 'delay' (one-way mean delay, seconds).
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path
from statistics import mean, median


COLS = [
    "timestamp", "state", "clockID", "delay", "offset",
    "slave_to_master", "master_to_slave", "drift", "packet", "sequence",
    "one_way_delay_mean", "one_way_delay_dev", "offset_mean", "offset_dev",
    "drift_mean", "drift_dev", "delay_ms", "delay_sm",
]


def percentile(values, q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * q
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--stats", required=True, help="ptpd-client.stats.log")
    p.add_argument("--label", default="PTP")
    p.add_argument("--state", default="slv", help="filter by state column")
    args = p.parse_args()

    offsets, delays = [], []
    with open(args.stats, newline="") as fh:
        rdr = csv.reader(fh)
        for row in rdr:
            if not row or row[0].startswith("#"):
                continue
            if len(row) < len(COLS):
                continue
            rec = dict(zip(COLS, row))
            if args.state and args.state not in rec["state"].lower():
                continue
            try:
                offsets.append(abs(float(rec["offset"])))
                delays.append(float(rec["delay"]))
            except ValueError:
                continue

    if not offsets:
        print("no slave-state samples found", file=sys.stderr)
        return 2

    def stat_block(label, values, scale=1e6):
        mn = min(values)
        mx = max(values)
        return {
            f"{label}_n": len(values),
            f"{label}_min_us": mn * scale,
            f"{label}_max_us": mx * scale,
            f"{label}_mean_us": mean(values) * scale,
            f"{label}_median_us": median(values) * scale,
            f"{label}_p95_us": percentile(values, 0.95) * scale,
            f"{label}_p99_us": percentile(values, 0.99) * scale,
        }

    summary = {}
    summary.update(stat_block("offset", offsets))
    summary.update(stat_block("delay", delays))
    print(f"=== {args.label} ptpd stats summary ===")
    for k, v in summary.items():
        if isinstance(v, float):
            print(f"  {k}: {v:.3f}")
        else:
            print(f"  {k}: {v}")
    print()
    print("Go if offset_p95_us < 1000 (i.e. < 1 ms) on the spike Wi-Fi link.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
