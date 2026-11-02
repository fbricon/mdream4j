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

import org.junit.jupiter.api.Test;

class PluginsTest {

  @Test
  void none() {
    Plugins plugins = Plugins.none();
    assertNull(plugins.filter());
    assertFalse(plugins.isolateMainEnabled());
    assertNull(plugins.frontmatter());
    assertFalse(plugins.tailwindEnabled());
    assertNull(plugins.extraction());
    assertTrue(plugins.tagOverrides().isEmpty());
  }

  @Test
  void filter() {
    Filter filter = Filter.exclude("nav", "footer");
    assertEquals(2, filter.exclude().size());
    assertTrue(filter.include().isEmpty());
    assertTrue(filter.processChildren());

    Plugins plugins = Plugins.builder().filter(filter).build();
    assertEquals(filter, plugins.filter());
  }

  @Test
  void isolateMainAndTailwind() {
    Plugins plugins = Plugins.builder().isolateMain().tailwind().build();
    assertTrue(plugins.isolateMainEnabled());
    assertTrue(plugins.tailwindEnabled());
  }

  @Test
  void frontmatter() {
    Frontmatter frontmatter =
        Frontmatter.builder().field("author", "Ada").metaFields("keywords").build();
    assertEquals("Ada", frontmatter.additionalFields().get("author"));
    assertEquals(1, frontmatter.metaFields().size());

    Plugins plugins = Plugins.builder().frontmatter(frontmatter).build();
    assertEquals(frontmatter, plugins.frontmatter());
  }

  @Test
  void extraction() {
    Extraction extraction = Extraction.of("h1", ".summary");
    Plugins plugins = Plugins.builder().extraction(extraction).build();
    assertEquals(extraction, plugins.extraction());
  }

  @Test
  void tagOverridesAreLowercasedAndReplaceable() {
    TagOverride sup = TagOverride.wrap("^", "^");
    TagOverride supReplacement = TagOverride.wrap("^", "^");
    Plugins plugins =
        Plugins.builder()
            .tagOverride("SUP", sup)
            .tagOverride("sup", supReplacement)
            .build();

    assertEquals(1, plugins.tagOverrides().size());
    assertEquals(supReplacement, plugins.tagOverrides().get("sup"));
  }

  @Test
  void tagOverrideBuilder() {
    TagOverride override =
        TagOverride.builder()
            .enter("<b>")
            .exit("</b>")
            .spacing(1, 2)
            .inline(false)
            .selfClosing(false)
            .collapseInnerWhiteSpace(true)
            .build();

    assertEquals("<b>", override.enter());
    assertEquals("</b>", override.exit());
    assertEquals(1, override.spacingBefore());
    assertEquals(2, override.spacingAfter());
    assertFalse(override.inline());
    assertFalse(override.selfClosing());
    assertTrue(override.collapseInnerWhiteSpace());
    assertNull(override.alias());
  }

  @Test
  void aliasAndWrap() {
    assertEquals("em", TagOverride.alias("em").alias());
    TagOverride wrap = TagOverride.wrap("~", "~");
    assertTrue(wrap.inline());
    assertEquals("~", wrap.enter());
    assertEquals("~", wrap.exit());
  }

  @Test
  void rejectsInvalidSpacing() {
    assertThrows(IllegalArgumentException.class, () -> TagOverride.builder().spacing(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> TagOverride.builder().spacing(0, 256));
  }

  @Test
  void filterRequiresASelector() {
    assertThrows(IllegalStateException.class, () -> Filter.builder().build());
  }
}
