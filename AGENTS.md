# AGENTS.md

Guidance for coding agents working in this repository.

## What this is

`mdream4j` is a dependency-free, GraalVM-native-clean Java 11 HTML-to-Markdown
converter. It is a 1:1 port of the Rust [mdream](https://github.com/harlan-zw/mdream)
core, not an independent implementation.

Parity contract: byte-identical Markdown, text, HTML and metadata output, one-shot
and streamed, with three documented deviations:

- lone surrogates are replaced with U+FFFD;
- `wrapWidth` counts code points, not bytes;
- `maxNodeBytes` counts UTF-8 bytes.

The current upstream pin is
[`9ea07b84`](https://github.com/harlan-zw/mdream/commit/9ea07b841c29f7d26ebb4122829ecb3201265bc3),
described in [`docs/REFERENCE.md`](docs/REFERENCE.md).

## Commands

| Task | Command |
|---|---|
| Compile and run the test suite (offline) | `./mvnw -o -pl mdream4j test` |
| Full build | `./mvnw verify` |
| Parity tests against the Rust bridge | `./mvnw -Pwith-reference verify` |
| Build the library jar | `./mvnw -q -o -pl mdream4j -Dmaven.test.skip=true package` |
| Benchmarks | `make bench` |
| Native-image smoke | `make native-smoke` |
| Regenerate the golden corpus | `make goldens` |

The reference profile needs the submodule and a Rust toolchain:

```shell
git submodule update --init --depth 1 tools/upstream
cargo build --release --manifest-path tools/reference/Cargo.toml
./mvnw -Pwith-reference verify
```

## Layout

| Path | Purpose |
|---|---|
| `mdream4j/src/main/java/io/github/fbricon/mdream4j/` | Public API; only this package is exported. |
| `mdream4j/src/main/java/.../internal/` | Parser, state machine, output emitters. Not API. |
| `mdream4j/src/test/java/` | JUnit 5 tests, including corpus parity tests. |
| `mdream4j/src/test/resources/golden/expected.jsonl` | Generated golden corpus, loaded from the test classpath. |
| `mdream4j-reference/` | Test-only bridge to the Rust reference; never published. |
| `mdream4j-bench/` | Benchmark harness; profile-gated, never published. |
| `tools/upstream/` | Read-only [upstream mdream](https://github.com/harlan-zw/mdream) submodule at the parity pin. |
| `tools/reference/` | Bridge to the [Rust reference](https://github.com/harlan-zw/mdream) plus the `mdream-golden` and `stream-golden` dumpers. |
| `tools/corpus/` | Corpus extractor and the extracted `cases.jsonl` inputs. |

## Non-negotiable rules

1. **Port, do not invent.** Match [upstream](https://github.com/harlan-zw/mdream)
   control flow, state names and edge-case behaviour exactly. If upstream does
   something odd, reproduce it.
2. **No runtime dependencies.** In the published `mdream4j` module, JUnit is the
   only test dependency. Do not add a compile or runtime dependency. Any benchmark
   or fuzz harness is profile-gated and never published.
3. **Java 11 only.** The build uses `--release 11`; do not use newer APIs.
4. **Stay native-image clean.** No reflection, dynamic proxies, JNI in the library,
   or classpath scanning.
5. **Never hand-edit `expected.jsonl`.** Generate it from the Rust reference with
   `make goldens`. A test asserts the corpus does not shrink (currently 1,218 cases).
6. **No debug residue.** Remove `MD_DBG`, `System.err`, `eprintln!` and temporary
   tracing before committing.
7. **Keep parity in both modes.** Any output change must hold for one-shot conversion
   and for every streamed chunk size (1, 2, 3, 7, 64, 4096).

## Style

- 2-space indentation, no tabs, roughly 100 columns.
- 4-space continuation indent; one parameter per line when a signature does not fit.
- Javadoc on every public type and method; use `{@code ...}` for literals.
- New Java files start with the MIT header:

  ```java
  /*
   * Copyright (c) 2026 mdream4j contributors.
   *
   * Licensed under the MIT License. See LICENSE in the project root.
   */
  ```

- Tests use JUnit 5, descriptive method names and one behaviour per test.
  Parameterize per corpus case (`@ParameterizedTest(name = "{0}")`) instead of
  looping inside a single test.
- Package-private by default inside `internal`.

## Parity workflow

The `mdream-backport` skill is the full runbook. Load
[`.agents/skills/mdream-backport/SKILL.md`](.agents/skills/mdream-backport/SKILL.md)
when syncing an upstream commit, diagnosing a corpus mismatch, or adding a
differential probe.

The skill's **Finish checklist** is authoritative. In this repository it means:

- [ ] `./mvnw -o -pl mdream4j test` and `./mvnw -Pwith-reference verify` pass.
- [ ] One-shot and streaming differentials report 0 mismatches.
- [ ] No debug instrumentation left behind.
- [ ] Upstream pin, `README.md` and `docs/REFERENCE.md` agree.

When bumping the pin, regenerate the goldens in the same change.
`docs/REVIEW-2026-11.md` is a dated snapshot; do not rewrite it.

## Commits

- Use Conventional Commits, for example `fix(parity): ...`, `test(parity): ...`,
  `docs: ...`, `chore(skills): ...`.
- Sign off every commit: `git commit --signoff`.
- Do not force-push.
- Do not commit build output (`target/`) or agent scratch files.
