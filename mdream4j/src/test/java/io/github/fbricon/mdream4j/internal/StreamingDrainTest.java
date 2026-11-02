/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fbricon.mdream4j.Clean;
import io.github.fbricon.mdream4j.Corpus;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Draining must be byte-transparent: a stream produces the same output with it on or off, for any
 * input at any chunk size.
 *
 * <p>Ports the upstream {@code drain_equiv} test. The golden corpus adds the rewrite-after-yield
 * constructs (autolink text equal to its URL, self-link headings, redundant {@code [url](url)})
 * that diverge from one-shot but must still stay drain-invariant.
 */
class StreamingDrainTest {

  private static final int[] CHUNK_SIZES = {1, 2, 3, 7, 64, 4096};

  /** Skip tiny chunks for inputs where the O(n^2/size) sweep would dominate the build. */
  private static final int LARGE_INPUT = 200_000;
  private static final int LARGE_INPUT_MIN_CHUNK = 64;

  /** Upstream's breadth corpus, including the deep-indent case a drained line can land in. */
  private static final String[] CORPUS = {
    "<h1>Title</h1><p>Para one.</p><p>Para <strong>two</strong>.</p>",
    "<ul><li>a</li><li>b<ul><li>b1</li><li>b2</li></ul></li></ul>",
    "<p>See <a href=\"https://example.com\">Example</a> and <a href=\"https://x.io\">the X"
        + " site</a>.</p>",
    "<blockquote><p>quote</p><blockquote><p>nested</p></blockquote></blockquote><p>after</p>",
    "<pre><code>let x = 1;\nlet y = 2;</code></pre><p>done</p>",
    "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>",
    "<h2>Section</h2><p>text with a <a href=\"/rel\">relative</a> link</p>",
    "<p>Visit <a href=\"https://x.io\">https://x.io</a> today.</p>",
    "<h2><a href=\"#section\">Section</a></h2><p>body</p>",
    "<p>link <a href=\"https://example.com\">https://example.com</a> end</p>",
    "<h2><a href=\"#x\">pre<div><a href=\"/b\">y</a></a></div></h2>",
    "<li><ul><li><ul><li><ul><li><ul><li><ul><li><p>q<br>\n<p>X",
  };

  private static final OutputFormat[] STREAMED_FORMATS = {OutputFormat.MARKDOWN, OutputFormat.TEXT};

  @Test
  void drainIsByteTransparent() {
    List<Options> optionSets = new ArrayList<>();
    optionSets.add(Options.defaults());
    optionSets.add(Options.builder().wrapWidth(12).build());
    optionSets.add(Options.builder().clean(safeClean()).build());

    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (String html : CORPUS) {
      for (Options options : optionSets) {
        for (int chunk : new int[] {1, 3, 7, 64, html.length()}) {
          checks++;
          compare("check " + checks, html, chunk, options, failures);
        }
      }
    }
    assertTransparent(failures, checks);
  }

  @Test
  void goldenCorpusStreamsIdenticallyWithAndWithoutDraining() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 1190, "corpus must not shrink: " + cases.size());

    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      for (OutputFormat format : STREAMED_FORMATS) {
        Options options = Corpus.options(corpusCase, format);
        for (int size : CHUNK_SIZES) {
          if (html.length() > LARGE_INPUT && size < LARGE_INPUT_MIN_CHUNK) {
            continue;
          }
          checks++;
          compare(id + " " + format + " size=" + size, html, size, options, failures);
        }
      }
    }
    assertTransparent(failures, checks);
  }

  private static void compare(
      String label, String html, int chunk, Options options, List<String> failures) {
    String drained = stream(html, chunk, options, false);
    String undrained = stream(html, chunk, options, true);
    if (!drained.equals(undrained) && failures.size() < 20) {
      failures.add(
          label
              + "\n  drained:   "
              + escape(drained)
              + "\n  undrained: "
              + escape(undrained));
    }
  }

  private static String stream(String html, int chunk, Options options, boolean disableDrain) {
    MarkdownStream stream = new ConverterStream(options, disableDrain);
    StringBuilder out = new StringBuilder();
    for (int start = 0; start < html.length(); start += chunk) {
      out.append(stream.feed(html.substring(start, Math.min(html.length(), start + chunk))));
    }
    out.append(stream.finish());
    return out.toString();
  }

  private static void assertTransparent(List<String> failures, int checks) {
    assertTrue(
        failures.isEmpty(),
        failures.size() + " drain mismatch(es):\n" + String.join("\n", failures));
    assertTrue(checks > 0, "no drain checks ran");
  }

  /** Everything except {@code fragments}, which needs the whole buffer and gates drain off. */
  private static Clean safeClean() {
    return Clean.builder()
        .urls()
        .emptyLinks()
        .redundantLinks()
        .selfLinkHeadings()
        .emptyImages()
        .emptyLinkText()
        .build();
  }

  private static String escape(String value) {
    String text = value.length() > 200 ? value.substring(0, 200) + "…" : value;
    return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
