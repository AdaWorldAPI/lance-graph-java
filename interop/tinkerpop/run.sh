#!/usr/bin/env bash
# Compile lance-graph-java, the graph consumer and this harness, then run it.
# Needs: JDK 28 (LGJ_JDK, default /opt/jdks/jdk-28; JAVA_HOME is not used,
# because it often points at an older JDK), the native library built in
# native/lgj-abi (cargo build --release), and ./fetch-deps.sh run once.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
J="${LGJ_JDK:-/opt/jdks/jdk-28}/bin"
SO="$root/native/lgj-abi/target/release/liblgj_abi.so"
out="$here/out"
rm -rf "$out" && mkdir -p "$out"
# The classpath is exactly the jars pinned in deps.lock, never a lib/ wildcard:
# a stale jar left in lib/ must not shadow a pinned one.
cp=""
while read -r coord _; do
  [ -z "$coord" ] && continue
  IFS=: read -r _ a v <<<"$coord"
  jar="$here/lib/$a-$v.jar"
  [ -f "$jar" ] || { echo "missing $jar; run ./fetch-deps.sh" >&2; exit 1; }
  cp="$cp$jar:"
done < <(grep -v '^#' "$here/deps.lock")
if ! "$J/javac" --release 28 --enable-preview -d "$out" -cp "$cp" \
  $(find "$root/java/src/main" "$root/java/src/test" "$root/consumers/graph/src/main" "$here/src" -name '*.java') \
  >"$out/javac.log" 2>&1; then
  cat "$out/javac.log" >&2
  echo "compile failed" >&2
  exit 1
fi
# Run every main, then fail if any failed: one red suite must not hide the next.
failed=""
for main in TinkerParityTest LoweringStrategyTest; do
  "$J/java" --enable-preview --enable-native-access=ALL-UNNAMED -Dlgj.library="$SO" \
    -cp "$out:$cp" "com.adaworldapi.interop.tinkerpop.$main" || failed="$failed $main"
done
if [ -n "$failed" ]; then
  echo "FAILED:$failed" >&2
  exit 1
fi
