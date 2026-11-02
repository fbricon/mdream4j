/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionsTest {

  @Test
  void defaults() {
    Options options = Options.defaults();
    assertNull(options.origin());
    assertNull(options.clean());
    assertNull(options.plugins());
    assertEquals(0, options.wrapWidth());
    assertEquals(0L, options.maxNodeBytes());
    assertEquals(OutputFormat.MARKDOWN, options.format());
    assertEquals(options, Options.builder().build());
  }

  @Test
  void builder() {
    Options options =
        Options.builder()
            .origin("https://example.com")
            .clean(Clean.all())
            .plugins(Plugins.isolateMain())
            .wrapWidth(80)
            .maxNodeBytes(4096)
            .format(OutputFormat.TEXT)
            .build();

    assertEquals("https://example.com", options.origin());
    assertEquals(Clean.all(), options.clean());
    assertTrue(options.plugins().isolateMainEnabled());
    assertEquals(80, options.wrapWidth());
    assertEquals(4096L, options.maxNodeBytes());
    assertEquals(OutputFormat.TEXT, options.format());
  }

  @Test
  void rejectsNegativeValues() {
    assertThrows(IllegalArgumentException.class, () -> Options.builder().wrapWidth(-1));
    assertThrows(IllegalArgumentException.class, () -> Options.builder().maxNodeBytes(-1));
  }

  @Test
  void rejectsNulls() {
    assertThrows(NullPointerException.class, () -> Options.builder().origin(null));
    assertThrows(NullPointerException.class, () -> Options.builder().clean(null));
    assertThrows(NullPointerException.class, () -> Options.builder().plugins(null));
    assertThrows(NullPointerException.class, () -> Options.builder().format(null));
  }

  @Test
  void valueSemantics() {
    Options a = Options.builder().origin("x").build();
    Options b = Options.builder().origin("x").build();
    Options c = Options.builder().origin("y").build();

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
    assertTrue(a.toString().contains("origin=x"));
  }

  @Test
  void cleanRules() {
    Clean none = Clean.none();
    assertFalse(none.urls());
    assertFalse(none.fragments());
    assertFalse(none.emptyLinks());
    assertFalse(none.redundantLinks());
    assertFalse(none.selfLinkHeadings());
    assertFalse(none.emptyImages());
    assertFalse(none.emptyLinkText());

    Clean all = Clean.all();
    assertTrue(all.urls());
    assertTrue(all.fragments());
    assertTrue(all.emptyLinks());
    assertTrue(all.redundantLinks());
    assertTrue(all.selfLinkHeadings());
    assertTrue(all.emptyImages());
    assertTrue(all.emptyLinkText());

    Clean partial = Clean.builder().urls().redundantLinks(true).build();
    assertTrue(partial.urls());
    assertTrue(partial.redundantLinks());
    assertFalse(partial.emptyLinks());
  }
}
