/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import io.github.fbricon.mdream4j.internal.Engine;
import java.util.Objects;

/**
 * Static facade for one-shot HTML to Markdown conversion.
 *
 * <p>For repeated conversions, prefer {@link MdreamConverter}: it reuses the
 * immutable option snapshot without re-validating it. Both are thread-safe.
 *
 * <pre>{@code
 * String markdown = HtmlToMarkdown.convert(html);
 *
 * String wrapped = HtmlToMarkdown.convert(
 *     html,
 *     Options.builder().wrapWidth(80).clean(Clean.all()).build());
 * }</pre>
 */
public final class HtmlToMarkdown {

  private HtmlToMarkdown() {}

  /**
   * Converts HTML to Markdown with the default options.
   *
   * @param html the HTML fragment or document; must not be {@code null}
   * @return the Markdown output
   */
  public static String convert(CharSequence html) {
    return Engine.convert(html, Options.defaults());
  }

  /**
   * Converts HTML to Markdown with the given options.
   *
   * @param html the HTML fragment or document; must not be {@code null}
   * @param options the conversion options; must not be {@code null}
   * @return the converted output
   */
  public static String convert(CharSequence html, Options options) {
    return Engine.convert(html, Objects.requireNonNull(options, "options"));
  }

  /**
   * Converts HTML and returns extraction/frontmatter metadata alongside the
   * output.
   *
   * @param html the HTML fragment or document; must not be {@code null}
   * @param options the conversion options; must not be {@code null}
   * @return the conversion result
   */
  public static MdreamResult convertResult(CharSequence html, Options options) {
    return Engine.convertResult(html, Objects.requireNonNull(options, "options"));
  }

  /**
   * Creates a streaming conversion.
   *
   * @param options the conversion options; must not be {@code null}
   * @return a new stream
   */
  public static MarkdownStream newStream(Options options) {
    return Engine.newStream(Objects.requireNonNull(options, "options"));
  }
}
