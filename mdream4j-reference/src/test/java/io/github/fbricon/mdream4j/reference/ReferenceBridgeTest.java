/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReferenceBridgeTest {

  @BeforeAll
  static void requireNativeLibrary() {
    Assumptions.assumeTrue(
        ReferenceBridge.isAvailable(),
        () -> "native reference library unavailable: " + ReferenceBridge.loadError());
  }

  @Test
  void convertsHtml() {
    assertEquals("# Hey Man", ReferenceBridge.convert("<h1>Hey Man</h1>"));
    assertEquals(
        "This is `my code`", ReferenceBridge.convert("This is <code>my code</code>"));
    assertEquals("ONLY\\_THIS\\_TEXT", ReferenceBridge.convert("<p>ONLY_THIS_TEXT</p>"));
  }

  @Test
  void honoursOptions() {
    assertEquals(
        "[x](https://example.com/x)",
        ReferenceBridge.convert("<a href=\"/x\">x</a>", "{\"origin\":\"https://example.com\"}"));
    assertEquals(
        "a\nb",
        ReferenceBridge.convert("<p>a b</p>", "{\"wrapWidth\":1}"));
    assertEquals(
        "plain",
        ReferenceBridge.convert("<p>plain</p>", "{\"format\":\"text\"}"));
  }

  @Test
  void streamingMatchesOneShot() {
    String html =
        "<h1>Title</h1><ul><li>one</li><li>two <b>bold</b></li></ul><p>tail &amp; more</p>";
    String expected = ReferenceBridge.convert(html);

    for (int size : new int[] {1, 2, 3, 7, 64, 4096}) {
      StringBuilder actual = new StringBuilder();
      try (ReferenceBridge.ReferenceStream stream = ReferenceBridge.newStream("{}")) {
        for (int i = 0; i < html.length(); i += size) {
          actual.append(stream.feed(html.substring(i, Math.min(html.length(), i + size))));
        }
        actual.append(stream.finish());
      }
      assertEquals(expected, actual.toString(), "chunk size " + size);
    }
  }

  @Test
  void supportsTagOverridesAndCleaning() {
    String options =
        "{\"plugins\":{\"tagOverrides\":{\"sup\":{\"enter\":\"^\",\"exit\":\"^\","
            + "\"isInline\":true}}}}";
    assertEquals("x^2^", ReferenceBridge.convert("<p>x<sup>2</sup></p>", options));

    String clean = "{\"clean\":{\"emptyLinks\":true}}";
    assertEquals("text", ReferenceBridge.convert("<a href=\"javascript:x\">text</a>", clean));
  }

  @Test
  void formats() {
    List<String> outputs =
        List.of(
            ReferenceBridge.convert("<p><b>x</b></p>", "{\"format\":\"markdown\"}"),
            ReferenceBridge.convert("<p><b>x</b></p>", "{\"format\":\"text\"}"),
            ReferenceBridge.convert("<p><b>x</b></p>", "{\"format\":\"html\"}"));
    assertEquals("**x**", outputs.get(0));
    assertEquals("x", outputs.get(1));
    assertEquals("<p><strong>x</strong></p>", outputs.get(2));
  }

  @Test
  void unpairedSurrogatesAreReplaced() {
    String html = "<p>a\uD800b</p>";
    assertTrue(ReferenceBridge.convert(html).contains("\uFFFD"));
  }
}
