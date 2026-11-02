/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Objects;

/**
 * A single chunk of split converted output with its metadata.
 */
public final class MarkdownChunk {

  private final String content;
  private final ChunkMetadata metadata;

  /**
   * Creates a chunk.
   *
   * @param content the chunk text; must not be {@code null}
   * @param metadata the chunk metadata; must not be {@code null}
   */
  public MarkdownChunk(String content, ChunkMetadata metadata) {
    this.content = Objects.requireNonNull(content, "content");
    this.metadata = Objects.requireNonNull(metadata, "metadata");
  }

  /** The chunk text. */
  public String content() {
    return content;
  }

  /** Metadata for this chunk. */
  public ChunkMetadata metadata() {
    return metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MarkdownChunk)) {
      return false;
    }
    MarkdownChunk other = (MarkdownChunk) o;
    return content.equals(other.content) && metadata.equals(other.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, metadata);
  }

  @Override
  public String toString() {
    return "MarkdownChunk{content=" + content + ", metadata=" + metadata + '}';
  }
}
