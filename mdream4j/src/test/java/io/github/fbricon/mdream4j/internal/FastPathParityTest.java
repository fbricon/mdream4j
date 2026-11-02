/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fbricon.mdream4j.Corpus;
import io.github.fbricon.mdream4j.MdreamResult;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plain-text fast paths must be byte-transparent: skipping the per-node general emitter and
 * gate branches must never change the output.
 *
 * <p>Ports the upstream {@code fast_paths} differential test over the whole golden corpus, one
 * shot and streamed at several chunk sizes.
 */
class FastPathParityTest {

  private static final int[] CHUNK_SIZES = {1, 7, 4096};

  /** Skip tiny chunks for inputs where the O(n^2/size) sweep would dominate the build. */
  private static final int LARGE_INPUT = 200_000;
  private static final int LARGE_INPUT_MIN_CHUNK = 64;

  @Test
  void oneShotFastAndGeneralPathsAgree() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 1190, "corpus must not shrink: " + cases.size());

    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      Options options = Corpus.options(corpusCase, OutputFormat.MARKDOWN);

      MdreamResult fast = Engine.convertResult(html, options, true);
      MdreamResult general = Engine.convertResult(html, options, false);
      checks++;
      if (!fast.markdown().equals(general.markdown()) && failures.size() < 20) {
        failures.add(
            id
                + "\n  fast:    "
                + escape(fast.markdown())
                + "\n  general: "
                + escape(general.markdown()));
      } else if (fast.truncated() != general.truncated() && failures.size() < 20) {
        failures.add(id + "\n  truncated differs: " + fast.truncated() + " vs " + general.truncated());
      }
    }

    assertTrue(
        failures.isEmpty(),
        failures.size() + " fast-path mismatch(es):\n" + String.join("\n", failures));
    assertTrue(checks > 0, "no fast-path checks ran");
  }

  @Test
  void gateIsOnForDefaultMarkdownText() {
    ConvertState state = new ConvertState(Options.defaults(), 1024);
    assertTrue(ParseOps.textNodeFastPathOk(state), "default markdown should take the fast path");

    ConvertState wrapped =
        new ConvertState(Options.builder().wrapWidth(80).build(), 1024);
    assertFalse(ParseOps.textNodeFastPathOk(wrapped), "wrapping needs the general path");

    ConvertState capped =
        new ConvertState(Options.builder().maxNodeBytes(1024).build(), 1024);
    assertFalse(ParseOps.textNodeFastPathOk(capped), "capping needs the general path");

    ConvertState text = new ConvertState(Options.builder().format(OutputFormat.TEXT).build(), 1024);
    assertFalse(ParseOps.textNodeFastPathOk(text), "only markdown has the fast path");
  }

  @Test
  void streamedFastAndGeneralPathsAgree() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 1190, "corpus must not shrink: " + cases.size());

    List<String> failures = new ArrayList<>();
    int checks = 0;
    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      Options options = Corpus.options(corpusCase, OutputFormat.MARKDOWN);
      for (int size : CHUNK_SIZES) {
        if (html.length() > LARGE_INPUT && size < LARGE_INPUT_MIN_CHUNK) {
          continue;
        }
        checks++;
        String fast = stream(html, size, options, true);
        String general = stream(html, size, options, false);
        if (!fast.equals(general) && failures.size() < 20) {
          failures.add(
              id
                  + " size="
                  + size
                  + "\n  fast:    "
                  + escape(fast)
                  + "\n  general: "
                  + escape(general));
        }
      }
    }

    assertTrue(
        failures.isEmpty(),
        failures.size() + " streaming fast-path mismatch(es):\n" + String.join("\n", failures));
    assertTrue(checks > 0, "no streaming fast-path checks ran");
  }

  private static String stream(String html, int chunk, Options options, boolean fastPaths) {
    ConverterStream stream = new ConverterStream(options, false, fastPaths);
    StringBuilder out = new StringBuilder();
    for (int start = 0; start < html.length(); start += chunk) {
      out.append(stream.feed(html.substring(start, Math.min(html.length(), start + chunk))));
    }
    out.append(stream.finish());
    return out.toString();
  }

  private static String escape(String value) {
    String text = value.length() > 200 ? value.substring(0, 200) + "…" : value;
    return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
