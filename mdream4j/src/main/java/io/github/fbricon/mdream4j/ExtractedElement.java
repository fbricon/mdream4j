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
 * An element captured by the extraction plugin.
 */
public final class ExtractedElement {

  private final String selector;
  private final String tagName;
  private final String textContent;
  private final Map<String, String> attributes;

  /**
   * Creates an extracted element.
   *
   * @param selector the selector that matched
   * @param tagName the element's lowercase tag name
   * @param textContent the element's text content
   * @param attributes the element's attributes, in document order
   */
  public ExtractedElement(
      String selector, String tagName, String textContent, Map<String, String> attributes) {
    this.selector = Objects.requireNonNull(selector, "selector");
    this.tagName = Objects.requireNonNull(tagName, "tagName");
    this.textContent = Objects.requireNonNull(textContent, "textContent");
    this.attributes =
        Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes")));
  }

  /** The selector that matched. */
  public String selector() {
    return selector;
  }

  /** The element's lowercase tag name. */
  public String tagName() {
    return tagName;
  }

  /** The element's text content. */
  public String textContent() {
    return textContent;
  }

  /** The element's attributes, in document order. */
  public Map<String, String> attributes() {
    return attributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ExtractedElement)) {
      return false;
    }
    ExtractedElement other = (ExtractedElement) o;
    return selector.equals(other.selector)
        && tagName.equals(other.tagName)
        && textContent.equals(other.textContent)
        && attributes.equals(other.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(selector, tagName, textContent, attributes);
  }

  @Override
  public String toString() {
    return "ExtractedElement{selector=" + selector + ", tagName=" + tagName
        + ", textContent=" + textContent + ", attributes=" + attributes + '}';
  }
}
