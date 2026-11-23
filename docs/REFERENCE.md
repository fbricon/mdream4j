# Reference bridge

`mdream4j-reference` is a **test-only** bridge to the upstream
[Rust mdream core](https://github.com/harlan-zw/mdream). It is never published and the library
never loads it.

It has three jobs:

1. Generate the checked-in golden file
   (`mdream4j/src/test/resources/golden/expected.jsonl`).
2. Differential testing: Java output must equal the reference output on the corpus and probes.
3. Benchmark baseline ("OG mdream") in the same JVM.

## Upstream pin

[`tools/upstream`](../tools/upstream) is a shallow submodule of
[mdream](https://github.com/harlan-zw/mdream) at
**[`63bfc0a`](https://github.com/harlan-zw/mdream/commit/63bfc0a6d86b5165833fd353d415415bf55cd561)**,
the [`v2.2.0` release](https://github.com/harlan-zw/mdream/releases/tag/v2.2.0). It carries every
commit merged since the previous pin,
[`9ea07b84`](https://github.com/harlan-zw/mdream/commit/9ea07b841c29f7d26ebb4122829ecb3201265bc3)
(2.0.3 plus thirteen post-release fixes), and the port covers that whole range.

The project version records this release as its first three segments (`2.2.0.N`); see
[`VERSIONING.md`](VERSIONING.md) for the scheme and the port-revision rules.

```shell
git submodule update --init --depth 1 tools/upstream
cd tools/upstream
git fetch --depth 1 origin 63bfc0a6d86b5165833fd353d415415bf55cd561
git checkout 63bfc0a
```

This section is the canonical record of the pin; `README.md` and `AGENTS.md` link here.

## Build

```shell
cargo build --release --manifest-path tools/reference/Cargo.toml
```

Produces the cdylib the Java tests load:

- `tools/reference/target/release/libmdream_reference.dylib` (macOS)
- `tools/reference/target/release/libmdream_reference.so` (Linux)
- `tools/reference/target/release/mdream_reference.dll` (Windows)

Run the Java bridge tests with:

```shell
./mvnw -Pwith-reference verify
```

Tests are skipped with an assumption when the native library is missing.

## Regenerate the corpus and goldens

```shell
node tools/corpus/extract.mjs
cargo run --quiet --release --manifest-path tools/reference/Cargo.toml \
  --bin mdream-golden -- tools/corpus/cases.jsonl > mdream4j/src/test/resources/golden/expected.jsonl
```

`tools/corpus/extract.mjs` pulls inputs from the upstream TypeScript conversion corpus and the
upstream HTML fixture pages into `tools/corpus/cases.jsonl`. Expected outputs are always produced
by the Rust reference, never hand-written, into
`mdream4j/src/test/resources/golden/expected.jsonl`.

The golden file is checked in; normal builds do not need Rust, Node or the submodule.

## Splitter differential

`splitter-dump` reads `{markdown, options}` JSONL on stdin and prints the reference chunks as
JSON, so the Java `Splitter` can be compared on the same inputs:

```shell
cargo run --quiet --release --manifest-path tools/reference/Cargo.toml \
  --bin splitter-dump < cases.jsonl
```

It is a manual parity aid. `SplitterTest` ports the 31 upstream unit tests and needs no Rust.

## Options JSON

The bridge accepts the same shape as the Java API:

```json
{
  "origin": "https://example.com",
  "wrapWidth": 80,
  "maxNodeBytes": 65536,
  "format": "markdown",
  "clean": { "urls": true, "emptyLinks": true },
  "plugins": {
    "isolateMain": true,
    "tailwind": true,
    "frontmatter": { "additionalFields": { "author": "Ada" }, "metaFields": ["keywords"] },
    "filter": { "exclude": ["nav", "footer"], "processChildren": false },
    "extraction": { "selectors": ["h1", ".summary"] },
    "tagOverrides": {
      "sup": { "enter": "^", "exit": "^", "isInline": true }
    }
  }
}
```

`format` is `markdown` (default), `text` or `html`. Unknown fields are ignored.
