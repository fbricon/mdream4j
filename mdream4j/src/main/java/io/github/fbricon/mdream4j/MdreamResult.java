/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a conversion that may also carry extraction and frontmatter data.
 */
public final class MdreamResult {

  private final String markdown;
  private final List<ExtractedElement> extracted;
  private final Map<String, String> frontmatter;
  private final boolean truncated;

  /**
   * Creates a result.
   *
   * @param markdown the converted output
   * @param extracted extracted elements, or {@code null} when extraction was not requested
   * @param frontmatter frontmatter fields in document order, or {@code null} when not requested
   * @param truncated whether {@link Options#maxNodeBytes()} fired
   */
  public MdreamResult(
      String markdown,
      List<ExtractedElement> extracted,
      Map<String, String> frontmatter,
      boolean truncated) {
    this.markdown = Objects.requireNonNull(markdown, "markdown");
    this.extracted = extracted == null
        ? null
        : Collections.unmodifiableList(new ArrayList<>(extracted));
    this.frontmatter = frontmatter == null
        ? null
        : Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
    this.truncated = truncated;
  }

  /** The converted output. */
  public String markdown() {
    return markdown;
  }

  /** Extracted elements, or {@code null} when extraction was not requested. */
  public List<ExtractedElement> extracted() {
    return extracted;
  }

  /** Frontmatter fields in document order, or {@code null} when not requested. */
  public Map<String, String> frontmatter() {
    return frontmatter;
  }

  /**
   * Whether {@link Options#maxNodeBytes()} dropped content. {@code false}
   * guarantees the output equals an uncapped conversion; {@code true} is
   * conservative.
   */
  public boolean truncated() {
    return truncated;
  }

  @Override
  public String toString() {
    return "MdreamResult{markdownLength=" + markdown.length()
        + ", extracted=" + (extracted == null ? "null" : extracted.size())
        + ", frontmatter=" + frontmatter
        + ", truncated=" + truncated + '}';
  }
}
