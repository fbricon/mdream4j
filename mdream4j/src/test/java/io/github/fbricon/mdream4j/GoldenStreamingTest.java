/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Streaming parity: concatenating every chunk a {@link MarkdownStream} returns must be
 * byte-identical to one-shot conversion, at every chunk size.
 *
 * <p>Small chunk sizes are skipped for very large documents because the sweep would otherwise be
 * quadratic; the interesting boundary behaviour is covered by every other case.
 */
class GoldenStreamingTest {

  private static final int[] CHUNK_SIZES = {1, 2, 3, 7, 64, 4096};

  /** Skip tiny chunks for inputs where the O(n^2/size) sweep would dominate the build. */
  private static final int LARGE_INPUT = 200_000;
  private static final int LARGE_INPUT_MIN_CHUNK = 64;

  @Test
  void feedConcatenationMatchesOneShot() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 147, "corpus must not shrink: " + cases.size());

    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      @SuppressWarnings("unchecked")
      Map<String, Object> outputs = (Map<String, Object>) corpusCase.get("outputs");
      String expected = (String) outputs.get("markdown");
      boolean expectedTruncated = Boolean.TRUE.equals(outputs.get("truncated"));

      for (int size : CHUNK_SIZES) {
        if (html.length() > LARGE_INPUT && size < LARGE_INPUT_MIN_CHUNK) {
          continue;
        }
        checks++;
        MarkdownStream stream = HtmlToMarkdown.newStream(Options.defaults());
        StringBuilder out = new StringBuilder();
        for (int start = 0; start < html.length(); start += size) {
          out.append(stream.feed(html.substring(start, Math.min(html.length(), start + size))));
        }
        out.append(stream.finish());
        String actual = out.toString();
        if (!expected.equals(actual) || stream.truncated() != expectedTruncated) {
          if (failures.size() < 20) {
            failures.add(
                id
                    + " size="
                    + size
                    + "\n  expected: "
                    + escape(expected)
                    + "\n  actual:   "
                    + escape(actual)
                    + (stream.truncated() == expectedTruncated
                        ? ""
                        : "\n  truncated flag differs"));
          }
        }
      }
    }

    assertTrue(
        failures.isEmpty(),
        failures.size() + " streaming mismatch(es):\n" + String.join("\n", failures));
    assertTrue(checks >= 800, "streaming checks must not shrink: " + checks);
  }

  private static String escape(String value) {
    String text = value.length() > 200 ? value.substring(0, 200) + "…" : value;
    return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
