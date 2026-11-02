# mdream4j

A fast, dependency-free HTML to Markdown converter for Java, ported from
[mdream](https://github.com/harlan-zw/mdream).

- **Byte-identical to mdream 2.0.3** (the parity contract)
- **Java 11** bytecode and API floor, **zero runtime dependencies**
- **GraalVM native-image** clean: no configuration, no reflection, no FFM
- **JPMS** module and **OSGi** bundle metadata
- One-shot and streaming conversion, GFM output

Status: **0.1.0**. The conversion core is ported and verified against the Rust reference on a
1190-case corpus, for markdown, text, HTML, metadata and streaming at six chunk sizes.

## Usage

```java
import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.Options;

String markdown = HtmlToMarkdown.convert("<h1>Hello</h1><p>World</p>");

String wrapped =
    HtmlToMarkdown.convert(
        html,
        Options.builder().wrapWidth(80).build());
```

A reusable converter is available for repeated calls. It is immutable and safe to share between
threads; conversion state is per call:

```java
MdreamConverter converter = new MdreamConverter(Options.defaults());
String markdown = converter.convert(html);
```

### Streaming

```java
MarkdownStream stream = HtmlToMarkdown.newStream(Options.defaults());
for (String chunk : chunks) {
  out.append(stream.feed(chunk));
}
out.append(stream.finish());
```

Concatenating everything `feed` returns with the value of `finish` is byte-identical to one-shot
conversion of the concatenated input.

### Results and chunks

`convertResult` returns the markdown plus extracted elements, frontmatter and the truncation flag
(`Options.maxNodeBytes`). `Splitter` splits markdown into chunks with heading metadata, or converts
HTML and splits in one call.

`Options` covers `origin`, `clean`, `plugins`, `wrapWidth`, `maxNodeBytes` and the output format
(`markdown`, `text`, `html`). Plugin options are `isolateMain`, `tailwind`, `frontmatter`,
`filter`, `extraction` and `tagOverrides`.

## Compatibility

| Concern | Guarantee |
|---|---|
| Bytecode / API | Java 11 (`--release 11`) |
| Runtime dependencies | none |
| JPMS module | `io.github.fbricon.mdream4j` |
| OSGi | `Bundle-SymbolicName: io.github.fbricon.mdream4j`, `Export-Package` limited to the public API |
| GraalVM native-image | zero configuration; `--no-fallback` builds |
| Threading | no global state; converters are immutable, streams are per conversion |

The native-image smoke builds an image from the library and diffs its output with the JVM's for
three generated documents, including streamed conversions:

```shell
make native-smoke   # needs GraalVM CE 21 or 25 on PATH
```

On GraalVM CE 25.0.2 the `--no-fallback` build succeeds with no configuration and reaches the
first conversion in 0.9 ms.

## Build

```shell
./mvnw verify
```

Requires JDK 11 or newer. The build compiles with `--release 11` and uses the checked-in golden
corpus, so no Rust, Node or submodule checkout is needed.

The differential tests against the Rust reference are opt-in:

```shell
git submodule update --init --depth 1 tools/upstream
cargo build --release --manifest-path tools/reference/Cargo.toml
./mvnw -Pwith-reference verify
```

See [`docs/REFERENCE.md`](docs/REFERENCE.md) for the corpus and bridge details.

## Benchmarks

[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) compares mdream4j with the Rust reference, flexmark,
remark, copy_down, jsoup and xberg on real pages and synthetic worst cases. On real pages
mdream4j is 3–7× faster than the JVM contenders and within 0.4–1.4× of the Rust reference.

## Consumers

Notes for the intended consumers (lemminx and eclipse.jdt.ls) are in
[`docs/CONSUMERS.md`](docs/CONSUMERS.md). They are not library commitments.
## License

MIT. mdream4j is an unofficial Java port of mdream, which is MIT licensed and
copyright (c) 2025 Harlan Wilton. See `LICENSE` and `LICENSE-mdream`.
