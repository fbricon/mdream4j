/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata describing the position and structure of a {@link MarkdownChunk}.
 */
public final class ChunkMetadata {

  private final Map<String, String> headers;
  private final String code;
  private final ChunkLoc loc;

  /**
   * Creates metadata.
   *
   * @param headers heading hierarchy in document order, keyed by {@code h1} to
   *     {@code h6}; {@code null} when the chunk has no enclosing heading
   * @param code code-fence language, or {@code null} when the chunk has none
   * @param loc line range, or {@code null} when unknown
   */
  public ChunkMetadata(Map<String, String> headers, String code, ChunkLoc loc) {
    this.headers = headers == null
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    this.code = code;
    this.loc = loc;
  }

  /**
   * Heading hierarchy in effect at the chunk position, keyed by {@code h1} to
   * {@code h6} and ordered from the outermost heading inwards.
   *
   * <p>Returns an empty map when no heading encloses the chunk.
   */
  public Map<String, String> headers() {
    return headers;
  }

  /** Code-fence language of the chunk, or {@code null} when it has none. */
  public String code() {
    return code;
  }

  /** Line range covered by the chunk, or {@code null} when unknown. */
  public ChunkLoc loc() {
    return loc;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ChunkMetadata)) {
      return false;
    }
    ChunkMetadata other = (ChunkMetadata) o;
    return headers.equals(other.headers)
        && Objects.equals(code, other.code)
        && Objects.equals(loc, other.loc);
  }

  @Override
  public int hashCode() {
    return Objects.hash(headers, code, loc);
  }

  @Override
  public String toString() {
    return "ChunkMetadata{headers=" + headers + ", code=" + code + ", loc=" + loc + '}';
  }
}
