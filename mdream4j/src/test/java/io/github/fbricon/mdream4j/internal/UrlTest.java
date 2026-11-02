/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlTest {

  @Test
  void emptyLinkHrefAppliesUrlPreprocessing() {
    for (String href : new String[] {"#", "javascript:void(0)", "data:text/html,x", "vbscript:x"}) {
      assertTrue(Url.isEmptyLinkHref(href), href);
    }
    // Leading C0 controls and spaces are ignored by the URL parser.
    for (String href :
        new String[] {
          " javascript:void(0)",
          "\tjavascript:void(0)",
          "\njavascript:void(0)",
          "\rjavascript:void(0)",
          "\001javascript:void(0)",
          "  \t javascript:void(0)",
          " data:text/html,x",
          " vbscript:x"
        }) {
      assertTrue(Url.isEmptyLinkHref(href), href);
    }
    // Tab, LF and CR are removed anywhere in the scheme.
    for (String href :
        new String[] {
          "java\tscript:void(0)",
          "java\nscript:void(0)",
          "java\rscript:void(0)",
          "j\ta\nv\ra\tscript:void(0)",
          "javascript\t:void(0)",
          "da\tta:x",
          "vb\tscript:x",
          " java\tscript:void(0)"
        }) {
      assertTrue(Url.isEmptyLinkHref(href), href);
    }
    // Case folding, with controls interleaved.
    for (String href :
        new String[] {"JavaScript:x", " JAVASCRIPT:x", "\tJaVa\tScRiPt:x", "DATA:x"}) {
      assertTrue(Url.isEmptyLinkHref(href), href);
    }
    // A bare `#` keeps its meaning through surrounding whitespace.
    for (String href : new String[] {" # ", "#\t", "\t#", "\n#\r"}) {
      assertTrue(Url.isEmptyLinkHref(href), href);
    }

    // An interior space is NOT removed, so the scheme is not `javascript:`.
    for (String href :
        new String[] {
          "java script:x",
          "notjavascript:x",
          "/javascript/guide",
          "https://x.com/a",
          "javascript",
          "#section",
          " #section",
          "#a\tb",
          "",
          "   ",
          "mailto:a@b.c"
        }) {
      assertFalse(Url.isEmptyLinkHref(href), href);
    }
    // Only ASCII letters case-fold: `\u001a` must not be read as `:`.
    assertFalse(Url.isEmptyLinkHref("javascript" + (char) 0x1A + "x"));
    // A non-breaking space is not ASCII whitespace and is not stripped.
    assertFalse(Url.isEmptyLinkHref("\u00a0javascript:x"));
  }

  @Test
  void dataUrlDetectionMatchesEmptyLinkPreprocessing() {
    for (String src :
        new String[] {
          "data:image/png;base64,AAA=",
          "DATA:image/png;base64,AAA=",
          " data:,x",
          "\tdata:,x",
          "da\tta:,x",
          "data:"
        }) {
      assertTrue(Url.isDataUrl(src), src);
    }
    for (String src :
        new String[] {
          "database.png",
          "/data/x.png",
          "https://example.com/data:x",
          "",
          "   ",
          "\u00a0data:,x"
        }) {
      assertFalse(Url.isDataUrl(src), src);
    }
  }

  @Test
  void autolinkUriDetection() {
    assertTrue(Url.isAutolinkUri("https://example.com"));
    assertTrue(Url.isAutolinkUri("http://example.com/a"));
    assertTrue(Url.isAutolinkUri("ftp://host/file"));
    assertTrue(Url.isAutolinkUri("mailto:a@b.com"));
    assertFalse(Url.isAutolinkUri("/relative/path"));
    assertFalse(Url.isAutolinkUri("example.com"));
    // whitespace / angle brackets break autolink syntax
    assertFalse(Url.isAutolinkUri("https://example.com/a b"));
    assertFalse(Url.isAutolinkUri("https://example.com/<x>"));
    // scheme matching is case-sensitive here
    assertFalse(Url.isAutolinkUri("HTTPS://example.com"));
    assertFalse(Url.isAutolinkUri(""));
  }

  @Test
  void safeHtmlUrlDetection() {
    assertTrue(Url.isSafeHtmlUrl("https://x.com/a", false));
    assertTrue(Url.isSafeHtmlUrl("http://x.com", false));
    assertTrue(Url.isSafeHtmlUrl("//cdn.x.com/a", false));
    assertTrue(Url.isSafeHtmlUrl("/path", false));
    assertTrue(Url.isSafeHtmlUrl("?q=1", false));
    assertTrue(Url.isSafeHtmlUrl("#frag", false));
    assertTrue(Url.isSafeHtmlUrl("relative/path", false));
    assertTrue(Url.isSafeHtmlUrl("mailto:a@b.c", false));
    assertTrue(Url.isSafeHtmlUrl("tel:123", false));
    assertTrue(Url.isSafeHtmlUrl("ftp://host/file", false));
    // a non-scheme character before the first colon makes it a relative URL
    assertTrue(Url.isSafeHtmlUrl("java script:x", false));
    assertTrue(Url.isSafeHtmlUrl("https ://x", false));

    assertFalse(Url.isSafeHtmlUrl("", false));
    assertFalse(Url.isSafeHtmlUrl("   ", false));
    assertFalse(Url.isSafeHtmlUrl("javascript:void(0)", false));
    assertFalse(Url.isSafeHtmlUrl(" JavaScript:x", false));
    assertFalse(Url.isSafeHtmlUrl("java\tscript:x", false));
    assertFalse(Url.isSafeHtmlUrl("data:text/html,x", false));
    assertFalse(Url.isSafeHtmlUrl("vbscript:x", false));
    // non-navigational schemes are rejected for images
    assertFalse(Url.isSafeHtmlUrl("mailto:a@b.c", true));
    assertFalse(Url.isSafeHtmlUrl("tel:123", true));
    assertFalse(Url.isSafeHtmlUrl("ftp://host/file", true));
    // http(s) stays safe for images
    assertTrue(Url.isSafeHtmlUrl("https://x.com/a.png", true));
  }

  @Test
  void trackingParamDetection() {
    assertTrue(Url.isTrackingParam("utm_source"));
    assertTrue(Url.isTrackingParam("fbclid"));
    assertTrue(Url.isTrackingParam("gclid"));
    assertTrue(Url.isTrackingParam("mc_eid"));
    assertTrue(Url.isTrackingParam("msclkid"));
    assertTrue(Url.isTrackingParam("oly_enc_id"));
    assertFalse(Url.isTrackingParam("id"));
    assertFalse(Url.isTrackingParam("page"));
    // prefixes are case-sensitive and are not percent-decoded
    assertFalse(Url.isTrackingParam("UTM_source"));
    assertFalse(Url.isTrackingParam("%75tm_source"));
  }

  @Test
  void stripTrackingBorrowsWhenClean() {
    // no query - borrowed, untouched
    String noQuery = "https://x.com/a";
    assertSame(noQuery, Url.stripTrackingParams(noQuery));
    // query with only non-tracking params - borrowed
    String cleanQuery = "https://x.com/a?id=1";
    assertSame(cleanQuery, Url.stripTrackingParams(cleanQuery));
    // a fragment before the query makes the whole thing opaque
    String opaque = "https://x.com/a#s?utm_source=n";
    assertSame(opaque, Url.stripTrackingParams(opaque));
  }

  @Test
  void stripTrackingRemovesParams() {
    assertEquals("https://x.com/a", Url.stripTrackingParams("https://x.com/a?utm_source=n"));
    assertEquals(
        "https://x.com/a?id=1&page=2",
        Url.stripTrackingParams("https://x.com/a?id=1&utm_source=n&page=2"));
    // all params stripped, fragment preserved
    assertEquals("https://x.com/a#sec", Url.stripTrackingParams("https://x.com/a?utm_source=n#sec"));
    // mixed kept + tracking, fragment preserved
    assertEquals(
        "https://x.com/a?id=1#sec", Url.stripTrackingParams("https://x.com/a?id=1&fbclid=z#sec"));
    // trailing tracking param takes the separator before it
    assertEquals(
        "https://x.com/a?id=1", Url.stripTrackingParams("https://x.com/a?id=1&utm_source=n"));
    // adjacent tracking params
    assertEquals(
        "https://x.com/a?id=3", Url.stripTrackingParams("https://x.com/a?utm_a=1&utm_b=2&id=3"));
    assertEquals("https://x.com/a", Url.stripTrackingParams("https://x.com/a?utm_a=1&fbclid=2"));
    // a fragment before the query makes the whole thing opaque
    assertEquals(
        "https://x.com/a#s?utm_source=n",
        Url.stripTrackingParams("https://x.com/a#s?utm_source=n"));
  }

  @Test
  void stripTrackingPreservesEmptyFieldsAndEdgeCases() {
    // leading empty fields collapse away (normalising owned strings, as in Rust)
    assertEquals("https://x.com/?id=1", Url.stripTrackingParamsOwned("https://x.com/?&&id=1"));
    assertEquals("https://x.com/", Url.stripTrackingParamsOwned("https://x.com/?&&"));
    assertEquals("https://x.com/", Url.stripTrackingParams("https://x.com/?&utm_x=1"));
    // a key without '=' is matched as the whole field
    assertEquals("https://x.com/?a=1", Url.stripTrackingParams("https://x.com/?a=1&utm_x"));
    // later empty fields keep their separators
    assertEquals("https://x.com/?a=1&", Url.stripTrackingParams("https://x.com/?a=1&utm_x=2&"));
    // non-ASCII query values survive
    assertEquals(
        "https://x.com/?q=\u540d&id=1", Url.stripTrackingParams("https://x.com/?q=\u540d&utm_x=1&id=1"));
    // case-sensitive: untouched and borrowed
    String upper = "https://x.com/?UTM_x=1";
    assertSame(upper, Url.stripTrackingParams(upper));
  }

  @Test
  void trackingCleanupIsLinear() {
    // Ported from the Rust work-budget test: every input must produce the
    // reference result, including the >8-removal and >1024-byte paths.
    for (int count : new int[] {1, 7, 8, 9, 1024, 2048, 4096}) {
      String kept = "id=" + "x".repeat(count * 8);
      String[][] cases = {
        {"utm_x=1&id=1&".repeat(count), "id=1&".repeat(count)},
        {"utm_x=1&".repeat(count) + kept, kept}
      };
      for (String[] pair : cases) {
        assertEquals(
            "https://x.test/?" + pair[1] + "#\u7ae0",
            Url.stripTrackingParamsOwned("https://x.test/?" + pair[0] + "#\u7ae0"));
      }
    }
    String fragment = "#\u7ae0".repeat(1024);
    for (int count : new int[] {0, 1, 8, 9}) {
      assertEquals(
          "https://x.test/" + fragment,
          Url.stripTrackingParamsOwned(
              "https://x.test/?&&" + "utm_x=1&".repeat(count) + fragment));
    }
    for (int size : new int[] {1023, 1024, 1025}) {
      String kept = "id=" + "x".repeat(size - "utm_x=1&id=".length());
      assertEquals(
          "https://x.test/?" + kept + "#\u7ae0",
          Url.stripTrackingParamsOwned("https://x.test/?utm_x=1&" + kept + "#\u7ae0"));
    }
  }

  @Test
  void trackingCleanupPreservesFields() {
    String[] fields = {
      "", "id=1", "utm_x=1", "fbclid=x", "\u540d=\u5024", "=x", "id=2=3", "%75tm_x=1", "UTM_x=1"
    };
    String[] prefixes = {"", "utm_x=1&".repeat(8), "utm_x=1&".repeat(9)};
    String[] fragments = {"", "#\u7ae0?utm_x=keep&x=1"};
    for (String prefix : prefixes) {
      for (String left : fields) {
        for (String middle : fields) {
          for (String right : fields) {
            for (String fragment : fragments) {
              String url =
                  "https://x.test/\u8def\u5f84?" + prefix + left + '&' + middle + '&' + right + fragment;
              String expected = reference(url);
              assertEquals(expected, Url.stripTrackingParamsOwned(url), url);

              boolean hasTracking = !prefix.isEmpty();
              if (!hasTracking) {
                for (String field : new String[] {left, middle, right}) {
                  int eq = field.indexOf('=');
                  String key = eq < 0 ? field : field.substring(0, eq);
                  if (Url.isTrackingParam(key)) {
                    hasTracking = true;
                  }
                }
              }
              String borrowed = Url.stripTrackingParams(url);
              if (hasTracking) {
                assertEquals(expected, borrowed, url);
              } else {
                assertSame(url, borrowed, url);
              }
            }
          }
        }
      }
    }
    for (String url :
        new String[] {
          "https://x.test/\u8def\u5f84",
          "https://x.test/#\u7ae0?utm_x=keep",
          "https://x.test/?",
          "https://x.test/?&&"
        }) {
      assertEquals(reference(url), Url.stripTrackingParamsOwned(url), url);
    }
  }

  @Test
  void trackingCleanupCompactsLongQueries() {
    StringBuilder tracked = new StringBuilder("https://x.test/?");
    for (int i = 0; i < 200; i++) {
      tracked.append("utm_a=1&");
    }
    tracked.append("id=1");
    assertEquals("https://x.test/?id=1", Url.stripTrackingParams(tracked.toString()));

    StringBuilder allTracked = new StringBuilder("https://x.test/?");
    for (int i = 0; i < 200; i++) {
      allTracked.append("utm_a=1&");
    }
    assertEquals("https://x.test/", Url.stripTrackingParams(allTracked.toString()));
  }

  @Test
  void resolveUrlPassthrough() {
    // empty and fragment-only are borrowed unchanged
    String empty = "";
    assertSame(empty, Url.resolveUrl(empty, null, false));
    String anchor = "#anchor";
    assertSame(anchor, Url.resolveUrl(anchor, "https://x.com", true));
    // absolute URL with no origin and no cleaning - unchanged
    String absolute = "https://x.com/a";
    assertSame(absolute, Url.resolveUrl(absolute, null, false));
  }

  @Test
  void resolveUrlProtocolRelative() {
    assertEquals("https://cdn.x.com/a.js", Url.resolveUrl("//cdn.x.com/a.js", null, false));
    // inherits the base scheme only
    assertEquals(
        "http://cdn.x.com/a.js", Url.resolveUrl("//cdn.x.com/a.js", "http://x.com", false));
    // an origin without an authority yields the https default
    assertEquals("https://cdn.x.com/a.js", Url.resolveUrl("//cdn.x.com/a.js", "docs", false));
  }

  @Test
  void resolveUrlRelativeAgainstOrigin() {
    assertEquals("https://x.com/page", Url.resolveUrl("page", "https://x.com", false));
    assertEquals("https://x.com/sub", Url.resolveUrl("./sub", "https://x.com", false));
    assertEquals("https://x.com/sub", Url.resolveUrl("./sub", "https://x.com/", false));
    assertEquals("https://x.com/path", Url.resolveUrl("/path", "https://x.com/", false));
  }

  @Test
  void resolveUrlResolvesRelativeReferences() {
    String doc = "https://x.com/a/b/doc.html?q=1#frag";
    assertEquals("https://x.com/a/b/c.html", Url.resolveUrl("c.html", doc, false));
    assertEquals("https://x.com/a/c.html", Url.resolveUrl("../c.html", doc, false));
    assertEquals("https://x.com/c", Url.resolveUrl("/a/../c", doc, false));
    assertEquals("https://x.com/c", Url.resolveUrl("../../../c", doc, false));
    assertEquals("https://x.com/a/", Url.resolveUrl("..", doc, false));
    assertEquals("https://x.com/a/b/c//d", Url.resolveUrl("c//d", doc, false));
    assertEquals(
        "https://x.com/a/b/c.html?p=../x#f", Url.resolveUrl("c.html?p=../x#f", doc, false));
    assertEquals("https://x.com/a/b/doc.html?p=2", Url.resolveUrl("?p=2", doc, false));
    // dot segments are normalised, non-dot dot-prefixed names are not
    assertEquals("https://x.com/a/b/.hidden", Url.resolveUrl(".hidden", doc, false));
    assertEquals("https://x.com/a/b/", Url.resolveUrl(".", doc, false));
    assertEquals("https://x.com/a/b/c/d", Url.resolveUrl("c/./d", doc, false));
  }

  @Test
  void resolveUrlOriginWithoutAuthorityIsAPrefix() {
    // A plain prefix keeps an unsafe origin recognisable downstream.
    assertEquals(
        "javascript:alert(1)/safe", Url.resolveUrl("/safe", "javascript:alert(1)", false));
    assertEquals("docs/b.html", Url.resolveUrl("b.html", "docs", false));
    assertEquals("docs/sub", Url.resolveUrl("./sub", "docs/", false));
  }

  @Test
  void resolveUrlCleansWhenRequested() {
    assertEquals("https://x.com/p", Url.resolveUrl("/p?utm_source=n", "https://x.com", true));
    // clean disabled - tracking param kept
    assertEquals(
        "https://x.com/p?utm_source=n", Url.resolveUrl("/p?utm_source=n", "https://x.com", false));
    // A query-like suffix inside the fragment is opaque.
    assertEquals(
        "https://x.com/#/route?utm_source=n&keep=1",
        Url.resolveUrl("https://x.com/#/route?utm_source=n&keep=1", null, true));
  }

  @Test
  void resolveUrlLeavesPercentEncodingAlone() {
    assertEquals("https://x.com/a%20b", Url.resolveUrl("a%20b", "https://x.com/d", false));
    assertEquals(
        "https://x.com/%E8%B7%AF", Url.resolveUrl("%E8%B7%AF", "https://x.com", false));
  }

  @Test
  void resolveUrlHandlesNonAsciiPaths() {
    assertEquals(
        "https://x.com/a/\u8def\u5f84/p",
        Url.resolveUrl("\u8def\u5f84/p", "https://x.com/a/b", false));
    assertEquals(
        "https://x.com/a/\u8def\u5f84",
        Url.resolveUrl("../\u8def\u5f84", "https://x.com/a/b/c", false));
  }

  @Test
  void resolveBaseHrefAcceptsOnlyHttpOrigins() {
    assertEquals("https://cdn.x.com/assets/", Url.resolveBaseHref("https://cdn.x.com/assets/", null));
    assertEquals(
        "https://x.com/base/", Url.resolveBaseHref("/base/", "https://x.com/old/page"));
    assertEquals("https://x.com/base", Url.resolveBaseHref("  /base  ", "https://x.com"));
    assertEquals(
        "https://cdn.x.com/a", Url.resolveBaseHref("//cdn.x.com/a", "https://x.com"));
    assertEquals(
        "https://x.com/old/sub/", Url.resolveBaseHref("./sub/", "https://x.com/old/page"));
    assertEquals("HTTPS://x.com", Url.resolveBaseHref("HTTPS://x.com", null));
    assertEquals(
        "https://user@host:8080/x", Url.resolveBaseHref("https://user@host:8080/x", null));

    assertNull(Url.resolveBaseHref("mailto:x", null));
    assertNull(Url.resolveBaseHref("ftp://x/y", null));
    assertNull(Url.resolveBaseHref("https:///path", null));
    assertNull(Url.resolveBaseHref("https://:8080/x", null));
    assertNull(Url.resolveBaseHref("/x", "javascript:alert(1)"));
  }

  @Test
  void slugifyBasic() {
    assertEquals("hello-world", Url.slugifyHeading("Hello World"));
    assertEquals("trim-me", Url.slugifyHeading("  Trim Me  "));
    // `_` is treated as a markdown emphasis marker and stripped
    assertEquals("keepunderscore", Url.slugifyHeading("Keep_Underscore"));
    // collapse repeated separators
    assertEquals("a-b", Url.slugifyHeading("a -- b"));
    // strip punctuation
    assertEquals("whats-new", Url.slugifyHeading("What's New?!"));
    // leading separators are dropped, non-ASCII characters too
    assertEquals("a-b", Url.slugifyHeading(" -- a b"));
    assertEquals("", Url.slugifyHeading("\u65e5\u672c\u8a9e"));
  }

  @Test
  void schemeUrlsNeverJoinedToOrigin() {
    // explicit schemes must pass through untouched, not get origin-prefixed
    assertEquals("mailto:a@b.com", Url.resolveUrl("mailto:a@b.com", "https://x.com", false));
    assertEquals("ftp://h/f", Url.resolveUrl("ftp://h/f", "https://x.com", false));
    assertEquals("tel:123", Url.resolveUrl("tel:123", "https://x.com", true));
  }

  @Test
  void slugifyStripsInlineMarkdown() {
    // links reduce to their text
    assertEquals("see-the-docs", Url.slugifyHeading("See [the docs](https://x.com)"));
    // emphasis / code markers dropped
    assertEquals("bold-and-code", Url.slugifyHeading("*bold* and `code`"));
    assertEquals("section", Url.slugifyHeading("<a href=\"#section\">Section</a>"));
    assertEquals("httpx", Url.slugifyHeading("<http://x>"));
    assertEquals("span", Url.slugifyHeading("\\<span\\>"));
    assertEquals("span", Url.slugifyHeading("`<span>`"));
    assertEquals("foobar", Url.slugifyHeading("`foo_bar"));
    assertEquals("foobar", Url.slugifyHeading("``foo_bar`"));
    assertEquals("foo_bar", Url.slugifyHeading("``foo_bar``"));
    assertEquals("foo_bar", Url.slugifyHeading("`` `foo_bar`"));
    assertEquals("foobar", Url.slugifyHeading("`foo_bar<span title=\"`\"></span>"));
    assertEquals(
        "foo_barspan-title", Url.slugifyHeading("`foo_bar\\<span title=\"`\"></span>"));
    assertEquals("foobar", Url.slugifyHeading("`foo_bar\\\\<span title=\"`\"></span>"));
    assertEquals("foobar", Url.slugifyHeading("\\`foo_bar"));
    assertEquals("foo_bar", Url.slugifyHeading("`foo\\_bar"));
    assertEquals("foo_bar", Url.slugifyHeading("`foo_bar\\`"));
    assertEquals("foo_bar-bazqux", Url.slugifyHeading("`foo_bar\\` baz_qux`"));
  }

  /**
   * Straightforward reference implementation from the Rust test: split the
   * query on `&`, drop fields whose key matches a tracking prefix, and only
   * keep the `?` when something remains.
   */
  private static String reference(String url) {
    int qmark = url.indexOf('?');
    if (qmark < 0) {
      return url;
    }
    if (url.substring(0, qmark).indexOf('#') >= 0) {
      return url;
    }
    int queryStart = qmark + 1;
    int hash = url.indexOf('#', queryStart);
    int queryEnd = hash < 0 ? url.length() : hash;
    String query = url.substring(queryStart, queryEnd);
    StringBuilder kept = new StringBuilder();
    int start = 0;
    while (true) {
      int amp = query.indexOf('&', start);
      String field = amp < 0 ? query.substring(start) : query.substring(start, amp);
      int eq = field.indexOf('=');
      String key = eq < 0 ? field : field.substring(0, eq);
      if (!Url.isTrackingParam(key)) {
        if (kept.length() > 0) {
          kept.append('&');
        }
        kept.append(field);
      }
      if (amp < 0) {
        break;
      }
      start = amp + 1;
    }
    return url.substring(0, qmark)
        + (kept.length() == 0 ? "" : "?")
        + kept
        + url.substring(queryEnd);
  }
}
