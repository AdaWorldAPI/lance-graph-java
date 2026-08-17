#!/usr/bin/env bash
# Build and run the JMH benchmark harness.
#
#   ./run.sh            # everything
#   ./run.sh C_         # only the execution-boundary sweep
#
# Every number in bench/README.md was produced by this script.
set -euo pipefail

BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$BENCH/.." && pwd)"

JDK="${JDK:-/opt/jdks/jdk-26.0.2}"
LIB_DIR="${LIB_DIR:-$ROOT/target/release}"

OUT="$BENCH/out"
CP_LIBS="$(find "$BENCH/lib" -name '*.jar' | sort | tr '\n' ':')"

if [ ! -f "$LIB_DIR/liblgj_abi.so" ]; then
  echo "FAIL: no native library at $LIB_DIR/liblgj_abi.so" >&2
  echo "build it with:" >&2
  echo "  cd $ROOT/native/lgj-abi && CARGO_TARGET_DIR=$ROOT/target cargo build --release" >&2
  exit 1
fi
if [ -z "$CP_LIBS" ]; then
  echo "FAIL: no jars in $BENCH/lib — see bench/README.md § 'Fetching JMH'" >&2
  exit 1
fi

echo "== compiling the library API"
rm -rf "$OUT"; mkdir -p "$OUT/api" "$OUT/bench" "$BENCH/results"
"$JDK/bin/javac" -d "$OUT/api" $(find "$ROOT/java/src/main/java" -name '*.java')

echo "== compiling the benchmarks (annotation processing ON: JMH needs it to emit BenchmarkList)"
# -proc:full is REQUIRED on JDK 23+: annotation processing is off by default there, and without it
# JMH's generator never runs and the harness fails at startup with "Unable to find the resource:
# /META-INF/BenchmarkList" — which looks like a classpath problem and is not one.
"$JDK/bin/javac" \
    -proc:full \
    --add-modules jdk.incubator.vector \
    -cp "$CP_LIBS$OUT/api" \
    -d "$OUT/bench" \
    $(find "$BENCH/src" -name '*.java')

echo "== running"
cd "$BENCH"
JAVA_TOOL_OPTIONS= "$JDK/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    --add-modules jdk.incubator.vector \
    -Dstdout.encoding=UTF-8 \
    -Dlgj.library="$LIB_DIR/liblgj_abi.so" \
    -cp "$CP_LIBS$OUT/api:$OUT/bench" \
    com.adaworldapi.lancegraph.bench.Harness "$@" \
  2>&1 | tee "$BENCH/results/jmh-run.txt"

echo
echo "results: bench/results/jmh-run.txt  and  bench/results/jmh-results.csv"
