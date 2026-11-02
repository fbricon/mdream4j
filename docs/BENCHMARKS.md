# Benchmarks

JMH benchmarks for mdream4j and its contenders live in `mdream4j-bench/`. They are
profile-gated: `./mvnw verify` never builds or runs them.

## Contenders

| Module | Contender | JDK |
|---|---|---|
| `mdream4j` | mdream4j | 11+ |
| `flexmark-published` | flexmark 0.64.8 + jsoup 1.15.4 | 11+ |
| `flexmark-patched` | flexmark 0.64.8 + jsoup 1.23.2 | 11+ |
| `remark` | remark 1.2.0 + jsoup 1.9.2 | 11+ |
| `copy-down` | copy_down 1.1 | 11+ |
| `jsoup` | jsoup 1.23.2 `parse` / `text` floors | 11+ |
| `xberg` | xberg html-to-markdown 3.19.1 | 25 |
| `reference` | upstream mdream through the Rust reference bridge | 25 |

Each contender is a separate Maven module and therefore a separate JMH JVM. Classpath
isolation is mandatory: remark needs jsoup 1.9.2, flexmark needs 1.15.4, and a single JVM
cannot load both.

## Running

```shell
# every contender available on this JDK
tools/bench/run.sh

# one fixture, no fork, for a quick check
tools/bench/run.sh -p fixtureName=wikipedia -f 1 -wi 1 -i 1

# include the Rust baseline (JDK 25, built reference bridge)
WITH_REFERENCE=1 tools/bench/run.sh
```

`run.sh` builds with `-Pwith-bench`, runs every module's `target/benchmarks.jar` with
`-prof gc`, then merges the JSON results into one Markdown table.

The `reference` module needs the native bridge:

```shell
cargo build --release --manifest-path tools/reference/Cargo.toml
```

## Fixtures

The seven real documents come from the pinned `tools/upstream` submodule and are copied into
the support module at build time:

| Name | Size | Source |
|---|---:|---|
| `nuxt` | 3.5 KB | `nuxt-example.html` |
| `vuejs-docs` | 112 KB | `vuejs-docs.html` |
| `wikipedia` | 166 KB | `wikipedia-small.html` |
| `mdn` | 235 KB | `mdn-array.html` |
| `react` | 265 KB | `react-learn.html` |
| `github` | 430 KB | `github-markdown-complete.html` |
| `wikipedia-xl` | 1.8 MB | `wikipedia-largest.html` |

Four synthetic inputs are generated once per JVM: `depth-512`, `table-huge` (2000 rows × 8
columns), `node-1mb` and `text-10mb`.

## Golden gate

`bench-digests.properties` holds the SHA-256 of the Rust reference output for each real
fixture. `Mdream4jBenchmark` asserts it in `@Setup`, so a benchmark run also proves that the
optimized path still matches upstream. Regenerate after changing fixtures or options:

```shell
make bench-digests
```

Other contenders legitimately produce different Markdown, so they only assert that their
output is stable between setup and teardown.

## JMH configuration

`@BenchmarkMode({AverageTime, Throughput})`, 3 × 2 s warmup, 5 × 2 s measurement,
`@Fork(3)`, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, `-prof gc`. Converters are built in
`@Setup(Level.Trial)` and inputs are held in a field. Cold start is a separate
`SingleShotTime` benchmark with one invocation per fork.

Run the matrix on JDK 11 and 25; report headline numbers on 11. `xberg` and the Rust
reference only run on 25.

## Targets

| Target | Threshold |
|---|---|
| 166 KB page, warm, JDK 11 | ≤1.5× Rust (~0.5 ms), ≥330 MB/s |
| 1.8 MB page | no super-linear growth |
| Allocation | ≤ flexmark's B/op on the same fixture |
| Streaming (64 KiB) | ≤1.3× one-shot |
| Native-image startup | <10 ms to first conversion |
