/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tag-override rendering cases recorded from the upstream Rust core. Each expected string is the
 * reference output at the pinned upstream commit, so these guard the parity fixes rather than the
 * Java implementation's own behaviour.
 */
class TagOverrideParityTest {

  @Test
  void zeroSpacingIsAppliedInsteadOfTheBuiltInSpacing() {
    Plugins plugins =
        Plugins.builder()
            .tagOverride("div", TagOverride.builder().spacing(0, 0).build())
            .build();
    assertEquals(
        "a b",
        HtmlToMarkdown.convert(
            "<div>a</div><div>b</div>", Options.builder().plugins(plugins).build()));
  }

  @Test
  void zeroSpacingOverridesFigcaptionCaptionSpacing() {
    Plugins plugins =
        Plugins.builder()
            .tagOverride("figcaption", TagOverride.builder().spacing(0, 0).build())
            .build();
    assertEquals(
        "![i]()*cap*",
        HtmlToMarkdown.convert(
            "<figure><img alt=\"i\"><figcaption>cap</figcaption></figure>",
            Options.builder().plugins(plugins).build()));
  }

  @Test
  void enterOnlyOverrideKeepsInlineMarkerState() {
    Plugins plugins =
        Plugins.builder().tagOverride("b", TagOverride.builder().enter("X").build()).build();
    assertEquals(
        "aX**c",
        HtmlToMarkdown.convert("<p>a<b> </b>c</p>", Options.builder().plugins(plugins).build()));
  }

  @Test
  void trOverrideTakesOverTheRowEdges() {
    Plugins plugins =
        Plugins.builder()
            .tagOverride("tr", TagOverride.builder().enter("R").exit("r").build())
            .build();
    assertEquals(
        "R ar",
        HtmlToMarkdown.convert(
            "<table><tr><td>a</td></tr></table>", Options.builder().plugins(plugins).build()));
  }

  @Test
  void aliasedCustomTagDoesNotInheritTheTargetsOverride() {
    Plugins plugins =
        Plugins.builder()
            .tagOverride("strong", TagOverride.builder().enter("S").exit("s").build())
            .tagOverride("x-foo", TagOverride.builder().alias("strong").build())
            .build();
    assertEquals(
        "**a**\n\nSbs",
        HtmlToMarkdown.convert(
            "<p><x-foo>a</x-foo></p><p><strong>b</strong></p>",
            Options.builder().plugins(plugins).build()));
  }

  @Test
  void emptyLinksAreDroppedOnTheFullEnterPath() {
    Options options =
        Options.builder()
            .maxNodeBytes(100000)
            .clean(Clean.builder().emptyLinks(true).build())
            .build();
    assertEquals(
        "[x]()\n\ny",
        HtmlToMarkdown.convert(
            "<div><p><a href=\"\">x</a></p><p><a href=\"#\">y</a></p></div>", options));
  }

  @Test
  void fencedCodeInAListIsClampedToTheNodeBudget() {
    String code = "line of code\n".repeat(2000);
    Options options = Options.builder().maxNodeBytes(128).build();
    assertEquals(
        "- ```\n"
            + "  line of code\n".repeat(8)
            + "  line of \n"
            + "  ```\n\n"
            + "after",
        HtmlToMarkdown.convert("<ul><li><pre>" + code + "</pre></li></ul><p>after</p>", options));
  }
}
