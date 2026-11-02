/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttributesTest {

  private static List<String> pairs(Attributes attrs) {
    List<String> pairs = new ArrayList<>();
    for (Attr attr : attrs) {
      pairs.add(attr.name() + "=" + attr.value());
    }
    return pairs;
  }

  @Test
  void clearedSlotsAreInvisibleAndReused() {
    Attributes attrs = new Attributes();
    attrs.insertKnown(Consts.ATTR_HREF, "/first.html");
    attrs.insertKnown(Consts.ATTR_TITLE, "Tom &amp; Jerry");
    attrs.insertCustom("data-x", "1");
    assertEquals("Tom & Jerry", attrs.getBit(Consts.ATTR_TITLE));
    assertEquals("1", attrs.get("data-x"));

    attrs.clear();
    assertNull(attrs.getBit(Consts.ATTR_HREF));
    assertNull(attrs.getBit(Consts.ATTR_TITLE));
    assertNull(attrs.get("data-x"));
    assertTrue(pairs(attrs).isEmpty());

    attrs.insertKnown(Consts.ATTR_SRC, "/img.png");
    attrs.insertKnown(Consts.ATTR_SRC, "/second.png");
    attrs.insertKnown(Consts.ATTR_HREF, null);
    assertEquals(
        List.of("src=/img.png", "href="),
        pairs(attrs));
    assertNull(attrs.get("data-x"));
    assertNull(attrs.getBit(Consts.ATTR_TITLE));
  }

  @Test
  void firstOccurrenceWins() {
    Attributes attrs = new Attributes();
    attrs.insertKnown(Consts.ATTR_HREF, "first");
    attrs.insertKnown(Consts.ATTR_HREF, "second");
    assertEquals("first", attrs.getBit(Consts.ATTR_HREF));

    attrs.insertCustom("data-x", "1");
    attrs.insertCustom("data-x", "2");
    assertEquals("1", attrs.get("data-x"));
  }

  @Test
  void namesAreCaseInsensitiveForLookup() {
    Attributes attrs = new Attributes();
    attrs.insertCustom("data-x", "v");
    assertEquals("v", attrs.get("DATA-X"));
    assertEquals("v", attrs.get("data-x"));
  }

  @Test
  void knownNameIsRebuiltFromItsBit() {
    Attributes attrs = new Attributes();
    attrs.insertKnown(Consts.ATTR_ARIA_LABEL, "label");
    assertEquals("aria-label", attrs.attrAt(0).name());
    assertFalse(attrs.containsBit(Consts.ATTR_ALT));
  }

  @Test
  void attributeEntitiesAreDecoded() {
    Attributes attrs = new Attributes();
    attrs.insertKnown(Consts.ATTR_TITLE, "a &amp; b &lt;c&gt;");
    assertEquals("a & b <c>", attrs.getBit(Consts.ATTR_TITLE));
  }

  @Test
  void reserveGrowsWithoutLosingSlots() {
    Attributes attrs = new Attributes();
    attrs.reserve(4);
    attrs.insertKnown(Consts.ATTR_SRC, "x");
    attrs.reserve(4);
    assertEquals("x", attrs.getBit(Consts.ATTR_SRC));
    assertEquals(1, attrs.liveCount());
  }
}
