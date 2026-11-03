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
 * Guards against quadratic scanning of long text runs.
 *
 * <p>Runs that cannot be split (non-batchable text such as {@code &}, and the contexts where
 * splitting would change the output) used to rescan the whole accumulated buffer for every token.
 * The inputs here are large enough that the old behaviour took tens of seconds while the linear
 * path takes milliseconds, so a generous timeout is still a reliable regression signal.
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
}
