/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.util.Objects;

/**
 * Tailwind utility-class to Markdown-emphasis mapping.
 *
 * <p>Direct port of {@code crates/core/src/tailwind.rs}. Classes are scanned
 * left to right; each recognised slot (weight, emphasis, decoration, display
 * visibility, position visibility) keeps the last class whose responsive
 * breakpoint is greater than or equal to the one already recorded. A later
 * class therefore wins ties, while a lower breakpoint can never override a
 * higher one.
 */
public final class Tailwind {

  /** Breakpoint priority of a class without a responsive prefix. */
  private static final int NO_BREAKPOINT = 0;

  private static final int SLOT_WEIGHT = 0;
  private static final int SLOT_EMPHASIS = 1;
  private static final int SLOT_DECORATION = 2;
  private static final int SLOT_DISPLAY_HIDDEN = 3;
  private static final int SLOT_POSITION_HIDDEN = 4;

  /**
   * Five slots packed into one {@code long}: four bits per slot, three for the
   * breakpoint (0-5) and one for the value. {@code 0xF} marks an unset slot.
   */
  private static final long INITIAL_STATE = 0xFFFFFL;

  private static final long SLOT_MASK = 0xFL;
  private static final long SLOT_UNSET = 0xFL;
  private static final long BREAKPOINT_MASK = 0x7L;
  private static final long VALUE_BIT = 0x8L;

  private static final String BOLD = "**";
  private static final String EMPHASIS = "*";
  private static final String STRIKETHROUGH = "~~";

  private static final String[] WEIGHT_CLASSES = {
    "font-bold", "font-semibold", "font-black", "font-extrabold", "font-medium", "bold"
  };
  private static final String[] NO_STRIKETHROUGH_CLASSES = {"no-underline", "underline"};
  private static final String[] VISIBLE_DISPLAY_CLASSES = {"block", "flex", "inline"};
  private static final String[] HIDDEN_POSITION_CLASSES = {"absolute", "fixed", "sticky"};
  private static final String[] VISIBLE_POSITION_CLASSES = {"static", "relative"};

  private Tailwind() {}

  /**
   * Maps a {@code class} attribute to Markdown delimiters and visibility.
   *
   * @param classesAttr the raw {@code class} attribute value
   * @return the delimiters to wrap around the node's content, plus whether the
   *     node must be dropped
   */
  public static Result processTailwindClasses(CharSequence classesAttr) {
    Objects.requireNonNull(classesAttr, "classesAttr");

    long state = INITIAL_STATE;
    final int length = classesAttr.length();
    int index = 0;
    while (index < length) {
      while (index < length && Chars.isUnicodeWhitespace(classesAttr.charAt(index))) {
        index++;
      }
      if (index >= length) {
        break;
      }
      int start = index;
      while (index < length && !Chars.isUnicodeWhitespace(classesAttr.charAt(index))) {
        index++;
      }
      int end = index;

      int breakpoint = breakpointOf(classesAttr, start, end);
      int baseStart = start + (breakpoint == NO_BREAKPOINT ? 0 : breakpoint == 5 ? 4 : 3);

      int slot;
      boolean value;
      // Exact names first; the infix probes below would match them too.
      if (baseEquals(classesAttr, baseStart, end, "italic")) {
        slot = SLOT_EMPHASIS;
        value = true;
      } else if (baseEquals(classesAttr, baseStart, end, "not-italic")) {
        slot = SLOT_EMPHASIS;
        value = false;
      } else if (matchesAny(classesAttr, baseStart, end, WEIGHT_CLASSES)) {
        slot = SLOT_WEIGHT;
        value = true;
      } else if (baseEquals(classesAttr, baseStart, end, "line-through")) {
        slot = SLOT_DECORATION;
        value = true;
      } else if (matchesAny(classesAttr, baseStart, end, NO_STRIKETHROUGH_CLASSES)) {
        slot = SLOT_DECORATION;
        value = false;
      } else if (baseEquals(classesAttr, baseStart, end, "hidden")) {
        slot = SLOT_DISPLAY_HIDDEN;
        value = true;
      } else if (matchesAny(classesAttr, baseStart, end, VISIBLE_DISPLAY_CLASSES)) {
        slot = SLOT_DISPLAY_HIDDEN;
        value = false;
      } else if (matchesAny(classesAttr, baseStart, end, HIDDEN_POSITION_CLASSES)) {
        slot = SLOT_POSITION_HIDDEN;
        value = true;
      } else if (matchesAny(classesAttr, baseStart, end, VISIBLE_POSITION_CLASSES)) {
        slot = SLOT_POSITION_HIDDEN;
        value = false;
      } else if (hasInfix(classesAttr, baseStart, end, "font-")) {
        slot = SLOT_WEIGHT;
        value = false;
      } else if (hasInfix(classesAttr, baseStart, end, "invisible")) {
        slot = SLOT_DISPLAY_HIDDEN;
        value = true;
      } else {
        continue;
      }
      state = setSlot(state, slot, breakpoint, value);
    }

    StringBuilder prefix = new StringBuilder();
    StringBuilder suffix = new StringBuilder();
    if (isEnabled(state, SLOT_WEIGHT)) {
      prefix.append(BOLD);
      suffix.append(BOLD);
    }
    if (isEnabled(state, SLOT_EMPHASIS)) {
      prefix.append(EMPHASIS);
      suffix.insert(0, EMPHASIS);
    }
    if (isEnabled(state, SLOT_DECORATION)) {
      prefix.append(STRIKETHROUGH);
      suffix.insert(0, STRIKETHROUGH);
    }

    return new Result(
        prefix.length() == 0 ? null : prefix.toString(),
        suffix.length() == 0 ? null : suffix.toString(),
        isEnabled(state, SLOT_DISPLAY_HIDDEN) || isEnabled(state, SLOT_POSITION_HIDDEN));
  }

  /**
   * Returns the responsive breakpoint priority of a class: {@code 0} for an
   * unprefixed class, then {@code 1} through {@code 5} for {@code sm:},
   * {@code md:}, {@code lg:}, {@code xl:} and {@code 2xl:}.
   */
  public static int breakpointOf(CharSequence cls) {
    Objects.requireNonNull(cls, "class");
    return breakpointOf(cls, 0, cls.length());
  }

  /**
   * Returns whether {@code needle} occurs anywhere in {@code haystack}.
   *
   * <p>Equivalent to Rust's byte-window scan for the ASCII needles used by the
   * Tailwind probes.
   */
  public static boolean hasInfix(CharSequence haystack, CharSequence needle) {
    Objects.requireNonNull(haystack, "haystack");
    Objects.requireNonNull(needle, "needle");
    return hasInfix(haystack, 0, haystack.length(), needle);
  }

  private static int breakpointOf(CharSequence cls, int start, int end) {
    if (end - start >= 3 && cls.charAt(start + 2) == ':') {
      char first = cls.charAt(start);
      char second = cls.charAt(start + 1);
      if (first == 's' && second == 'm') {
        return 1;
      }
      if (first == 'm' && second == 'd') {
        return 2;
      }
      if (first == 'l' && second == 'g') {
        return 3;
      }
      if (first == 'x' && second == 'l') {
        return 4;
      }
    }
    if (end - start >= 4
        && cls.charAt(start) == '2'
        && cls.charAt(start + 1) == 'x'
        && cls.charAt(start + 2) == 'l'
        && cls.charAt(start + 3) == ':') {
      return 5;
    }
    return NO_BREAKPOINT;
  }

  private static boolean baseEquals(CharSequence cls, int start, int end, String name) {
    int nameLength = name.length();
    if (end - start != nameLength) {
      return false;
    }
    for (int i = 0; i < nameLength; i++) {
      if (cls.charAt(start + i) != name.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesAny(CharSequence cls, int start, int end, String[] names) {
    for (int i = 0; i < names.length; i++) {
      if (baseEquals(cls, start, end, names[i])) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasInfix(
      CharSequence haystack, int start, int end, CharSequence needle) {
    int needleLength = needle.length();
    if (needleLength == 0) {
      return true;
    }
    int last = end - needleLength;
    for (int i = start; i <= last; i++) {
      int j = 0;
      while (j < needleLength && haystack.charAt(i + j) == needle.charAt(j)) {
        j++;
      }
      if (j == needleLength) {
        return true;
      }
    }
    return false;
  }

  private static long setSlot(long state, int slot, int breakpoint, boolean value) {
    int shift = slot * 4;
    int current = (int) ((state >>> shift) & SLOT_MASK);
    if (current != SLOT_UNSET && breakpoint < (current & BREAKPOINT_MASK)) {
      return state;
    }
    long packed = breakpoint | (value ? VALUE_BIT : 0L);
    return (state & ~(SLOT_MASK << shift)) | (packed << shift);
  }

  private static boolean isEnabled(long state, int slot) {
    long current = (state >>> (slot * 4)) & SLOT_MASK;
    return current != SLOT_UNSET && (current & VALUE_BIT) != 0L;
  }

  /** Markdown delimiters and visibility produced by {@link #processTailwindClasses}. */
  public static final class Result {

    private final String prefix;
    private final String suffix;
    private final boolean hidden;

    private Result(String prefix, String suffix, boolean hidden) {
      this.prefix = prefix;
      this.suffix = suffix;
      this.hidden = hidden;
    }

    /** The Markdown prefix, or {@code null} when no prefix applies. */
    public String prefix() {
      return prefix;
    }

    /** The Markdown suffix, or {@code null} when no suffix applies. */
    public String suffix() {
      return suffix;
    }

    /** Whether the node's whole subtree must be dropped. */
    public boolean hidden() {
      return hidden;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Result)) {
        return false;
      }
      Result other = (Result) o;
      return hidden == other.hidden
          && Objects.equals(prefix, other.prefix)
          && Objects.equals(suffix, other.suffix);
    }

    @Override
    public int hashCode() {
      return Objects.hash(prefix, suffix, hidden);
    }

    @Override
    public String toString() {
      return "Result{prefix=" + prefix + ", suffix=" + suffix + ", hidden=" + hidden + '}';
    }
  }
}
