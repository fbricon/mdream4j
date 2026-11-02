/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Plugin configuration: filters, frontmatter, extraction, Tailwind and tag
 * overrides.
 *
 * <p>Every feature is disabled by default.
 */
public final class Plugins {

  private static final Plugins NONE = builder().build();

  private final Filter filter;
  private final boolean isolateMain;
  private final Frontmatter frontmatter;
  private final boolean tailwind;
  private final Extraction extraction;
  private final Map<String, TagOverride> tagOverrides;

  private Plugins(Builder builder) {
    this.filter = builder.filter;
    this.isolateMain = builder.isolateMain;
    this.frontmatter = builder.frontmatter;
    this.tailwind = builder.tailwind;
    this.extraction = builder.extraction;
    this.tagOverrides =
        Collections.unmodifiableMap(new LinkedHashMap<>(builder.tagOverrides));
  }

  /** Returns a configuration with every plugin disabled. */
  public static Plugins none() {
    return NONE;
  }

  /** Returns a configuration that restricts output to {@code <main>}. */
  public static Plugins isolateMain() {
    return builder().isolateMain().build();
  }

  /** Returns a builder with every plugin disabled. */
  public static Builder builder() {
    return new Builder();
  }

  /** The content filter, or {@code null}. */
  public Filter filter() {
    return filter;
  }

  /** Whether output is restricted to {@code <main>}. */
  public boolean isolateMainEnabled() {
    return isolateMain;
  }

  /** The frontmatter configuration, or {@code null}. */
  public Frontmatter frontmatter() {
    return frontmatter;
  }

  /** Whether Tailwind class conversion is enabled. */
  public boolean tailwindEnabled() {
    return tailwind;
  }

  /** The extraction configuration, or {@code null}. */
  public Extraction extraction() {
    return extraction;
  }

  /** Per-tag overrides keyed by lowercase tag name. */
  public Map<String, TagOverride> tagOverrides() {
    return tagOverrides;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Plugins)) {
      return false;
    }
    Plugins other = (Plugins) o;
    return isolateMain == other.isolateMain
        && tailwind == other.tailwind
        && Objects.equals(filter, other.filter)
        && Objects.equals(frontmatter, other.frontmatter)
        && Objects.equals(extraction, other.extraction)
        && tagOverrides.equals(other.tagOverrides);
  }

  @Override
  public int hashCode() {
    return Objects.hash(filter, isolateMain, frontmatter, tailwind, extraction, tagOverrides);
  }

  @Override
  public String toString() {
    return "Plugins{filter=" + filter + ", isolateMain=" + isolateMain
        + ", frontmatter=" + frontmatter + ", tailwind=" + tailwind
        + ", extraction=" + extraction + ", tagOverrides=" + tagOverrides + '}';
  }

  /** Builder for {@link Plugins}. */
  public static final class Builder {

    private Filter filter;
    private boolean isolateMain;
    private Frontmatter frontmatter;
    private boolean tailwind;
    private Extraction extraction;
    private final Map<String, TagOverride> tagOverrides = new LinkedHashMap<>();

    private Builder() {}

    /** Sets the content filter. */
    public Builder filter(Filter filter) {
      this.filter = Objects.requireNonNull(filter, "filter");
      return this;
    }

    /** Enables or disables restriction to {@code <main>}. */
    public Builder isolateMain(boolean enabled) {
      this.isolateMain = enabled;
      return this;
    }

    /** Enables restriction to {@code <main>}. */
    public Builder isolateMain() {
      return isolateMain(true);
    }

    /** Enables frontmatter with default metadata extraction. */
    public Builder frontmatter() {
      return frontmatter(Frontmatter.defaults());
    }

    /** Sets the frontmatter configuration. */
    public Builder frontmatter(Frontmatter frontmatter) {
      this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
      return this;
    }

    /** Enables or disables Tailwind class conversion. */
    public Builder tailwind(boolean enabled) {
      this.tailwind = enabled;
      return this;
    }

    /** Enables Tailwind class conversion. */
    public Builder tailwind() {
      return tailwind(true);
    }

    /** Sets the extraction configuration. */
    public Builder extraction(Extraction extraction) {
      this.extraction = Objects.requireNonNull(extraction, "extraction");
      return this;
    }

    /**
     * Adds or replaces an override for {@code tagName}. Names are normalised to
     * lowercase, matching the converter's lookup.
     */
    public Builder tagOverride(String tagName, TagOverride override) {
      Objects.requireNonNull(tagName, "tagName");
      Objects.requireNonNull(override, "override");
      tagOverrides.put(tagName.toLowerCase(Locale.ROOT), override);
      return this;
    }

    /** Builds an immutable {@link Plugins}. */
    public Plugins build() {
      return new Plugins(this);
    }
  }
}
