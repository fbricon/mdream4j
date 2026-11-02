/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/** Tailwind utility-class conversion result stored on a node. Mirrors Rust {@code TailwindData}. */
final class TailwindData {

  final String prefix;
  final String suffix;
  final boolean hidden;

  TailwindData(String prefix, String suffix, boolean hidden) {
    this.prefix = prefix;
    this.suffix = suffix;
    this.hidden = hidden;
  }
}
