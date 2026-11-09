/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.smoke;

import io.github.fbricon.mdream4j.HtmlToMarkdown;
import io.github.fbricon.mdream4j.MarkdownStream;
import io.github.fbricon.mdream4j.Options;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Converts three generated documents and prints a digest per conversion.
 *
 * <p>The same class runs on the JVM and inside a GraalVM native image; the smoke script diffs
 * the two outputs. That catches anything native-image changes about class initialisation, charset
 * handling or the converter's caches without shipping per-platform golden files. The documents
 * are generated so the smoke needs no fixture checkout.
 *
 * <p>Every streamed conversion is also compared with its one-shot result, so the invariant that
 * streaming never changes the output is asserted on the native image too.
 */
public final class NativeSmoke {

  /**
   * Results go to stdout, timing to stderr; {@code run.sh} diffs the former and parses the latter,
   * so the handlers below must emit the message and nothing else.
   */
  private static final System.Logger REPORT =
      System.getLogger("io.github.fbricon.mdream4j.smoke.report");

  private static final System.Logger TIMING =
      System.getLogger("io.github.fbricon.mdream4j.smoke.timing");

  private NativeSmoke() {}

  public static void main(String[] args) {
    configureLogging();
    String kitchenSink = kitchenSink();
    String large = largeDocument();
    String deep = deepDocument();

    long start = System.nanoTime();
    String first = HtmlToMarkdown.convert(kitchenSink);
    long firstMillis = System.nanoTime() - start;

    report("kitchen-sink/default", first);
    report(
        "kitchen-sink/wrap80",
        HtmlToMarkdown.convert(kitchenSink, Options.builder().wrapWidth(80).build()));
    report("large/default", HtmlToMarkdown.convert(large));
    reportStream("large/stream-8k", large, 8192);
    report("deep/default", HtmlToMarkdown.convert(deep));
    reportStream("deep/stream-7", deep, 7);

    TIMING.log(System.Logger.Level.INFO, "first-conversion-ms " + (firstMillis / 1_000_000.0));
  }

  private static String kitchenSink() {
    return "<!doctype html><html><head><title>Smoke &amp; Test</title></head><body>"
        + "<h1 id=\"top\">Heading <em>one</em></h1>"
        + "<p>Text with <strong>bold</strong>, <em>italic</em>, <del>struck</del> and "
        + "<code>inline code</code>, plus &lt;escaped&gt; &amp; entities &nbsp; and "
        + "a non-breaking space.</p>"
        + "<p><a href=\"https://example.com/page?q=1&amp;r=2\" title=\"Example\">titled link</a>, "
        + "<a href=\"/relative\">relative link</a>, <a href=\"#top\">anchor</a> and "
        + "<a href=\"mailto:dev@example.com\">mail</a>.</p>"
        + "<ul><li>first<ul><li>nested <a href=\"https://example.org\">link</a></li>"
        + "<li>nested two</li></ul></li><li>second</li></ul>"
        + "<ol start=\"3\"><li>three</li><li>four<ol><li>four.one</li></ol></li></ol>"
        + "<blockquote><p>Quoted <strong>text</strong>.</p><blockquote><p>Nested quote.</p>"
        + "</blockquote></blockquote>"
        + "<pre><code class=\"language-java\">int x = 1;\nif (x &lt; 2) {\n  x++;\n}\n</code></pre>"
        + "<table><thead><tr><th>Left</th><th align=\"center\">Center</th>"
        + "<th align=\"right\">Right</th></tr></thead><tbody>"
        + "<tr><td>a</td><td>b</td><td>c</td></tr>"
        + "<tr><td><code>x|y</code></td><td>2</td><td>3</td></tr></tbody></table>"
        + "<figure><img src=\"/img.png\" alt=\"Alt text\" title=\"Title\">"
        + "<figcaption>A caption</figcaption></figure>"
        + "<details><summary>More</summary><p>Hidden text.</p></details>"
        + "<hr><p>Line one<br>Line two<br><br>Line four</p>"
        + "<p>Some <span class=\"x\">spanned</span> text and <sup>sup</sup><sub>sub</sub>.</p>"
        + "<p>Wrapping matters when a paragraph is much longer than the configured width, so this"
        + " sentence keeps going with several clauses, commas, and words that must flow onto more"
        + " than one line while the converter decides where to break them, and it ends here.</p>"
        + "</body></html>";
  }

  /** About 1 MB of paragraphs, links and emphasis, built deterministically. */
  private static String largeDocument() {
    StringBuilder html = new StringBuilder(1 << 20);
    html.append("<article><h1>Large</h1>");
    for (int i = 0; i < 2000; i++) {
      html.append("<p>Paragraph ")
          .append(i)
          .append(" has <strong>bold</strong> and <em>italic</em> text with a ")
          .append("<a href=\"https://example.com/")
          .append(i)
          .append("\">link ")
          .append(i)
          .append("</a> and <code>code</code>, plus enough trailing words to keep the run")
          .append(" crossing the text-run window boundary.</p>");
    }
    html.append("</article>");
    return html.toString();
  }

  private static String deepDocument() {
    StringBuilder html = new StringBuilder(4096);
    for (int i = 0; i < 512; i++) {
      html.append("<div>");
    }
    html.append("deep text");
    for (int i = 0; i < 512; i++) {
      html.append("</div>");
    }
    return html.toString();
  }

  private static void reportStream(String name, String html, int chunk) {
    String streamed = stream(html, chunk);
    String oneShot = HtmlToMarkdown.convert(html);
    if (!streamed.equals(oneShot)) {
      throw new IllegalStateException(name + ": streamed output differs from one-shot output");
    }
    report(name, streamed);
  }

  private static String stream(String html, int chunk) {
    MarkdownStream stream = HtmlToMarkdown.newStream(Options.defaults());
    StringBuilder out = new StringBuilder(html.length());
    for (int offset = 0; offset < html.length(); offset += chunk) {
      out.append(stream.feed(html.substring(offset, Math.min(html.length(), offset + chunk))));
    }
    out.append(stream.finish());
    return out.toString();
  }

  private static void report(String name, String markdown) {
    REPORT.log(System.Logger.Level.INFO, name + " " + sha256(markdown) + " " + markdown.length());
  }

  /**
   * Points each smoke logger at its stream with a message-only formatter.
   *
   * <p>{@code System.Logger} has no output API of its own, so this binds the JUL handlers behind
   * it. The default {@code SimpleFormatter} would add timestamps and class names, which would
   * break both {@code run.sh}'s diff and its timing parser.
   */
  private static void configureLogging() {
    bind(REPORT, System.out);
    bind(TIMING, System.err);
  }

  private static void bind(System.Logger logger, PrintStream stream) {
    Logger jul = Logger.getLogger(logger.getName());
    jul.setUseParentHandlers(false);
    jul.setLevel(Level.ALL);
    for (Handler handler : jul.getHandlers()) {
      jul.removeHandler(handler);
    }
    Handler handler =
        new StreamHandler(stream, new MessageOnlyFormatter()) {
          @Override
          public synchronized void close() {
            // System.out/err outlive this handler; never let shutdown close them.
            flush();
          }
        };
    handler.setLevel(Level.ALL);
    jul.addHandler(handler);
  }

  /** Emits exactly the log message plus the platform line separator. */
  private static final class MessageOnlyFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      return record.getMessage() + System.lineSeparator();
    }
  }

  private static String sha256(String value) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the smoke test", e);
    }
    byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16));
      hex.append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
