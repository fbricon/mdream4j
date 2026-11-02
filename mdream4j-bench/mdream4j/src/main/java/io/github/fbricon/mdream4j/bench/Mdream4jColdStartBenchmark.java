/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.github.fbricon.mdream4j.HtmlToMarkdown;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * First conversion in a fresh JVM.
 *
 * <p>Every measurement iteration gets its own fork, so class loading of mdream4j is included. JVM
 * process startup itself is measured separately by the native-image smoke test.
 */
@State(Scope.Benchmark)
public class Mdream4jColdStartBenchmark {

  @Param({"nuxt"})
  public String fixtureName;

  private String html;

  @Setup
  public void setUp() {
    html = Fixtures.load(fixtureName);
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @Warmup(iterations = 0)
  @Measurement(iterations = 1)
  @Fork(value = 10, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public String firstConversion() {
    return HtmlToMarkdown.convert(html);
  }
}
