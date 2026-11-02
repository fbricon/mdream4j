/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Shared JMH shape: one row per fixture, one conversion per invocation.
 *
 * <p>Every contender module extends this class in its own Maven module, so each contender runs in
 * a JVM with its own dependency set. That isolation matters: remark needs jsoup 1.9.2 while
 * flexmark needs 1.15.4 or newer, and both versions cannot share a classpath.
 *
 * <p>The trial converts once in {@link #setUp()} and once in {@link #tearDown()}. The digests must
 * match, which catches an input mutated by a converter and work optimised away across forks.
 * mdream4j additionally compares that digest with the checked-in Rust reference digest.
 */
@State(Scope.Benchmark)
public abstract class ConvertBenchmarkBase {

  @Param({
    "nuxt",
    "vuejs-docs",
    "wikipedia",
    "mdn",
    "react",
    "github",
    "wikipedia-xl",
    "depth-512",
    "table-huge",
    "node-1mb",
    "text-10mb"
  })
  public String fixtureName;

  private String html;
  private HtmlConverter converter;
  private String trialDigest;

  /** Builds the converter under test; called once per trial. */
  protected abstract HtmlConverter createConverter();

  /** Whether the output must equal the reference digest. */
  protected boolean checksGolden() {
    return false;
  }

  @Setup(Level.Trial)
  public void setUp() {
    html = Fixtures.load(fixtureName);
    converter = createConverter();
    String output = converter.convert(html);
    if (checksGolden()) {
      Digests.assertGolden(fixtureName, output);
    }
    trialDigest = Digests.sha256(output);
  }

  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.Throughput})
  @Warmup(iterations = 3, time = 2)
  @Measurement(iterations = 5, time = 2)
  @Fork(value = 3, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public String convert() {
    return converter.convert(html);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    String digest = Digests.sha256(converter.convert(html));
    if (!digest.equals(trialDigest)) {
      throw new AssertionError(
          fixtureName + ": output is not deterministic (got " + digest + ')');
    }
  }
}
