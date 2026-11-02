/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/** The two rare per-node fields, kept together so every node stays small. */
final class NodeExtras {

  /** Only set for custom (non-builtin) tags; built-in names come from {@code tagId}. */
  String customName;

  TailwindData tailwind;

  NodeExtras(String customName) {
    this.customName = customName;
  }
}
