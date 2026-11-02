#!/usr/bin/env bash
# Builds the library, converts three generated documents on the JVM and in a GraalVM native
# image, and diffs the two outputs.
#
# The library ships no native-image configuration, so `--no-fallback` makes the build fail
# instead of silently switching to the JVM if reachability analysis ever misses something.
#
# Usage:
#   tools/native-smoke/run.sh
#
# Environment:
#   MVN   Maven command (default ./mvnw)
set -euo pipefail

cd "$(dirname "$0")/../.."

MVN="${MVN:-./mvnw}"
TARGET="tools/native-smoke/target"
SOURCE="tools/native-smoke/src/io/github/fbricon/mdream4j/smoke/NativeSmoke.java"
MAIN="io.github.fbricon.mdream4j.smoke.NativeSmoke"

if ! command -v native-image >/dev/null 2>&1; then
  echo "native-image not found on PATH." >&2
  echo "Install GraalVM CE 21 or 25 and retry; the library itself targets Java 11 bytecode." >&2
  exit 1
fi

echo "== building mdream4j"
"$MVN" -q -pl mdream4j -am -Dmaven.test.skip=true package

jar="$(ls mdream4j/target/mdream4j-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)"
if [ -z "$jar" ]; then
  echo "no library jar under mdream4j/target" >&2
  exit 1
fi

# A native-image directory in the jar would mean the build needs configuration the plan forbids.
if jar tf "$jar" | grep -q '^META-INF/native-image/'; then
  echo "$jar ships native-image configuration; it must be zero-config" >&2
  exit 1
fi

rm -rf "$TARGET"
mkdir -p "$TARGET/classes"

echo "== compiling the smoke on the Java 11 floor"
javac --release 11 -cp "$jar" -d "$TARGET/classes" "$SOURCE"

echo "== running on the JVM"
java -cp "$TARGET/classes:$jar" "$MAIN" >"$TARGET/jvm.txt" 2>"$TARGET/jvm.err"

echo "== building the native image"
if ! native-image --no-fallback -cp "$TARGET/classes:$jar" -o "$TARGET/native-smoke" "$MAIN" \
    >"$TARGET/native-image.log" 2>&1; then
  tail -40 "$TARGET/native-image.log" >&2
  exit 1
fi

echo "== running the native image"
"$TARGET/native-smoke" >"$TARGET/native.txt" 2>"$TARGET/native.err"

diff -u "$TARGET/jvm.txt" "$TARGET/native.txt"

echo "== parity ok"
cat "$TARGET/native.txt"
echo "native first conversion: $(sed -n 's/^first-conversion-ms //p' "$TARGET/native.err") ms"
echo "JVM first conversion:    $(sed -n 's/^first-conversion-ms //p' "$TARGET/jvm.err") ms"
