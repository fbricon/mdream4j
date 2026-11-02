/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * Shared character predicates and ASCII helpers.
 *
 * <p>The upstream crate uses two different whitespace notions, and the port must keep them apart:
 *
 * <ul>
 *   <li>{@link #isAsciiWhitespace(char)} mirrors Rust's {@code is_whitespace(byte)} used by the
 *       HTML scanner and Markdown output: space, tab, LF, FF and CR.
 *   <li>{@link #isUnicodeWhitespace(char)} mirrors {@code char::is_whitespace} ({@code
 *       White_Space}) used by {@code str::trim}.
 * </ul>
 */
final class Chars {

  /** Bits 9 (tab), 10 (LF), 12 (FF), 13 (CR) and 32 (space). */
  private static final long WHITESPACE_MASK =
      (1L << 9) | (1L << 10) | (1L << 12) | (1L << 13) | (1L << 32);

  private Chars() {}

  /** Rust's {@code is_whitespace(byte)}: the ASCII whitespace set. */
  static boolean isAsciiWhitespace(char c) {
    return c <= 32 && ((WHITESPACE_MASK >>> c) & 1) == 1;
  }

  /** Rust's {@code char::is_whitespace}: the Unicode {@code White_Space} set. */
  static boolean isUnicodeWhitespace(char c) {
    switch (c) {
      case '\t':
      case '\n':
      case '\u000B':
      case '\f':
      case '\r':
      case ' ':
      case '\u0085':
      case '\u00A0':
      case '\u1680':
      case '\u2028':
      case '\u2029':
      case '\u202F':
      case '\u205F':
      case '\u3000':
        return true;
      default:
        return c >= '\u2000' && c <= '\u200A';
    }
  }

  static boolean isAsciiAlphabetic(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  static boolean isAsciiAlphanumeric(char c) {
    return isAsciiAlphabetic(c) || (c >= '0' && c <= '9');
  }

  static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  /** Lowercases ASCII letters, returning the same instance when there is nothing to change. */
  static String asciiLower(String value) {
    StringBuilder out = null;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        if (out == null) {
          out = new StringBuilder(value);
        }
        out.setCharAt(i, (char) (c + ('a' - 'A')));
      }
    }
    return out == null ? value : out.toString();
  }

  static boolean asciiEqualsIgnoreCase(char a, char b) {
    return asciiLower(a) == asciiLower(b);
  }

  static boolean asciiEqualsIgnoreCase(String a, String b) {
    int len = a.length();
    if (len != b.length()) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      if (!asciiEqualsIgnoreCase(a.charAt(i), b.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Rust's {@code str::trim_ascii}: strips the ASCII whitespace set on both ends. */
  static String trimAscii(CharSequence value) {
    int start = 0;
    int end = value.length();
    while (start < end && isAsciiWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && isAsciiWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.subSequence(start, end).toString();
  }

  /** Rust's {@code str::trim}: strips the Unicode {@code White_Space} set on both ends. */
  static String trimUnicode(CharSequence value) {
    int start = 0;
    int end = value.length();
    while (start < end && isUnicodeWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && isUnicodeWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return value.subSequence(start, end).toString();
  }

  static boolean hasVisible(CharSequence value) {
    return hasVisible(value, 0, value.length());
  }

  /** Whether {@code value[start, end)} contains a non-whitespace character. */
  static boolean hasVisible(CharSequence value, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!isAsciiWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
