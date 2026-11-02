/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void resultDefensivelyCopies() {
    ExtractedElement element =
        new ExtractedElement("h1", "h1", "Title", Collections.singletonMap("id", "title"));
    MdreamResult result =
        new MdreamResult("# Title", List.of(element), Collections.singletonMap("title", "Title"), false);

    assertEquals("# Title", result.markdown());
    assertEquals(1, result.extracted().size());
    assertEquals("Title", result.frontmatter().get("title"));
    assertFalse(result.truncated());
    assertThrows(UnsupportedOperationException.class, () -> result.extracted().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.frontmatter().clear());
  }

  @Test
  void absentMetadataIsNull() {
    MdreamResult result = new MdreamResult("text", null, null, true);
    assertNull(result.extracted());
    assertNull(result.frontmatter());
    assertTrue(result.truncated());
  }

  @Test
  void extractedElementValueSemantics() {
    ExtractedElement a =
        new ExtractedElement("h1", "h1", "Title", Collections.singletonMap("id", "title"));
    ExtractedElement b =
        new ExtractedElement("h1", "h1", "Title", Collections.singletonMap("id", "title"));

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertTrue(a.toString().contains("textContent=Title"));
  }
}
