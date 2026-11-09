/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Streaming parity: concatenating every chunk a {@link MarkdownStream} returns must be
 * byte-identical to one-shot conversion, at every chunk size and for every output format.
 *
 * <p>Each corpus case is its own JUnit invocation, named by corpus id; every format and chunk size
 * for that case is checked as a named assertion, so all of them are reported before the invocation
 * fails.
 *
 * <p>Small chunk sizes are skipped for very large documents because the sweep would otherwise be
 * quadratic; the interesting boundary behaviour is covered by every other case.
 */
class GoldenStreamingTest {

  private static final int[] CHUNK_SIZES = {1, 2, 3, 7, 64, 4096};

  private static final OutputFormat[] FORMATS = {
    OutputFormat.MARKDOWN, OutputFormat.TEXT, OutputFormat.HTML
  };

  /** Skip tiny chunks for inputs where the O(n^2/size) sweep would dominate the build. */
  private static final int LARGE_INPUT = 200_000;
  private static final int LARGE_INPUT_MIN_CHUNK = 64;

  /** Guards against silently dropping cases when the golden file is regenerated. */
  @Test
  void corpusDoesNotShrink() throws IOException {
    int size = Corpus.loadCases().size();
    assertTrue(size >= 1218, "corpus must not shrink: " + size);
  }

  static Stream<Arguments> cases() throws IOException {
    return Corpus.loadCases().stream().map(corpusCase -> Arguments.of(corpusCase.get("id"), corpusCase));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void feedConcatenationMatchesOneShot(String id, Map<String, Object> corpusCase) {
    String html = (String) corpusCase.get("html");
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) corpusCase.get("outputs");
    boolean expectedTruncated = Boolean.TRUE.equals(outputs.get("truncated"));

    List<Executable> checks = new ArrayList<>();
    for (OutputFormat format : FORMATS) {
      String expected = (String) outputs.get(format.name().toLowerCase(Locale.ROOT));
      for (int size : CHUNK_SIZES) {
        if (html.length() > LARGE_INPUT && size < LARGE_INPUT_MIN_CHUNK) {
          continue;
        }
        MarkdownStream stream = HtmlToMarkdown.newStream(Corpus.options(corpusCase, format));
        StringBuilder out = new StringBuilder();
        for (int start = 0; start < html.length(); start += size) {
          out.append(stream.feed(html.substring(start, Math.min(html.length(), start + size))));
        }
        out.append(stream.finish());
        String actual = out.toString();
        String label = id + " " + format + " size=" + size;
        checks.add(
            () ->
                assertEquals(
                    expected,
                    actual,
                    () -> label + "\n  expected: " + escape(expected) + "\n  actual:   " + escape(actual)));
        if (format == OutputFormat.MARKDOWN) {
          boolean actualTruncated = stream.truncated();
          checks.add(
              () ->
                  assertEquals(
                      expectedTruncated,
                      actualTruncated,
                      () -> label + " truncated flag differs"));
        }
      }
    }
    assertAll(id, checks);
  }

  private static String escape(String value) {
    String text = value.length() > 200 ? value.substring(0, 200) + "…" : value;
    return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
