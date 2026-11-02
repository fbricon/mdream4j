/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import com.overzealous.remark.Options;
import com.overzealous.remark.Remark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** remark 1.2.0 with its GitHub profile, which enables tables. */
@State(Scope.Benchmark)
public class RemarkBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    Remark remark = new Remark(Options.github());
    return remark::convert;
  }
}
