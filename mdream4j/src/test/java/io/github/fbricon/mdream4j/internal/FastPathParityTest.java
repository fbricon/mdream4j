/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The plain-text fast paths must be byte-transparent: skipping the per-node general emitter and
 * gate branches must never change the output.
 *
 * <p>Ports the upstream {@code fast_paths} differential test over the whole golden corpus, one
 * shot and streamed at several chunk sizes. Each corpus case is its own JUnit invocation.
 */
class FastPathParityTest {

  private static final int[] CHUNK_SIZES = {1, 7, 4096};

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
  void oneShotFastAndGeneralPathsAgree(String id, Map<String, Object> corpusCase) {
    String html = (String) corpusCase.get("html");
    Options options = Corpus.options(corpusCase, OutputFormat.MARKDOWN);

    MdreamResult fast = Engine.convertResult(html, options, true);
    MdreamResult general = Engine.convertResult(html, options, false);

    assertAll(
        id,
        () ->
            assertEquals(
                general.markdown(),
                fast.markdown(),
                () ->
                    id
                        + "\n  fast:    "
                        + escape(fast.markdown())
                        + "\n  general: "
                        + escape(general.markdown())),
        () ->
            assertEquals(
                general.truncated(),
                fast.truncated(),
                () -> id + " truncated differs: " + fast.truncated() + " vs " + general.truncated()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void streamedFastAndGeneralPathsAgree(String id, Map<String, Object> corpusCase) {
    String html = (String) corpusCase.get("html");
    Options options = Corpus.options(corpusCase, OutputFormat.MARKDOWN);

    List<Executable> checks = new ArrayList<>();
    for (int size : CHUNK_SIZES) {
      if (html.length() > LARGE_INPUT && size < LARGE_INPUT_MIN_CHUNK) {
        continue;
      }
      String fast = stream(html, size, options, true);
      String general = stream(html, size, options, false);
      String label = id + " size=" + size;
      checks.add(
          () ->
              assertEquals(
                  general,
                  fast,
                  () ->
                      label
                          + "\n  fast:    "
                          + escape(fast)
                          + "\n  general: "
                          + escape(general)));
    }
    assertAll(id, checks);
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
