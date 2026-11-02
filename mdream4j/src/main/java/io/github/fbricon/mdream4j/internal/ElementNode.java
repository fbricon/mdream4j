/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * One open or retired HTML element. Mirrors Rust {@code ElementNode}.
 *
 * <p>The default instance is a reusable empty slot; the parser fills it in place.
 */
final class ElementNode {

  Attributes attributes = new Attributes();
  NodeExtras extras;

  int depth;
  int index;
  int currentWalkIndex;

  /** Child-content counter used by whitespace and empty-link handling. */
  int childTextNodeIndex;

  /** Built-in tag id, or {@code -1} for a custom element. */
  int tagId = -1;

  boolean containsWhitespace;
  boolean excludedFromMarkdown;

  /**
   * A plugin skipped this node's start, so its end must not be emitted either. Unlike {@link
   * #excludedFromMarkdown}, the node's descendants stay eligible.
   */
  boolean enterSkipped;

  /** Cached from the tag handler - avoids repeated {@link Tags#getTagHandler} lookups. */
  boolean isInline;

  boolean excludesTextNodes;
  boolean isNonNesting;
  boolean collapsesInnerWhiteSpace;

  /** Blank lines to surround the element with, or {@code null}. */
  int[] spacing;

  ElementNode() {}

  String customName() {
    return extras != null ? extras.customName : null;
  }

  TailwindData tailwind() {
    return extras != null ? extras.tailwind : null;
  }

  void setTailwind(TailwindData data) {
    if (extras == null) {
      extras = new NodeExtras(null);
    }
    extras.tailwind = data;
  }

  /**
   * Get the tag name. For built-in tags, derives from {@code tagId}. For custom tags, uses {@code
   * customName}.
   */
  String name() {
    String custom = customName();
    if (custom != null) {
      return custom;
    }
    if (tagId >= 0) {
      return Consts.TAG_NAMES[tagId];
    }
    return "";
  }

  void reset() {
    attributes.clear();
    extras = null;
    depth = 0;
    index = 0;
    currentWalkIndex = 0;
    childTextNodeIndex = 0;
    tagId = -1;
    containsWhitespace = false;
    excludedFromMarkdown = false;
    enterSkipped = false;
    isInline = false;
    excludesTextNodes = false;
    isNonNesting = false;
    collapsesInnerWhiteSpace = false;
    spacing = null;
  }
}
