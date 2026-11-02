/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Port of {@code crates/core/tests/splitter.rs}. */
class SplitterTest {

  private static SplitterOptions defaults() {
    return SplitterOptions.defaults();
  }

  // ── Header-based splitting ──

  @Test
  void splitsOnRepeatedH2() {
    String md = "## Section A\nContent A\n## Section B\nContent B\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertTrue(chunks.size() >= 2, "expected at least 2 chunks, got " + chunks.size());
    assertTrue(chunks.get(0).content().contains("Content A"));
    assertTrue(chunks.get(1).content().contains("Content B"));
  }

  @Test
  void tracksHeaderHierarchy() {
    String md = "## Parent\nSome text\n### Child\nChild text\n## Another Parent\nMore text\n";
    SplitterOptions opts =
        SplitterOptions.builder()
            .headersToSplitOn(2, 3)
            .stripHeaders(false)
            .build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    MarkdownChunk last = chunks.get(chunks.size() - 1);
    assertEquals("Another Parent", last.metadata().headers().get("h2"));
    assertFalse(last.metadata().headers().containsKey("h3"));
  }

  @Test
  void doesNotSplitOnFirstOccurrence() {
    String md = "## Only Section\nContent here\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).content().contains("Content here"));
  }

  @Test
  void splitsOnH1WhenConfigured() {
    String md = "# Title A\nContent A\n# Title B\nContent B\n";
    SplitterOptions opts = SplitterOptions.builder().headersToSplitOn(1).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() >= 2);
  }

  @Test
  void ignoresLevelsOutsideOneToSix() {
    String md = "## Section A\nContent A\n## Section B\nContent B\n";
    SplitterOptions opts = SplitterOptions.builder().headersToSplitOn(0, 7, 8, 255).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).content().contains("Content A"));
    assertTrue(chunks.get(0).content().contains("Content B"));
  }

  @Test
  void clearsChildHeadersOnParentSplit() {
    String md = "## Sec 1\n### Sub 1\nText\n## Sec 2\nText 2\n";
    SplitterOptions opts = SplitterOptions.builder().headersToSplitOn(2, 3).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    MarkdownChunk last = chunks.get(chunks.size() - 1);
    assertEquals("Sec 2", last.metadata().headers().get("h2"));
    assertFalse(last.metadata().headers().containsKey("h3"));
  }

  // ── strip_headers ──

  @Test
  void stripHeadersRemovesHeaderLines() {
    String md = "## Header\nParagraph text\n";
    SplitterOptions opts = SplitterOptions.builder().stripHeaders(true).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertEquals(1, chunks.size());
    assertFalse(chunks.get(0).content().contains("## Header"));
    assertTrue(chunks.get(0).content().contains("Paragraph text"));
  }

  @Test
  void stripHeadersFalseKeepsHeaders() {
    String md = "## Header\nParagraph text\n";
    SplitterOptions opts = SplitterOptions.builder().stripHeaders(false).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).content().contains("## Header"));
  }

  // ── Code block handling ──

  @Test
  void extractsCodeLanguage() {
    String md = "## Section\n```rust\nfn main() {}\n```\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertEquals(1, chunks.size());
    assertEquals("rust", chunks.get(0).metadata().code());
  }

  @Test
  void noSplitInsideCodeBlock() {
    String md =
        "## Before\nText\n```\n## Fake Header Inside Code\nmore code\n```\n## After\nEnd text\n";
    SplitterOptions opts =
        SplitterOptions.builder().headersToSplitOn(2).stripHeaders(false).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    StringBuilder all = new StringBuilder();
    for (MarkdownChunk chunk : chunks) {
      if (all.length() > 0) {
        all.append('\n');
      }
      all.append(chunk.content());
    }
    assertTrue(all.toString().contains("## Fake Header Inside Code"));
  }

  @Test
  void backtickSafetyPreventsCodeBlockSplit() {
    String md =
        "Some text before\n```python\n"
            + "x = 1\n".repeat(200)
            + "```\nSome text after\n";
    SplitterOptions opts =
        SplitterOptions.builder().chunkSize(100).chunkOverlap(10).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertFalse(chunks.isEmpty());
  }

  @Test
  void sizeSplitsSkipSeparatorsInsideFences() {
    String md =
        "\n\n  Intro text before any code.\n\n```rust\nfn a() {}\n\nfn b() {}\n```\n\n"
            + "Between the first two blocks.\n\n````md\n```\nnested\n```\n````\n\n"
            + "An inline ``` marker in prose.\n\n```js\nlet x = 1;\n\nlet y = 2;\n```\n\n"
            + "Closing words after the last block.\n```\nopen at the end\n\nstill open\n";
    SplitterOptions opts = SplitterOptions.builder().chunkSize(40).chunkOverlap(0).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);

    assertChunk(chunks.get(0), "Intro text before any code.", "rust", 1, 3);
    assertChunk(chunks.get(1), "```rust\nfn a() {}\n\nfn b() {}\n```", null, 3, 9);
    assertChunk(chunks.get(2), "Between the first two blocks.", "`md", 9, 11);
    assertChunk(chunks.get(3), "````md\n```\nnested\n```\n````", null, 11, 17);
    assertChunk(
        chunks.get(4), "An inline ``` marker in prose.\n\n```js", "js", 17, 20);
    assertChunk(
        chunks.get(5),
        "let x = 1;\n\nlet y = 2;\n```\n\nClosing words after the last block.",
        null,
        20,
        26);
    assertChunk(chunks.get(6), "```\nopen at the end\n\nstill open", null, 26, 30);
    assertEquals(7, chunks.size());
  }

  // ── HR splitting ──

  @Test
  void splitsOnHrDashes() {
    String md = "Part one\n---\nPart two\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertTrue(chunks.size() >= 2, "expected split on --- HR");
    assertTrue(chunks.get(0).content().contains("Part one"));
  }

  @Test
  void splitsOnHrAsterisks() {
    String md = "Part one\n***\nPart two\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertTrue(chunks.size() >= 2, "expected split on *** HR");
  }

  @Test
  void splitsOnHrUnderscores() {
    String md = "Part one\n___\nPart two\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertTrue(chunks.size() >= 2, "expected split on ___ HR");
  }

  // ── Size-based splitting ──

  @Test
  void splitsLargeContentBySize() {
    String paragraph = "Lorem ipsum dolor sit amet. ".repeat(100);
    String md = "## Section\n" + paragraph + "\n";
    SplitterOptions opts = SplitterOptions.builder().chunkSize(200).chunkOverlap(50).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() > 1, "expected multiple chunks for large content");
    int total = 0;
    for (MarkdownChunk chunk : chunks) {
      total += chunk.content().length();
    }
    assertTrue(total > 0);
  }

  @Test
  void separatorPriorityPrefersDoubleNewline() {
    String md =
        "Paragraph one.\n\nParagraph two.\nSame paragraph continued.\n\nParagraph three.\n";
    SplitterOptions opts = SplitterOptions.builder().chunkSize(40).chunkOverlap(0).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() >= 2);
  }

  // ── Overlap ──

  @Test
  void overlapOnlyOnSizeSplits() {
    String paragraph = "Word ".repeat(300);
    String md = paragraph + "\n";
    SplitterOptions opts = SplitterOptions.builder().chunkSize(200).chunkOverlap(50).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() > 1);
    if (chunks.size() >= 2) {
      String endOfFirst = chunks.get(0).content();
      String startOfSecond = chunks.get(1).content();
      String overlapRegion =
          endOfFirst.substring(Math.max(0, endOfFirst.length() - 50)).trim();
      assertTrue(
          startOfSecond.contains(overlapRegion) || overlapRegion.isEmpty(),
          "expected overlap between chunks");
    }
  }

  @Test
  void noOverlapOnHeaderSplits() {
    String md = "## Section A\nContent A is here.\n## Section B\nContent B is here.\n";
    SplitterOptions opts = SplitterOptions.builder().chunkOverlap(100).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    if (chunks.size() >= 2) {
      assertFalse(chunks.get(1).content().contains("Content A"));
    }
  }

  // ── return_each_line ──

  @Test
  void returnEachLineSplitsIntoLines() {
    String md = "## Section\nLine one\nLine two\nLine three\n";
    SplitterOptions opts = SplitterOptions.builder().returnEachLine(true).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertEquals(3, chunks.size());
    assertEquals("Line one", chunks.get(0).content());
    assertEquals("Line two", chunks.get(1).content());
    assertEquals("Line three", chunks.get(2).content());
  }

  @Test
  void returnEachLineSkipsEmptyLines() {
    String md = "Line one\n\nLine two\n\n\nLine three\n";
    SplitterOptions opts = SplitterOptions.builder().returnEachLine(true).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertEquals(3, chunks.size());
  }

  @Test
  void returnEachLinePreservesHeadersMetadata() {
    String md = "## Section\nLine one\nLine two\n";
    SplitterOptions opts = SplitterOptions.builder().returnEachLine(true).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    for (MarkdownChunk chunk : chunks) {
      assertEquals("Section", chunk.metadata().headers().get("h2"));
    }
  }

  // ── Edge cases ──

  @Test
  void emptyInput() {
    assertTrue(Splitter.splitMarkdown("", defaults()).isEmpty());
  }

  @Test
  void whitespaceOnlyInput() {
    assertTrue(Splitter.splitMarkdown("   \n\n  \n", defaults()).isEmpty());
  }

  @Test
  void frontmatterDetection() {
    String md = "---\ntitle: Hello\nauthor: Test\n---\n## Section\nContent here\n";
    SplitterOptions opts = SplitterOptions.builder().stripHeaders(false).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertFalse(chunks.isEmpty());
    StringBuilder all = new StringBuilder();
    for (MarkdownChunk chunk : chunks) {
      if (all.length() > 0) {
        all.append('\n');
      }
      all.append(chunk.content());
    }
    assertTrue(all.toString().contains("Content here"));
  }

  @Test
  void consecutiveHeaders() {
    String md = "## A\n## B\n## C\nFinal content\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertFalse(chunks.isEmpty());
    assertTrue(chunks.get(chunks.size() - 1).content().contains("Final content"));
  }

  @Test
  void tinyChunkSize() {
    String md = "## Section\nHello world this is a test of tiny chunks\n";
    SplitterOptions opts = SplitterOptions.builder().chunkSize(10).chunkOverlap(2).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() > 1);
  }

  @Test
  void locLineNumbers() {
    String md = "## Section\nLine 1\nLine 2\nLine 3\n";
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, defaults());
    assertEquals(1, chunks.size());
    ChunkLoc loc = chunks.get(0).metadata().loc();
    assertNotNull(loc);
    assertEquals(1, loc.from());
    assertTrue(loc.to() >= 1);
  }

  @Test
  void mixedHeaderLevels() {
    String md =
        "# Title\n## Section 1\n### Sub 1.1\nText\n## Section 2\n#### Deep\nMore text\n";
    SplitterOptions opts =
        SplitterOptions.builder().headersToSplitOn(2, 3, 4).stripHeaders(false).build();
    List<MarkdownChunk> chunks = Splitter.splitMarkdown(md, opts);
    assertTrue(chunks.size() >= 2);
  }

  @Test
  void htmlToMarkdownChunksConvenience() {
    String html = "<h2>Section A</h2><p>Content A</p><h2>Section B</h2><p>Content B</p>";
    List<MarkdownChunk> chunks =
        Splitter.htmlToMarkdownChunks(html, Options.defaults(), defaults());
    assertTrue(chunks.size() >= 2);
    assertTrue(chunks.get(0).content().contains("Content A"));
    assertTrue(chunks.get(1).content().contains("Content B"));
  }

  @Test
  void panicsWhenOverlapExceedsSize() {
    SplitterOptions opts = SplitterOptions.builder().chunkSize(100).chunkOverlap(200).build();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> Splitter.splitMarkdown("Some text", opts));
    assertEquals("chunk_overlap must be less than chunk_size", thrown.getMessage());
  }

  private static void assertChunk(
      MarkdownChunk chunk, String content, String code, int from, int to) {
    assertEquals(content, chunk.content());
    assertEquals(code, chunk.metadata().code());
    ChunkLoc loc = chunk.metadata().loc();
    assertNotNull(loc);
    assertEquals(from, loc.from());
    assertEquals(to, loc.to());
  }
}
