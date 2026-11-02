/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

/**
 * One-based line range a chunk covers in the source document.
 *
 * <p>Both ends are inclusive, matching the reference splitter.
 */
public final class ChunkLoc {

  private final int from;
  private final int to;

  /**
   * Creates a line range.
   *
   * @param from first line, one-based
   * @param to last line, one-based and inclusive
   */
  public ChunkLoc(int from, int to) {
    this.from = from;
    this.to = to;
  }

  /** First line, one-based. */
  public int from() {
    return from;
  }

  /** Last line, one-based and inclusive. */
  public int to() {
    return to;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ChunkLoc)) {
      return false;
    }
    ChunkLoc other = (ChunkLoc) o;
    return from == other.from && to == other.to;
  }

  @Override
  public int hashCode() {
    return 31 * from + to;
  }

  @Override
  public String toString() {
    return "ChunkLoc{from=" + from + ", to=" + to + '}';
  }
}
