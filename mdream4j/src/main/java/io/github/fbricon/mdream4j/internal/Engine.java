/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.MdreamResult;
import io.github.fbricon.mdream4j.Options;
import java.util.Objects;

/**
 * Internal conversion entry point.
 *
 * <p>Scaffold only: the porter package is filled in by milestones M1–M5. This
 * class exists so the public API compiles and its contracts are reviewable.
 */
public final class Engine {

  private static final String NOT_IMPLEMENTED = "mdream4j conversion is not implemented yet (M1)";

  private Engine() {}

  public static String convert(CharSequence html, Options options) {
    Objects.requireNonNull(html, "html");
    throw new UnsupportedOperationException(NOT_IMPLEMENTED);
  }

  public static MdreamResult convertResult(CharSequence html, Options options) {
    Objects.requireNonNull(html, "html");
    throw new UnsupportedOperationException(NOT_IMPLEMENTED);
  }

  public static MarkdownStream newStream(Options options) {
    throw new UnsupportedOperationException(NOT_IMPLEMENTED);
  }
}
