# Benchmarks

JMH benchmarks for mdream4j and its contenders live in `mdream4j-bench/`. They are
profile-gated: `./mvnw verify` never builds or runs them. This file is the canonical
record of the results; `README.md` summarises it.

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

Run the matrix on JDK 11 and 25. `xberg` and the Rust reference only load on 25, so
the results below are from 25; the JDK 11 target below still applies to the library.

## Targets

| Target | Threshold |
|---|---|
| 166 KB page, warm, JDK 11 | ≤1.5× Rust (~0.5 ms), ≥330 MB/s |
| 1.8 MB page | no super-linear growth |
| Allocation | ≤ flexmark's B/op on the same fixture |
| Streaming (64 KiB) | ≤1.3× one-shot |
| Native-image startup | <10 ms to first conversion |

## Results

Measured on Temurin 25.0.5+8, macOS arm64 (Apple silicon), `-Xms2g -Xmx2g`,
with a shortened matrix: 2 × 1 s warmup, 3 × 1 s measurement, 1 fork, `-prof gc`.
The committed defaults (3 × 2 s / 5 × 2 s / 3 forks) give the same ordering with
tighter error bars.

Milliseconds per conversion, lower is better:

| Fixture | mdream4j | mdream (Rust) | flexmark pub | flexmark patched | remark | copy_down | jsoup parse | xberg |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nuxt (3.5 KB) | 0.0097 | 0.0098 | 0.065 | 0.064 | 0.056 | 0.333 | 0.020 | 0.064 |
| wikipedia (166 KB) | 0.711 | 0.570 | 2.884 | 2.859 | 3.137 | 37.48 | 1.325 | 5.270 |
| mdn (235 KB) | 1.072 | 0.973 | 5.917 | 5.763 | 4.395 | 52.89 | 1.724 | 8.103 |
| github (430 KB) | 0.903 | 1.446 | 4.469 | 4.237 | 3.270 | 58.84 | 1.826 | 8.627 |
| react (265 KB) | 0.737 | 0.843 | 2.852 | 2.744 | 2.693 | 18.19 | 1.975 | 8.349 |
| wikipedia-xl (1.8 MB) | 11.88 | 8.391 | n/r | n/r | n/r | n/r | n/r | n/r |
| depth-512 | 0.0582 | 0.0440 | 0.647 | 0.310 | 1.156 | 2.563 | 0.271 | 0.295 |
| table-huge (2000×8) | 3.711 | 2.636 | 27.06 | 26.16 | 16.89 | 4557.71 | 4.040 | 18.41 |
| node-1mb | 0.924 | 1.769 | n/r | n/r | n/r | n/r | n/r | n/r |
| text-10mb | 9.795 | 21.08 | n/r | n/r | n/r | n/r | n/r | n/r |

`n/r` means not run: flexmark on `wikipedia-xl` takes around 100 s per
conversion (the markdown→HTML→markdown pipeline is super-linear), and the
other contenders were left off that fixture for the same reason. The two
synthetic inputs are only meaningful for the two engines that can process a
single 1–10 MB node without a blow-up.

Allocation, MB per conversion (`gc.alloc.rate.norm`):

| Fixture | mdream4j | mdream (Rust) | flexmark patched | remark |
|---|---:|---:|---:|---:|
| nuxt | 0.02 | 0.01 | 0.07 | 0.08 |
| wikipedia | 1.65 | 0.61 | 4.24 | 5.94 |
| mdn | 2.34 | 0.98 | 6.35 | 6.90 |
| github | 1.69 | 1.15 | 6.42 | 5.72 |
| react | 1.53 | 0.71 | 3.76 | 5.72 |
| wikipedia-xl | 28.84 | 9.27 | n/r | n/r |
| depth-512 | 0.17 | 0.01 | 0.63 | 0.74 |
| table-huge | 7.88 | 1.13 | 61.20 | 65.65 |

Headlines:

- mdream4j is 0.46–0.87× the Rust reference on the large documents
  (`text-10mb`, `node-1mb`, `github`, `react`) and 1.0–1.4× on the others; the
  Rust build is SIMD-assisted while the port is plain Java.
- It is 3.6–6.6× faster than flexmark and remark on real pages, 1.6–2.7×
  faster than jsoup's parse floor, and 6.6–11× faster than xberg.
- Allocation stays within Rust's order of magnitude and well under flexmark's
  and remark's.
- `text-10mb` at 9.8 ms is about 1.0 GB/s of input, so the text-run window is
  linear in input size rather than quadratic.

## Native image

`tools/native-smoke/run.sh` builds a `--no-fallback` image with GraalVM CE and diffs its output
against the JVM's for three generated documents (a kitchen-sink page, a ~1 MB article and a
512-deep nest), converting the large and deep ones both one-shot and streamed. On GraalVM CE
25.0.2 (macOS arm64) the image builds with no configuration and reaches its first conversion in
**0.9 ms**, against 22 ms on the JVM where class loading dominates; the <10 ms target holds with
room to spare.

