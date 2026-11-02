#!/usr/bin/env node
// Regenerates the benchmark golden digests from the Rust reference.
//
// The benchmark asserts that mdream4j still produces the reference output for
// every real-world fixture. Contenders produce different (also valid) Markdown,
// so they only assert their own output is stable.
//
// Usage: node tools/bench/digests.mjs
//
// Requires the pinned submodule and a built reference dumper:
//   cargo build --release --manifest-path tools/reference/Cargo.toml
//   --bin mdream-golden

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../..");
const upstream = path.join(repo, "tools/upstream");

const fixtures = {
  nuxt: "crates/core/tests/fixtures/nuxt-example.html",
  "vuejs-docs": "crates/core/tests/fixtures/vuejs-docs.html",
  wikipedia: "crates/core/tests/fixtures/wikipedia-small.html",
  mdn: "crates/core/tests/fixtures/mdn-array.html",
  react: "crates/core/tests/fixtures/react-learn.html",
  github: "crates/core/tests/fixtures/github-markdown-complete.html",
  "wikipedia-xl": "crates/node/test/fixtures/wikipedia-largest.html",
};

const dumper = path.join(repo, "tools/reference/target/release/mdream-golden");
if (!fs.existsSync(dumper)) {
  console.error(`missing reference dumper: ${dumper}`);
  console.error(
    "build it with: cargo build --release --manifest-path tools/reference/Cargo.toml --bin mdream-golden",
  );
  process.exit(1);
}

const cases = Object.entries(fixtures).map(([id, relative]) => {
  const html = fs.readFileSync(path.join(upstream, relative), "utf8");
  return { id, html, options: {} };
});

const input = path.join(os.tmpdir(), "mdream4j-bench-cases.jsonl");
fs.writeFileSync(input, cases.map((entry) => JSON.stringify(entry)).join("\n") + "\n");
const golden = execFileSync(dumper, [input], { cwd: repo, maxBuffer: 64 * 1024 * 1024 })
  .toString();
fs.rmSync(input);

const outputs = new Map();
for (const line of golden.split("\n")) {
  if (line.trim() === "") {
    continue;
  }
  const entry = JSON.parse(line);
  outputs.set(entry.id, entry.outputs.markdown);
}

const properties = [];
for (const entry of cases) {
  const markdown = outputs.get(entry.id);
  if (markdown === undefined) {
    throw new Error(`reference produced no output for ${entry.id}`);
  }
  const bytes = Buffer.byteLength(entry.html, "utf8");
  const digest = createHash("sha256").update(markdown, "utf8").digest("hex");
  properties.push(`${entry.id}.sha256=${digest}`);
  properties.push(`${entry.id}.bytes=${bytes}`);
}

const target = path.join(repo, "mdream4j-bench/support/src/main/resources/bench-digests.properties");
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, properties.join("\n") + "\n");
console.log(`wrote ${cases.length} digests to ${path.relative(repo, target)}`);
