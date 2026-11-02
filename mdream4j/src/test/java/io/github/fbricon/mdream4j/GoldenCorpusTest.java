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
import java.util.List;
import java.util.Map;
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
  private static final int MARKDOWN_FLOOR = 147;
  private static final int TEXT_FLOOR = 147;
  private static final int HTML_FLOOR = 13;

  @Test
  void matchesReferenceCorpus() throws IOException {
    List<Map<String, Object>> cases = Corpus.loadCases();
    assertTrue(cases.size() >= 147, "corpus must not shrink: " + cases.size());

    int markdownPass = 0;
    int textPass = 0;
    int htmlPass = 0;
    StringBuilder report = new StringBuilder();
    List<String> markdownDiffs = new ArrayList<>();

    for (Map<String, Object> corpusCase : cases) {
      String id = (String) corpusCase.get("id");
      String html = (String) corpusCase.get("html");
      @SuppressWarnings("unchecked")
      Map<String, Object> outputs = (Map<String, Object>) corpusCase.get("outputs");
      String expectedMarkdown = (String) outputs.get("markdown");
      String expectedText = (String) outputs.get("text");
      String expectedHtml = (String) outputs.get("html");
      boolean expectedTruncated = Boolean.TRUE.equals(outputs.get("truncated"));

      MdreamResult markdown = HtmlToMarkdown.convertResult(html, Options.defaults());
      if (expectedMarkdown.equals(markdown.markdown()) && markdown.truncated() == expectedTruncated) {
        markdownPass++;
      } else if (markdownDiffs.size() < 40) {
        markdownDiffs.add(diff(id, expectedMarkdown, markdown.markdown()));
      }

      String text =
          HtmlToMarkdown.convert(html, Options.builder().format(OutputFormat.TEXT).build());
      if (expectedText.equals(text)) {
        textPass++;
      }

      String htmlOut =
          HtmlToMarkdown.convert(html, Options.builder().format(OutputFormat.HTML).build());
      if (expectedHtml.equals(htmlOut)) {
        htmlPass++;
      }
    }

    int total = cases.size();
    report.append("markdown: ").append(markdownPass).append('/').append(total).append('\n');
    report.append("text: ").append(textPass).append('/').append(total).append('\n');
    report.append("html: ").append(htmlPass).append('/').append(total).append('\n');
    report.append('\n');
    for (String diff : markdownDiffs) {
      report.append(diff).append('\n');
    }
    Path reportPath = Paths.get("target", "golden-report.txt");
    Files.createDirectories(reportPath.getParent());
    Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));

    assertTrue(markdownPass >= MARKDOWN_FLOOR, "markdown parity: " + markdownPass + "/" + total);
    assertTrue(textPass >= TEXT_FLOOR, "text parity: " + textPass + "/" + total);
    assertTrue(htmlPass >= HTML_FLOOR, "html parity: " + htmlPass + "/" + total);
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
