# Skill: mdream-backport

# mdream backport

Workflow for keeping `mdream4j` byte-identical to upstream Rust `mdream`. The Java
port is a 1:1 translation: find the upstream behaviour, port it exactly, then prove
it with the differential harnesses.

## Ground rules

- **Port, do not invent.** Reproduce the upstream control flow and names
  (`snake_case` → `camelCase`) instead of designing a Java-only fix.
- **No dependencies.** The library stays dependency-free and GraalVM-native clean.
- **Evidence before "done".** Both differentials must report `0 mismatch(es)`:
  one-shot and streaming. A list of ported commits is not evidence.
- Keep temporary instrumentation out of the final diff. Search for `MD_DBG`,
  `System.err`, `eprintln!` before committing.

## Backport strategy

1. **Pin a release, not `main` HEAD.** Prefer a tag (`v2.2.0`), record its commit,
   and note the previous pin. Read `CHANGELOG.md` for the behaviour changes the
   differential must cover.
2. **Size the gap first.**
   `git log --oneline --no-merges <old>..<new> | wc -l` and skim the subjects.
   A 40–50 commit gap is normal; do not estimate from the count alone.
3. **Choose sequential or batch.**
   - *Sequential* when each commit is self-contained: port, build, verify, repeat.
   - *Batch* when upstream refactors across commits so intermediate trees do not
     build. Port the final source, then hunt for un-ported transitions with the
     corpus and the harvested literals (below). Record which commit introduced a
     helper with `git log -S` so the reasoning stays auditable.
4. **Audit the final source, not the commit list.** Diff the Rust and Java field
   sets, helper names and tokenizer branches in
   `convert/{mod,parse,output}.rs`. Every Rust field or `fn` with no Java
   counterpart is either dead or a gap; decide which, explicitly.
5. **Finish broad.** Run the full corpus, the all-tests harvest, and streaming on
   both. Focused per-commit probes miss interactions between two later commits.

## Java translation hazards

These produced most of the rework in the v2.2.0 backport.

| Rust | Java |
|---|---|
| byte offset / `str::len()` | char index in UTF-16; only convert at output boundaries. A value that feeds `maxNodeBytes` must use `Scan.utf8Length(...)`, never `String.length()`. |
| `usize::MAX` sentinel | `-1` or `Integer.MAX_VALUE`; pick one per field, document it, and check every comparison. |
| `saturating_sub` | `Math.max(0, a - b)`; `checked_sub` → an explicit `a >= b` guard. |
| `drain(..=index)` | `subList(0, index + 1).clear()` — the Rust range is **inclusive**. |
| `Option<u8>` tag id | `-1` for none; do not confuse it with index 0. |
| `last()`, `rposition`, `is_some_and` | explicit `isEmpty()` / `size() - 1`; preserve short-circuit order. |
| `trim_ascii_end`, `is_ascii_*` | `Chars` helpers; never `String.trim()`/`strip()` (Unicode sets differ). |
| `bytes[i]` on ASCII markup | `charAt(i)` is safe only while the byte is ASCII; decode otherwise. |
| `str::chars().next()` | guard `isEmpty()` before `charAt(0)` (a real crash in `orderedDigits`). |
| `replace_range(a..b, v)` | `StringBuilder.replace(a, b, v)`; both end-exclusive. |
| JS-engine ports (`inlineAtRoot`, …) | port the Rust function, not the JS one; upstream Rust already reconciles the engines. |

## Environment

- `./mvnw` is canonical. `mvnd` is a drop-in local accelerator when installed.
- Source the JDK manager first (`source ~/.sdkman/bin/sdkman-init.sh`); the active
  JDK can differ between shells. `UnsupportedClassVersionError` on an ad-hoc probe
  means it was compiled with another JDK: re-source, rebuild, recompile.
- Maven is usable offline (`-o`). Cargo needs a warmed crate cache; the reference
  bridge is test-only and never published.
- Keep work files in `$MDREAM_WORK` (default `/tmp/mdream-work`); never in the repo.

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
| `scripts/diff.sh`, `scripts/stream-diff.sh` | Differential wrappers; build the probes and compare in one call. |
| `scripts/extract-rust-cases.py` | Harvests HTML literals from upstream Rust tests. |

## Setup

```shell
git submodule update --init --depth 1 tools/upstream
cd tools/upstream && git fetch --depth 50 origin main && git checkout <commit> && cd -
make reference
./mvnw -q -o -pl mdream4j -Dmaven.test.skip=true package
```

`mdream-golden` lives at `tools/reference/target/release/mdream-golden`;
`stream-golden` next to it. When the target is a release tag, fetch the tag
(`git fetch --depth 1 origin refs/tags/v2.2.0 && git checkout v2.2.0`).

## Differential harness

The wrappers are the repeatable version of the ad-hoc probes. They resolve the
repo root from the current directory or `$MDREAM_REPO`, compile `Probe`/`StreamProbe`
into `$MDREAM_WORK/classes`, and exit non-zero on a mismatch.

### One-shot

```shell
SKILL_DIR=${SKILL_DIR:-.agents/skills/mdream-backport}
$SKILL_DIR/scripts/diff.sh cases.jsonl
$SKILL_DIR/scripts/diff.sh --format text cases.jsonl
$SKILL_DIR/scripts/diff.sh --ids-out mismatches.txt cases.jsonl
```

`cases.jsonl` is `{"id","html","options"}`; `--format` accepts `markdown`,
`text` and `html`. `--ids-out` writes the failures, ready for `--ids-file` on the
next comparison or for a one-case JSONL.

### Streaming

```shell
$SKILL_DIR/scripts/stream-diff.sh cases.jsonl
$SKILL_DIR/scripts/stream-diff.sh --chunks "1 7 37 4096" cases.jsonl
```

`stream-golden` and the Java probe chunk on **UTF-8 bytes**, advancing to the next
character boundary, so both sides split identically. The wrapper flattens
`{"id","streamed","oneshot","panicked"}` and compares the `streamed` column.

Chunk sizes are boundary probes: a failure at size *n* points at the construct
spanning that boundary. Use the corpus sizes 1, 2, 3, 7, 64, 4096 and, for a
harvest, 1, 7, 37, 4096. A large size (4096 or 10⁹) separates whole-input logic
from chunk handling.

### Harvest upstream test literals

`tools/upstream/crates/core/tests/*.rs` is the richest source of edge cases. Run
the harvest after every batch, even when each commit had a focused probe — it
caught 64 mismatches the per-commit probes missed in the v2.2.0 backport.

```shell
python3 $SKILL_DIR/scripts/extract-rust-cases.py --all-tests --stats
$SKILL_DIR/scripts/diff.sh $SKILL_DIR/work/rust-cases.jsonl
$SKILL_DIR/scripts/stream-diff.sh --chunks "1 7 37 4096" $SKILL_DIR/work/rust-cases.jsonl
```

`--all-tests` scans every top-level test file (about 4,200 candidate cases at
v2.2.0); without it, the script scans the six core conversion/streaming suites.
Some literals are expected Markdown, not HTML inputs: `*.suspicious.txt` lists
them. They are still valid inputs, but do not read them as upstream assertions.
IDs are per file and index, hence stable for a pin.

## Diagnose a mismatch

1. Shrink to a minimal HTML input. Feed the extracted corpus id back through
   `--ids-file`, or copy its `html` into a one-case file.
2. Find the behaviour in `tools/upstream/crates/core/src/convert/{mod,output,parse}.rs`
   and the matching upstream test in `tools/upstream/crates/core/tests/`.
3. Map a helper, field or condition to its commit with
   `git log -S '<symbol>' --oneline -- crates/core/src` and read that commit's
   tests. In v2.2.0 this settled several link/emphasis questions that a local
   guess would have got wrong (`cache_follows_separator` → `9c10169`).
4. Port the whole transition, not a local workaround. Output that matches while
   the state differs will break a later case.
5. Add temporary tracing behind an env var (`MD_DBG`) on the Java side, read the
   Rust source at the same event, and compare the state tuple (buffer, markers,
   link, offsets). Remove the tracing before committing.
6. Rebuild and re-run both differentials, the harvest, then the corpus tests.

## Regenerate goldens after an upstream bump

```shell
# 1. Pin the submodule (detached HEAD or a tag is fine).
cd tools/upstream && git fetch --depth 1 origin refs/tags/v<new> \
  && git checkout v<new> && cd -

# 2. Re-extract cases, rebuild the bridge, regenerate expected outputs.
make goldens

# 3. Run the suite: goldens now encode the new reference behaviour.
./mvnw -q -o -pl mdream4j test
```

`make goldens` runs the reference build, `tools/corpus/extract.mjs` and
`mdream-golden`. It also bumps the `mdream` version in
`tools/reference/Cargo.lock`; commit that. Verify the result rather than trusting
it:

```shell
tools/reference/target/release/mdream-golden tools/corpus/cases.jsonl \
  | cmp - mdream4j/src/test/resources/golden/expected.jsonl
wc -l tools/corpus/cases.jsonl mdream4j/src/test/resources/golden/expected.jsonl
```

The two line counts must match. Raised corpus counts live in **four** tests;
grep for the old number and update every hit:

- `mdream4j/src/test/java/io/github/mdream4j/GoldenCorpusTest.java`
- `mdream4j/src/test/java/io/github/mdream4j/GoldenStreamingTest.java`
- `mdream4j/src/test/java/io/github/mdream4j/internal/StreamingDrainTest.java`
- `mdream4j/src/test/java/io/github/mdream4j/internal/FastPathParityTest.java`

Update the counts and pin in `AGENTS.md`, `README.md`, `docs/REFERENCE.md`,
`docs/VERSIONING.md` and the `mdream.upstream.*` POM properties in the same change.
A new upstream release also moves the project version to `<new-upstream>.1-SNAPSHOT`;
see `docs/VERSIONING.md`. Leave dated snapshots such as `docs/REVIEW-2026-11.md`
alone.

## Git and PR hygiene

- Conventional Commits; sign off every commit (`git commit --signoff`).
  Suggested subject for a pin bump: `fix(parity): sync to upstream vX.Y.Z`.
- Push topic branches to your fork (for this repository, `fbricon/mdream4j`),
  never to `origin`, and open the PR from the fork unless the user explicitly
  asks for a branch on `origin`.
- No force-push. Do not commit `target/`, `$MDREAM_WORK`, or any agent scratch
  file. Commit the `tools/upstream` pointer and `tools/reference/Cargo.lock`
  with the port.

## Finish checklist

- [ ] One-shot differential: 0 mismatches on the full corpus.
- [ ] Harvested upstream literals: 0 mismatches one-shot and streaming.
- [ ] Streaming corpus: 0 mismatches at 1, 2, 3, 7, 64 and 4096.
- [ ] `./mvnw -q -o -pl mdream4j test` passes.
- [ ] `./mvnw -Pwith-reference verify` passes (needs the native bridge).
- [ ] Golden corpus regenerated; `cmp` against a fresh reference dump is clean;
      line counts match; the four shrink guards are raised.
- [ ] No `MD_DBG` / debug prints left in Java or Rust.
- [ ] Upstream pin, `README.md`, `AGENTS.md`, `docs/REFERENCE.md`,
      `docs/VERSIONING.md` and the `mdream.upstream.*` POM properties agree; no
      dated snapshot rewritten.
- [ ] `git status` shows only intended files (no `target/`, no scratch), and the
      submodule tree is clean.
- [ ] Commit signed off; PR opened from the fork.
