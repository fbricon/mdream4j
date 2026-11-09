# Reference bridge

`mdream4j-reference` is a **test-only** bridge to the upstream Rust mdream core. It is never
published and the library never loads it.

It has three jobs:

1. Generate the checked-in golden file (`golden/expected.jsonl`).
2. Differential fuzzing: Java output must equal the reference output.
3. Benchmark baseline ("OG mdream") in the same JVM.

## Upstream pin

`tools/upstream` is a shallow submodule at **`9ea07b84`**, `mdream` 2.0.3 plus the thirteen
post-release fixes merged on `main` (issues #386, #420, #445–#451 and the workspace
dependency refresh). The npm `2.0.3` package state is `55937d7c`; the `v2.0.3` git tag
(`a7a8802`) is 66 commits earlier and is **not** the parity target.

```shell
git submodule update --init --depth 1 tools/upstream
cd tools/upstream
git fetch --depth 1 origin 9ea07b841c29f7d26ebb4122829ecb3201265bc3
git checkout 9ea07b84
```

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
  --bin mdream-golden -- tools/corpus/cases.jsonl > golden/expected.jsonl
```

`tools/corpus/extract.mjs` pulls inputs from the upstream TypeScript conversion corpus and the
upstream HTML fixture pages into `tools/corpus/cases.jsonl`. Expected outputs are always produced
by the Rust reference, never hand-written, into `golden/expected.jsonl`.

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
