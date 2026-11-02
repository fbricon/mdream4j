/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import java.util.Objects;

/**
 * Immutable conversion options.
 *
 * <p>Use {@link #builder()} or {@link #defaults()}. Instances are safe to share
 * across threads.
 */
public final class Options {

  private static final Options DEFAULTS = builder().build();

  private final String origin;
  private final Clean clean;
  private final Plugins plugins;
  private final int wrapWidth;
  private final long maxNodeBytes;
  private final OutputFormat format;

  private Options(Builder builder) {
    this.origin = builder.origin;
    this.clean = builder.clean;
    this.plugins = builder.plugins;
    this.wrapWidth = builder.wrapWidth;
    this.maxNodeBytes = builder.maxNodeBytes;
    this.format = builder.format;
  }

  /** Returns the default options: Markdown, no cleaning, no plugins, no caps. */
  public static Options defaults() {
    return DEFAULTS;
  }

  /** Returns a builder initialised with the default options. */
  public static Builder builder() {
    return new Builder();
  }

  /** Base URL for resolving relative links and images, or {@code null}. */
  public String origin() {
    return origin;
  }

  /** Post-processing cleanup rules, or {@code null} when disabled. */
  public Clean clean() {
    return clean;
  }

  /** Plugin configuration, or {@code null} when disabled. */
  public Plugins plugins() {
    return plugins;
  }

  /** Hard-wrap prose at this many characters; {@code 0} disables wrapping. */
  public int wrapWidth() {
    return wrapWidth;
  }

  /** Per-construct byte cap; {@code 0} means unlimited. */
  public long maxNodeBytes() {
    return maxNodeBytes;
  }

  /** Output format. */
  public OutputFormat format() {
    return format;
  }

  /** Returns these options with a different output format, reusing this instance when unchanged. */
  Options withFormat(OutputFormat newFormat) {
    Objects.requireNonNull(newFormat, "format");
    if (format == newFormat) {
      return this;
    }
    Builder builder = new Builder();
    builder.origin = origin;
    builder.clean = clean;
    builder.plugins = plugins;
    builder.wrapWidth = wrapWidth;
    builder.maxNodeBytes = maxNodeBytes;
    builder.format = newFormat;
    return new Options(builder);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Options)) {
      return false;
    }
    Options other = (Options) o;
    return wrapWidth == other.wrapWidth
        && maxNodeBytes == other.maxNodeBytes
        && Objects.equals(origin, other.origin)
        && Objects.equals(clean, other.clean)
        && Objects.equals(plugins, other.plugins)
        && format == other.format;
  }

  @Override
  public int hashCode() {
    return Objects.hash(origin, clean, plugins, wrapWidth, maxNodeBytes, format);
  }

  @Override
  public String toString() {
    return "Options{origin=" + origin + ", clean=" + clean + ", plugins=" + plugins
        + ", wrapWidth=" + wrapWidth + ", maxNodeBytes=" + maxNodeBytes
        + ", format=" + format + '}';
  }

  /** Builder for {@link Options}. */
  public static final class Builder {

    private String origin;
    private Clean clean;
    private Plugins plugins;
    private int wrapWidth;
    private long maxNodeBytes;
    private OutputFormat format = OutputFormat.MARKDOWN;

    private Builder() {}

    /** Sets the base URL used to resolve relative links and images. */
    public Builder origin(String origin) {
      this.origin = Objects.requireNonNull(origin, "origin");
      return this;
    }

    /** Sets the post-processing cleanup rules. */
    public Builder clean(Clean clean) {
      this.clean = Objects.requireNonNull(clean, "clean");
      return this;
    }

    /** Sets the plugin configuration. */
    public Builder plugins(Plugins plugins) {
      this.plugins = Objects.requireNonNull(plugins, "plugins");
      return this;
    }

    /** Hard-wraps prose at {@code width} characters; {@code 0} disables it. */
    public Builder wrapWidth(int width) {
      if (width < 0) {
        throw new IllegalArgumentException("wrapWidth must be >= 0");
      }
      this.wrapWidth = width;
      return this;
    }

    /** Caps buffered constructs at {@code bytes} UTF-8 bytes; {@code 0} is unlimited. */
    public Builder maxNodeBytes(long bytes) {
      if (bytes < 0) {
        throw new IllegalArgumentException("maxNodeBytes must be >= 0");
      }
      this.maxNodeBytes = bytes;
      return this;
    }

    /** Sets the output format. */
    public Builder format(OutputFormat format) {
      this.format = Objects.requireNonNull(format, "format");
      return this;
    }

    /** Builds an immutable {@link Options}. */
    public Options build() {
      return new Options(this);
    }
  }
}
