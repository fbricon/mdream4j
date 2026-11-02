/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * One attribute: a known name interned to its bit, or an owned lowercase name.
 *
 * <p>Mirrors Rust {@code Attr}. An {@code ATTR_NONE} bit means {@link #name} owns the name.
 */
final class Attr {

  /** Marks a retired slot; never a stored bit. Mirrors Rust {@code FREE_SLOT}. */
  static final int FREE_SLOT = 0xFFFF - 1;

  int bit = FREE_SLOT;

  /** Non-null only when {@link #bit} is {@code ATTR_NONE}. */
  String name;

  String value = "";

  Attr() {}

  Attr(int bit, String name, String value) {
    this.bit = bit;
    this.name = name;
    this.value = value;
  }

  String name() {
    return name != null ? name : Consts.attr_name(bit);
  }

  String value() {
    return value;
  }
}
