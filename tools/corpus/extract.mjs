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

// 3. `maxNodeBytes` and element-depth caps. Every construct that buffers
// document bytes has its own cap path, so each one gets a dedicated case.
const cap = (id, html, maxNodeBytes) =>
  cases.push({ id, html, options: { maxNodeBytes } });
const words = "word ".repeat(300);
cap("cap-text-8", `<p>${words}</p>`, 8);
cap("cap-text-64", `<p>${words}</p>`, 64);
cap("cap-text-1024", `<p>${words}</p>`, 1024);
cap("cap-utf8", `<p>${"é".repeat(100)}</p>`, 10);
cap("cap-attr", `<p title="${"x".repeat(300)}">hi</p>`, 32);
cap("cap-tag", `<div class="${"y".repeat(300)}">hi</div>`, 32);
cap("cap-img", `<img src="/a.png" alt="${"z".repeat(300)}">`, 16);
cap("cap-code", `<p><code>${"c".repeat(200)}</code></p>`, 20);
cap("cap-pre", `<pre><code>${"p".repeat(200)}</code></pre>`, 20);
cap("cap-fence", `<pre><code class="js">${"q".repeat(200)}</code></pre>`, 20);
cap("cap-link", `<a href="/u">${"t".repeat(200)}</a>`, 20);
cap("cap-link-attr", `<a href="/${"u".repeat(200)}">text</a>`, 20);
cap(
  "cap-table",
  `<table><tr><th>A</th><th>B</th></tr><tr><td>${"d".repeat(200)}</td><td>2</td></tr></table>`,
  24,
);
cap("cap-comment", `<!-- ${"m".repeat(300)} --><p>after</p>`, 16);
cap("cap-script", `<script>${"s".repeat(300)}</script><p>after</p>`, 16);
cap("cap-style", `<style>${"s".repeat(300)}</style><p>after</p>`, 16);
cap("cap-many", `<p>${"a".repeat(50)}</p>`.repeat(20), 32);
// 600 nested divs pass MAX_ELEMENT_DEPTH; the tail is flattened, not dropped.
const deep = `${"<div>".repeat(600)}deep${"</div>".repeat(600)}`;
cap("cap-depth", deep, 0);
cap("cap-depth-cap", deep, 64);
cap("cap-custom", `<my-widget data-x="${"w".repeat(200)}">${"v".repeat(100)}</my-widget>`, 32);
cap("cap-heading", `<h1>${"h".repeat(200)}</h1>`, 20);
cap("cap-list", `<ul><li>${"l".repeat(200)}</li><li>b</li></ul>`, 20);
cap("cap-quote", `<blockquote><p>${"b".repeat(200)}</p></blockquote>`, 20);
cap("cap-raw", `<div>${`<span>${"r".repeat(100)}</span>`.repeat(10)}</div>`, 32);
cap("cap-entity", `<p>${"&amp;".repeat(100)}</p>`, 10);
cap("cap-select", `<select><option>${"o".repeat(100)}</option></select>`, 20);

fs.mkdirSync(path.dirname(corpusPath), { recursive: true });
const out = cases.map((entry) => JSON.stringify(entry)).join("\n") + "\n";
fs.writeFileSync(corpusPath, out);

console.log(`wrote ${cases.length} cases to ${path.relative(repo, corpusPath)}`);
