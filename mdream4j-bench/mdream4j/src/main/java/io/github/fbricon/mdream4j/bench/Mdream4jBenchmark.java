/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.github.fbricon.mdream4j.HtmlToMarkdown;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** One-shot, default-options conversion with mdream4j. */
@State(Scope.Benchmark)
public class Mdream4jBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    return HtmlToMarkdown::convert;
  }

  @Override
  protected boolean checksGolden() {
    return true;
  }
}
