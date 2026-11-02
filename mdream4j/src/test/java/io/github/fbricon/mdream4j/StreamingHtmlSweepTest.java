/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HTML streaming must equal one-shot output wherever the input splits.
 *
 * <p>{@code fixtures/html-stream-parity.txt} is upstream's sweep corpus: every input a parity
 * sweep found diverging, usually a whitespace run after a drained chunk that was trimmed as if it
 * started the document. Each case is fed char by char and, below {@link #EVERY_SPLIT_MAX_LEN}, at
 * every single split point, which is stronger than the fixed chunk sizes in {@link
 * GoldenStreamingTest}.
 */
class StreamingHtmlSweepTest {

  /** Each split point costs a full conversion, so larger inputs only stream char by char. */
  private static final int EVERY_SPLIT_MAX_LEN = 2048;

  @Test
  void htmlStreamMatchesOneShotOnSweep() throws IOException {
    List<String> inputs = loadSweep();
    assertTrue(inputs.size() > 200, "sweep corpus failed to load: " + inputs.size());

    Options html = Options.builder().format(OutputFormat.HTML).build();
    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (String input : inputs) {
      String expected = HtmlToMarkdown.convert(input, html);
      checks++;
      String charChunked = streamAt(input, charBoundaries(input, 0));
      if (!expected.equals(charChunked) && failures.size() < 10) {
        failures.add("char chunks: " + escape(input) + "\n" + diff(expected, charChunked));
      }
      if (input.length() <= EVERY_SPLIT_MAX_LEN) {
        for (int split : charBoundaries(input, 1)) {
          checks++;
          String single = streamAt(input, new int[] {split});
          if (!expected.equals(single) && failures.size() < 10) {
            failures.add(
                "split at " + split + ": " + escape(input) + "\n" + diff(expected, single));
          }
        }
      }
    }

    assertTrue(
        failures.isEmpty(),
        failures.size() + " HTML streaming mismatch(es):\n" + String.join("\n", failures));
    assertTrue(checks > 40_000, "sweep checks must not shrink: " + checks);
  }

  /** Every char index in {@code (from, length)} that does not split a surrogate pair. */
  private static int[] charBoundaries(String input, int from) {
    int[] boundaries = new int[input.length()];
    int count = 0;
    for (int i = Math.max(1, from); i < input.length(); i++) {
      if (Character.isLowSurrogate(input.charAt(i))
          && Character.isHighSurrogate(input.charAt(i - 1))) {
        continue;
      }
      boundaries[count++] = i;
    }
    int[] trimmed = new int[count];
    System.arraycopy(boundaries, 0, trimmed, 0, count);
    return trimmed;
  }

  private static String streamAt(String html, int[] splits) {
    MarkdownStream stream =
        HtmlToMarkdown.newStream(Options.builder().format(OutputFormat.HTML).build());
    StringBuilder out = new StringBuilder();
    int start = 0;
    for (int end : splits) {
      out.append(stream.feed(html.substring(start, end)));
      start = end;
    }
    out.append(stream.feed(html.substring(start)));
    out.append(stream.finish());
    return out.toString();
  }

  private static List<String> loadSweep() throws IOException {
    try (InputStream in =
        StreamingHtmlSweepTest.class.getResourceAsStream("/fixtures/html-stream-parity.txt")) {
      assertTrue(in != null, "missing /fixtures/html-stream-parity.txt");
      String sweep = new String(in.readAllBytes(), UTF_8);
      List<String> inputs = new ArrayList<>();
      int start = 0;
      for (int i = 0; i < sweep.length(); i++) {
        if (sweep.charAt(i) == '\u001e') {
          inputs.add(sweep.substring(start, i));
          start = i + 1;
        }
      }
      inputs.add(sweep.substring(start));
      return inputs;
    }
  }

  private static String diff(String expected, String actual) {
    return "  expected: " + escape(expected) + "\n  actual:   " + escape(actual);
  }

  private static String escape(String value) {
    String text = value.length() > 200 ? value.substring(0, 200) + "…" : value;
    return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
