#!/usr/bin/env bash
# Builds and runs every benchmark contender in its own JVM, then merges the results.
#
# Usage:
#   tools/bench/run.sh                        # all contenders available on this JDK
#   tools/bench/run.sh -p fixtureName=wikipedia
#   WITH_REFERENCE=1 tools/bench/run.sh       # also benchmark the Rust reference (JDK 25)
#
# Environment:
#   MVN        Maven command (default ./mvnw)
#   JAVA       java command (default java)
set -euo pipefail

cd "$(dirname "$0")/../.."

MVN="${MVN:-./mvnw}"
JAVA="${JAVA:-java}"
PROFILES="-Pwith-bench"
if [ "${WITH_REFERENCE:-0}" = "1" ]; then
  PROFILES="$PROFILES -Pwith-reference"
fi

# shellcheck disable=SC2086
"$MVN" -q $PROFILES -DskipTests package

modules=(mdream4j flexmark-published flexmark-patched remark copy-down jsoup xberg reference)
for module in "${modules[@]}"; do
  jar="mdream4j-bench/$module/target/benchmarks.jar"
  if [ ! -f "$jar" ]; then
    echo "skip $module (not built on this JDK)"
    continue
  fi
  echo "== $module"
  "$JAVA" -jar "$jar" \
    -rf json -rff "mdream4j-bench/$module/target/jmh-result.json" \
    -prof gc "$@"
done

node tools/bench/report.mjs
