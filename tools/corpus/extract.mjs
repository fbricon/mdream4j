#!/usr/bin/env node
// Builds tools/corpus/corpus.jsonl from upstream sources.
//
// The upstream corpus is a TypeScript array of HTML inputs; expected outputs
// come from the Rust reference, not from this script. Fixture pages are added
// as large-input cases.
//
// Usage: node tools/corpus/extract.mjs

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../..");
const upstream = path.join(repo, "tools/upstream");
const corpusPath = path.join(repo, "tools/corpus/corpus.jsonl");

const cases = [];

// 1. TypeScript conversion corpus: an array of HTML input strings.
const corpusTs = path.join(upstream, "packages/js/test/fixtures/conversion-corpus.ts");
const source = fs.readFileSync(corpusTs, "utf8");
const transformed = source.replace(
  /export\s+const\s+CONVERSION_CORPUS\s*:\s*string\[\]\s*=/,
  "export default",
);
const modulePath = path.join(here, ".conversion-corpus.mjs");
fs.writeFileSync(modulePath, transformed);
try {
  const { default: inputs } = await import(`${modulePath}?v=${Date.now()}`);
  inputs.forEach((html, index) => {
    cases.push({ id: `js-corpus-${String(index + 1).padStart(3, "0")}`, html, options: {} });
  });
} finally {
  fs.rmSync(modulePath, { force: true });
}

// 2. Upstream fixture pages.
const fixturesDir = path.join(upstream, "crates/core/tests/fixtures");
for (const name of fs.readdirSync(fixturesDir).sort()) {
  if (!name.endsWith(".html")) {
    continue;
  }
  cases.push({
    id: `fixture-${name.replace(/\.html$/, "")}`,
    html: fs.readFileSync(path.join(fixturesDir, name), "utf8"),
    options: {},
  });
}

fs.mkdirSync(path.dirname(corpusPath), { recursive: true });
const out = cases.map((entry) => JSON.stringify(entry)).join("\n") + "\n";
fs.writeFileSync(corpusPath, out);

console.log(`wrote ${cases.length} cases to ${path.relative(repo, corpusPath)}`);
