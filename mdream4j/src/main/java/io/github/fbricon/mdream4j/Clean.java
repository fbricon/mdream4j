/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Objects;

/**
 * Post-processing cleanup rules applied to generated Markdown.
 *
 * <p>Every rule is disabled by default. Use {@link #all()} to enable all of
 * them, or {@link #builder()} for individual flags.
 */
public final class Clean {

  private final boolean urls;
  private final boolean fragments;
  private final boolean emptyLinks;
  private final boolean redundantLinks;
  private final boolean selfLinkHeadings;
  private final boolean emptyImages;
  private final boolean emptyLinkText;

  private Clean(Builder builder) {
    this.urls = builder.urls;
    this.fragments = builder.fragments;
    this.emptyLinks = builder.emptyLinks;
    this.redundantLinks = builder.redundantLinks;
    this.selfLinkHeadings = builder.selfLinkHeadings;
    this.emptyImages = builder.emptyImages;
    this.emptyLinkText = builder.emptyLinkText;
  }

  /** Returns a configuration with every cleanup rule disabled. */
  public static Clean none() {
    return builder().build();
  }

  /** Returns a configuration with every cleanup rule enabled. */
  public static Clean all() {
    return builder().all().build();
  }

  /** Returns a mutable builder initialised with every rule disabled. */
  public static Builder builder() {
    return new Builder();
  }

  /** Whether tracking query parameters are stripped from URLs. */
  public boolean urls() {
    return urls;
  }

  /** Whether fragment-only links that match no heading slug are stripped. */
  public boolean fragments() {
    return fragments;
  }

  /** Whether links with meaningless or executable hrefs are unwrapped to text. */
  public boolean emptyLinks() {
    return emptyLinks;
  }

  /** Whether links whose text equals their URL are unwrapped to text. */
  public boolean redundantLinks() {
    return redundantLinks;
  }

  /** Whether self-referencing heading anchors are removed. */
  public boolean selfLinkHeadings() {
    return selfLinkHeadings;
  }

  /** Whether images with no alt text are dropped. */
  public boolean emptyImages() {
    return emptyImages;
  }

  /** Whether links that produce no visible text are dropped. */
  public boolean emptyLinkText() {
    return emptyLinkText;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Clean)) {
      return false;
    }
    Clean other = (Clean) o;
    return urls == other.urls
        && fragments == other.fragments
        && emptyLinks == other.emptyLinks
        && redundantLinks == other.redundantLinks
        && selfLinkHeadings == other.selfLinkHeadings
        && emptyImages == other.emptyImages
        && emptyLinkText == other.emptyLinkText;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        urls, fragments, emptyLinks, redundantLinks, selfLinkHeadings, emptyImages, emptyLinkText);
  }

  @Override
  public String toString() {
    return "Clean{urls="
        + urls
        + ", fragments="
        + fragments
        + ", emptyLinks="
        + emptyLinks
        + ", redundantLinks="
        + redundantLinks
        + ", selfLinkHeadings="
        + selfLinkHeadings
        + ", emptyImages="
        + emptyImages
        + ", emptyLinkText="
        + emptyLinkText
        + '}';
  }

  /** Builder for {@link Clean}. */
  public static final class Builder {

    private boolean urls;
    private boolean fragments;
    private boolean emptyLinks;
    private boolean redundantLinks;
    private boolean selfLinkHeadings;
    private boolean emptyImages;
    private boolean emptyLinkText;

    private Builder() {}

    /** Enables or disables tracking-parameter cleanup. */
    public Builder urls(boolean enabled) {
      this.urls = enabled;
      return this;
    }

    /** Enables tracking-parameter cleanup. */
    public Builder urls() {
      return urls(true);
    }

    /** Enables or disables stripping of unmatched fragment-only links. */
    public Builder fragments(boolean enabled) {
      this.fragments = enabled;
      return this;
    }

    /** Enables stripping of unmatched fragment-only links. */
    public Builder fragments() {
      return fragments(true);
    }

    /** Enables or disables unwrapping of meaningless hrefs. */
    public Builder emptyLinks(boolean enabled) {
      this.emptyLinks = enabled;
      return this;
    }

    /** Enables unwrapping of meaningless hrefs. */
    public Builder emptyLinks() {
      return emptyLinks(true);
    }

    /** Enables or disables unwrapping of links whose text equals their URL. */
    public Builder redundantLinks(boolean enabled) {
      this.redundantLinks = enabled;
      return this;
    }

    /** Enables unwrapping of links whose text equals their URL. */
    public Builder redundantLinks() {
      return redundantLinks(true);
    }

    /** Enables or disables removal of self-referencing heading anchors. */
    public Builder selfLinkHeadings(boolean enabled) {
      this.selfLinkHeadings = enabled;
      return this;
    }

    /** Enables removal of self-referencing heading anchors. */
    public Builder selfLinkHeadings() {
      return selfLinkHeadings(true);
    }

    /** Enables or disables dropping of images with no alt text. */
    public Builder emptyImages(boolean enabled) {
      this.emptyImages = enabled;
      return this;
    }

    /** Enables dropping of images with no alt text. */
    public Builder emptyImages() {
      return emptyImages(true);
    }

    /** Enables or disables dropping of links with no visible text. */
    public Builder emptyLinkText(boolean enabled) {
      this.emptyLinkText = enabled;
      return this;
    }

    /** Enables dropping of links with no visible text. */
    public Builder emptyLinkText() {
      return emptyLinkText(true);
    }

    /** Enables every cleanup rule. */
    public Builder all() {
      this.urls = true;
      this.fragments = true;
      this.emptyLinks = true;
      this.redundantLinks = true;
      this.selfLinkHeadings = true;
      this.emptyImages = true;
      this.emptyLinkText = true;
      return this;
    }

    /** Builds an immutable {@link Clean}. */
    public Clean build() {
      return new Clean(this);
    }
  }
}
