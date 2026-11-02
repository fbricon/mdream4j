# mdream4j

A fast, dependency-free HTML to Markdown converter for Java, ported from
[mdream](https://github.com/harlan-zw/mdream).

- **Byte-identical to mdream v2.0.3** (the parity contract)
- **Java 11** bytecode, **zero runtime dependencies**
- **GraalVM native-image** clean
- **JPMS** module and **OSGi** bundle metadata
- One-shot and streaming conversion, GFM output

Status: **scaffold**. The public API compiles and is reviewable; conversion is
implemented in milestones M1–M5 (see `PLAN.md`).

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

A reusable converter is available for repeated calls:

```java
MdreamConverter converter = new MdreamConverter(Options.defaults());
String markdown = converter.convert(html);
```

## Build

```shell
./mvnw verify
```

Requires JDK 11 or newer. The build compiles with `--release 11`.

## Consumers

Notes for the intended consumers (lemminx and eclipse.jdt.ls) are in
[`docs/CONSUMERS.md`](docs/CONSUMERS.md). They are not library commitments.

## License

MIT. mdream4j is an unofficial Java port of mdream, which is MIT licensed and
copyright (c) 2025 Harlan Wilton. See `LICENSE` and `LICENSE-mdream`.
