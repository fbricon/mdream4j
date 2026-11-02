/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import org.jsoup.Jsoup;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** Text floor: jsoup parse plus text extraction, no Markdown. */
@State(Scope.Benchmark)
public class JsoupTextBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    return html -> Jsoup.parse(html).text();
  }
}
