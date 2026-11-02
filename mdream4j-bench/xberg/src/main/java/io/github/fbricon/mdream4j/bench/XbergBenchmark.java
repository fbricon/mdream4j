/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.xberg.htmltomarkdown.HtmlToMarkdown;
import io.xberg.htmltomarkdown.HtmlToMarkdownRsException;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** xberg html-to-markdown: Java 25 FFM over a Rust core with bundled natives. */
@State(Scope.Benchmark)
public class XbergBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    return html -> {
      try {
        return HtmlToMarkdown.convert(html).content();
      } catch (HtmlToMarkdownRsException e) {
        throw new IllegalStateException(e);
      }
    };
  }
}
