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
cp="$(ls "$here"/lib/*.jar | tr '\n' ':')"
if ! "$J/javac" --release 28 --enable-preview -d "$out" -cp "$cp" \
  $(find "$root/java/src/main" "$root/java/src/test" "$root/consumers/graph/src/main" "$here/src" -name '*.java') \
  >"$out/javac.log" 2>&1; then
  cat "$out/javac.log" >&2
  echo "compile failed" >&2
  exit 1
fi
exec "$J/java" --enable-preview --enable-native-access=ALL-UNNAMED -Dlgj.library="$SO" \
  -cp "$out:$cp" com.adaworldapi.interop.tinkerpop.TinkerParityTest
