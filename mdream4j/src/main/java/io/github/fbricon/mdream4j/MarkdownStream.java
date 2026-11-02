/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

/**
 * Incremental HTML to Markdown conversion.
 *
 * <p>Concatenating every value returned by {@link #feed(CharSequence)} with the
 * value returned by {@link #finish()} is byte-identical to one-shot
 * conversion of the concatenated input. A stream is not thread-safe; create
 * one per conversion.
 */
public interface MarkdownStream {

  /**
   * Feeds the next chunk of HTML.
   *
   * @param html the next chunk; must not be {@code null}
   * @return markdown that is complete at this point, possibly empty
   */
  String feed(CharSequence html);

  /**
   * Signals end of input.
   *
   * @return the remaining markdown
   */
  String finish();

  /** Whether {@link Options#maxNodeBytes()} dropped content. */
  boolean truncated();
}
