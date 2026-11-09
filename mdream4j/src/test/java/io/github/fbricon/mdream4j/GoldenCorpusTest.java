/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential test against {@code /golden/expected.jsonl} on the test classpath, the reference
 * outputs recorded from the upstream Rust core.
 *
 * <p>Each corpus case is its own JUnit invocation, named by corpus id, so a mismatch points at the
 * failing case and the remaining cases still run. Every format is checked for every case: the test
 * asserts exact parity, with no tolerance floor.
 */
class GoldenCorpusTest {

  /** Guards against silently dropping cases when the golden file is regenerated. */
  @Test
  void corpusDoesNotShrink() throws IOException {
    int size = Corpus.loadCases().size();
    assertTrue(size >= 1218, "corpus must not shrink: " + size);
  }

  static Stream<Arguments> cases() throws IOException {
    return Corpus.loadCases().stream().map(corpusCase -> Arguments.of(corpusCase.get("id"), corpusCase));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void matchesReference(String id, Map<String, Object> corpusCase) {
    String html = (String) corpusCase.get("html");
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) corpusCase.get("outputs");
    String expectedMarkdown = (String) outputs.get("markdown");
    String expectedText = (String) outputs.get("text");
    String expectedHtml = (String) outputs.get("html");
    boolean expectedTruncated = Boolean.TRUE.equals(outputs.get("truncated"));

    MdreamResult markdown = HtmlToMarkdown.convertResult(html, Corpus.options(corpusCase));
    String text = HtmlToMarkdown.convert(html, Corpus.options(corpusCase, OutputFormat.TEXT));
    String htmlOut = HtmlToMarkdown.convert(html, Corpus.options(corpusCase, OutputFormat.HTML));

    assertAll(
        id,
        () ->
            assertEquals(
                expectedMarkdown,
                markdown.markdown(),
                () -> diff(id, expectedMarkdown, markdown.markdown())),
        () ->
            assertEquals(
                expectedTruncated,
                markdown.truncated(),
                () -> id + ": truncated flag differs"),
        () ->
            assertEquals(
                expectedPairs(outputs.get("frontmatter")),
                actualFrontmatter(markdown.frontmatter()),
                () -> id + ": frontmatter differs"),
        () ->
            assertEquals(
                expectedExtracted(outputs.get("extracted")),
                actualExtracted(markdown.extracted()),
                () -> id + ": extracted elements differ"),
        () -> assertEquals(expectedText, text, () -> diff(id, expectedText, text)),
        () -> assertEquals(expectedHtml, htmlOut, () -> diff(id, expectedHtml, htmlOut)));
  }

  /** The recorded {@code [key, value]} pairs, or {@code null} when the plugin was off. */
  private static List<List<String>> expectedPairs(Object value) {
    if (value == null) {
      return null;
    }
    List<List<String>> pairs = new ArrayList<>();
    for (Object item : (List<?>) value) {
      List<?> pair = (List<?>) item;
      pairs.add(Arrays.asList((String) pair.get(0), (String) pair.get(1)));
    }
    return pairs;
  }

  private static List<List<String>> actualFrontmatter(Map<String, String> entries) {
    if (entries == null) {
      return null;
    }
    List<List<String>> pairs = new ArrayList<>();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      pairs.add(Arrays.asList(entry.getKey(), entry.getValue()));
    }
    return pairs;
  }

  private static List<String> expectedExtracted(Object value) {
    if (value == null) {
      return null;
    }
    List<String> elements = new ArrayList<>();
    for (Object item : (List<?>) value) {
      Map<?, ?> element = (Map<?, ?>) item;
      elements.add(
          element.get("selector")
              + "|"
              + element.get("tagName")
              + "|"
              + element.get("textContent")
              + "|"
              + element.get("attributes"));
    }
    return elements;
  }

  private static List<String> actualExtracted(List<ExtractedElement> extracted) {
    if (extracted == null) {
      return null;
    }
    List<String> elements = new ArrayList<>();
    for (ExtractedElement element : extracted) {
      List<List<String>> attributes = new ArrayList<>();
      for (Map.Entry<String, String> attribute : element.attributes().entrySet()) {
        attributes.add(Arrays.asList(attribute.getKey(), attribute.getValue()));
      }
      elements.add(
          element.selector()
              + "|"
              + element.tagName()
              + "|"
              + element.textContent()
              + "|"
              + attributes);
    }
    return elements;
  }

  private static String diff(String id, String expected, String actual) {
    if (expected.equals(actual)) {
      return id + ": identical";
    }
    int limit = Math.min(expected.length(), actual.length());
    int index = 0;
    while (index < limit && expected.charAt(index) == actual.charAt(index)) {
      index++;
    }
    int from = Math.max(0, index - 30);
    return id
        + ": first difference at "
        + index
        + "\n  expected: "
        + escape(expected.substring(from, Math.min(expected.length(), index + 50)))
        + "\n  actual:   "
        + escape(actual.substring(from, Math.min(actual.length(), index + 50)));
  }

  private static String escape(String value) {
    return value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }
}
