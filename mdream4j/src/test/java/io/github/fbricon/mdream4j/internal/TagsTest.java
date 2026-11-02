/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TagsTest {

  @Test
  void everyRenderedBuiltinNameHasAHandler() {
    // Rust's table deliberately leaves these four tags handler-less.
    for (int id = 0; id < Consts.MAX_TAG_ID; id++) {
      boolean unhandled =
          id == Consts.TAG_HTML
              || id == Consts.TAG_CAPTION
              || id == Consts.TAG_OBJECT
              || id == Consts.TAG_PATH;
      if (unhandled) {
        assertNull(Tags.getTagHandler(id), "unexpected handler for " + Consts.TAG_NAMES[id]);
      } else {
        assertNotNull(Tags.getTagHandler(id), "missing handler for " + Consts.TAG_NAMES[id]);
      }
    }
  }

  @Test
  void outOfRangeIdsHaveNoHandler() {
    assertNull(Tags.getTagHandler(-1));
    assertNull(Tags.getTagHandler(Consts.MAX_TAG_ID));
  }

  @Test
  void representativeHandlers() {
    TagHandler br = Tags.getTagHandler(Consts.TAG_BR);
    assertTrue(br.isSelfClosing);
    assertTrue(br.isInline);
    assertTrue(br.collapsesInnerWhiteSpace);
    assertEquals(0, br.spacing[0]);
    assertEquals(0, br.spacing[1]);

    TagHandler p = Tags.getTagHandler(Consts.TAG_P);
    assertNull(p.spacing);

    TagHandler a = Tags.getTagHandler(Consts.TAG_A);
    assertEquals(
        Consts.ATTR_HREF | Consts.ATTR_TITLE | Consts.ATTR_ARIA_LABEL, a.wantedAttrs);

    TagHandler script = Tags.getTagHandler(Consts.TAG_SCRIPT);
    assertTrue(script.isNonNesting);
    assertTrue(script.excludesTextNodes);

    TagHandler pre = Tags.getTagHandler(Consts.TAG_PRE);
    assertEquals(
        Consts.ATTR_CLASS | Consts.ATTR_DATA_LANGUAGE | Consts.ATTR_DATA_LANG, pre.wantedAttrs);
  }
}
