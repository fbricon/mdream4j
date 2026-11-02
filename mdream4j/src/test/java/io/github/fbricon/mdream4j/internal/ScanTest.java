/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScanTest {

  private static Attributes parseAttributes(String attrs, int mask) {
    Attributes out = new Attributes();
    Scan.TagScan scan =
        Scan.processTagAttributes(
            attrs + ">", 0, null, mask, new Scan.AttrBudget(0), out);
    assertTrue(scan.complete, attrs);
    return out;
  }

  @Test
  void whitespaceDetection() {
    for (char c : new char[] {' ', '\t', '\n', '\r', 0x0C}) {
      assertTrue(Scan.isWhitespace(c), Integer.toHexString(c));
    }
    for (char c : new char[] {'a', '0', '-', 0}) {
      assertFalse(Scan.isWhitespace(c), Integer.toHexString(c));
    }
  }

  @Test
  void parsesQuotedAndUnquotedAttributes() {
    Attributes a = parseAttributes("href=\"/x\" id=main", Consts.ATTR_ALL);
    assertEquals("/x", a.get("href"));
    assertEquals("main", a.get("id"));
  }

  @Test
  void quoteInsideUnquotedValueIsOrdinary() {
    Attributes a = parseAttributes("alt=Bob's src=/i.png", Consts.ATTR_ALL);
    assertEquals("Bob's", a.get("alt"));
    assertEquals("/i.png", a.get("src"));

    Attributes b = parseAttributes("alt=Bob\"s", Consts.ATTR_ALL);
    assertEquals("Bob\"s", b.get("alt"));
  }

  @Test
  void slashInsideUnquotedValueIsOrdinary() {
    assertEquals("/a/b/", parseAttributes("href=/a/b/", Consts.ATTR_ALL).get("href"));
  }

  @Test
  void duplicateAttributeKeepsTheFirst() {
    assertEquals(
        "/first", parseAttributes("href=/first href=/second", Consts.ATTR_ALL).get("href"));
    assertEquals("/first", parseAttributes("href=/first HREF=/second", Consts.ATTR_ALL).get("href"));
    assertEquals(
        "/1", parseAttributes("href=\"/1\" href='/2' href=/3", Consts.ATTR_ALL).get("href"));
  }

  @Test
  void parsesValuelessAndEmptyAttributes() {
    Attributes a = parseAttributes("disabled checked", Consts.ATTR_ALL);
    assertTrue(a.get("disabled") != null);
    assertTrue(a.get("checked") != null);
    assertFalse(parseAttributes("", Consts.ATTR_ALL).iterator().hasNext());
  }

  @Test
  void namesLowercasedValuesDecoded() {
    Attributes a = parseAttributes("DATA-X='a &amp; b'", Consts.ATTR_ALL);
    assertEquals("a & b", a.get("data-x"));
  }

  @Test
  void ambiguousAmpersandsInAttributes() {
    Attributes a = parseAttributes("title='&copycat &copy=1 &copy! &copy;cat'", Consts.ATTR_ALL);
    assertEquals("&copycat &copy=1 \u00a9! \u00a9cat", a.get("title"));
  }

  @Test
  void valuelessEqualsAttributeKeptAsEmpty() {
    Attributes a = parseAttributes("href=", Consts.ATTR_ALL);
    assertEquals("", a.get("href"));
  }

  @Test
  void filteredMaskStoresOnlyWantedNames() {
    int mask = Consts.ATTR_HREF | Consts.ATTR_TITLE | Consts.ATTR_ARIA_LABEL;
    Attributes a =
        parseAttributes(
            "class=btn href=\"/x\" rel=nofollow data-id='7' TITLE=\"t\" target=_blank", mask);
    assertEquals("/x", a.get("href"));
    assertEquals("t", a.get("title"));
    assertNull(a.get("class"));
    assertNull(a.get("rel"));
    assertNull(a.get("data-id"));
    assertNull(a.get("target"));
  }

  @Test
  void filteredMaskKeepsTrailingAndValuelessForms() {
    assertTrue(parseAttributes("hidden href", Consts.ATTR_HREF).get("href") != null);
    assertTrue(parseAttributes("hidden href=", Consts.ATTR_HREF).get("href") != null);
    assertEquals("/i.png", parseAttributes("class=c src=/i.png", Consts.ATTR_SRC).get("src"));
    assertNull(parseAttributes("class=c src=/i.png", Consts.ATTR_SRC).get("class"));
  }

  @Test
  void noneStoresNothingAndAllStoresEverything() {
    assertFalse(parseAttributes("href=/x class=c", Consts.ATTR_NONE).iterator().hasNext());
    Attributes all = parseAttributes("href=/x class=c", Consts.ATTR_ALL);
    assertTrue(all.get("href") != null && all.get("class") != null);
  }

  @Test
  void colspanUsesTheFilteredPath() {
    Attributes attrs = parseAttributes("colspan=2 id=x", Consts.ATTR_COLSPAN);
    assertEquals("2", attrs.get("colspan"));
    assertNull(attrs.get("id"));
  }

  @Test
  void findsTagClose() {
    String html = "a href=\"x\">rest";
    Attributes attrs = new Attributes();
    Scan.TagScan scan =
        Scan.processTagAttributes(html, 1, null, Consts.ATTR_ALL, new Scan.AttrBudget(0), attrs);
    assertTrue(scan.complete);
    assertFalse(scan.selfClosing);
    assertEquals("rest", html.substring(scan.newPosition));
    assertEquals("x", attrs.get("href"));
  }

  @Test
  void unterminatedQuotedValueLeavesTagIncomplete() {
    Attributes attrs = new Attributes();
    Scan.TagScan scan =
        Scan.processTagAttributes(
            "a href=\"x", 1, null, Consts.ATTR_ALL, new Scan.AttrBudget(0), attrs);
    assertFalse(scan.complete);
    assertFalse(attrs.iterator().hasNext());
  }

  @Test
  void quotedValueHidesTagTerminator() {
    String html = "a href=\"x>y\">rest";
    Attributes attrs = new Attributes();
    Scan.TagScan scan =
        Scan.processTagAttributes(html, 1, null, Consts.ATTR_ALL, new Scan.AttrBudget(0), attrs);
    assertTrue(scan.complete);
    assertEquals("x>y", attrs.get("href"));
    assertEquals("rest", html.substring(scan.newPosition));
  }

  @Test
  void bothMasksAgreeOnTagEnd() {
    String[][] cases = {
      {"a href=\"x>y\">rest", "13"},
      {"a href = \"x>y\">rest", "15"},
      {"a href=x/>rest", "10"},
      {"a href=a\"b>rest", "11"},
      {"a href=a'b>rest", "11"},
      {"a href=a'b c=d'e>rest", "17"},
      {"a href=x='y>rest", "12"},
      {"a>rest", "2"},
    };
    for (String[] testCase : cases) {
      String html = testCase[0];
      int end = Integer.parseInt(testCase[1]);
      Attributes extracted = new Attributes();
      Scan.TagScan withAttrs =
          Scan.processTagAttributes(
              html, 1, null, Consts.ATTR_ALL, new Scan.AttrBudget(0), extracted);
      Attributes bare = new Attributes();
      Scan.TagScan withoutAttrs =
          Scan.processTagAttributes(
              html, 1, null, Consts.ATTR_NONE, new Scan.AttrBudget(0), bare);
      assertTrue(withAttrs.complete, html);
      assertEquals(end, withAttrs.newPosition, html);
      assertEquals(withAttrs.complete, withoutAttrs.complete, html);
      assertEquals(withAttrs.newPosition, withoutAttrs.newPosition, html);
      assertEquals(withAttrs.selfClosing, withoutAttrs.selfClosing, html);
      assertFalse(bare.iterator().hasNext(), html);
    }
  }

  @Test
  void chunkedScanMatchesWholeScan() {
    String[] cases = {
      "href=\"/x\" id=main",
      "alt=Bob's src=/i.png",
      "href=/a/b/",
      "href=/first HREF=/second",
      "disabled checked",
      "DATA-X='a &amp; b'",
      "title='&copycat &copy=1 &copy! &copy;cat'",
      "class=btn href=\"/x\" rel=nofollow data-id='7' TITLE=\"t\" target=_blank",
      "href = \"x>y\"",
      "a=b c='d e' f=g/",
    };
    for (String attrs : cases) {
      String html = attrs + ">";
      Attributes whole = new Attributes();
      Scan.TagScan wholeScan =
          Scan.processTagAttributes(html, 0, null, Consts.ATTR_ALL, new Scan.AttrBudget(0), whole);
      assertTrue(wholeScan.complete, attrs);
      for (int split = 1; split < html.length(); split++) {
        Attributes chunked = new Attributes();
        Scan.AttrBudget budget = new Scan.AttrBudget(0);
        Scan.TagScan first =
            Scan.processTagAttributes(
                html.substring(0, split), 0, null, Consts.ATTR_ALL, budget, chunked);
        if (first.complete) {
          assertEquals(wholeScan.newPosition, first.newPosition, attrs + " @ " + split);
          assertSamePairs(whole, chunked, attrs + " @ " + split);
          continue;
        }
        Scan.TagResume resume =
            Scan.resumeTagAttributes(
                html.substring(split), false, Consts.ATTR_ALL, first.carry, chunked);
        assertTrue(resume.complete, attrs + " @ " + split);
        assertEquals(
            wholeScan.newPosition - split, resume.newPosition, attrs + " @ " + split);
        assertSamePairs(whole, chunked, attrs + " @ " + split);
      }
    }
  }

  private static void assertSamePairs(Attributes expected, Attributes actual, String message) {
    StringBuilder left = new StringBuilder();
    for (Attr attr : expected) {
      left.append(attr.name()).append('=').append(attr.value()).append(';');
    }
    StringBuilder right = new StringBuilder();
    for (Attr attr : actual) {
      right.append(attr.name()).append('=').append(attr.value()).append(';');
    }
    assertEquals(left.toString(), right.toString(), message);
  }

  @Test
  void commentTerminators() {
    assertEquals(5, Scan.processCommentOrDoctype("<!-->", 0).newPosition);
    assertEquals(6, Scan.processCommentOrDoctype("<!--->", 0).newPosition);
    assertEquals(8, Scan.processCommentOrDoctype("<!--x-->", 0).newPosition);
    assertEquals(9, Scan.processCommentOrDoctype("<!--x--!>", 0).newPosition);
    assertEquals(15, Scan.processCommentOrDoctype("<!doctype html>", 0).newPosition);
    assertFalse(Scan.processCommentOrDoctype("<!--x--", 0).complete);
  }

  @Test
  void discardedCommentScan() {
    Scan.DiscardedComment state = new Scan.DiscardedComment();
    state.state = Scan.DiscardedCommentState.COMMENT;
    assertEquals(4, Scan.discardedCommentEnd("a-->", state));
    state = new Scan.DiscardedComment();
    assertEquals(1, Scan.discardedCommentEnd(">", state));
    state = new Scan.DiscardedComment();
    assertEquals(-1, Scan.discardedCommentEnd("abc", state));
  }

  @Test
  void discardedCloseTagAndCdata() {
    Scan.DiscardedCloseTag close = new Scan.DiscardedCloseTag();
    assertEquals(2, Scan.discardedCloseTagEnd("a>", close));
    close = new Scan.DiscardedCloseTag();
    assertEquals(5, Scan.discardedCloseTagEnd("a=\"x>\">", close));
    int[] brackets = {0};
    assertEquals(4, Scan.discardedCdataEnd("x]]>", brackets));
    brackets[0] = 0;
    assertEquals(-1, Scan.discardedCdataEnd("x]]", brackets));
    assertEquals(1, Scan.discardedGt(">"));
    assertEquals(-1, Scan.discardedGt("abc"));
  }
}
