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

// 4. Options and plugins. The shapes match the reference bridge's OptionsJson
// so the same JSON feeds both the Rust reference and the Java tests.
// `options` is the only difference from section 1: the HTML inputs are taken
// from the upstream plugin tests, where the declarative Rust engine is the
// engine under test.
const opt = (id, html, options) => cases.push({ id, html, options });

// --- clean ---
opt(
  "clean-urls",
  '<a href="https://x.io/a?utm_source=n&amp;utm_medium=e&amp;id=1">text</a>',
  { clean: { urls: true } },
);
opt(
  "clean-empty-links",
  '<p><a href="javascript:alert(1)">js</a> <a href="#">hash</a> <a href="/ok">ok</a></p>',
  { clean: { emptyLinks: true } },
);
opt(
  "clean-redundant-links",
  '<p><a href="https://example.com">https://example.com</a> and <a href="/x">text</a></p>',
  { clean: { redundantLinks: true } },
);
opt(
  "clean-self-link-headings",
  '<h2><a href="#section">Section</a></h2><p><a href="#other">Other</a></p>',
  { clean: { selfLinkHeadings: true } },
);
opt("clean-empty-images", '<p><img src="/a.png" alt=""> <img src="/b.png" alt="B"></p>', {
  clean: { emptyImages: true },
});
opt("clean-empty-link-text", '<p><a href="/x"><svg></svg></a> tail</p>', {
  clean: { emptyLinkText: true },
});
opt(
  "clean-fragments",
  '<h2 id="s">Section</h2><p><a href="#s">ok</a> <a href="#nope">broken</a></p>',
  { clean: { fragments: true } },
);
opt(
  "clean-all",
  '<p><a href="https://x.io/?utm_source=a">https://x.io/</a></p><h2><a href="#t">T</a></h2><img alt="" src="/i">',
  {
    clean: {
      urls: true,
      emptyLinks: true,
      redundantLinks: true,
      selfLinkHeadings: true,
      emptyImages: true,
      emptyLinkText: true,
    },
  },
);
opt(
  "clean-fragments-title",
  '<h2 id="s">Section</h2><p><a href="#s" title="Tip">ok</a> <a href="#nope" title="Tip">broken</a></p>',
  { clean: { fragments: true } },
);
opt(
  "clean-fragments-markup",
  "<h2>Hello <code>world</code></h2><p><a href=\"#hello-world\">ok</a> <a href=\"#nope\">bad</a></p>",
  { clean: { fragments: true } },
);
opt(
  "clean-fragments-unicode",
  '<h2 id="u">Überblick</h2><p><a href="#%C3%BCberblick">encoded</a> <a href="#überblick">raw</a></p>',
  { clean: { fragments: true } },
);
opt(
  "clean-fragments-nested-markers",
  '<h2 id="a">A</h2><p><a href="#a">ok <em>still</em></a> <a href="#missing">bad <strong>gone</strong></a></p>',
  { clean: { fragments: true } },
);
opt(
  "clean-fragments-self-links",
  '<h2 id="t"><a href="#t">T</a></h2><p><a href="#t">ok</a> <a href="#x">nope</a></p>',
  { clean: { fragments: true, selfLinkHeadings: true } },
);
opt(
  "clean-empty-link-text-title",
  '<p><a href="/x" title="T"></a> tail</p>',
  { clean: { emptyLinkText: true } },
);
opt(
  "clean-redundant-links-title",
  '<p><a href="https://example.com" title="Example">https://example.com</a></p>',
  { clean: { redundantLinks: true } },
);
opt("origin-links", '<a href="/a">A</a><img src="i.png" alt="I">', {
  origin: "https://example.com/docs/",
});
opt(
  "origin-redundant-links",
  '<a href="/docs/a">https://example.com/docs/a</a>',
  { origin: "https://example.com/docs/", clean: { redundantLinks: true } },
);

// --- autolink ---
opt(
  "autolink-default",
  '<p>Visit <a href="https://x.io">https://x.io</a> today.</p>',
  {},
);
opt(
  "autolink-with-title",
  '<p><a href="https://x.io" title="X">https://x.io</a></p>',
  {},
);
opt("autolink-mailto", '<p><a href="mailto:a@b.c">mailto:a@b.c</a></p>', {});
opt("autolink-ftp", '<p><a href="ftp://x.io/f">ftp://x.io/f</a></p>', {});
opt("autolink-not-a-uri", '<p><a href="/x">/x</a> <a href="#top">#top</a></p>', {});
opt(
  "autolink-query",
  '<p><a href="https://x.io/a?b=1&amp;c=2">https://x.io/a?b=1&amp;c=2</a></p>',
  {},
);
opt(
  "autolink-trailing-space",
  '<p><a href="https://x.io">https://x.io </a>end</p>',
  {},
);
opt(
  "autolink-nested-marker",
  '<p><a href="https://x.io"><em>https://x.io</em></a></p>',
  {},
);

// --- filter ---
opt("filter-exclude-tags", '<div>keep</div><aside>drop</aside><nav>drop</nav><p>keep2</p>', {
  plugins: { filter: { exclude: ["aside", "nav"] } },
});
opt("filter-include-tags", '<div>no</div><h1>Yes</h1><p>Yes2</p><aside>no2</aside>', {
  plugins: { filter: { include: ["h1", "p"] } },
});
opt("filter-include-list", "<h1>Included</h1><p>secret</p>", {
  plugins: { filter: { include: ["h1, h2"] } },
});
opt(
  "filter-selectors",
  '<p class="hide">a</p><p id="keep">b</p><div data-x="1">c</div>',
  { plugins: { filter: { exclude: [".hide", "#keep"] } } },
);
opt(
  "filter-list-child",
  '<ul><li>a</li><div class="hide">hidden</div><li>b</li></ul>',
  { plugins: { filter: { exclude: [".hide"] } } },
);
opt(
  "filter-process-children",
  '<div class="hide"><p>child</p></div><p>after</p>',
  { plugins: { filter: { exclude: [".hide"], processChildren: true } } },
);

// --- isolateMain ---
opt(
  "isolate-main-explicit",
  "<body><nav>Navigation</nav><h1>Outside</h1><main><h1>Article</h1><p>Body</p><h2>Sub</h2><p>More</p></main><footer>Footer</footer></body>",
  { plugins: { isolateMain: true } },
);
opt(
  "isolate-main-heuristic",
  "<body><header>Site header</header><h1>Article</h1><p>Body</p><footer>Site footer</footer></body>",
  { plugins: { isolateMain: true } },
);
opt("isolate-main-none", "<p>first</p><p>second</p>", { plugins: { isolateMain: true } });

// --- frontmatter ---
opt(
  "frontmatter-basic",
  '<html><head><title>Test Page Title</title><meta name="description" content="A test page"></head><body><h1>Main Content</h1><p>Text</p></body></html>',
  { plugins: { frontmatter: {} } },
);
opt(
  "frontmatter-additional",
  "<html><head><title>Test Page</title></head><body><p>Content</p></body></html>",
  { plugins: { frontmatter: { additionalFields: { layout: "post", date: "2025-05-10" } } } },
);
opt(
  "frontmatter-meta-fields",
  '<html><head><title>T</title><meta name="author" content="John Doe"><meta name="keywords" content="key1, key2"></head><body><p>Content</p></body></html>',
  { plugins: { frontmatter: { metaFields: ["author", "keywords"] } } },
);
opt(
  "frontmatter-og",
  '<html><head><title>Page Title</title><meta property="og:title" content="OG Title"><meta name="twitter:description" content="Twitter Description"></head><body><p>Content</p></body></html>',
  { plugins: { frontmatter: {} } },
);
opt("frontmatter-no-head", "<p>body only</p>", { plugins: { frontmatter: {} } });

// --- tailwind ---
opt("tailwind-bold", '<p class="font-bold">This is bold text</p>', {
  plugins: { tailwind: true },
});
opt("tailwind-italic", '<p class="italic">This is italic text</p>', {
  plugins: { tailwind: true },
});
opt("tailwind-combined", '<p class="font-bold italic">This is bold and italic</p>', {
  plugins: { tailwind: true },
});
opt(
  "tailwind-nested",
  '<div><p>Regular text with <span class="font-bold">bold</span> word</p><p class="italic">Italic paragraph</p></div>',
  { plugins: { tailwind: true } },
);
opt(
  "tailwind-absolute",
  '<p>Regular content</p><p class="absolute">Hidden</p><p>More regular content</p>',
  { plugins: { tailwind: true } },
);
opt(
  "tailwind-breakpoints",
  '<p class="italic font-bold md:font-normal lg:font-bold">Multiple classes</p>',
  { plugins: { tailwind: true } },
);
opt(
  "tailwind-underline-link",
  '<a class="underline" href="https://example.com/source">Source</a>',
  { plugins: { tailwind: true } },
);

// --- tagOverrides ---
opt("override-strong", "<p><strong>bold</strong></p>", {
  plugins: { tagOverrides: { strong: { enter: "__", exit: "__" } } },
});
opt("override-alias", "<p><x-heading>content</x-heading></p>", {
  plugins: { tagOverrides: { "x-heading": { alias: "h2" } } },
});
opt("override-spacing", "<div>first</div><div>second</div>", {
  plugins: { tagOverrides: { div: { spacing: [1, 1] } } },
});
opt("override-inline", "<p>before <span>middle</span> after</p>", {
  plugins: { tagOverrides: { span: { isInline: false, spacing: [2, 2] } } },
});
opt("override-custom", "<p><x-note>important</x-note></p>", {
  plugins: {
    tagOverrides: {
      "x-note": {
        enter: "> **Note:** ",
        exit: "",
        isInline: true,
        spacing: [0, 0],
        collapsesInnerWhiteSpace: true,
      },
    },
  },
});
opt("override-inline-unset", "<p><em>a </em><x-y>2</x-y>z</p>", {
  plugins: { tagOverrides: { "x-y": { enter: "**", exit: "**" } } },
});
opt("override-sup-sub", "<p>E = mc<sup>2</sup> and H<sub>2</sub>O</p>", {
  plugins: {
    tagOverrides: { sup: { enter: "^", exit: "^" }, sub: { enter: "~", exit: "~" } },
  },
});
opt("override-cdata", "<p>a<![CDATA[x]]>b</p>", {
  plugins: {
    tagOverrides: { "#cdata-section": { enter: "[", exit: "]", spacing: [0, 0], isInline: true } },
  },
});
opt("override-cdata-alias", "<p>a<![CDATA[x]]>b</p>", {
  plugins: { tagOverrides: { "#cdata-section": { alias: "em" } } },
});
opt("cdata-default", "<p>a<![CDATA[x]]>b</p>", {});
opt("cdata-unterminated", "<p>a<![CDATA[x</p>", {});
opt("cdata-unterminated-override", "<p>a<![CDATA[x</p>", {
  plugins: {
    tagOverrides: { "#cdata-section": { enter: "[", exit: "]", spacing: [0, 0], isInline: true } },
  },
});
opt('override-anchor-enter', '<details><a href="/x">a[b]</a></details>', {
  plugins: { tagOverrides: { a: { enter: "[" } } },
});

// --- extraction ---
opt("extract-headings", "<h1>One</h1><p>x</p><h2>Two</h2>", {
  plugins: { extraction: { selectors: ["h1", "h2"] } },
});
opt('extract-attr', '<a href="/a" title="T">A</a><a href="/b">B</a>', {
  plugins: { extraction: { selectors: ["a[href]"] } },
});
opt('extract-nested', '<div class="c"><p>inner <b>bold</b></p></div>', {
  plugins: { extraction: { selectors: ["div.c"] } },
});
opt("extract-list", "<ul><li>a</li><li>b</li></ul>", {
  plugins: { extraction: { selectors: ["li"] } },
});
opt('extract-multi', '<h2 id="s">S</h2><img src="/i" alt="I">', {
  plugins: { extraction: { selectors: ["h2", "img"] } },
});

// --- everything at once ---
const PAGE =
  '<html><head><title>Page</title><meta name="description" content="About"></head><body><nav>Menu</nav><main><h1>Hello</h1><form>Sign up</form><p class="font-bold">Bold</p><a href="/a">A</a><x-note>Note</x-note></main><footer>Footer</footer></body></html>';
opt("combo-options", PAGE, {
  origin: "https://example.com",
  clean: { urls: true },
  plugins: {
    frontmatter: {},
    isolateMain: true,
    tailwind: true,
    tagOverrides: { "x-note": { alias: "strong" } },
    filter: { exclude: ["form"] },
    extraction: { selectors: ["a[href]", "x-note"] },
  },
});

fs.mkdirSync(path.dirname(corpusPath), { recursive: true });
const out = cases.map((entry) => JSON.stringify(entry)).join("\n") + "\n";
fs.writeFileSync(corpusPath, out);

console.log(`wrote ${cases.length} cases to ${path.relative(repo, corpusPath)}`);
