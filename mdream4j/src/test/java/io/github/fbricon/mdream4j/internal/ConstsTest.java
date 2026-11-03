/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ConstsTest {

  /** Every single-bit mask, in declaration order with its canonical name. */
  private static final int[] ATTR_BITS = {
    Consts.ATTR_HREF,
    Consts.ATTR_TITLE,
    Consts.ATTR_ARIA_LABEL,
    Consts.ATTR_SRC,
    Consts.ATTR_ALT,
    Consts.ATTR_CLASS,
    Consts.ATTR_ALIGN,
    Consts.ATTR_NAME,
    Consts.ATTR_PROPERTY,
    Consts.ATTR_CONTENT,
    Consts.ATTR_START,
    Consts.ATTR_COLSPAN,
    Consts.ATTR_DATA_LANGUAGE,
    Consts.ATTR_DATA_LANG,
  };

  private static final String[] ATTR_NAMES = {
    "href",
    "title",
    "aria-label",
    "src",
    "alt",
    "class",
    "align",
    "name",
    "property",
    "content",
    "start",
    "colspan",
    "data-language",
    "data-lang",
  };

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String upper(String s) {
    return s.toUpperCase(Locale.ROOT);
  }

  @Test
  void tagTableRoundTrips() {
    assertEquals(113, Consts.MAX_TAG_ID, "MAX_TAG_ID");
    assertEquals(Consts.MAX_TAG_ID, Consts.TAG_NAMES.length, "TAG_NAMES length");

    int used = 0;
    for (int id = 0; id < Consts.MAX_TAG_ID; id++) {
      String name = Consts.TAG_NAMES[id];
      assertNotNull(name, "TAG_NAMES[" + id + "]");
      if (name.isEmpty()) {
        continue;
      }
      used++;
      assertEquals(Integer.valueOf(id), Consts.get_tag_id(name), "get_tag_id(" + name + ")");
      assertEquals(
          Integer.valueOf(id),
          Consts.get_tag_id_ci_bytes(bytes(name)),
          "get_tag_id_ci_bytes(" + name + ")");
      assertEquals(
          Integer.valueOf(id),
          Consts.get_tag_id_ci_bytes(bytes(upper(name))),
          "get_tag_id_ci_bytes(" + upper(name) + ")");
    }
    assertEquals(Consts.MAX_TAG_ID, used, "every tag slot is named");
  }

  @Test
  void strictLookupIsCaseSensitive() {
    for (String name : Consts.TAG_NAMES) {
      assertNull(Consts.get_tag_id(upper(name)), "get_tag_id(" + upper(name) + ")");
    }
    assertEquals(Integer.valueOf(Consts.TAG_HTML), Consts.get_tag_id("html"));
    assertNull(Consts.get_tag_id("HTML"));
  }

  @Test
  void lookupRejectsUnknownAndOutOfRangeNames() {
    assertNull(Consts.get_tag_id("my-element"));
    assertNull(Consts.get_tag_id(""));
    assertNull(Consts.get_tag_id_bytes(bytes("my-element")));
    assertNull(Consts.get_tag_id_ci_bytes(new byte[0]));
    // One byte past the 10-byte builtin maximum: never a builtin, even case-folded.
    assertNull(Consts.get_tag_id_ci_bytes(bytes("blockquotes")));
    assertNull(Consts.get_tag_id_ci_bytes(bytes("BLOCKQUOTES")));
    assertNull(Consts.get_tag_id_ci_bytes(new byte[64]));
    assertEquals(
        Integer.valueOf(Consts.TAG_BLOCKQUOTE), Consts.get_tag_id_ci_bytes(bytes("BLOCKQUOTE")));
    assertEquals(
        Integer.valueOf(Consts.TAG_FIGCAPTION), Consts.get_tag_id_ci_bytes(bytes("FigCaption")));
  }

  @Test
  void attrTableRoundTrips() {
    assertEquals(ATTR_BITS.length, ATTR_NAMES.length);

    int union = 0;
    for (int i = 0; i < ATTR_BITS.length; i++) {
      int bit = ATTR_BITS[i];
      String name = ATTR_NAMES[i];

      assertEquals(1, Integer.bitCount(bit), "single bit for " + name);
      assertEquals(0, union & bit, "duplicate bit for " + name);
      union |= bit;

      assertEquals(name, Consts.attr_name(bit), "attr_name(" + bit + ")");
      assertEquals(bit, Consts.attr_bit(bytes(name)), "attr_bit(" + name + ")");
      assertEquals(bit, Consts.attr_bit(bytes(upper(name))), "attr_bit(" + upper(name) + ")");
    }

    assertEquals((1 << ATTR_BITS.length) - 1, union, "bits are 1 << 0 .. 1 << 13");
    assertEquals(0x3FFF, union);
    assertEquals(0xFFFF, Consts.ATTR_ALL);
    assertEquals(0, Consts.ATTR_NONE);
  }

  @Test
  void attrBitMatchesTheByteTableForCharSequences() {
    for (int i = 0; i < ATTR_BITS.length; i++) {
      int bit = ATTR_BITS[i];
      String name = ATTR_NAMES[i];
      assertEquals(bit, Consts.attr_bit(name), "attr_bit(" + name + ")");
      assertEquals(bit, Consts.attr_bit(new StringBuilder(upper(name))), "uppercase " + name);
    }
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(""));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit("data-foo"));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit("hrefs"));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit("\u00e1lt"));
  }

  @Test
  void attrNameFallsBackToEmptyString() {
    assertEquals("", Consts.attr_name(Consts.ATTR_NONE));
    assertEquals("", Consts.attr_name(Consts.ATTR_ALL));
    assertEquals("", Consts.attr_name(Consts.ATTR_HREF | Consts.ATTR_SRC));
    assertEquals("", Consts.attr_name(1 << 14));
    assertEquals("", Consts.attr_name(-1));
  }

  @Test
  void attrBitRejectsUnknownNames() {
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(new byte[0]));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("data-foo")));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("hrefs")));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("titl")));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("action")));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("aria_label")));
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(bytes("ari-label")));
    // Non-ASCII bytes never fold to ASCII letters.
    assertEquals(Consts.ATTR_NONE, Consts.attr_bit(new byte[] {(byte) 0xE1, 'l', 't'}));
  }

  @Test
  void maxLengthsDeriveFromTables() {
    int maxTag = 0;
    for (String name : Consts.TAG_NAMES) {
      maxTag = Math.max(maxTag, name.length());
    }
    assertEquals(maxTag, Consts.MAX_BUILTIN_TAG_NAME, "MAX_BUILTIN_TAG_NAME");
    assertEquals(10, Consts.MAX_BUILTIN_TAG_NAME);
    assertEquals(Consts.MAX_BUILTIN_TAG_LEN, Consts.MAX_BUILTIN_TAG_NAME);

    int maxAttr = 0;
    for (String name : ATTR_NAMES) {
      maxAttr = Math.max(maxAttr, name.length());
    }
    assertEquals(maxAttr, Consts.MAX_WANTED_ATTR_NAME, "MAX_WANTED_ATTR_NAME");
    assertEquals(13, Consts.MAX_WANTED_ATTR_NAME);
  }
}
