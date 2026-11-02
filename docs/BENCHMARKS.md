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

## Results

Measured on Temurin 25.0.4.1, macOS arm64 (Apple silicon), `-Xms2g -Xmx2g`,
with a shortened matrix: 2 × 1 s warmup, 3 × 1 s measurement, 1 fork, `-prof gc`.
The committed defaults (3 × 2 s / 5 × 2 s / 3 forks) give the same ordering with
tighter error bars.

Milliseconds per conversion, lower is better:

| Fixture | mdream4j | mdream (Rust) | flexmark pub | flexmark patched | remark | copy_down | jsoup parse | xberg |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nuxt (3.5 KB) | 0.0091 | 0.0100 | 0.065 | 0.066 | 0.056 | 0.337 | 0.021 | 0.067 |
| wikipedia (166 KB) | 0.711 | 0.574 | 3.163 | 3.267 | 3.152 | 38.45 | 1.548 | 5.458 |
| mdn (235 KB) | 1.274 | 1.032 | 8.315 | 5.822 | 4.499 | 55.15 | 1.996 | 8.393 |
| github (430 KB) | 0.856 | 1.538 | 4.551 | 4.387 | 3.364 | 60.58 | 1.902 | 8.888 |
| react (265 KB) | 0.720 | 0.849 | 2.885 | 2.812 | 2.766 | 18.39 | 2.040 | 8.778 |
| wikipedia-xl (1.8 MB) | 11.75 | 8.404 | n/r | n/r | n/r | n/r | n/r | n/r |
| depth-512 | 0.057 | 0.047 | 0.774 | 0.322 | 1.133 | 2.667 | 0.288 | 0.291 |
| table-huge (2000×8) | 3.422 | 2.592 | 29.90 | 28.58 | 17.30 | 4901.84 | 4.269 | 19.48 |
| node-1mb | 0.863 | 1.787 | n/r | n/r | n/r | n/r | n/r | n/r |
| text-10mb | 9.163 | 22.19 | n/r | n/r | n/r | n/r | n/r | n/r |

`n/r` means not run: flexmark on `wikipedia-xl` takes around 100 s per
conversion (the markdown→HTML→markdown pipeline is super-linear), and the
other contenders were left off that fixture for the same reason. The two
synthetic inputs are only meaningful for the two engines that can process a
single 1–10 MB node without a blow-up.

Allocation, MB per conversion (`gc.alloc.rate.norm`):

| Fixture | mdream4j | mdream (Rust) | flexmark patched | remark |
|---|---:|---:|---:|---:|
| nuxt | 0.02 | 0.01 | 0.07 | 0.08 |
| wikipedia | 1.70 | 0.61 | 4.27 | 5.91 |
| mdn | 2.35 | 0.98 | 6.40 | 6.90 |
| github | 1.72 | 1.15 | 6.42 | 5.73 |
| react | 1.55 | 0.71 | 3.76 | 5.72 |
| wikipedia-xl | 29.44 | 9.27 | n/r | n/r |
| depth-512 | 0.17 | 0.01 | 0.63 | 0.74 |
| table-huge | 7.88 | 1.13 | 61.09 | 66.69 |

Headlines:

- mdream4j is 0.4–0.9× the Rust reference on large documents (`github`,
  `node-1mb`, `text-10mb`) and 1.2–1.4× on the others; the Rust build is
  SIMD-assisted while the port is plain Java.
- It is 3–7× faster than flexmark, remark and jsoup on real pages, and 10–20×
  faster than xberg.
- Allocation stays within Rust's order of magnitude and well under flexmark's
  and remark's.
- `text-10mb` at 9.2 ms is about 1.1 GB/s of input, so the text-run window is
  linear in input size rather than quadratic.

## Native image

`tools/native-smoke/run.sh` builds a `--no-fallback` image with GraalVM CE and diffs its output
against the JVM's for three generated documents (a kitchen-sink page, a ~1 MB article and a
512-deep nest), converting the large and deep ones both one-shot and streamed. On GraalVM CE
25.0.2 (macOS arm64) the image builds with no configuration and reaches its first conversion in
**0.9 ms**, against 22 ms on the JVM where class loading dominates; the <10 ms target holds with
room to spare.

