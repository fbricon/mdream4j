# mdream4j — implementation plan

Status: proposal, 2026-11-02 (rev 2).
Companion research: [`REPORT.md`](./REPORT.md) (FFM mechanics and measurements).
Port source: `mdream` npm **2.0.3**, the released state at `55937d7c` (MIT). Note: the
`v2.0.3` git tag (`a7a8802`) is 66 commits earlier and is **not** the parity target.

**Rev 2 changes:** byte-identical parity is now a hard requirement; Java **11** is the bytecode
baseline; **GraalVM native-image** is a hard requirement; consumer analysis for **lemminx** and
**eclipse.jdt.ls** added. Two of these constraints independently kill the FFM option.

**Rev 3:** **GFM output is accepted for both consumers.** No remark/flexmark dialect emulation, no
`OutputStyle` compatibility profile. Consumer golden tests are re-based to GFM output.

**Rev 4:** consumer implementation and migration details moved out of this plan into
[`docs/CONSUMERS.md`](./docs/CONSUMERS.md). §8 is now purely the **API surface and guarantees**
the library must expose so that lemminx and eclipse.jdt.ls can migrate on their own schedule.

---

## 1. TL;DR

**Build a pure-Java port of mdream's Rust core. Target Java 11 bytecode. Keep it
native-image-clean. Use FFM only for a test-only reference bridge.**

The new constraints settle the architecture:

| Constraint | Consequence |
|---|---|
| **Byte-identical to mdream v2.0.3** | Port 1:1, function for function; golden corpus + differential fuzzing |
| **Java 11** | Rules out FFM (needs 22+) and records/sealed/pattern-matching. Hand-written builders. |
| **GraalVM native-image** | Rules out reflection, ServiceLoader, dynamic proxies, JNI/FFM, runtime resource loading |
| **lemminx** (Java 11 + native-image) | Smallest real surface (one wrapper class); validates the API and the native-image story |
| **eclipse.jdt.ls** (Java 21, no native-image) | Larger wrapper (custom flexmark renderers); migrates independently, tracked in `docs/CONSUMERS.md` |

Measured transcode costs mean a Java port can be fast, not merely "acceptable":

| Operation (166 KB page) | Cost |
|---|---|
| `String.getBytes(UTF_8)` + `new String(byte[], UTF_8)` | **0.142 ms** |
| `String.toCharArray()` + `new String(char[])` | **0.008 ms** |
| Rust mdream, entire conversion | ~0.34 ms |

A `char[]`-based core avoids ~40% of the budget before parsing. That is why the port can
plausibly reach 1–1.5× Rust rather than 3–5×.

---

## 2. Decision: port vs. FFM

FFM is now **structurally excluded**, not merely disfavoured:

1. `java.lang.foreign` is final in Java 22. It cannot run on Java 11. A multi-release JAR
   (base 11 + `META-INF/versions/22`) could hide it, but then lemminx on 11 silently gets a
   different engine than jdt.ls on 21 — untestable parity, two code paths.
2. GraalVM native-image + FFM + an embedded per-platform `.dylib`/`.so` is hostile: native access
   flags, dlopen at runtime, no build-time closure, and the library would ship machine code inside
   an image that is supposed to be self-contained. lemminx already builds a native image with
   `--no-fallback`; adding FFM there is a regression.
3. `io.xberg:html-to-markdown:3.19.1` already ships the FFM design: **15 MB JAR**, 6 bundled
   natives, **Jackson** (despite a "zero-dependency" README), a JSON round-trip per call, no
   streaming, no `module-info`, no Android. That is the shape of the competition, and it is a
   shape we should not copy.

**FFM survives only as `mdream4j-reference`** — a test-scope Rust `cdylib` + Java bridge used to
prove byte equality and to benchmark "OG mdream" in-process. It is never published.

---

## 3. Scope

### 3.1 In scope (v1.0)

- One-shot: `HTML → Markdown | plain text | allowlisted HTML`
- Streaming with byte-exact parity to one-shot
- Full GFM: ATX headings, nested/ordered lists, task lists, fenced code with info strings,
  dynamic backtick runs, pipe tables with alignment/colspan, blockquotes, hard breaks, escaping,
  link/image rules
- Parser quirks: implied end tags, rawtext, table/select/head recovery, depth cap 512,
  malformed-markup recovery
- Options: `origin`, `clean` (7 flags), `wrapWidth`, `maxNodeBytes`, `format`
- Declarative plugins: `frontmatter`, `isolateMain`, `tailwind`, `filter`, `extraction`,
  `tagOverrides`
- Splitter + chunk metadata
- **Zero runtime dependencies**, `module-info` + OSGi headers, **Java 11 bytecode**,
  **GraalVM native-image clean**, no preview features

### 3.2 Out of scope

- Generic hook-plugin SPI (mirror Rust's closed plugin set)
- `llms.txt`, content negotiation, crawler, Vite/Nuxt integrations
- Android-specific artifacts (the plain JAR already runs there)
- JS-only options (`literalEnter/Exit`, `'#cdata-section'` overrides, frontmatter callbacks)
- **Dialect emulation**: no remark- or flexmark-compatible output profiles. Consumers accept GFM
  output (decided, rev 3).

### 3.3 The parity contract

**Byte-identical output to mdream `v2.0.3` on the full conformance corpus**, with exactly three
documented deviations, each covered by a test:

1. Unpaired UTF-16 surrogates → U+FFFD (Rust cannot receive them).
2. `wrapWidth` counts Unicode code points, not UTF-16 units (`codePointCount`).
3. `maxNodeBytes` counts **UTF-8 bytes**, not chars.

Everything else that differs is a bug.

---

## 4. Runtime constraints

### 4.1 Java 11 baseline

Compile with `--release 11` on JDK 25. Language/API rules:

| Allowed | Forbidden |
|---|---|
| `var`, diamond, `List.of`/`Map.of`, `String.isBlank/strip/repeat/lines`, `StandardCharsets`, `CharsetDecoder`, `Arrays.mismatch`, `VarHandle` | records, sealed types, pattern matching for `switch`/`instanceof`, text blocks, `Stream.toList()`, `HexFormat`, `java.lang.foreign` |

Consequences for the API:

- `Options` is a **final class with a nested `Builder`**, not a record. Hand-written
  `equals`/`hashCode`/`toString` (or a small generated-source step; no runtime deps).
- No sealed `OutputFormat`; use a plain `enum` (closed by construction).
- No `switch` patterns in `OutputOps`; use tag-id `switch` on `int` plus the handler table
  (which is what the Rust code does anyway).
- `StringBuilder` and `char[]` behaviour is identical on 11 — the compact-string optimisations
  landed in 9. The `char[]` design (§5.2) is unaffected.
- **Test matrix:** Temurin 11, 17, 21, 25. Benchmarks are reported on **11 (the floor) and 25**,
  because C2 differs and the consumer runs on 11.
- The **reference module** compiles with `--release 25` and is only built under `-Pwith-reference`.
  The library module must never see `java.lang.foreign`.

### 4.2 GraalVM native-image

The library must contribute **zero** configuration. Rules, enforced by a CI smoke image:

- No reflection, no `Class.forName`, no `ServiceLoader`, no `Proxy`, no `MethodHandles` lookups.
- No JNI/FFM, no `System.loadLibrary`.
- No runtime resource loading (no `getResourceAsStream`), no `ResourceBundle`.
- Static initialisers must be **deterministic and side-effect free**: no `System.getProperty`,
  `Locale.getDefault`, `TimeZone`, `Files`, `Random`, or network. Pure table construction in
  `<clinit>` is fine and will be initialised at build time.
- `StandardCharsets.UTF_8` only; never `Charset.forName` at runtime.
- No `java.util.regex` (also a perf rule), no `String.format`, no `MessageFormat`.
- No `ThreadLocal` required; per-call state. (Supported either way, but avoid it for isolates.)
- Do not add `native-image.properties` unless a real need appears — an empty config is the goal.

**GraalVM + Java 11 nuance:** GraalVM for JDK 11 is EOL. Build native images with GraalVM CE
21/25; class files at major version 55 are accepted. So "Java 11" is a *bytecode and API* floor,
not a build-runtime requirement.

**Acceptance gate:** a CI job that `native-image`s a 30-line sample converting the three fixtures
and compares output hashes. A consumer-side native build (lemminx's `native` profile) is a useful
downstream smoke test but is owned by that repository.

### 4.3 OSGi / JPMS

Both consumers are OSGi-based:

- Ship `module-info.java` (release 11) **and** OSGi manifest headers generated by
  `bnd-maven-plugin` / `maven-bundle-plugin`:
  `Bundle-SymbolicName: io.github.fbricon.mdream4j`, `Export-Package` (public API only),
  `Bundle-RequiredExecutionEnvironment: JavaSE-11`, `Automatic-Module-Name` for tools that
  ignore `module-info`.
- No `META-INF/services`, no fragments, no `DynamicImport-Package`.
- Verify with `jdeps --check` and `bnd` baseline in CI.

---

## 5. Architecture

### 5.1 Maven multi-module layout

```
mdream4j/
├── pom.xml                       # parent + Maven Wrapper (mvnw) checked in
├── mdream4j/                     # published: io.github.fbricon:mdream4j
│   ├── src/main/java/module-info.java
│   ├── src/main/java/io/github/fbricon/mdream4j/
│   │   ├── HtmlToMarkdown.java        # facade: convert / convertResult / newStream
│   │   ├── MarkdownStream.java        # feed / finish / truncated
│   │   ├── MdreamConverter.java       # reusable, thread-safe
│   │   ├── Options.java               # final class + Builder (Java 11)
│   │   ├── Clean.java, Plugins.java, Filter.java, Frontmatter.java, Extraction.java,
│   │   │   TagOverride.java, IsolateMain.java, Tailwind.java
│   │   ├── OutputFormat.java          # enum
│   │   ├── MdreamResult.java, ExtractedElement.java
│   │   ├── Splitter.java, MarkdownChunk.java, ChunkMetadata.java
│   │   └── internal/                  # not exported
│   │       ├── Consts.java, TagHandler.java, Tags.java
│   │       ├── Entities.java, EntityTable.java
│   │       ├── Scan.java, Url.java, Selector.java, Tailwind.java
│   │       ├── Node.java, Attributes.java, NodeStack.java
│   │       ├── ConvertState.java, ParseOps.java, OutputOps.java,
│   │       │   HtmlOutputOps.java, PluginOps.java, Buffer.java
│   ├── src/test/java/                 # JUnit 5 (Java 11-compatible)
│   └── src/test/resources/golden/     # JSONL conformance corpus
├── mdream4j-reference/            # test-scope, release 25, never published
├── mdream4j-bench/                # JMH 1.37, one module/profile per contender
├── mdream4j-cli/                  # optional; hyperfine table
└── docs/                          # methodology, parity report, ADRs
```

### 5.2 Internal representation: `char[]`, not `byte[]`

Deliberate deviation from Rust; the most important performance decision. See the measurements in
§1. Design rules:

- Everything internal is `char` + offset/length. No `substring` in hot paths.
- `convert(String)`: `String.getChars(0, n, reuseBuf, 0)` into a reusable `char[]`.
- `CharSequence` overloads scan with `charAt` (keep call sites monomorphic).
- Byte input (`InputStream`): `CharsetDecoder` with a 4-byte carry, mirroring
  `crates/edge::process_chunk_bytes`.
- Output: a hand-rolled `Buffer` (`char[]` + `len`, `System.arraycopy` rewrites), because
  `StringBuilder` cannot do the in-place rewrite the blockquote/heading/fence code needs.
- `maxNodeBytes` still counts UTF-8 bytes; `wrapWidth` counts code points.
- No regex anywhere in the hot path.

Kill criterion (S0 spike): if `char[]` is not ≥20% faster end-to-end than `byte[]` on the real
fixtures, adopt `byte[]` and the Rust model 1:1.

### 5.3 Porting order (dependency-first, always compiling)

| Stage | Modules | Gate |
|---|---|---|
| S1 | `Consts`, `Tags`, `Attributes`, `Node`, `NodeStack`, `Entities` | table + entity unit tests match Rust |
| S2 | `Scan` | tokenizer trace matches Rust on malformed/partial tags |
| S3 | `Url`, `Selector`, `Tailwind` | table-driven parity |
| S4 | `ParseOps` | enter/exit/text/drop **event trace** matches Rust on all fixtures |
| S5 | `OutputOps` (5.8k LOC — the long pole) | byte equality on all Markdown goldens |
| S6 | `ConvertState` + `HtmlToMarkdown` + `MarkdownStream` | stream/one-shot parity at every chunk size |
| S7 | `Splitter` | chunk count, content, metadata, overlap |
| S8 | `HtmlOutputOps`, text format | format parity |

Port function-for-function in upstream order. Do not reorder branch checks, replace tables with
`switch`/regex, or "improve" the linear attribute scan. `NodeStack` retires nodes rather than
removing them — do not model it with `ArrayList.remove(last)`.

---

## 6. Quality strategy

### 6.1 Golden corpus (checked in; no Rust required to build)

A Rust dumper (`mdream-golden`) reads `{id, html, options}` JSONL and writes expected
`markdown`/`text`/`html` + `truncated`. Sources:

1. `crates/core/tests/conversion.rs` — 374 tests, **664 assertions**
2. `packages/js/test/fixtures/conversion-corpus.ts` — 150 input/expected pairs
3. `crates/core/tests/fixtures/*` — 8 pages, 3 KB → 1.8 MB
4. `streaming_drain.rs` (133), `max_node_bytes.rs` (103), `depth_limit.rs` (27),
   `implied_end_tags.rs` (22), `whitespace_breaks.rs` (19), `splitter.rs` (31)

Output: `golden/*.jsonl`, ~1,100 cases. Regeneration is a maintainer task (`make goldens`).

### 6.2 Differential fuzzing

`mdream4j-reference` + Jazzer:

- **Reference fuzz**: random/mutated HTML → Java output == Rust output, `truncated` equal.
- **Streaming invariant**: chunk sizes `{1,2,3,7,64,4096,random}` concatenate to one-shot.
- **Drain transparency**: port `new_drain_disabled()` and assert on/off equality.
- **Cap parity**, **overflow parity** (depth 511/512/513, all three overflow modes).

Short budget per PR, 30-minute soak nightly. Corpus committed on failure.

### 6.3 Targeted suites

Entities (all names + legacy), URLs (resolve/tracking/slugify/`<base>`), tables
(colspan/rowspan/empty/block-in-cell/alignment/column cap), lists (`start`, indentation,
empty-item hazards), `pre`/`code` (fence length, info sanitization), blockquotes (nesting, the
8 KiB flush).

### 6.4 Read-bound invariants

Port the upstream test counters (`row_line_scanned`, `quoted_bytes`, `break_run_scanned`,
`code_span_closed_bytes`, `heading_run_bytes_read`). They catch accidental quadratic rescans —
the usual way a naive port dies on 1.8 MB inputs.

### 6.5 Consumer-shaped API tests

The conformance corpus is library-owned. Add a small **consumer-shaped** suite (fragments without
`<html>`, Javadoc-style tables, `file:` links, sloppy markup, concurrent calls) that exercises the
guarantees in §8.2. Consumer migration is tracked separately in `docs/CONSUMERS.md`.

---

## 7. Benchmark plan

### 7.1 Contenders

| # | Contender | Coordinates / invocation | Isolation |
|---|---|---|---|
| 1 | **mdream4j** | local | own JVM |
| 2 | flexmark (as published) | `flexmark-html2md-converter:0.64.8` + jsoup 1.15.4 | own JVM |
| 3 | flexmark (patched) | same + jsoup 1.23.2 | own JVM |
| 4 | remark | `com.kotcrab.remark:remark:1.2.0` + jsoup 1.9.2 + commons-lang3 3.4 | own JVM |
| 5 | xberg Java (FFM) | `io.xberg:html-to-markdown:3.19.1` + Jackson 2.22.4 | own JVM |
| 6 | copy_down | `io.github.furstenheim:copy_down:1.1` | own JVM |
| 7 | jsoup floor | `org.jsoup:jsoup:1.23.2` — `parse`, `parse().text()` | own JVM |
| 8 | OG mdream — Rust | via `mdream4j-reference` (FFM) | own JVM |
| 9 | OG mdream — CLI | `hyperfine` (startup reported separately) | shell |
| 10 | OG mdream — NAPI/JS | `pnpm bench` upstream | Node |
| 11 | Tika 4 (optional) | `tika-core:4.2.0` + html module | own JVM |

Classpath isolation is mandatory: remark needs jsoup ≤1.14.3 (`Whitelist` removed in 1.15.1),
flexmark ≥1.15.4, xberg brings Jackson. One JMH process per contender, `-rf json`, merge.

### 7.2 Fixtures

`nuxt` 3.5 KB, `vuejs-docs` 112 KB, `wikipedia` 166 KB, `mdn` 235 KB, `react` 265 KB,
`github` 430 KB, `wikipedia-xl` 1.8 MB, plus synthetic (depth 512, huge table, 10 MB text,
1 MB single node).

### 7.3 JMH configuration

`@BenchmarkMode({AverageTime, Throughput})`, 3×2 s warmup, 5×2 s measurement, `@Fork(3)`,
`-Xms2g -Xmx2g -XX:+AlwaysPreTouch`, **`-prof gc`** (report B/op), converters built in
`@Setup(Level.Trial)`, `Blackhole.consume`, input from a field, and a **`@TearDown` SHA-256
assertion** against the golden output. Separate `SingleShotTime` run for cold start.

Run the whole matrix on **JDK 11 and 25**; headline numbers on 11.

### 7.4 Targets

| Target | Threshold |
|---|---|
| 166 KB page, warm, JDK 11 | ≤1.5× Rust (~0.5 ms), ≥330 MB/s |
| 1.8 MB page | no super-linear growth |
| Allocation | ≤ flexmark's B/op on the same fixture |
| Streaming (64 KiB) | ≤1.3× one-shot |
| Native-image startup | <10 ms to first conversion |

---

## 8. API requirements (driven by consumers)

Two consumers are known: **lemminx** (Java 11, GraalVM native image, untrusted schema/Javadoc
HTML) and **eclipse.jdt.ls** (Java 21, OSGi, Javadoc HTML). Their current implementations,
pre-processing needs, migration steps, expected output diffs and test re-basing are documented
separately in [`docs/CONSUMERS.md`](./docs/CONSUMERS.md). Those migrations are owned by the
consumer repositories; this plan only commits to the surface and guarantees they need.

### 8.1 Required public surface

| Requirement | API |
|---|---|
| One-shot conversion of a fragment or document | `HtmlToMarkdown.convert(String)` / `convert(CharSequence)` |
| Result metadata | `MdreamResult` (`markdown`, `truncated`, extracted elements) |
| Reusable, pre-configured converter | `new MdreamConverter(Options)`; immutable, thread-safe |
| Streaming with byte-identical output | `MarkdownStream.feed(CharSequence)` / `finish()`; `InputStream` overload with explicit charset |
| Options without records | `Options.builder()…build()` |
| Relative-link resolution | `Options.origin(String)` |
| Tracking-parameter cleaning | `Options.clean(Clean.…)` |
| Untrusted-input bounds | `Options.maxNodeBytes(long)` (UTF-8 bytes) |
| Line wrapping | `Options.wrapWidth(int)` (code points) |
| Output selection | `OutputFormat.MARKDOWN` / `TEXT` / `HTML` |
| HTML-shape mappings | `Options.tagOverrides(Map<String, TagOverride>)` |
| Structural extraction | `frontmatter`, `isolateMain`, `filter`, `extraction`, `tailwind` plugins |

### 8.2 Behavioural guarantees

- **Fragments are first-class.** No `<html>`/`<body>` wrapper required; unclosed and unbalanced
  tags are recovered, not rejected.
- **Total function.** Malformed HTML never throws; only `null` input is rejected, and that
  behaviour is documented.
- **Thread safety.** No global mutable state and no global lock. Either the stateless static facade
  or a converter holding an immutable `Options` snapshot is safe to use from concurrent LSP
  request threads.
- **Deterministic.** Same input + options ⇒ same output, independent of locale, timezone, platform
  or thread.
- **No trailing newline** is appended; output matches mdream byte-for-byte (§3.3).
- **UTF-16 in/out.** Unpaired surrogates become U+FFFD (documented deviation).
- **Byte input** is decoded with an explicit charset and a carry buffer so that streaming and
  one-shot output agree.
- **Not a sanitizer.** The library drops `<script>`/`<style>` content and unwraps unknown tags,
  but consumers keep their own jsoup sanitization. This is stated in Javadoc, not enforced.
- **URLs are opaque.** `file:`, `jdt:`, `eclipse-javadoc:` and other schemes pass through
  unchanged; only `origin` resolution and the opt-in `clean` rules rewrite them.
- **Java 11 bytecode, zero runtime dependencies, native-image clean, JPMS + OSGi metadata** (§4).

### 8.3 Explicit non-goals

- No remark/flexmark compatibility profile, no `OutputStyle`, no dialect switch.
- No HTML pre-processing helpers (`<tt>`→`<code>`, `<dl>` reshaping). Consumers use
  `tagOverrides` or their own jsoup pass.
- No LSP/markdown-rendering integration, no logging framework, no global registry.
- Consumer dependency removal, golden re-basing and release timing are not library milestones.

---

## 9. Work plan

| Milestone | Content | Calendar (1 dev + agents) |
|---|---|---|
| **M0 — Harness** | Repo, wrapper, CI (11/17/21/25), 11 benchmark contenders, reference bridge, golden dumper, native-image smoke, **API surface review against both consumer wrappers** | 2 weeks |
| **M1 — Walking skeleton** | S1–S4 minimal + S5 for core tags; one-shot + streaming; 100 goldens | 3 weeks |
| **M2 — Markdown parity** | full `OutputOps` (tables, lists, escaping, fences, wrapping, spacing); 664 assertions green | 5–6 weeks |
| **M3 — Streaming + caps** | chunk sweep, drain parity, `maxNodeBytes`, depth overflow | 3 weeks |
| **M4 — Options + plugins** | clean, frontmatter, isolateMain, filter, extraction, tailwind, tagOverrides | 3 weeks |
| **M5 — Formats + splitter** | text + HTML output, splitter | 2 weeks |
| **M6 — API freeze + 0.1** | Javadoc, examples, publish `docs/CONSUMERS.md`, OSGi/JPMS verification, 0.1.0 to Central | 1 week |
| **M7 — Hardening + 1.0** | Jazzer soak, perf tuning, docs, Central 1.0.0 | 3 weeks |

≈5 months to a thorough 1.0; a usable 0.1 after M2 (~3 months). Consumer migrations are separate
efforts in their own repositories and can start as soon as 0.1 is available.

### 9.1 Agent usage

**Parallelise:** leaf modules (`Consts`, `Tags`, `Entities`, `Url`, `Selector`, `Tailwind`,
`Splitter`), golden extraction, JUnit translation, benchmark modules, CI, docs, and independent
line-by-line review of ported functions.

**Never parallelise:** `ConvertState`, `ParseOps`, `OutputOps`, `processHtml`. They mutate the same
~180 fields; parallel ports produce semantic merge bugs that goldens catch too late. One driver,
Rust file open.

Every agent gets: the exact Rust file, the Java target file, the golden subset that must pass, and
the instruction to preserve branch order and magic constants.

### 9.2 Upstream sync

Pin the released `mdream` 2.0.3 state at `55937d7c` (the `v2.0.3` tag is 66 commits earlier).
Quarterly: checkout new tag → regenerate goldens → run Java suite → publish a behaviour diff.
Adopt one upstream PR per commit. Treat it as a fork with a sync process.

---

## 10. Build, CI, publishing

- **Toolchain:** JDK 25 compiler, `--release 11`, Maven Wrapper, JUnit 5, AssertJ, JMH 1.37
  (`-proc:full` on JDK 23+), Jazzer, bnd.
- **CI:** tests on Temurin `{11, 17, 21, 25}` × `{ubuntu, macos, windows}`; JMH on one pinned
  Linux runner; nightly fuzz; native-image smoke (GraalVM CE 21/25); `mvn -B verify` +
  checkstyle + `-Xlint:all -Werror`; `jdeps --check`; bnd baseline.
- **Publishing:** `io.github.fbricon:mdream4j` via Central Publisher Portal,
  `central-publishing-maven-plugin:0.12.0`, sources + javadoc + GPG, immutable GAVs.
- **Licensing:** MIT + `LICENSE-mdream` with upstream's text verbatim
  (`Copyright (c) 2025 Harlan Wilton`) inside the JAR at `META-INF/`, plus `THIRD-PARTY.md`
  ("mdream4j is a Java port of mdream v2.0.3, MIT"). No endorsement claims. Do not copy
  remark (Apache-2.0) or flexmark (BSD-2) code.

---

## 11. Immediate next steps (2 weeks)

1. **Scaffold**: `git init`, parent POM, wrapper, `module-info`, OSGi headers, CI, licences.
2. **Reference bridge**: Rust `mdream-reference` cdylib + Java FFM bridge; 100% equality on 8 fixtures.
3. **Goldens**: dumper → `golden/*.jsonl` (~1,100 cases), checked in.
4. **Benchmarks**: wire all 11 contenders; publish the baseline on JDK 11 and 25. This also
   produces the first real xberg number (their README figure is vendor-supplied).
5. **Native smoke**: `native-image` a sample; prove zero config.
6. **API review**: run the two consumer wrappers against the draft surface on paper (and a throwaway
   branch), record gaps in `docs/CONSUMERS.md`. No consumer code is modified in this plan.
7. **Representation spike (2 days)**: `char[]` vs `byte[]` end-to-end; kill criterion §5.2.

---

## 12. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `output.rs` port outruns estimates | High | High | Walking skeleton early; cut M5; goldens per function |
| Subtle output drift | Medium | High | Reference fuzzing + SHA gate in JMH |
| `char[]` slower than expected | Medium | Medium | 2-day spike with kill criterion |
| Java 11 JIT underperforms 25 | Medium | Medium | Benchmark both; report 11; optimise against 11 |
| GraalVM needs config after all | Low | Medium | Native smoke from day one; no reflection by design |
| A consumer need surfaces after API freeze | Medium | Medium | Surface review in M0 against both wrappers; `docs/CONSUMERS.md` as the gap log; freeze at M6 |
| Upstream drift | High | Medium | Pin + quarterly sync; fork semantics |
| OSGi/JPMS friction | Low | Medium | bnd + real module-info from day one; verify in CI |
| Naming / trademark | Low | Medium | `mdream4j`, explicit "unofficial port" |

---

## 13. Open decisions

1. **GroupId**: decided — `io.github.fbricon:mdream4j`. A future rename is an automated
   find/replace plus a republish; GAVs stay immutable per release.
2. **Text and HTML output formats**: v1.0 or v1.1? Recommend v1.1 unless a consumer needs them.

Consumer-specific decisions (lemminx `unescapeJava`, jdt.ls `<dl>`/`sup` handling, migration
timing, jsoup upgrade) live in [`docs/CONSUMERS.md`](./docs/CONSUMERS.md) §4 and do not block the
library.

---

## Appendix A — Why the earlier FFM verdict changed

`REPORT.md` §0 recommended FFM. Three facts overturned it:

1. `io.xberg:html-to-markdown` already ships that architecture (Java 25 FFM + Rust core +
   per-platform natives), is MIT, actively maintained, and has 15 language bindings. It is not a
   gap.
2. Their Java layer does a **JSON round-trip per conversion** and is 15 MB with Jackson — so the
   "FFM is faster/simpler" premise does not hold against a well-built Java port.
3. The new constraints — **Java 11** and **GraalVM native-image** — are incompatible with FFM.
   The port is now the only design that satisfies all three requirements simultaneously.

FFM remains the correct tool for the **reference bridge**, where exactness matters and none of its
downsides ship.
