/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import io.github.fbricon.mdream4j.internal.Engine;
import java.util.Objects;

/**
 * A reusable converter holding an immutable {@link Options} snapshot.
 *
 * <p>Instances are thread-safe: conversion state is created per call, so a
 * single converter can serve concurrent requests.
 */
public final class MdreamConverter {

  private final Options options;

  /** Creates a converter with the default options. */
  public MdreamConverter() {
    this(Options.defaults());
  }

  /**
   * Creates a converter with the given options.
   *
   * @param options the options snapshot; must not be {@code null}
   */
  public MdreamConverter(Options options) {
    this.options = Objects.requireNonNull(options, "options");
  }

  /** The options this converter was created with. */
  public Options options() {
    return options;
  }

  /**
   * Converts HTML to the configured output format.
   *
   * @param html the HTML fragment or document; must not be {@code null}
   * @return the converted output
   */
  public String convert(CharSequence html) {
    return Engine.convert(html, options);
  }

  /**
   * Converts HTML and returns extraction/frontmatter metadata alongside the
   * output.
   *
   * @param html the HTML fragment or document; must not be {@code null}
   * @return the conversion result
   */
  public MdreamResult convertResult(CharSequence html) {
    return Engine.convertResult(html, options);
  }

  /** Creates a streaming conversion with this converter's options. */
  public MarkdownStream newStream() {
    return Engine.newStream(options);
  }
}
