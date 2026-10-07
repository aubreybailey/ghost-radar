#!/usr/bin/env python3
"""Inspect a Ghost Radar survey recording (Download/GhostRadar/survey-*.csv).

    python3 tools/survey.py data/survey-....csv [--plot out.png] [--step 0.6]

Prints the session timeline, the dead-reckoned path at each Mark, and per-device
RSSI statistics; optionally plots the path and the strongest devices' RSSI along it.
"""
import argparse, csv, math, sys
from collections import defaultdict

import numpy as np


def load(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            r["t"] = int(r["ms"]) / 1000.0
            rows.append(r)
    return rows


def headings(rows):
    """Pose headings as (times, unwrapped radians) for interpolation."""
    t, h = [], []
    for r in rows:
        if r["event"] in ("pose", "step") and r["heading_deg"]:
            t.append(r["t"]); h.append(math.radians(float(r["heading_deg"])))
    order = np.argsort(t)
    return np.array(t)[order], np.unwrap(np.array(h)[order])


def step_times(rows, accepted_only=True):
    """The step detector delivers in ~1 s batches; spread each batch evenly over
    the interval since the previous delivery (capped at 1.2 s)."""
    batches = defaultdict(int)
    for r in rows:
        if r["event"] == "step" and (r["accepted"] == "1" or not accepted_only):
            batches[r["t"]] += 1
    out, prev = [], None
    for t in sorted(batches):
        n = batches[t]
        span = min(1.2, t - prev) if prev is not None else 1.0
        out += [t - span + span * (i + 1) / n for i in range(n)]
        prev = t
    return np.array(out)


def integrate(rows, step_m):
    """Dead-reckon accepted steps. Recordings from v1.0.13+ log each step's own
    heading and delivery lag (info lag_ms=...); older ones need the batch spread
    and a heading lookup."""
    acc = [r for r in rows if r["event"] == "step" and r["accepted"] == "1"]
    if acc and all(r["info"].startswith("lag_ms=") for r in acc):
        st = np.array([r["t"] - int(r["info"][7:]) / 1000.0 for r in acc])
        hd = np.radians([float(r["heading_deg"]) for r in acc])
        order = np.argsort(st)
        st, hd = st[order], hd[order]
    else:
        ht, hv = headings(rows)
        st = step_times(rows)
        hd = np.interp(st, ht, hv)
    xy = np.cumsum(np.stack([np.sin(hd), np.cos(hd)], 1) * step_m, 0)
    xy = np.vstack([[0, 0], xy])
    times = np.concatenate([[rows[0]["t"]], st])
    return times, xy


def position_at(times, xy, t):
    i = np.searchsorted(times, t, side="right") - 1
    return xy[max(0, i)]


def addr_type(addr):
    """Guess from the top two bits. Only meaningful for random addresses; a public
    (hardware) address can have any bits, and Android doesn't tell us which it is."""
    top = int(addr[:2], 16) >> 6
    return {0b11: "static?", 0b01: "rotating?", 0b00: "public/NRPA", 0b10: "public"}[top]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--plot")
    ap.add_argument("--step", type=float, default=0.6)
    ap.add_argument("--top", type=int, default=6)
    a = ap.parse_args()

    rows = load(a.csv)
    dur = rows[-1]["t"]
    info = next((r["info"] for r in rows if r["event"] == "start"), "")
    print(f"{a.csv}\n  {info}, {dur:.0f} s, {len(rows)} rows")

    steps = [r for r in rows if r["event"] == "step"]
    acc = sum(r["accepted"] == "1" for r in steps)
    print(f"  steps: {acc} kept, {len(steps) - acc} ignored by tilt gate")
    first_step = min((r["t"] for r in steps), default=dur)
    print(f"  still before first step: {first_step:.1f} s")

    times, xy = integrate(rows, a.step)
    marks = [(r["info"], r["t"]) for r in rows if r["event"] == "mark"]
    print("  marks:")
    for m, t in marks:
        p = position_at(times, xy, t)
        print(f"    #{m} at {t:5.1f}s  pos ({p[0]:+5.1f}, {p[1]:+5.1f}) m")
    end = xy[-1]
    print(f"  end position ({end[0]:+.1f}, {end[1]:+.1f}) m, path length {len(xy) - 1} steps x {a.step} m")

    # Per-device stats
    names, kinds, adv = {}, {}, defaultdict(list)
    for r in rows:
        if r["event"] == "dev":
            parts = r["info"].split("|")
            names[r["addr"]] = parts[0]; kinds[r["addr"]] = parts[1]
        elif r["event"] == "adv":
            adv[r["addr"]].append((r["t"], int(r["rssi"])))
    stats = []
    for addr, s in adv.items():
        t = np.array([x[0] for x in s]); v = np.array([x[1] for x in s])
        still = v[t < first_step]
        stats.append(dict(addr=addr, n=len(v), mean=v.mean(), std=v.std(), lo=v.min(), hi=v.max(),
                          still_std=still.std() if len(still) >= 5 else float("nan"),
                          span=t.max() - t.min(), name=names.get(addr, ""), kind=kinds.get(addr, "")))
    stats.sort(key=lambda d: -d["n"])
    print(f"\n  {len(stats)} devices (sorted by advert count)")
    print(f"  {'address':17} {'type':14} {'n':>4} {'mean':>6} {'std':>5} {'min':>4} {'max':>4} {'seen':>5}  name / kind")
    for d in stats:
        print(f"  {d['addr']:17} {addr_type(d['addr']):14} {d['n']:4d} {d['mean']:6.1f} {d['std']:5.1f} "
              f"{d['lo']:4d} {d['hi']:4d} {d['span']:4.0f}s  {d['name']} {('/ ' + d['kind']) if d['kind'] else ''}")

    if a.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        top = [d for d in stats if d["n"] >= 40][: a.top]
        cols = 3
        rws = 1 + math.ceil(len(top) / cols)
        fig = plt.figure(figsize=(12, 4 * rws))
        ax = fig.add_subplot(rws, 1, 1)
        ax.plot(xy[:, 0], xy[:, 1], "-o", ms=3, color="#888")
        for m, t in marks:
            p = position_at(times, xy, t)
            ax.annotate(f"M{m}", p, color="C3", fontsize=11, weight="bold")
            ax.plot(*p, "s", color="C3")
        ax.plot(0, 0, "k^", ms=10)
        ax.set_aspect("equal"); ax.set_title(f"Dead-reckoned path ({a.step} m/step), marks in red"); ax.grid(alpha=.3)
        for i, d in enumerate(top):
            axd = fig.add_subplot(rws, cols, cols + i + 1)
            s = np.array(adv[d["addr"]])
            pts = np.array([position_at(times, xy, t) for t in s[:, 0]])
            jitter = np.random.default_rng(0).normal(0, 0.06, pts.shape)
            sc = axd.scatter(*(pts + jitter).T, c=s[:, 1], cmap="plasma", vmin=-95, vmax=-40, s=10)
            axd.plot(xy[:, 0], xy[:, 1], "-", color="#ccc", lw=.8, zorder=0)
            axd.set_aspect("equal")
            axd.set_title(f"{d['addr']}\n{d['name'] or d['kind'] or '?'}  ({d['n']} adv)", fontsize=9)
            fig.colorbar(sc, ax=axd, fraction=.046)
        fig.tight_layout()
        fig.savefig(a.plot, dpi=80)
        print(f"\n  plot -> {a.plot}")


if __name__ == "__main__":
    sys.exit(main())
