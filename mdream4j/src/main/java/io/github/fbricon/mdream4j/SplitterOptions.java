/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Arrays;

/**
 * Options for splitting converted output into chunks.
 *
 * <p>Use {@link #defaults()} or {@link #builder()}. Instances are immutable and
 * safe to share across threads.
 */
public final class SplitterOptions {

  private static final int[] DEFAULT_SPLIT_LEVELS = {2, 3, 4, 5, 6};

  private static final SplitterOptions DEFAULTS = builder().build();

  private final int[] headersToSplitOn;
  private final boolean returnEachLine;
  private final boolean stripHeaders;
  private final int chunkSize;
  private final int chunkOverlap;

  private SplitterOptions(Builder builder) {
    this.headersToSplitOn = builder.headersToSplitOn;
    this.returnEachLine = builder.returnEachLine;
    this.stripHeaders = builder.stripHeaders;
    this.chunkSize = builder.chunkSize;
    this.chunkOverlap = builder.chunkOverlap;
  }

  /**
   * Returns the default options: split on h2 to h6, strip header lines, 1000
   * bytes per chunk and 200 bytes of overlap.
   */
  public static SplitterOptions defaults() {
    return DEFAULTS;
  }

  /** Returns a builder initialised with the default options. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Heading levels to split on, each from 1 to 6.
   *
   * <p>Levels outside that range are ignored.
   *
   * @return a copy of the configured levels
   */
  public int[] headersToSplitOn() {
    return headersToSplitOn.clone();
  }

  /** Whether every non-blank line becomes its own chunk. */
  public boolean returnEachLine() {
    return returnEachLine;
  }

  /** Whether header lines are removed from chunk content. */
  public boolean stripHeaders() {
    return stripHeaders;
  }

  /** Maximum chunk size in UTF-8 bytes. */
  public int chunkSize() {
    return chunkSize;
  }

  /** Overlap in UTF-8 bytes between size-based chunks. */
  public int chunkOverlap() {
    return chunkOverlap;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SplitterOptions)) {
      return false;
    }
    SplitterOptions other = (SplitterOptions) o;
    return Arrays.equals(headersToSplitOn, other.headersToSplitOn)
        && returnEachLine == other.returnEachLine
        && stripHeaders == other.stripHeaders
        && chunkSize == other.chunkSize
        && chunkOverlap == other.chunkOverlap;
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(headersToSplitOn);
    result = 31 * result + Boolean.hashCode(returnEachLine);
    result = 31 * result + Boolean.hashCode(stripHeaders);
    result = 31 * result + chunkSize;
    result = 31 * result + chunkOverlap;
    return result;
  }

  @Override
  public String toString() {
    return "SplitterOptions{headersToSplitOn=" + Arrays.toString(headersToSplitOn)
        + ", returnEachLine=" + returnEachLine
        + ", stripHeaders=" + stripHeaders
        + ", chunkSize=" + chunkSize
        + ", chunkOverlap=" + chunkOverlap + '}';
  }

  /** Builder for {@link SplitterOptions}. */
  public static final class Builder {

    private int[] headersToSplitOn = DEFAULT_SPLIT_LEVELS;
    private boolean returnEachLine;
    private boolean stripHeaders = true;
    private int chunkSize = 1000;
    private int chunkOverlap = 200;

    private Builder() {}

    /**
     * Sets the heading levels to split on.
     *
     * @param levels levels from 1 to 6; others are ignored during splitting
     */
    public Builder headersToSplitOn(int... levels) {
      this.headersToSplitOn = levels.clone();
      return this;
    }

    /** Returns each non-blank line as an individual chunk. */
    public Builder returnEachLine(boolean value) {
      this.returnEachLine = value;
      return this;
    }

    /** Removes header lines from chunk content when {@code true}. */
    public Builder stripHeaders(boolean value) {
      this.stripHeaders = value;
      return this;
    }

    /** Sets the maximum chunk size in UTF-8 bytes. */
    public Builder chunkSize(int size) {
      if (size < 0) {
        throw new IllegalArgumentException("chunkSize must be >= 0");
      }
      this.chunkSize = size;
      return this;
    }

    /** Sets the overlap in UTF-8 bytes between size-based chunks. */
    public Builder chunkOverlap(int overlap) {
      if (overlap < 0) {
        throw new IllegalArgumentException("chunkOverlap must be >= 0");
      }
      this.chunkOverlap = overlap;
      return this;
    }

    /** Builds immutable {@link SplitterOptions}. */
    public SplitterOptions build() {
      return new SplitterOptions(this);
    }
  }
}
