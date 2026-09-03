#!/usr/bin/env bash
# The §5 gate runner: build java/ TWICE (shipped LaneProbe vs the probed variant), run Component I
# against each, and leave two CSVs for gate.py. This script measures; it never decides.
#
#   ./gate-run.sh                 # full: 5 forks x 8 iterations per arm (~2-3 min)
#   LGJ_BENCH_QUICK=1 ./gate-run.sh   # smoke: 1 fork — gate.py REFUSES the result, on purpose
#
# Outputs: results/gate-before.csv, results/gate-after.csv, results/gate-run.txt
set -euo pipefail

BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$BENCH/.." && pwd)"
JDK="${JDK:-/opt/jdks/jdk-26.0.2}"
LIB_DIR="${LIB_DIR:-$ROOT/target/release}"
OUT="$BENCH/out-gate"
CP_LIBS="$(find "$BENCH/lib" -name '*.jar' | sort | tr '\n' ':')"
API="$ROOT/java/src/main/java"
PROBE="$API/com/adaworldapi/lancegraph/LaneProbe.java"
VARIANT="$BENCH/variants/probed/LaneProbe.java"

[ -f "$LIB_DIR/liblgj_abi.so" ] || { echo "FAIL: no $LIB_DIR/liblgj_abi.so" >&2; exit 1; }
[ -n "$CP_LIBS" ] || { echo "FAIL: no jars in $BENCH/lib" >&2; exit 1; }
[ -f "$PROBE" ] && [ -f "$VARIANT" ] || { echo "FAIL: LaneProbe seam files missing" >&2; exit 1; }
# The variant must differ from the shipped file, or the two builds are one build.
cmp -s "$PROBE" "$VARIANT" && { echo "FAIL: variant is identical to the shipped LaneProbe" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/before" "$OUT/after" "$OUT/bench" "$BENCH/results"

echo "== build BEFORE: java/src/main as shipped"
"$JDK/bin/javac" -d "$OUT/before" $(find "$API" -name '*.java')

echo "== build AFTER: same tree, LaneProbe.java swapped for bench/variants/probed"
"$JDK/bin/javac" -d "$OUT/after" $(find "$API" -name '*.java' ! -path "$PROBE") "$VARIANT"

# One compiled bench, run against each API build (the bench class is identical by construction).
echo "== build the bench once"
"$JDK/bin/javac" -proc:full --add-modules jdk.incubator.vector \
    -cp "$CP_LIBS$OUT/before" -d "$OUT/bench" $(find "$BENCH/src" -name '*.java')

run_arm() {
  local arm="$1"
  echo "== run $arm"
  ( cd "$BENCH" && JAVA_TOOL_OPTIONS= "$JDK/bin/java" \
      --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
      -Dstdout.encoding=UTF-8 -Dlgj.library="$LIB_DIR/liblgj_abi.so" \
      -Dlgj.bench.result="results/gate-$arm.csv" \
      -cp "$CP_LIBS$OUT/$arm:$OUT/bench" \
      com.adaworldapi.lancegraph.bench.Harness I_ProductionAccessorGate )
}
{ run_arm before; run_arm after; } 2>&1 | tee "$BENCH/results/gate-run.txt"

echo
echo "arms written: results/gate-before.csv  results/gate-after.csv"
echo "verdict:      ./gate.py --n <N_ns> --amendment <sha> results/gate-before.csv results/gate-after.csv"
