/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.reference;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fbricon.mdream4j.Clean;
import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.MdreamResult;
import io.github.fbricon.mdream4j.Options;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Differential test for {@code maxNodeBytes}, against the live Rust reference.
 *
 * <p>The golden corpus only covers small capped inputs. The constructs that hold output back — an
 * open quote, a trailing heading {@code #} run, a blank link, a streaming {@code <br>} run, a code
 * fence or span — only outgrow the cap on documents too large to check in, so this suite builds
 * them here and compares both the exact truncation point and the {@code truncated} flag. The
 * scenarios mirror {@code crates/core/tests/max_node_bytes.rs::held_back_output_is_capped} and its
 * neighbours.
 */
class CappedParityTest {

  /** Enough repeats for every scenario to cross the largest cap below. */
  private static final int HUGE = 300_000;

  private static final int[] CAPS = {4 * 1024, 64 * 1024};

  private static final int[] CHUNKS = {37, 8 * 1024};

  @BeforeAll
  static void requireNativeLibrary() {
    Assumptions.assumeTrue(
        ReferenceBridge.isAvailable(),
        () -> "native reference library unavailable: " + ReferenceBridge.loadError());
  }

  @Test
  void oneShotMatchesReference() {
    List<String> failures = new ArrayList<>();
    for (Scenario scenario : scenarios()) {
      for (int cap : CAPS) {
        Options options = scenario.options(cap);
        String json = scenario.json(cap);
        MdreamResult actual = HtmlToMarkdown.convertResult(scenario.html(), options);
        ReferenceBridge.ReferenceResult expected =
            ReferenceBridge.convertResult(scenario.html(), json);
        compare(scenario.name() + " cap=" + cap, expected, actual, failures);
      }
    }
    assertTrue(failures.isEmpty(), report(failures));
  }

  @Test
  void streamingMatchesReference() {
    List<String> failures = new ArrayList<>();
    for (Scenario scenario : scenarios()) {
      String html = scenario.html();
      for (int cap : CAPS) {
        Options options = scenario.options(cap);
        String json = scenario.json(cap);
        for (int chunk : CHUNKS) {
          String label = scenario.name() + " cap=" + cap + " chunk=" + chunk;
          MarkdownStream javaStream = HtmlToMarkdown.newStream(options);
          StringBuilder javaOut = new StringBuilder();
          String refText;
          boolean refTruncated;
          try (ReferenceBridge.ReferenceStream refStream = ReferenceBridge.newStream(json)) {
            StringBuilder refOut = new StringBuilder();
            for (int start = 0; start < html.length(); start += chunk) {
              String piece = html.substring(start, Math.min(html.length(), start + chunk));
              javaOut.append(javaStream.feed(piece));
              refOut.append(refStream.feed(piece));
            }
            javaOut.append(javaStream.finish());
            refOut.append(refStream.finish());
            refTruncated = refStream.truncated();
            refText = refOut.toString();
          }
          boolean javaTruncated = javaStream.truncated();
          String javaText = javaOut.toString();
          if (refText.equals(javaText) && refTruncated == javaTruncated) {
            continue;
          }
          if (failures.size() < 10) {
            int divergence = firstDivergence(refText, javaText);
            failures.add(
                label
                    + "\n  truncated: expected "
                    + refTruncated
                    + " actual "
                    + javaTruncated
                    + "\n  lengths:   expected "
                    + refText.length()
                    + " actual "
                    + javaText.length()
                    + "\n  at "
                    + divergence
                    + ": expected "
                    + window(refText, divergence)
                    + "\n             actual "
                    + window(javaText, divergence));
          }
        }
      }
    }
    assertTrue(failures.isEmpty(), report(failures));
  }

  private static void compare(
      String label,
      ReferenceBridge.ReferenceResult expected,
      MdreamResult actual,
      List<String> failures) {
    if (expected.markdown.equals(actual.markdown()) && expected.truncated == actual.truncated()) {
      return;
    }
    if (failures.size() >= 10) {
      return;
    }
    StringBuilder failure = new StringBuilder(label);
    failure.append("\n  truncated: expected ").append(expected.truncated);
    failure.append(" actual ").append(actual.truncated());
    failure.append("\n  lengths:   expected ").append(expected.markdown.length());
    failure.append(" actual ").append(actual.markdown().length());
    int divergence = firstDivergence(expected.markdown, actual.markdown());
    failure
        .append("\n  at ")
        .append(divergence)
        .append(": expected ")
        .append(window(expected.markdown, divergence))
        .append("\n             actual ")
        .append(window(actual.markdown(), divergence));
    failures.add(failure.toString());
  }

  private static int firstDivergence(String expected, String actual) {
    int limit = Math.min(expected.length(), actual.length());
    for (int i = 0; i < limit; i++) {
      if (expected.charAt(i) != actual.charAt(i)) {
        return i;
      }
    }
    return limit;
  }

  private static String window(String value, int from) {
    int start = Math.max(0, from - 40);
    int end = Math.min(value.length(), from + 60);
    return escape(value.substring(start, end));
  }

  private static String escape(String value) {
    return value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private static String report(List<String> failures) {
    return failures.size() + " capped mismatch(es):\n" + String.join("\n", failures);
  }

  /** A source document plus the Java and JSON options that select the same behaviour. */
  private static List<Scenario> scenarios() {
    List<Scenario> scenarios = new ArrayList<>();
    scenarios.add(
        new Scenario(
            "quoted-inline",
            "<p>before</p><blockquote>" + repeat("word <em>e</em> ") + "</blockquote><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "quoted-strong",
            "<p>before</p><blockquote><strong>"
                + repeat("<p>para text</p>")
                + "</strong></blockquote><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "quoted-link",
            "<p>before</p><blockquote><a href=\"/x\"><ul>"
                + repeat("<li>item</li>")
                + "</ul></a></blockquote><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "quote-caption",
            "<p>before</p><blockquote><figure><figcaption><ul>"
                + repeat("<li>item</li>")
                + "</ul></figcaption></figure></blockquote><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "heading-hashes",
            "<p>before</p><h1>" + repeat("#<span></span>") + "</h1><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "heading-hashes-emphasis",
            "<p>before</p><h1>" + repeat("#<em> </em>") + "</h1><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "heading-hashes-empty-link",
            "<p>before</p><h1>" + repeat("####<a href=\"/x\"> </a>") + "</h1><p>after</p>",
            Clean.builder().emptyLinkText(true).build()));
    scenarios.add(
        new Scenario(
            "blank-link-text",
            "<p>before</p><p><a href=\"/x\">"
                + repeat("&#x3000;<!---->&nbsp;<span></span>")
                + "</a></p><p>after</p>",
            Clean.builder().emptyLinkText(true).build()));
    scenarios.add(
        new Scenario(
            "self-link-heading",
            "<p>before</p><h1><a href=\"#a\">"
                + repeat("word <em>e</em> ")
                + "</a></h1><p>after</p>",
            Clean.builder().selfLinkHeadings(true).build()));
    scenarios.add(
        new Scenario(
            "br-run",
            "<p>before</p><p>a" + repeat("<br>") + "</p><p>after</p>",
            null));
    scenarios.add(
        new Scenario(
            "code-fence",
            "<pre><code>" + repeat("line of code\n") + "</code></pre>",
            null));
    scenarios.add(
        new Scenario(
            "inline-code", "<p>" + repeat("<code>x</code> ") + "</p>", null));
    scenarios.add(new Scenario("text-node", "<p>" + repeat("word ") + "</p>", null));
    scenarios.add(
        new Scenario(
            "table-row",
            "<table><tr>" + repeat("<td>cell</td>") + "</tr></table>",
            null));
    return scenarios;
  }

  private static String repeat(String unit) {
    StringBuilder out = new StringBuilder(HUGE + unit.length());
    while (out.length() < HUGE) {
      out.append(unit);
    }
    return out.toString();
  }

  private static final class Scenario {
    private final String name;
    private final String html;
    private final Clean clean;

    Scenario(String name, String html, Clean clean) {
      this.name = name;
      this.html = html;
      this.clean = clean;
    }

    String name() {
      return name;
    }

    String html() {
      return html;
    }

    Options options(int cap) {
      Options.Builder builder = Options.builder().maxNodeBytes(cap);
      if (clean != null) {
        builder.clean(clean);
      }
      return builder.build();
    }

    String json(int cap) {
      StringBuilder json = new StringBuilder("{\"maxNodeBytes\":").append(cap);
      if (clean != null) {
        json.append(",\"clean\":{\"emptyLinkText\":")
            .append(clean.emptyLinkText())
            .append(",\"selfLinkHeadings\":")
            .append(clean.selfLinkHeadings())
            .append('}');
      }
      return json.append('}').toString();
    }
  }
}
