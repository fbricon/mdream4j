/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Filter plugin: include or exclude elements matching CSS selectors.
 *
 * <p>When {@link #include()} is non-empty, only matching subtrees are
 * converted. Otherwise, matching subtrees are removed. If both lists are set,
 * {@code include} takes precedence.
 */
public final class Filter {

  private final List<String> include;
  private final List<String> exclude;
  private final boolean processChildren;

  private Filter(Builder builder) {
    this.include = Collections.unmodifiableList(new ArrayList<>(builder.include));
    this.exclude = Collections.unmodifiableList(new ArrayList<>(builder.exclude));
    this.processChildren = builder.processChildren;
  }

  /** Includes only elements matching the given selectors. */
  public static Filter include(String... selectors) {
    return builder().include(selectors).build();
  }

  /** Excludes elements matching the given selectors. */
  public static Filter exclude(String... selectors) {
    return builder().exclude(selectors).build();
  }

  /** Returns a builder for a filter with no selectors. */
  public static Builder builder() {
    return new Builder();
  }

  /** Selectors to keep, or an empty list. */
  public List<String> include() {
    return include;
  }

  /** Selectors to remove, or an empty list. */
  public List<String> exclude() {
    return exclude;
  }

  /**
   * Whether an included element's subtree is kept; defaults to {@code true}.
   *
   * <p>With {@code false}, only elements that match an {@code include} selector themselves are
   * kept, even inside a matching ancestor.
   */
  public boolean processChildren() {
    return processChildren;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Filter)) {
      return false;
    }
    Filter other = (Filter) o;
    return processChildren == other.processChildren
        && include.equals(other.include)
        && exclude.equals(other.exclude);
  }

  @Override
  public int hashCode() {
    return Objects.hash(include, exclude, processChildren);
  }

  @Override
  public String toString() {
    return "Filter{include=" + include + ", exclude=" + exclude
        + ", processChildren=" + processChildren + '}';
  }

  /** Builder for {@link Filter}. */
  public static final class Builder {

    private final List<String> include = new ArrayList<>();
    private final List<String> exclude = new ArrayList<>();
    private boolean processChildren = true;

    private Builder() {}

    /** Adds selectors to keep. */
    public Builder include(String... selectors) {
      Objects.requireNonNull(selectors, "selectors");
      for (String selector : selectors) {
        include.add(Objects.requireNonNull(selector, "selector"));
      }
      return this;
    }

    /** Adds selectors to remove. */
    public Builder exclude(String... selectors) {
      Objects.requireNonNull(selectors, "selectors");
      for (String selector : selectors) {
        exclude.add(Objects.requireNonNull(selector, "selector"));
      }
      return this;
    }

    /** Sets whether children of an excluded element are still processed. */
    public Builder processChildren(boolean enabled) {
      this.processChildren = enabled;
      return this;
    }

    /** Builds an immutable {@link Filter}. */
    public Filter build() {
      if (include.isEmpty() && exclude.isEmpty()) {
        throw new IllegalStateException("a filter needs at least one include or exclude selector");
      }
      return new Filter(this);
    }
  }
}
