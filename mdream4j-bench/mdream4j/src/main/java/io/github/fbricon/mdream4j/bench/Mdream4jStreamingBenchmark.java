/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.bench;

import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.Options;
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

/** One-shot versus 64 Ki-code-unit streaming on the same fixture. */
@State(Scope.Benchmark)
public class Mdream4jStreamingBenchmark {

  /** Streaming chunk size in UTF-16 code units. */
  private static final int CHUNK_SIZE = 64 * 1024;

  @Param({"vuejs-docs", "wikipedia", "github", "wikipedia-xl"})
  public String fixtureName;

  private String html;
  private String expected;

  @Setup(Level.Trial)
  public void setUp() {
    html = Fixtures.load(fixtureName);
    expected = Digests.sha256(HtmlToMarkdown.convert(html));
  }

  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.Throughput})
  @Warmup(iterations = 3, time = 2)
  @Measurement(iterations = 5, time = 2)
  @Fork(value = 3, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public String stream() {
    return streamToString();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    String digest = Digests.sha256(streamToString());
    if (!digest.equals(expected)) {
      throw new AssertionError(fixtureName + ": streaming output is not deterministic");
    }
  }

  private String streamToString() {
    StringBuilder output = new StringBuilder(html.length() / 2);
    // Reuse the benchmark body without JMH measuring this call.
    MarkdownStream stream = HtmlToMarkdown.newStream(Options.defaults());
    for (int offset = 0; offset < html.length(); offset += CHUNK_SIZE) {
      output.append(stream.feed(html.substring(offset, Math.min(offset + CHUNK_SIZE, html.length()))));
    }
    output.append(stream.finish());
    return output.toString();
  }
}
