#!/bin/sh
# Streaming differential: Java MarkdownStream vs the Rust stream-golden reference.
#
# Usage:
#   scripts/stream-diff.sh [--max-report N] [--chunks "1 2 3 7 64 4096"] CASES.jsonl
#
# Runs every chunk size and compares the concatenated feed()/finish() output.
# Exit status 0 means every case matched at every chunk size. A failure also
# writes the mismatching ids under $MDREAM_WORK/stream/.
#
# Paths are resolved from the repository root (the current directory, or
# $MDREAM_REPO). The Rust reference must already be built: make reference.
set -eu

SKILL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=${MDREAM_REPO:-$(git rev-parse --show-toplevel)}
J=${MDREAM_WORK:-/tmp/mdream-work}
MAX_REPORT=5
CHUNKS="1 2 3 7 64 4096"
CASES=

while [ $# -gt 0 ]; do
  case $1 in
    --max-report) MAX_REPORT=$2; shift 2 ;;
    --chunks) CHUNKS=$2; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) CASES=$1; shift ;;
  esac
done

if [ -z "$CASES" ]; then
  echo "usage: $0 [options] CASES.jsonl" >&2
  exit 2
fi
if [ ! -f "$CASES" ]; then
  echo "no such case file: $CASES" >&2
  exit 2
fi
GOLDEN=$REPO/tools/reference/target/release/stream-golden
if [ ! -x "$GOLDEN" ]; then
  echo "missing $GOLDEN; run: make reference" >&2
  exit 2
fi
if [ ! -d "$REPO/mdream4j/target/classes" ]; then
  echo "missing $REPO/mdream4j/target/classes; run:" >&2
  echo "  ./mvnw -q -o -pl mdream4j -Dmaven.test.skip=true package" >&2
  exit 2
fi

mkdir -p "$J/classes" "$J/stream"
javac -cp "$REPO/mdream4j/target/classes" -d "$J/classes" \
  "$SKILL_DIR/scripts/Json.java" "$SKILL_DIR/scripts/StreamProbe.java"

name=$(basename "$CASES" .jsonl)
status=0
for chunk in $CHUNKS; do
  bin=$J/stream/$name-$chunk.bin
  cases=$J/stream/$name-$chunk-cases.jsonl
  java_out=$J/stream/$name-$chunk-java.bin
  ref=$J/stream/$name-$chunk-ref.jsonl
  flat=$ref.flat
  python3 "$SKILL_DIR/scripts/to-stream-docs.py" "$CASES" "$bin" \
    --chunk-size "$chunk" --emit-jsonl "$cases" >/dev/null
  java -cp "$J/classes:$REPO/mdream4j/target/classes" StreamProbe "$bin" "$java_out"
  "$GOLDEN" "$cases" > "$ref"
  python3 - "$ref" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as src, open(path + ".flat", "w", encoding="utf-8") as dst:
    for line in src:
        if line.strip():
            record = json.loads(line)
            dst.write(json.dumps({"id": record["id"],
                                  "outputs": {"markdown": record["streamed"]}}) + "\n")
PY
  printf '%s chunk=%s: ' "$name" "$chunk"
  python3 "$SKILL_DIR/scripts/compare.py" --java "$java_out" --reference "$flat" \
    --format markdown --max-report "$MAX_REPORT" \
    --ids-out "$J/stream/$name-$chunk-mismatches.txt" || status=1
done
exit $status
