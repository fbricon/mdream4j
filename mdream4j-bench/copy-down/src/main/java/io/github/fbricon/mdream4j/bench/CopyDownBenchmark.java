/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.github.furstenheim.CopyDown;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** copy_down 1.1 with its default options. */
@State(Scope.Benchmark)
public class CopyDownBenchmark extends ConvertBenchmarkBase {

  @Override
  protected HtmlConverter createConverter() {
    CopyDown copyDown = new CopyDown();
    return copyDown::convert;
  }
}
