#!/usr/bin/env bash
# Download the pinned TinkerGraph closure into ./lib, verifying every checksum.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib
repo=https://repo1.maven.org/maven2
grep -v '^#' deps.lock | while read -r coord sum; do
  [ -z "$coord" ] && continue
  IFS=: read -r g a v <<<"$coord"
  jar="lib/$a-$v.jar"
  if [ ! -f "$jar" ]; then
    url="$repo/${g//./\/}/$a/$v/$a-$v.jar"
    # Maven Central answers 429 under bursts; back off and retry a few times.
    for delay in 0 2 4 8 16; do
      sleep "$delay"
      if curl -fsS -o "$jar.part" "$url"; then break; fi
    done
    mv "$jar.part" "$jar"
  fi
  echo "$sum  $jar" | sha256sum -c --quiet - || { echo "checksum mismatch: $jar" >&2; rm -f "$jar"; exit 1; }
done
echo "deps ok: $(ls lib/*.jar | wc -l) jars"
