---
name: mdream-backport
description: Port an upstream Rust mdream fix into the Java port and prove byte parity (one-shot and streaming). Use when syncing mdream4j to a new upstream commit, diagnosing a golden-corpus mismatch, or adding a differential probe.
---

# mdream backport

Workflow for keeping `mdream4j` byte-identical to upstream Rust `mdream`. The Java
port is a 1:1 translation: find the upstream commit, port the exact behaviour, then
prove it with the differential harnesses.

## Ground rules

- **Port, do not invent.** Reproduce the upstream control flow and names
  (`snake_case` → `camelCase`) instead of designing a Java-only fix.
- **No dependencies.** The library stays dependency-free and GraalVM-native clean.
- **Evidence before "done".** Both differentials must report `0 mismatch(es)`:
  one-shot and streaming.
- Keep temporary instrumentation out of the final diff. Search for `MD_DBG`,
  `System.err`, `eprintln!` before committing.

## Where this skill lives

This file is plain Markdown, and the scripts are plain POSIX shell, Python 3 and
Java: nothing here depends on a particular agent runtime. The canonical copy is
`.agents/skills/mdream-backport/`.

Agents that scan `.agents/skills` (Codex, OpenCode V2 and others) find it
directly. Claude Code only scans `.claude/skills`, so it reaches the canonical
copy through a symlink:

| Agent | Discovery path |
|---|---|
| Anything using the `.agents/skills` convention | `.agents/skills/mdream-backport/` |
| Claude Code | `.claude/skills/mdream-backport` → `.agents/skills/mdream-backport` |

The commands below use `SKILL_DIR` for the directory containing this file. In this
repository that is `.agents/skills/mdream-backport`; set it to the installed
location when the skill lives elsewhere. `extract-rust-cases.py` locates the
checkout itself, or from `MDREAM_REPO`.

## Layout

| Path | What it is |
|---|---|
| `tools/upstream` | Shallow submodule pinned to the parity target commit. |
| `tools/reference` | Test-only Rust bridge; bins `mdream-golden` (one-shot) and `stream-golden` (streaming). |
| `tools/corpus` | Case extractor plus the extracted `cases.jsonl` inputs. |
| `mdream4j/src/test/resources/golden/expected.jsonl` | Checked-in expected outputs, loaded from the test classpath. |
| `mdream4j/src/main/java/.../internal/OutputOps.java` | Output/state machine; most parity fixes land here. |
| `mdream4j/src/main/java/.../internal/ParseOps.java` | Tokenizer/streaming parse loop. |
| `mdream4j/src/main/java/.../internal/ConvertState.java` | Fields and constants. |

## Setup

```shell
git submodule update --init --depth 1 tools/upstream
cd tools/upstream && git fetch --depth 50 origin main && git checkout <commit> && cd -
cargo build --release --manifest-path tools/reference/Cargo.toml
./mvnw -q -o -pl mdream4j -Dmaven.test.skip=true package
```

`mdream-golden` lives at `tools/reference/target/release/mdream-golden`;
`stream-golden` next to it.

## Differential harness

The scripts in `scripts/` are the repeatable version of the ad-hoc probes. Compile
them once, then convert a case file and compare.

### One-shot

```shell
SKILL_DIR=${SKILL_DIR:-.agents/skills/mdream-backport}
J=/tmp/mdream-work && mkdir -p $J/classes
javac -cp mdream4j/target/classes -d $J/classes \
  $SKILL_DIR/scripts/{Json,Probe}.java
python3 $SKILL_DIR/scripts/to-docs.py cases.jsonl $J/cases.bin
java -cp $J/classes:mdream4j/target/classes Probe $J/cases.bin $J/java.bin
tools/reference/target/release/mdream-golden cases.jsonl > $J/ref.jsonl
python3 $SKILL_DIR/scripts/compare.py \
  --java $J/java.bin --reference $J/ref.jsonl --format markdown
```

`cases.jsonl` is `{"id","html","options"}`; `--format` also accepts `text` and
`html`. Add `--ids-out mismatches.txt` to feed only the failures back through
`--ids-file`.

### Streaming

`stream-golden` and the Java probe chunk on **UTF-8 bytes**, advancing to the next
character boundary, so both sides split identically.

```shell
SKILL_DIR=${SKILL_DIR:-.agents/skills/mdream-backport}
python3 $SKILL_DIR/scripts/to-stream-docs.py \
  cases.jsonl $J/stream.bin --chunk-size 7 --emit-jsonl $J/stream-cases.jsonl
javac -cp mdream4j/target/classes -d $J/classes \
  $SKILL_DIR/scripts/{Json,StreamProbe}.java
java -cp $J/classes:mdream4j/target/classes StreamProbe $J/stream.bin $J/stream-java.bin
tools/reference/target/release/stream-golden $J/stream-cases.jsonl > $J/stream-ref.jsonl
```

`stream-golden` writes `{"id","streamed","oneshot","panicked"}`. Flatten it to the
shape `compare.py` reads:

```shell
python3 - "$J/stream-ref.jsonl" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as src, open(path + ".flat", "w", encoding="utf-8") as dst:
    for line in src:
        if line.strip():
            r = json.loads(line)
            dst.write(json.dumps({"id": r["id"], "outputs": {"markdown": r["streamed"]}}) + "\n")
PY
python3 $SKILL_DIR/scripts/compare.py \
  --java $J/stream-java.bin --reference $J/stream-ref.jsonl.flat --format markdown
```

Repeat at several chunk sizes (1, 2, 7, 64, 4096). Streaming bugs are usually
boundary-dependent: a failure at chunk size *n* only points at the construct that
spans that boundary.

## Diagnose a mismatch

1. Shrink to a minimal HTML input. Feed the extracted corpus id back through
   `--ids-file`, or copy its `html` into a one-case file.
2. Look up the matching upstream test in `tools/upstream/crates/core/tests/` and
   the behaviour in `tools/upstream/crates/core/src/convert/{mod,output,parse}.rs`.
3. Compare the two state machines at the same event. Add a temporary guard on an
   env var (`MD_DBG`) in both, run the same case, diff the traces, then remove both.
4. Port the missing state transition or hold. Pay special attention to the
   streaming `get_markdown_chunk` holds (open markers, code spans/fences, quote
   frames, link bracket, item marker line, heading run, how a drain classifies the
   cut line, and `uncut_block_lead_start`).
5. Rebuild and re-run both differentials, then the corpus tests.

## Regenerate goldens after an upstream bump

```shell
# 1. Pin the submodule (detached HEAD is fine).
cd tools/upstream && git fetch --depth 50 origin main && git checkout <commit> && cd -

# 2. Re-extract cases, rebuild the bridge, regenerate expected outputs.
node tools/corpus/extract.mjs
cargo build --release --manifest-path tools/reference/Cargo.toml
cargo run --quiet --release --manifest-path tools/reference/Cargo.toml \
  --bin mdream-golden -- tools/corpus/cases.jsonl > mdream4j/src/test/resources/golden/expected.jsonl

# 3. Run the suite: goldens now encode the new reference behaviour.
./mvnw -q -o -pl mdream4j test
```

Update `README.md` (corpus size, parity target) and `docs/REFERENCE.md` (pin and
fetch command) in the same change.

## Finish checklist

- [ ] One-shot differential: 0 mismatches on the full corpus.
- [ ] Streaming differential: 0 mismatches at every chunk size.
- [ ] `./mvnw -q -o -pl mdream4j test` passes.
- [ ] `./mvnw -Pwith-reference verify` passes (needs the native bridge).
- [ ] No `MD_DBG` / debug prints left in Java or Rust.
- [ ] Golden corpus regenerated and checked in; upstream pin, README and
      `docs/REFERENCE.md` agree.
