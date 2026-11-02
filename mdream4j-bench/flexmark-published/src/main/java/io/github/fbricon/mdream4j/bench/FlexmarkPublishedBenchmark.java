/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** flexmark 0.64.8 as published, with the jsoup version it declares. */
@State(Scope.Benchmark)
public class FlexmarkPublishedBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    return FlexmarkHtmlConverter.builder().build()::convert;
  }
}
