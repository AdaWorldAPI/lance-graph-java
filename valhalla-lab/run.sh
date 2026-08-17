#!/usr/bin/env bash
# The A/B. One experiment source, two object models, two JDKs, one diff.
#
# Every measurement this lab reports is produced by this script. Nothing is quoted from memory.
set -uo pipefail

LAB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$LAB/.." && pwd)"

STABLE_JDK="${STABLE_JDK:-/opt/jdks/jdk-26.0.2}"
VALHALLA_JDK="${VALHALLA_JDK:-/opt/jdks/jdk-27}"
LIB_DIR="${LIB_DIR:-$ROOT/target/release}"

OUT="$LAB/results"
mkdir -p "$OUT"

VAL_EXPORTS=(
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED
)

banner() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail()   { printf '\033[31mFAIL:\033[0m %s\n' "$*" >&2; exit 1; }

# ── 0. the A/B is only honest if the two vocabularies differ by exactly the modifier ──────────
banner "0. verifying the two vocabularies differ ONLY by the 'value' modifier"
if diff <(sed 's/^value record/record/' "$LAB/src/valhalla/com/adaworldapi/lancegraph/lab/Vocab.java") \
        "$LAB/src/stable/com/adaworldapi/lancegraph/lab/Vocab.java" > "$OUT/vocab-diff.txt"; then
  echo "OK — src/valhalla/Vocab.java == src/stable/Vocab.java modulo 'value'"
else
  cat "$OUT/vocab-diff.txt"
  fail "the two vocabularies differ by more than the 'value' modifier; the A/B would not be an A/B"
fi

if [ ! -f "$LIB_DIR/liblgj_abi.so" ]; then
  fail "no native library at $LIB_DIR/liblgj_abi.so
build it with:
  cd $ROOT/native/lgj-abi && CARGO_TARGET_DIR=$ROOT/target cargo build --release"
fi

# ── 1. compile ────────────────────────────────────────────────────────────────────────────────
compile () {                 # $1=tag  $2=jdk  $3=vocab-root  shift 3 -> extra javac args
  local tag=$1 jdk=$2 vocab=$3; shift 3
  local api="$OUT/$tag-api" lab="$OUT/$tag-lab"
  rm -rf "$api" "$lab"; mkdir -p "$api" "$lab"

  # the production API, compiled by THIS jdk, with no preview features anywhere
  "$jdk/bin/javac" -d "$api" $(find "$ROOT/java/src/main/java" -name '*.java') \
      2> "$OUT/$tag-api-javac.log" || { cat "$OUT/$tag-api-javac.log"; fail "$tag: API compile"; }

  "$jdk/bin/javac" "$@" -cp "$api" -d "$lab" \
      $(find "$LAB/src/shared" "$vocab" -name '*.java') \
      2> "$OUT/$tag-lab-javac.log" || { cat "$OUT/$tag-lab-javac.log"; fail "$tag: lab compile"; }
  echo "compiled $tag"
}

banner "1. compiling"
compile stable   "$STABLE_JDK"   "$LAB/src/stable"
compile valhalla "$VALHALLA_JDK" "$LAB/src/valhalla" \
        --enable-preview -source 27 -target 27 "${VAL_EXPORTS[@]}"

# ── 2. run ────────────────────────────────────────────────────────────────────────────────────
run () {                     # $1=tag $2=jdk $3=label  shift 3 -> extra jvm args
  local tag=$1 jdk=$2 label=$3; shift 3
  local f="$OUT/$tag-$label.txt"
  echo "--> $tag/$label"
  ( JAVA_TOOL_OPTIONS= "$jdk/bin/java" \
      --enable-native-access=ALL-UNNAMED \
      -Dstdout.encoding=UTF-8 \
      -Dlgj.library="$LIB_DIR/liblgj_abi.so" \
      "$@" \
      -cp "$OUT/$tag-api:$OUT/$tag-lab" com.adaworldapi.lancegraph.lab.RunAll ) \
    > "$f" 2>&1
  local rc=$?
  echo "    exit=$rc  -> ${f#$LAB/}"
  return 0
}

banner "2. running (default VM settings)"
run stable   "$STABLE_JDK"   default
run valhalla "$VALHALLA_JDK" default --enable-preview "${VAL_EXPORTS[@]}"

banner "3. running with escape analysis OFF (what the object model costs unaided)"
run stable   "$STABLE_JDK"   noea -XX:-DoEscapeAnalysis
run valhalla "$VALHALLA_JDK" noea --enable-preview "${VAL_EXPORTS[@]}" -XX:-DoEscapeAnalysis

banner "4. running with Valhalla flattening knobs OFF (does flattening cause the difference?)"
run valhalla "$VALHALLA_JDK" noarrayflat --enable-preview "${VAL_EXPORTS[@]}" \
     -XX:+UnlockDiagnosticVMOptions -XX:-UseArrayFlattening
run valhalla "$VALHALLA_JDK" nofieldflat --enable-preview "${VAL_EXPORTS[@]}" \
     -XX:+UnlockDiagnosticVMOptions -XX:-UseFieldFlattening
run valhalla "$VALHALLA_JDK" noflat --enable-preview "${VAL_EXPORTS[@]}" \
     -XX:+UnlockDiagnosticVMOptions -XX:-UseArrayFlattening -XX:-UseFieldFlattening

banner "5. A/B diff"
diff "$OUT/stable-default.txt" "$OUT/valhalla-default.txt" > "$OUT/AB-default.diff"
echo "wrote ${OUT#$LAB/}/AB-default.diff ($(wc -l < "$OUT/AB-default.diff") lines)"

banner "done — results in $OUT"
ls -1 "$OUT"/*.txt
