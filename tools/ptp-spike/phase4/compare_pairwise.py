#!/usr/bin/env python3
"""Compute pairwise frame-time differences across RecSync CSV recordings.

Each RecSync CSV file contains one leader-domain timestamp (nanoseconds)
per line, one line per encoded frame. This script:

1. Loads every CSV in --csv-dir.
2. Matches frames across devices by nearest timestamp within
   --match-threshold-ms (default 16 ms = half a 30 fps period).
3. For each matched group, computes the spread (max - min) across
   devices.
4. Reports max / mean / p50 / p95 / p99 of the spread and saves a
   histogram + CDF plot.

Run twice (once for SNTP, once for PTP) and compare the resulting
percentiles to make the Go/No-go call.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import numpy as np


def load_csv(path: Path) -> np.ndarray:
    vals = []
    for line in path.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            vals.append(int(line))
        except ValueError:
            continue
    return np.array(vals, dtype=np.int64)


def match_frames(series: dict[str, np.ndarray], threshold_ns: int) -> np.ndarray:
    """Return a 2D array shaped (N_matched, N_devices) of leader-time ns.

    Greedy nearest-neighbour around the device with the fewest frames.
    Rows where any device cannot supply a frame within threshold are
    dropped.
    """
    names = sorted(series.keys())
    if len(names) < 2:
        raise SystemExit("need >= 2 CSVs in --csv-dir")
    # Pick the device with fewest frames as the reference axis.
    ref = min(names, key=lambda n: series[n].size)
    ref_ts = series[ref]
    others = [n for n in names if n != ref]
    out = np.empty((ref_ts.size, len(names)), dtype=np.int64)
    keep = np.ones(ref_ts.size, dtype=bool)

    ref_idx = names.index(ref)
    out[:, ref_idx] = ref_ts

    for n in others:
        ts = series[n]
        for i, t in enumerate(ref_ts):
            j = int(np.searchsorted(ts, t))
            cands = []
            if j > 0:
                cands.append(ts[j - 1])
            if j < ts.size:
                cands.append(ts[j])
            if not cands:
                keep[i] = False
                continue
            best = min(cands, key=lambda v: abs(v - t))
            if abs(best - t) > threshold_ns:
                keep[i] = False
                continue
            out[i, names.index(n)] = best

    return out[keep]


def summarise(spread_ns: np.ndarray, label: str, out_dir: Path) -> dict:
    spread_ms = spread_ns.astype(np.float64) / 1e6
    pct = lambda q: float(np.percentile(spread_ms, q))
    stats = {
        "label": label,
        "matched_groups": int(spread_ms.size),
        "max_ms": float(spread_ms.max()) if spread_ms.size else 0.0,
        "p99_ms": pct(99) if spread_ms.size else 0.0,
        "p95_ms": pct(95) if spread_ms.size else 0.0,
        "p50_ms": pct(50) if spread_ms.size else 0.0,
        "mean_ms": float(spread_ms.mean()) if spread_ms.size else 0.0,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = out_dir / f"summary_{label}.txt"
    summary.write_text(
        "\n".join(f"{k}: {v}" for k, v in stats.items()) + "\n",
        encoding="utf-8",
    )
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        fig, axes = plt.subplots(1, 2, figsize=(11, 4))
        axes[0].hist(spread_ms, bins=80)
        axes[0].set_title(f"{label}: spread per matched group (ms)")
        axes[0].set_xlabel("spread (ms)")
        axes[0].set_ylabel("count")
        sorted_ms = np.sort(spread_ms)
        cdf = np.linspace(0, 1, sorted_ms.size, endpoint=True)
        axes[1].plot(sorted_ms, cdf)
        axes[1].set_title(f"{label}: spread CDF")
        axes[1].set_xlabel("spread (ms)")
        axes[1].set_ylabel("F(x)")
        fig.tight_layout()
        fig.savefig(out_dir / f"plot_{label}.png", dpi=120)
        plt.close(fig)
    except ImportError:
        print("matplotlib not installed; skipping plots", file=sys.stderr)
    return stats


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--csv-dir", required=True)
    p.add_argument("--label", required=True, help="e.g. SNTP or PTP")
    p.add_argument("--match-threshold-ms", type=float, default=16.0)
    p.add_argument("--out-dir", default="phase4/output")
    args = p.parse_args()

    csv_dir = Path(args.csv_dir)
    csvs = sorted(csv_dir.glob("*.csv"))
    if not csvs:
        print(f"no CSVs in {csv_dir}", file=sys.stderr)
        return 2
    series = {c.stem: load_csv(c) for c in csvs}
    threshold_ns = int(args.match_threshold_ms * 1_000_000)
    matched = match_frames(series, threshold_ns)
    spread_ns = matched.max(axis=1) - matched.min(axis=1)
    stats = summarise(spread_ns, args.label, Path(args.out_dir))
    print(f"=== {args.label} ===")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    print(f"\nGo if p95_ms drops by > 50% vs the SNTP baseline AND max_ms < 1.0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
