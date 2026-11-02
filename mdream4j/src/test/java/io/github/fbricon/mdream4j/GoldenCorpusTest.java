/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Differential test against {@code golden/corpus.jsonl}, the reference outputs recorded from the
 * upstream Rust core.
 *
 * <p>The report lands in {@code target/golden-report.txt}; the assertion only guards against
 * regressions below the recorded parity floor.
 */
class GoldenCorpusTest {

  /** Parity floors, raised as the port converges. */
  private static final int MARKDOWN_FLOOR = 1190;
  private static final int TEXT_FLOOR = 1190;
  private static final int HTML_FLOOR = 1190;
  private static final int METADATA_FLOOR = 1190;

  @Test
  void matchesReferenceCorpus() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 1190, "corpus must not shrink: " + cases.size());

    int markdownPass = 0;
    int textPass = 0;
    int htmlPass = 0;
    int metadataPass = 0;
    StringBuilder report = new StringBuilder();
    List<String> markdownDiffs = new ArrayList<>();
    List<String> textDiffs = new ArrayList<>();
    List<String> htmlDiffs = new ArrayList<>();
    List<String> metadataDiffs = new ArrayList<>();

    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      @SuppressWarnings("unchecked")
      Map<String, Object> outputs = (Map<String, Object>) corpusCase.get("outputs");
      String expectedMarkdown = (String) outputs.get("markdown");
      String expectedText = (String) outputs.get("text");
      String expectedHtml = (String) outputs.get("html");
      boolean expectedTruncated = Boolean.TRUE.equals(outputs.get("truncated"));

      MdreamResult markdown = HtmlToMarkdown.convertResult(html, Corpus.options(corpusCase));
      if (expectedMarkdown.equals(markdown.markdown()) && markdown.truncated() == expectedTruncated) {
        markdownPass++;
      } else if (markdownDiffs.size() < 40) {
        markdownDiffs.add(diff(id, expectedMarkdown, markdown.markdown()));
      }

      if (metadataMatches(markdown, outputs)) {
        metadataPass++;
      } else if (metadataDiffs.size() < 40) {
        metadataDiffs.add(
            id
                + "\n  expected frontmatter: "
                + expectedPairs(outputs.get("frontmatter"))
                + "\n  actual frontmatter:   "
                + actualFrontmatter(markdown.frontmatter())
                + "\n  expected extracted:   "
                + expectedExtracted(outputs.get("extracted"))
                + "\n  actual extracted:     "
                + actualExtracted(markdown.extracted()));
      }

      String text =
          HtmlToMarkdown.convert(
              html, Corpus.options(corpusCase, OutputFormat.TEXT));
      if (expectedText.equals(text)) {
        textPass++;
      } else if (textDiffs.size() < 40) {
        textDiffs.add(diff(id, expectedText, text));
      }

      String htmlOut =
          HtmlToMarkdown.convert(
              html, Corpus.options(corpusCase, OutputFormat.HTML));
      if (expectedHtml.equals(htmlOut)) {
        htmlPass++;
      } else if (htmlDiffs.size() < 40) {
        htmlDiffs.add(diff(id, expectedHtml, htmlOut));
      }
    }

    int total = cases.size();
    report.append("markdown: ").append(markdownPass).append('/').append(total).append('\n');
    report.append("text: ").append(textPass).append('/').append(total).append('\n');
    report.append("html: ").append(htmlPass).append('/').append(total).append('\n');
    report.append("metadata: ").append(metadataPass).append('/').append(total).append('\n');
    report.append('\n');
    for (String diff : markdownDiffs) {
      report.append(diff).append('\n');
    }
    for (String diff : textDiffs) {
      report.append(diff).append('\n');
    }
    for (String diff : htmlDiffs) {
      report.append(diff).append('\n');
    }
    for (String diff : metadataDiffs) {
      report.append(diff).append('\n');
    }
    Path reportPath = Paths.get("target", "golden-report.txt");
    Files.createDirectories(reportPath.getParent());
    Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));

    assertTrue(markdownPass >= MARKDOWN_FLOOR, "markdown parity: " + markdownPass + "/" + total);
    assertTrue(textPass >= TEXT_FLOOR, "text parity: " + textPass + "/" + total);
    assertTrue(htmlPass >= HTML_FLOOR, "html parity: " + htmlPass + "/" + total);
    assertTrue(metadataPass >= METADATA_FLOOR, "metadata parity: " + metadataPass + "/" + total);
  }

  private static boolean metadataMatches(MdreamResult result, Map<String, Object> outputs) {
    return Objects.equals(
            expectedPairs(outputs.get("frontmatter")), actualFrontmatter(result.frontmatter()))
        && Objects.equals(
            expectedExtracted(outputs.get("extracted")), actualExtracted(result.extracted()));
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
