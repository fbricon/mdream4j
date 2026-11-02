/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.Options;
import java.util.Objects;

/**
 * Streaming converter state.
 *
 * <p>Mirrors {@code MarkdownStreamProcessor} in {@code lib.rs}: each chunk is parsed on top of the
 * unconsumed tail of the previous one, and the output that is already final is handed out after
 * every feed.
 */
final class ConverterStream implements MarkdownStream {

  /** Initial buffer capacity for a stream, matching {@code STREAM_START_CAPACITY}. */
  static final int STREAM_START_CAPACITY = 4096;

  private final ConvertState state;
  private final StringBuilder carry = new StringBuilder();
  private boolean finished;

  ConverterStream(Options options) {
    this(options, false);
  }

  /**
   * Builds a stream with draining optionally disabled.
   *
   * <p>Drain is an optimisation that must never change the output, so the tests compare the two
   * modes; mirrors {@code MarkdownStreamProcessor::new_drain_disabled}.
   */
  ConverterStream(Options options, boolean disableDrain) {
    this.state = new ConvertState(options, STREAM_START_CAPACITY);
    this.state.streaming = true;
    this.state.disableDrain = disableDrain;
  }

  @Override
  public String feed(CharSequence html) {
    Objects.requireNonNull(html, "html");
    if (finished) {
      throw new IllegalStateException("stream already finished");
    }
    String chunk = html.toString();
    if (carry.length() == 0) {
      int consumed = ParseOps.processHtml(state, chunk);
      carry.append(chunk, consumed, chunk.length());
    } else {
      carry.append(chunk);
      String buffer = carry.toString();
      int consumed = ParseOps.processHtml(state, buffer);
      carry.delete(0, consumed);
    }
    return OutputOps.getMarkdownChunk(state);
  }

  @Override
  public String finish() {
    if (finished) {
      return "";
    }
    finished = true;
    String buffer = carry.toString();
    carry.setLength(0);
    int consumed = buffer.isEmpty() ? 0 : ParseOps.processHtml(state, buffer);
    ParseOps.finalizeState(state, buffer.substring(consumed));
    return OutputOps.getFinalMarkdownChunk(state);
  }

  @Override
  public boolean truncated() {
    return state.truncated;
  }
}
