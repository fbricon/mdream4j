#!/usr/bin/env node
// Merges the per-contender JMH JSON results into one Markdown table.
//
// Each contender runs in its own JVM and writes
// mdream4j-bench/<module>/target/jmh-result.json. Run tools/bench/run.sh first.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../..");
const bench = path.join(repo, "mdream4j-bench");

const labels = {
  Mdream4jBenchmark: "mdream4j",
  Mdream4jStreamingBenchmark: "mdream4j stream 64Ki",
  FlexmarkPublishedBenchmark: "flexmark (published jsoup)",
  FlexmarkPatchedBenchmark: "flexmark (patched jsoup)",
  RemarkBenchmark: "remark",
  CopyDownBenchmark: "copy_down",
  JsoupParseBenchmark: "jsoup parse",
  JsoupTextBenchmark: "jsoup text",
  XbergBenchmark: "xberg",
  ReferenceBenchmark: "mdream (Rust)",
};

const sizes = new Map();
const properties = path.join(bench, "support/src/main/resources/bench-digests.properties");
if (fs.existsSync(properties)) {
  for (const line of fs.readFileSync(properties, "utf8").split("\n")) {
    const match = /^([^.#]+)\.bytes=(\d+)$/.exec(line.trim());
    if (match) {
      sizes.set(match[1], Number(match[2]));
    }
  }
}

const rows = [];
for (const module of fs.readdirSync(bench, { withFileTypes: true })) {
  if (!module.isDirectory()) {
    continue;
  }
  const file = path.join(bench, module.name, "target/jmh-result.json");
  if (!fs.existsSync(file)) {
    continue;
  }
  for (const entry of JSON.parse(fs.readFileSync(file, "utf8"))) {
    if (entry.mode !== "avgt") {
      continue;
    }
    const simple = entry.benchmark.split(".").pop();
    const fixture = entry.params?.fixtureName ?? "";
    const millis = entry.primaryMetric?.score;
    if (millis === undefined) {
      continue;
    }
    const bytes = sizes.get(fixture);
    const alloc = entry.secondaryMetrics?.["gc.alloc.rate.norm"]?.score;
    rows.push({
      name: labels[simple] ?? simple,
      fixture,
      millis,
      mbps: bytes ? bytes / (millis / 1000) / 1e6 : null,
      alloc,
    });
  }
}

if (rows.length === 0) {
  console.log("no JMH results found; run tools/bench/run.sh");
  process.exit(0);
}

rows.sort((a, b) => a.name.localeCompare(b.name) || a.fixture.localeCompare(b.fixture));

const fixed = (value, digits) => (value === null || value === undefined ? "–" : value.toFixed(digits));

console.log("| Contender | Fixture | ms/op | MB/s | B/op |");
console.log("|---|---|---:|---:|---:|");
for (const row of rows) {
  console.log(
    `| ${row.name} | ${row.fixture} | ${fixed(row.millis, 3)} | ${fixed(row.mbps, 0)} | ${fixed(row.alloc, 0)} |`,
  );
}
