#!/usr/bin/env python3
"""The §5 verdict function of `.claude/plans/epoch-recheck-v3.md`, and nothing else.

    gate.py --n N_NS --amendment SHA before.csv after.csv
    gate.py --selftest

Inputs are two JMH CSVs from `gate-run.sh` (one per build). Output is exactly one verdict from
the table in §5 — PASS / FAIL / UNDERPOWERED — plus the labels and flags §5 says to record.
Everything numeric here is what the plan pre-registered; if the plan and this file disagree, the
plan wins and this file is wrong.

The rules, in the plan's own order:
  score      JMH avgt ns/op divided by CALLS_PER_OP (a constant the bench states: 1)
  delta_ns   score(after) - score(before)                                        (signed)
  hw_delta   sqrt(hw_before^2 + hw_after^2)   — independent arms, FIXED estimator
  N > 0      required; N <= 0 is not a cutoff and the run is void
  ex ante    hw_delta < N/2 or the run is UNDERPOWERED by construction (not a verdict)
  per-arm    hw < 10% of its own score — a subordinate sanity check, reported, not a verdict
  verdict    delta+hw < N -> PASS ; delta-hw >= N -> FAIL ; else UNDERPOWERED
  label      interval contains 0 -> "cost below the harness's resolution" (never "free")
  flag       ratio = after/before >= 2.0 -> recorded for investigation; blocks nothing
"""
import argparse
import csv
import math
import re
import sys

CALLS_PER_OP = 1           # I_ProductionAccessorGate.CALLS_PER_OP — restated, not read
MIN_SAMPLES = 5 * 8        # @Fork(5) x @Measurement(8): a quick run cannot be evidence
RATIO_FLAG = 2.0
BENCH_NAME = "I_ProductionAccessorGate.classidAt"


def read_arm(path):
    try:
        rows = [r for r in csv.DictReader(open(path)) if BENCH_NAME in r["Benchmark"]]
    except OSError as e:
        sys.exit(f"{path}: {e.strerror} — run gate-run.sh first")
    if len(rows) != 1:
        sys.exit(f"{path}: expected exactly one {BENCH_NAME} row, found {len(rows)}")
    r = rows[0]
    if r["Unit"] != "ns/op":
        sys.exit(f"{path}: unit is {r['Unit']}, expected ns/op")
    samples = int(r["Samples"])
    if samples < MIN_SAMPLES:
        sys.exit(f"{path}: {samples} samples < {MIN_SAMPLES} (5 forks x 8 iterations) — "
                 "a quick run is not evidence; re-run gate-run.sh without LGJ_BENCH_QUICK")
    score = float(r["Score"]) / CALLS_PER_OP
    hw = float(r["Score Error (99.9%)"]) / CALLS_PER_OP
    return score, hw, samples


def verdict(before, after, n):
    """The table. `before`/`after` are (score, hw). Returns a dict; nothing else decides."""
    if not n > 0:
        return {"void": f"N = {n} is not a cutoff (N > 0 required); the run is void"}
    sb, hb = before
    sa, ha = after
    delta = sa - sb
    hw = math.sqrt(hb * hb + ha * ha)
    out = {
        "delta_ns": delta, "hw_delta": hw, "ratio": sa / sb if sb else float("inf"),
        "arm_ok_before": hb < 0.10 * sb, "arm_ok_after": ha < 0.10 * sa,
        "powered": hw < n / 2,
        "label_below_resolution": (delta - hw) <= 0 <= (delta + hw),
        "ratio_flag": (sa / sb if sb else float("inf")) >= RATIO_FLAG,
    }
    if not out["powered"]:
        out["verdict"] = "UNDERPOWERED"
        out["why"] = f"hw_delta {hw:.3f} >= N/2 {n / 2:.3f}: underpowered by construction"
    elif delta + hw < n:
        out["verdict"] = "PASS"
        out["why"] = f"delta + hw = {delta + hw:.3f} < N {n}"
    elif delta - hw >= n:
        out["verdict"] = "FAIL"
        out["why"] = f"delta - hw = {delta - hw:.3f} >= N {n}"
    else:
        out["verdict"] = "UNDERPOWERED"
        out["why"] = f"[{delta - hw:.3f}, {delta + hw:.3f}] straddles N {n}"
    return out


def selftest():
    """The plan's own worked examples, pinned. Each is a case a prior rule got wrong."""
    cases = [
        # (before, after, N, expected verdict, expect label, note)
        ((100, 2), (109, 2), 10, "UNDERPOWERED", False, "delta 9, hw 2.83: upper bound 11.8 straddles N=10 (the struck 'ship if delta<N')"),
        ((100, 1), (99, 50), 10, "UNDERPOWERED", True, "delta -1, hw 50: not a PASS (the struck 'delta<=0 passes')"),
        ((100, 3), (80, 4), 20, "PASS", False, "delta -20, hw 5: a real speedup, NOT 'below resolution' (N=20: at N=10 hw=N/2 is refused ex ante, strictly)"),
        ((100, 1), (102, 1), 10, "PASS", False, "delta 2, hw 1.41: under budget"),
        ((100, 1), (101, 1), 10, "PASS", True, "delta 1, hw 1.41: passes AND is below resolution"),
        ((100, 1), (150, 1), 10, "FAIL", False, "delta 50: over budget; ratio 1.5 no flag"),
        ((10, 0.5), (40, 0.5), 50, "PASS", False, "delta 30 under N=50 but ratio 4.0 -> flag"),
        ((100, 10), (100, 10), 10, "UNDERPOWERED", True, "hw 14.1 >= N/2: the jointly-unsatisfiable case, caught ex ante; label still attaches (interval contains 0)"),
    ]
    bad = 0
    for before, after, n, want, want_label, note in cases:
        v = verdict(before, after, n)
        ok = v["verdict"] == want and v["label_below_resolution"] == want_label
        bad += not ok
        print(f"{'ok ' if ok else 'BAD'} {want:12} label={want_label!s:5} {note}"
              + ("" if ok else f"  -> got {v['verdict']} label={v['label_below_resolution']}"))
    v = verdict((100, 1), (101, 1), 0)
    ok = "void" in v
    bad += not ok
    print(f"{'ok ' if ok else 'BAD'} VOID                     N=0 is refused")
    v = verdict((10, 0.5), (40, 0.5), 50)
    ok = v["ratio_flag"] and v["verdict"] == "PASS"
    bad += not ok
    print(f"{'ok ' if ok else 'BAD'} FLAG                     ratio 4.0 flagged, PASS untouched")
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=float, help="the amendment's cutoff, ns per accessor call")
    ap.add_argument("--amendment", help="sha of the commit that recorded N (must precede both runs)")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("before", nargs="?")
    ap.add_argument("after", nargs="?")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(1 if selftest() else 0)
    if a.n is None or not a.amendment or not a.before or not a.after:
        ap.error("--n, --amendment, before.csv and after.csv are all required")
    if not re.fullmatch(r"[0-9a-f]{7,40}", a.amendment):
        ap.error("--amendment must be a commit sha")
    sb, hb, nb = read_arm(a.before)
    sa, ha, na = read_arm(a.after)
    v = verdict((sb, hb), (sa, ha), a.n)
    print(f"amendment {a.amendment}  N = {a.n} ns/call  CALLS_PER_OP = {CALLS_PER_OP}")
    print(f"before  {sb:10.3f} ± {hb:.3f} ns/call  ({nb} samples; arm sanity {'ok' if v.get('arm_ok_before') else 'FAILED: hw >= 10%'})")
    print(f"after   {sa:10.3f} ± {ha:.3f} ns/call  ({na} samples; arm sanity {'ok' if v.get('arm_ok_after') else 'FAILED: hw >= 10%'})")
    if "void" in v:
        print("VOID:", v["void"]); sys.exit(2)
    print(f"delta   {v['delta_ns']:+10.3f} ± {v['hw_delta']:.3f} ns/call   ratio {v['ratio']:.3f}")
    print(f"VERDICT {v['verdict']}  — {v['why']}")
    if v["label_below_resolution"]:
        print("LABEL   cost below the harness's resolution (interval contains 0) — never 'free'")
    if v["ratio_flag"]:
        print(f"FLAG    ratio >= {RATIO_FLAG}: record for investigation; blocks nothing")
    sys.exit({"PASS": 0, "FAIL": 1, "UNDERPOWERED": 3}[v["verdict"]])


if __name__ == "__main__":
    main()
