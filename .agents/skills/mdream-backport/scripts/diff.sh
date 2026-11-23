#!/bin/sh
# One-shot differential: Java Probe vs the Rust mdream-golden reference.
#
# Usage:
#   scripts/diff.sh [--format markdown|text|html] [--max-report N]
#                   [--ids-file FILE] [--ids-out FILE] CASES.jsonl
#
# Compiles Probe.java into $MDREAM_WORK (default /tmp/mdream-work), converts
# CASES.jsonl with the Java library, runs mdream-golden on the same file and
# compares the requested output. Exit status 0 means every case matched.
#
# Paths are resolved from the repository root (the current directory, or
# $MDREAM_REPO). The Rust reference must already be built: make reference.
set -eu

SKILL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPO=${MDREAM_REPO:-$(git rev-parse --show-toplevel)}
J=${MDREAM_WORK:-/tmp/mdream-work}
FORMAT=markdown
MAX_REPORT=20
IDS_IN=
IDS_OUT=
CASES=

while [ $# -gt 0 ]; do
  case $1 in
    --format) FORMAT=$2; shift 2 ;;
    --max-report) MAX_REPORT=$2; shift 2 ;;
    --ids-file) IDS_IN=$2; shift 2 ;;
    --ids-out) IDS_OUT=$2; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
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
GOLDEN=$REPO/tools/reference/target/release/mdream-golden
if [ ! -x "$GOLDEN" ]; then
  echo "missing $GOLDEN; run: make reference" >&2
  exit 2
fi

if [ ! -d "$REPO/mdream4j/target/classes" ]; then
  echo "missing $REPO/mdream4j/target/classes; run:" >&2
  echo "  ./mvnw -q -o -pl mdream4j -Dmaven.test.skip=true package" >&2
  exit 2
fi

mkdir -p "$J/classes"
JAVA_OUT=$J/diff-java.bin
REF_OUT=$J/diff-ref.jsonl

javac -cp "$REPO/mdream4j/target/classes" -d "$J/classes" \
  "$SKILL_DIR/scripts/Json.java" "$SKILL_DIR/scripts/Probe.java"
python3 "$SKILL_DIR/scripts/to-docs.py" "$CASES" "$J/diff-cases.bin"
java -cp "$J/classes:$REPO/mdream4j/target/classes" Probe "$J/diff-cases.bin" "$JAVA_OUT"
"$GOLDEN" "$CASES" > "$REF_OUT"

set -- --java "$JAVA_OUT" --reference "$REF_OUT" --format "$FORMAT" \
  --max-report "$MAX_REPORT"
if [ -n "$IDS_IN" ]; then
  set -- "$@" --ids-file "$IDS_IN"
fi
if [ -n "$IDS_OUT" ]; then
  set -- "$@" --ids-out "$IDS_OUT"
fi
python3 "$SKILL_DIR/scripts/compare.py" "$@"
