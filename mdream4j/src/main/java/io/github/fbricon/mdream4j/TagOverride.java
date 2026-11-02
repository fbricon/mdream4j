/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Objects;

/**
 * Per-tag rendering override, keyed by lowercase tag name in
 * {@link Plugins.Builder#tagOverride(String, TagOverride)}.
 *
 * <p>Every property is optional; unset properties fall back to the tag's
 * built-in behaviour.
 */
public final class TagOverride {

  private final String enter;
  private final String exit;
  private final int spacingBefore;
  private final int spacingAfter;
  private final Boolean inline;
  private final Boolean selfClosing;
  private final Boolean collapseInnerWhiteSpace;
  private final String alias;

  private TagOverride(Builder builder) {
    this.enter = builder.enter;
    this.exit = builder.exit;
    this.spacingBefore = builder.spacingBefore;
    this.spacingAfter = builder.spacingAfter;
    this.inline = builder.inline;
    this.selfClosing = builder.selfClosing;
    this.collapseInnerWhiteSpace = builder.collapseInnerWhiteSpace;
    this.alias = builder.alias;
  }

  /**
   * Creates an inline override that wraps the tag's content with {@code enter}
   * and {@code exit}, for example {@code wrap("^", "^")} to render
   * {@code <sup>} as {@code ^text^}.
   */
  public static TagOverride wrap(String enter, String exit) {
    return builder().enter(enter).exit(exit).inline(true).build();
  }

  /**
   * Creates an override that renders the tag exactly like the named built-in
   * tag, for example {@code alias("em")} for {@code <dfn>}.
   *
   * <p>Unknown tag names are rejected by {@link Builder#build()}.
   */
  public static TagOverride alias(String tagName) {
    return builder().alias(tagName).build();
  }

  /** Returns a builder with every property unset. */
  public static Builder builder() {
    return new Builder();
  }

  /** Markdown emitted when entering the tag, or {@code null}. */
  public String enter() {
    return enter;
  }

  /** Markdown emitted when exiting the tag, or {@code null}. */
  public String exit() {
    return exit;
  }

  /** Blank lines inserted before the tag's output. */
  public int spacingBefore() {
    return spacingBefore;
  }

  /** Blank lines inserted after the tag's output. */
  public int spacingAfter() {
    return spacingAfter;
  }

  /** Whether the tag is inline, or {@code null} for the built-in default. */
  public Boolean inline() {
    return inline;
  }

  /** Whether the tag is void/self-closing, or {@code null}. */
  public Boolean selfClosing() {
    return selfClosing;
  }

  /** Whether inner whitespace collapses, or {@code null}. */
  public Boolean collapseInnerWhiteSpace() {
    return collapseInnerWhiteSpace;
  }

  /** Built-in tag name to alias, or {@code null}. */
  public String alias() {
    return alias;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TagOverride)) {
      return false;
    }
    TagOverride other = (TagOverride) o;
    return spacingBefore == other.spacingBefore
        && spacingAfter == other.spacingAfter
        && Objects.equals(enter, other.enter)
        && Objects.equals(exit, other.exit)
        && Objects.equals(inline, other.inline)
        && Objects.equals(selfClosing, other.selfClosing)
        && Objects.equals(collapseInnerWhiteSpace, other.collapseInnerWhiteSpace)
        && Objects.equals(alias, other.alias);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        enter, exit, spacingBefore, spacingAfter, inline, selfClosing, collapseInnerWhiteSpace,
        alias);
  }

  @Override
  public String toString() {
    return "TagOverride{enter=" + enter + ", exit=" + exit
        + ", spacing=" + spacingBefore + "," + spacingAfter
        + ", inline=" + inline + ", selfClosing=" + selfClosing
        + ", collapseInnerWhiteSpace=" + collapseInnerWhiteSpace
        + ", alias=" + alias + '}';
  }

  /** Builder for {@link TagOverride}. */
  public static final class Builder {

    private String enter;
    private String exit;
    private int spacingBefore;
    private int spacingAfter;
    private Boolean inline;
    private Boolean selfClosing;
    private Boolean collapseInnerWhiteSpace;
    private String alias;

    private Builder() {}

    /** Sets the markdown emitted when entering the tag. */
    public Builder enter(String markdown) {
      this.enter = Objects.requireNonNull(markdown, "markdown");
      return this;
    }

    /** Sets the markdown emitted when exiting the tag. */
    public Builder exit(String markdown) {
      this.exit = Objects.requireNonNull(markdown, "markdown");
      return this;
    }

    /** Sets the blank lines inserted before and after the tag's output. */
    public Builder spacing(int before, int after) {
      if (before < 0 || before > 255 || after < 0 || after > 255) {
        throw new IllegalArgumentException("spacing must be between 0 and 255");
      }
      this.spacingBefore = before;
      this.spacingAfter = after;
      return this;
    }

    /** Sets whether the tag is inline. */
    public Builder inline(boolean enabled) {
      this.inline = enabled;
      return this;
    }

    /** Sets whether the tag is void/self-closing. */
    public Builder selfClosing(boolean enabled) {
      this.selfClosing = enabled;
      return this;
    }

    /** Sets whether runs of inner whitespace collapse to one space. */
    public Builder collapseInnerWhiteSpace(boolean enabled) {
      this.collapseInnerWhiteSpace = enabled;
      return this;
    }

    /** Aliases the tag to a built-in tag name. */
    public Builder alias(String tagName) {
      this.alias = Objects.requireNonNull(tagName, "tagName");
      return this;
    }

    /** Builds an immutable {@link TagOverride}. */
    public TagOverride build() {
      return new TagOverride(this);
    }
  }
}
