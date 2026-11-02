/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * Immutable per-tag conversion flags.
 *
 * <p>Mirrors Rust {@code TagHandler}. Instances are created once in {@link Tags} and shared.
 */
final class TagHandler {

  /** Void element: no content, no closing tag. */
  final boolean isSelfClosing;

  /** Raw-text element that never nests other elements. */
  final boolean isNonNesting;

  /** Runs of whitespace inside the element collapse to a single space. */
  final boolean collapsesInnerWhiteSpace;

  /** Inline element: no line breaks are introduced around its content. */
  final boolean isInline;

  /** Blank lines to surround the element with, or {@code null} for none. */
  final int[] spacing;

  /** Text children are not rendered (script, style, inert rawtext). */
  final boolean excludesTextNodes;

  /** {@code ATTR_*} bitmask of the attribute names the renderer reads. */
  final int wantedAttrs;

  TagHandler(
      boolean isSelfClosing,
      boolean isNonNesting,
      boolean collapsesInnerWhiteSpace,
      boolean isInline,
      int[] spacing,
      boolean excludesTextNodes,
      int wantedAttrs) {
    this.isSelfClosing = isSelfClosing;
    this.isNonNesting = isNonNesting;
    this.collapsesInnerWhiteSpace = collapsesInnerWhiteSpace;
    this.isInline = isInline;
    this.spacing = spacing;
    this.excludesTextNodes = excludesTextNodes;
    this.wantedAttrs = wantedAttrs;
  }
}
