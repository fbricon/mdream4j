# Consumer notes — lemminx and eclipse.jdt.ls

Status: working notes, 2026-11-02.

This document is **not** part of the library plan. It records what the two known consumers do
today, what they will need to change, and which decisions belong to them. The library's
obligations are limited to the API surface and guarantees in `PLAN.md` §8.

Ownership split:

| Concern | Owner |
|---|---|
| Markdown generation, streaming, options, `tagOverrides` | mdream4j |
| HTML pre-processing / sanitization / DOM reshaping | consumer |
| Link rewriting (`file:`, `jdt:`, `eclipse-javadoc:`) | consumer |
| Golden-test re-basing and output review | consumer |
| OSGi bundle wiring, target platform, `about.html` | consumer |
| LSP plumbing (`MarkdownContent`, snippet escaping) | consumer |

GFM output is accepted for both consumers (decided 2026-11-02). Neither migration should add a
dialect-compatibility layer.

---

## 1. Shared observations

- Both consumers convert **HTML fragments**, not documents: no `<html>`/`<body>` wrapper, and
  often sloppy markup (`<p>multi<p>`, unclosed inline tags). mdream's parser handles this; the
  library must not require well-formed input.
- Both already depend on **jsoup** for parsing/sanitization. Keeping that pre-pass is cheaper than
  teaching the converter to reshape HTML.
- Both call the converter from **concurrent LSP request threads**. The library must not hold global
  mutable state or a global lock; per-call state + a reusable immutable-config converter is the
  contract.
- Both ship inside **OSGi** runtimes, and lemminx additionally builds a **GraalVM native image**
  (`--no-fallback`). The library contributes `module-info` plus OSGi headers and needs no
  native-image configuration.
- Both pin **old jsoup** versions for reasons unrelated to the converter. Replacing the markdown
  engine does not force a jsoup upgrade, but it does remove the constraint that kept jsoup old in
  lemminx (remark 1.0.0 needs ≤1.14.x).

---

## 2. lemminx

### 2.1 Current state

| Fact | Reference |
|---|---|
| remark 1.0.0, compile scope | `org.eclipse.lemminx/pom.xml:240-244` |
| jsoup 1.14.2 pinned | `org.eclipse.lemminx/pom.xml:245-249` |
| Java 11 (`<release>11</release>`) | `pom.xml:118-123` |
| native-image profile, `--no-fallback` | `org.eclipse.lemminx/pom.xml:142-192` |
| Reflection entry for `Remark.cleaner` | `org.eclipse.lemminx/src/main/resources/META-INF/native-image/reflect-config.json:2-7` |
| Only conversion class | `org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/utils/MarkdownConverter.java` |
| Golden test | `org.eclipse.lemminx/src/test/java/org/eclipse/lemminx/utils/MarkdownConverterTest.java` |

Surface: one class, one static `convert(String html)`; a `Remark` singleton built in a static
initializer; a jsoup `Cleaner` with a `Safelist` fetched by reflection so `file:` URLs survive.

Hidden dependency: `MarkdownConverter` statically imports
`org.apache.commons.lang3.StringEscapeUtils.unescapeJava` and `unescapeXml` (lines 15-16).
`commons-lang3` arrives transitively through remark and is **not declared** in the POM. Removing
remark breaks compilation unless those calls are replaced first.

Post-processing: `unescapeXml(html)` on non-HTML input; otherwise `unescapeJava(remark.convert(html))`.
`unescapeJava` strips markdown escapes and also mangles Windows paths (`C:\Users` → `C:Users`).

### 2.2 What changes

1. Declare `commons-lang3` explicitly or replace both helpers locally. Recommended: drop the
   `unescapeJava` post-pass entirely; it exists to undo remark's escaping and corrupts paths.
2. Replace the reflection hack on `Remark.cleaner` with an explicit `Safelist` that includes the
   `file:` protocol. Same behaviour, no reflection, and the `reflect-config.json` entry for
   `com.overzealous.remark.Remark` can be deleted.
3. Call the new library behind the existing `convert(String)` seam.
4. Re-base `MarkdownConverterTest`. 4 of 8 assertions change; the ~30 `Source: [x](file:/…)`
   integration assertions pass unchanged.

Expected assertion changes:

| Input | remark today | mdream | Action |
|---|---|---|---|
| `This is<br><b>bold</b>` | `This is\n**bold**` | hard break (two trailing spaces) | re-base |
| `<h1>Hey Man</h1>` | `# Hey Man #` | `# Hey Man` | re-base |
| `<ul><li>Coffee…</ul>` | ` *  Coffee` | `- Coffee` | re-base |
| `<p>ONLY_THIS_TEXT</p>` | `ONLY_THIS_TEXT` | `ONLY\_THIS\_TEXT` | re-base (renders identically) |
| tables | indented code block | GFM pipe table | re-base |
| `<hr>` | `----` | `---` | re-base |
| `file:` links | unchanged | unchanged | no change |

### 2.3 Acceptance checklist

- `MarkdownConverterTest` green with reviewed expectations.
- `Source:` documentation tests green without changes.
- No `com.overzealous.remark` / `commons-lang3`-via-remark references remain.
- `reflect-config.json` no longer mentions remark.
- `native-image` profile builds and a hover smoke test runs against the produced image.
- Bundle resolves with the new `Bundle-SymbolicName`/`Export-Package` headers.

### 2.4 Effort

2–3 days plus test review. This is the proof point and should land first.

---

## 3. eclipse.jdt.ls

### 3.1 Current state

| Fact | Reference |
|---|---|
| flexmark-html2md-converter 0.64.8 | `org.eclipse.jdt.ls.tp/org.eclipse.jdt.ls.tp.target:74-81` |
| 21 wrapped flexmark bundles | `org.eclipse.jdt.ls.core/META-INF/MANIFEST.MF:36-56` |
| jsoup pinned (1.23.2; excludes flexmark's 1.15.4) | `org.eclipse.jdt.ls.tp/org.eclipse.jdt.ls.tp.target:92-99` |
| Only production class importing flexmark | `org.eclipse.jdt.ls.core/src/.../javadoc/HtmlToMarkdownConverter.java` |
| Public seam | `HtmlToMarkdownConverter.convert(String)` (line 108) |
| Thin subclass | `JavaDoc2MarkdownConverter.java` |
| jsoup pre-pass helpers | `TableHelper.java`, `HtmlToPlainText.java`, `JavadocContentAccess2.java` |

`HtmlToMarkdownConverter` (482 lines) is the only class importing `com.vladsch.*`. It configures
flexmark through a `MutableDataSet` and registers **four custom renderers**: `a`, `tt`, `dfn`,
`dl`. It also does a jsoup pre-pass (`TableHelper`, `separateConsecutiveCodeTags`) and a
`fixSnippet` post-pass.

No GraalVM/native-image usage; Java 21 BREE; `jsoup` is used by non-flexmark code paths too, so it
stays.

### 3.2 What changes

1. Keep `HtmlToMarkdownConverter.convert(String)` as the public seam — tests construct it directly.
2. Keep the jsoup sanitize + table-normalize pre-pass; add the HTML-shape rewrites there:
   - `<tt>` → `<code>`
   - `<dfn>` → `<em>` (or keep mdream's `**` and re-base)
   - `<dl>` reshaping, if the raw-HTML block output is unacceptable for Javadoc hovers
3. Move `processA` link semantics into the pre-pass: unwrap empty/`#`/`eclipse-javadoc:` hrefs and
   apply `JDTUtils.cleanupURL`, so the converter only sees final URLs.
4. Map purely presentational tags (`sup`, `sub`, `ins`, `u`) with `tagOverrides` if the rendered
   Javadoc should keep them; otherwise accept raw HTML.
5. Keep `fixSnippet` byte-for-byte; it is engine-independent.
6. Remove the 21 `wrapped.com.vladsch.*` bundles from `MANIFEST.MF` and both `.product` files,
   remove the target-platform entries, update `about.html`, and add the new bundle.
7. Re-base goldens in:
   - `JavaDoc2MarkdownConverterTest`
   - `JavadocContentTest`
   - `HoverHandlerTest`
   - `CompletionHandlerTest`
   - `JavaDocImageExtractionTest`

Expected output changes:

| Construct | flexmark today | mdream (GFM) |
|---|---|---|
| `h1`/`h2` | setext (`===`/`---`) | ATX (`#`/`##`) |
| `<br>` | `<br />` | hard break (two trailing spaces) |
| `<hr>` | `*** ** * ** ***` | `---` |
| bullets | `* item` | `- item` |
| `<tt>` | inline code via custom renderer | plain text unless pre-mapped |
| `<dfn>` | `_term_` | `**term**` |
| `<dl>` | custom 4-space layout | raw HTML block |
| `<sup>/<sub>/<ins>/<u>` | `^x^`/`~x~`/`++x++` | raw HTML unless overridden |
| tables | padded cells | GFM table (alignment preserved) |
| quotes | curly | straight |
| links | custom `a` renderer | mdream rules; rewriting stays in the pre-pass |

### 3.3 Acceptance checklist

- `HtmlToMarkdownConverter` no longer imports `com.vladsch.*`.
- Five golden test classes green with reviewed expectations; `fixSnippet` tests unchanged.
- Hover/completion/signature-help documentation renders equivalently in a smoke test.
- Target platform resolves without flexmark; bundle starts in a clean Eclipse install.
- No `wrapped.com.vladsch.*` entries remain in manifests or `.product` files.

### 3.4 Effort

1–2 weeks plus test review. This is a separate epic in the jdt.ls repo, after lemminx proves the
engine.

---

## 4. Open consumer decisions

These are **not** library decisions and must not block the library beyond making the surface
flexible enough:

1. **lemminx `unescapeJava`**: drop it (recommended — it currently strips markdown escapes and
   mangles Windows paths) or preserve the quirk for bit-identical hover text?
2. **jdt.ls `<dl>`**: accept mdream's raw-HTML block, or reshape `<dl>` in the jsoup pre-pass?
3. **jdt.ls `sup`/`sub`/`ins`/`u`**: `tagOverrides`, pre-pass rewriting, or accept raw HTML?
4. **jdt.ls timing**: after mdream4j 0.1 (recommended) or later.
5. **lemminx jsoup upgrade**: opportunistic once remark is gone, or stay on 1.14.2?

---

## 5. Source material

Consumer dossiers were produced by read-only exploration on 2026-11-02. Re-verify line numbers
before landing any consumer PR; both repositories move.
