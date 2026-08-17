#!/usr/bin/env bash
# Turn results/jmh-results.csv into the tables in RESULTS.md.
#
# Kept as a script rather than done by hand so that a re-run's numbers can be regenerated
# mechanically — a table transcribed by hand is a table that can drift from its own data.
set -euo pipefail
CSV="${1:-$(dirname "${BASH_SOURCE[0]}")/results/jmh-results.csv}"

python3 - "$CSV" <<'PY'
import csv, sys, collections

rows = list(csv.DictReader(open(sys.argv[1])))

def key(r):
    return r['Benchmark'].rsplit('.', 1)[-1]

def num(r, f):
    v = r.get(f, '')
    return float(v) if v not in ('', 'NaN', None) else float('nan')

# ── Components A and B: flat tables ───────────────────────────────────────────────────────────
for cls, title in (('A_DowncallOverhead', 'A — membrane crossing, isolated'),
                   ('B_SegmentAccess',    'B — reading native memory (65,536 i32)')):
    sel = [r for r in rows if cls in r['Benchmark']]
    if not sel:
        continue
    print(f"\n### {title}\n")
    print("| benchmark | mean | ±99.9% CI | unit |")
    print("|---|---:|---:|---|")
    for r in sorted(sel, key=lambda r: num(r, 'Score')):
        print(f"| `{key(r)}` | {num(r,'Score'):.3f} | ±{num(r,'Score Error (99.9%)'):.3f} "
              f"| {r['Unit']} |")

# ── Component C: the row sweep, one column per arm ───────────────────────────────────────────
sweep = collections.defaultdict(dict)
for r in rows:
    if 'C_ExecutionBoundary' not in r['Benchmark'] or not r.get('Param: rows'):
        continue
    sweep[int(r['Param: rows'])][key(r)] = (num(r, 'Score'), num(r, 'Score Error (99.9%)'))

if sweep:
    arms = ['native_fusedPlan', 'java_vectorApi', 'java_scalarLoop']
    print("\n### C/D — where does execution belong? (µs/op, mean ± 99.9% CI)\n")
    print("| rows | lane KiB | " + " | ".join(f"`{a}`" for a in arms)
          + " | fastest | native/vector |")
    print("|---:|---:|" + "---:|" * (len(arms) + 2))
    for n in sorted(sweep):
        cells, scores = [], {}
        for a in arms:
            if a in sweep[n]:
                s, e = sweep[n][a]
                scores[a] = s
                cells.append(f"{s:.3f} ±{e:.3f}")
            else:
                cells.append("—")
        best = min(scores, key=scores.get) if scores else "—"
        ratio = (f"{scores['native_fusedPlan'] / scores['java_vectorApi']:.2f}x"
                 if 'native_fusedPlan' in scores and 'java_vectorApi' in scores else "—")
        print(f"| {n:,} | {n * 4 // 1024} | " + " | ".join(cells)
              + f" | **{best}** | {ratio} |")

# ── Component E/F: fusion sweep ───────────────────────────────────────────────────────────────
fus = collections.defaultdict(dict)
for r in rows:
    if 'E_FusionAndPlanning' not in r['Benchmark'] or not r.get('Param: predicates') \
            or not r.get('Param: rows'):
        continue
    fus[(int(r['Param: rows']), int(r['Param: predicates']))][key(r)] = (
        num(r, 'Score'), num(r, 'Score Error (99.9%)'))

if fus:
    arms = ['fused', 'unfused', 'fusedScalarKernel', 'planConstructionOnly']
    print("\n### E/F — fusion and the cost of the fluent API (µs/op)\n")
    print("| rows | predicates | " + " | ".join(f"`{a}`" for a in arms) + " | unfused/fused |")
    print("|---:|---:|" + "---:|" * (len(arms) + 1))
    for p in sorted(fus):
        cells, scores = [], {}
        for a in arms:
            if a in fus[p]:
                s, e = fus[p][a]
                scores[a] = s
                cells.append(f"{s:.3f} ±{e:.3f}")
            else:
                cells.append("—")
        ratio = (f"**{scores['unfused'] / scores['fused']:.2f}x**"
                 if 'fused' in scores and 'unfused' in scores else "—")
        print(f"| {p[0]:,} | {p[1]} | " + " | ".join(cells) + f" | {ratio} |")
print()
PY
