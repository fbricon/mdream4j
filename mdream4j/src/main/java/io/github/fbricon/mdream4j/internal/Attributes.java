/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Compact attribute storage: one linear-scanned array (elements have 0-4 attributes; cheaper than
 * hashing). Known names intern to {@link Consts#attr_bit}, costing no allocation; only
 * {@code ATTR_ALL} long-tail names keep an owned string.
 *
 * <p>Mirrors Rust {@code Attributes}. Retired slots stay in the backing array but are invisible to
 * every lookup and iteration, which stops at the first retired slot.
 */
final class Attributes implements Iterable<Attr> {

  private static final Attr[] EMPTY = new Attr[0];

  private Attr[] inner = EMPTY;

  /**
   * Number of slots ever claimed since the last growth. Mirrors the Rust {@code Vec} length: it can
   * exceed the live count after {@link #clear()}.
   */
  private int length;

  Attributes() {}

  void reserve(int cap) {
    int free = length - liveCount();
    int needed = length + Math.max(0, cap - free);
    if (needed > inner.length) {
      grow(needed);
    }
  }

  /** Number of live (non-retired) slots. */
  int liveCount() {
    int count = 0;
    while (count < length && inner[count].bit != Attr.FREE_SLOT) {
      count++;
    }
    return count;
  }

  Attr attrAt(int liveIndex) {
    return inner[liveIndex];
  }

  String getBit(int bit) {
    int index = locateBit(bit);
    return index >= 0 ? inner[index].value : null;
  }

  /**
   * Lookup by name, for names with no bit of their own ({@code style}, {@code hidden}, {@code id})
   * and for selector matching against arbitrary attributes.
   */
  String get(String key) {
    int bit = Consts.attr_bit(bytes(key));
    if (bit != Consts.ATTR_NONE) {
      return getBit(bit);
    }
    int live = liveCount();
    for (int i = 0; i < live; i++) {
      Attr attr = inner[i];
      if (attr.name != null && equalsIgnoreAsciiCase(attr.name, key)) {
        return attr.value;
      }
    }
    return null;
  }

  boolean containsBit(int bit) {
    return getBit(bit) != null;
  }

  /** Live slot for {@code bit}, or {@code -1}. */
  private int locateBit(int bit) {
    for (int i = 0; i < length; i++) {
      int slot = inner[i].bit;
      if (slot == Attr.FREE_SLOT) {
        return -1;
      }
      if (slot == bit) {
        return i;
      }
    }
    return -1;
  }

  /** Index a new attribute takes: the first retired slot, else the end. */
  private int claimIndex() {
    for (int i = 0; i < length; i++) {
      if (inner[i].bit == Attr.FREE_SLOT) {
        return i;
      }
    }
    if (length == inner.length) {
      grow(length + 1);
    }
    inner[length] = new Attr();
    return length++;
  }

  /** Store a known attribute by bit. The first occurrence wins, as in HTML. */
  void insertKnown(int bit, String value) {
    if (bit == Consts.ATTR_NONE) {
      throw new IllegalArgumentException("a known attribute has a bit");
    }
    if (locateBit(bit) >= 0) {
      return;
    }
    int index = claimIndex();
    Attr slot = inner[index];
    slot.bit = bit;
    slot.name = null;
    slot.value = writeAttributeValue(value);
  }

  /**
   * Store a name outside the known set; reachable only under {@code ATTR_ALL}. {@code name} must
   * already be lowercase. First occurrence wins, as above.
   */
  void insertCustom(String name, String value) {
    int live = liveCount();
    for (int i = 0; i < live; i++) {
      Attr attr = inner[i];
      if (attr.name != null && attr.name.equals(name)) {
        return;
      }
    }
    int index = claimIndex();
    Attr slot = inner[index];
    slot.bit = Consts.ATTR_NONE;
    slot.name = name;
    slot.value = writeAttributeValue(value);
  }

  void clear() {
    for (int i = 0; i < length; i++) {
      Attr attr = inner[i];
      if (attr.bit == Attr.FREE_SLOT) {
        break;
      }
      attr.bit = Attr.FREE_SLOT;
      attr.name = null;
      attr.value = "";
    }
  }

  /** Name/value pairs in source order, with a known name rebuilt from its bit. */
  @Override
  public Iterator<Attr> iterator() {
    return new Iterator<Attr>() {
      private int index;
      private final int end = liveCount();

      @Override
      public boolean hasNext() {
        return index < end;
      }

      @Override
      public Attr next() {
        if (index >= end) {
          throw new NoSuchElementException();
        }
        return inner[index++];
      }
    };
  }

  private void grow(int needed) {
    int capacity = Math.max(needed, Math.max(4, inner.length * 2));
    Attr[] grown = new Attr[capacity];
    System.arraycopy(inner, 0, grown, 0, inner.length);
    inner = grown;
  }

  private static String writeAttributeValue(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder buffer = new StringBuilder(value.length());
    Entities.decodeAttributeEntitiesInto(value, buffer);
    return buffer.toString();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Rust's {@code eq_ignore_ascii_case}: fold only {@code A-Z}. */
  private static boolean equalsIgnoreAsciiCase(String left, String right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int i = 0; i < left.length(); i++) {
      char a = left.charAt(i);
      char b = right.charAt(i);
      if (a >= 'A' && a <= 'Z') {
        a = (char) (a + ('a' - 'A'));
      }
      if (b >= 'A' && b <= 'Z') {
        b = (char) (b + ('a' - 'A'));
      }
      if (a != b) {
        return false;
      }
    }
    return true;
  }
}
