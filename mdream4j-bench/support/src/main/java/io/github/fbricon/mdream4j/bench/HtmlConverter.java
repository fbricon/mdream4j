/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

/** A benchmarked HTML-to-Markdown implementation. */
@FunctionalInterface
public interface HtmlConverter {

  /**
   * Converts one HTML document.
   *
   * @param html the input document
   * @return the converted output
   */
  String convert(String html);
}
