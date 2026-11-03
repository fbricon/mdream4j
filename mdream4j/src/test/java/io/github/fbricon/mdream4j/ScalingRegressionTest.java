/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Guards against quadratic scanning of long text runs and long streaming tokens.
 *
 * <p>Runs that cannot be split (non-batchable text such as {@code &}, and the contexts where
 * splitting would change the output) used to rescan the whole accumulated buffer for every token.
 * A streamed tag whose name or quoted value ran past a chunk boundary was carried raw and re-fed
 * on every chunk, which copied the growing prefix quadratically. The inputs here are large enough
 * that the old behaviour took seconds while the linear path takes milliseconds, so a generous
 * timeout is still a reliable regression signal.
 */
class ScalingRegressionTest {

  /** ~488 KB of escaped ampersands, decoded to one long non-batchable run. */
  private static final int DENSE_ENTITIES = 100_000;

  private static String repeat(String value, int times) {
    StringBuilder out = new StringBuilder(value.length() * times);
    for (int i = 0; i < times; i++) {
      out.append(value);
    }
    return out.toString();
  }

  @Test
  void denseEntityRunScalesLinearly() {
    String html = repeat("&amp;", DENSE_ENTITIES);
    String markdown =
        assertTimeout(
            Duration.ofSeconds(5), () -> HtmlToMarkdown.convert(html, Options.defaults()));
    assertEquals(DENSE_ENTITIES, markdown.length());
  }

  @Test
  void denseEntityRunInsidePreScalesLinearly() {
    String html = "<pre>" + repeat("&amp;", DENSE_ENTITIES) + "</pre>";
    String markdown =
        assertTimeout(
            Duration.ofSeconds(5), () -> HtmlToMarkdown.convert(html, Options.defaults()));
    // The code fence wraps the text: "```\n" + text + "\n```".
    assertEquals(DENSE_ENTITIES + 8, markdown.length());
  }

  @Test
  void denseEntityRunInsideBlockquoteScalesLinearly() {
    String html = "<blockquote>" + repeat("&nbsp;", DENSE_ENTITIES) + "</blockquote>";
    assertTimeout(Duration.ofSeconds(5), () -> HtmlToMarkdown.convert(html, Options.defaults()));
  }

  @Test
  void cappedDenseRunScalesLinearly() {
    Options capped = Options.builder().maxNodeBytes(1_000_000).build();
    String html = repeat("&", 2 * DENSE_ENTITIES);
    String markdown =
        assertTimeout(Duration.ofSeconds(5), () -> HtmlToMarkdown.convert(html, capped));
    assertEquals(2 * DENSE_ENTITIES, markdown.length());
  }

  @Test
  void smallCapDoesNotRescanTheWholeRun() {
    Options capped = Options.builder().maxNodeBytes(64 * 1024).build();
    String html = repeat("&", 2 * DENSE_ENTITIES);
    String markdown =
        assertTimeout(Duration.ofSeconds(5), () -> HtmlToMarkdown.convert(html, capped));
    assertEquals(64 * 1024, markdown.length());
  }

  // === Streaming tokens that span chunks ===

  private static final int STREAM_CHUNK = 1024;

  private static String stream(String html, Options options) {
    MarkdownStream stream = HtmlToMarkdown.newStream(options);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < html.length(); i += STREAM_CHUNK) {
      out.append(stream.feed(html.substring(i, Math.min(html.length(), i + STREAM_CHUNK))));
    }
    out.append(stream.finish());
    return out.toString();
  }

  @Test
  void unterminatedStartTagStreamsLinearly() {
    String html = "<" + repeat("x", 8 * 1024 * 1024);
    String markdown = assertTimeout(Duration.ofSeconds(5), () -> stream(html, Options.defaults()));
    assertEquals("", markdown);
  }

  @Test
  void unterminatedEndTagStreamsLinearly() {
    String html = "</" + repeat("x", 4 * 1024 * 1024);
    String markdown = assertTimeout(Duration.ofSeconds(5), () -> stream(html, Options.defaults()));
    assertEquals("", markdown);
  }

  @Test
  void unterminatedQuotedAttributeStreamsLinearly() {
    String html = "<a href=\"" + repeat("x", 16 * 1024 * 1024);
    String markdown = assertTimeout(Duration.ofSeconds(5), () -> stream(html, Options.defaults()));
    assertEquals("", markdown);
  }

  @Test
  void cappedStreamingTagDropsAtTheCap() {
    Options capped = Options.builder().maxNodeBytes(4096).build();
    String html = "<" + repeat("x", 1024 * 1024) + ">after";
    MdreamResult oneShot = HtmlToMarkdown.convertResult(html, capped);
    MarkdownStream stream = HtmlToMarkdown.newStream(capped);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < html.length(); i += STREAM_CHUNK) {
      out.append(stream.feed(html.substring(i, Math.min(html.length(), i + STREAM_CHUNK))));
    }
    out.append(stream.finish());
    assertEquals(oneShot.markdown(), out.toString());
    assertEquals(oneShot.truncated(), stream.truncated());
  }

  @Test
  void spanningTagNamesMatchOneShotAtEverySplit() {
    String longName = repeat("x", 48);
    String[] inputs = {
      "<" + longName + ">after",
      "<" + longName,
      "</" + longName + ">after",
      "</" + longName,
      "<title><" + longName + ">after",
      "<title>hi</" + longName + ">after",
      "<a href=\"" + longName,
      "<a " + longName + ">after",
    };
    for (String html : inputs) {
      for (long cap : new long[] {0, 32, 4096}) {
        Options options = cap == 0 ? Options.defaults() : Options.builder().maxNodeBytes(cap).build();
        MdreamResult oneShot = HtmlToMarkdown.convertResult(html, options);
        for (int split = 1; split < html.length(); split++) {
          MarkdownStream stream = HtmlToMarkdown.newStream(options);
          String markdown =
              stream.feed(html.substring(0, split))
                  + stream.feed(html.substring(split))
                  + stream.finish();
          assertEquals(oneShot.markdown(), markdown, "cap=" + cap + " split=" + split + " " + html);
          assertEquals(oneShot.truncated(), stream.truncated(), "cap=" + cap + " split=" + split);
        }
      }
    }
  }
}
