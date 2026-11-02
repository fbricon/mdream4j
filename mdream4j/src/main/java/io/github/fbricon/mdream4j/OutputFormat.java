/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

/**
 * Output format for a conversion.
 *
 * <p>The Rust upstream exposes these variants behind Cargo features. mdream4j
 * always ships all three.
 */
public enum OutputFormat {

  /** GitHub-flavoured Markdown (the default). */
  MARKDOWN,

  /** Plain text with Markdown and HTML syntax omitted. */
  TEXT,

  /** Allowlisted semantic HTML. */
  HTML
}
