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

class SelectorTest {

  @Test
  void classAndWordSelectorsUseAsciiWhitespace() {
    char[][] separators = {
      {' ', 1}, {'\t', 1}, {'\n', 1}, {'\r', 1}, {0x0C, 1}, {'\u00a0', 0}, {0x0B, 0}
    };
    for (char[] entry : separators) {
      ElementNode element = new ElementNode();
      element.attributes.insertKnown(
          Consts.ATTR_CLASS, "btn" + entry[0] + "active" + entry[0] + "primary");
      boolean expected = entry[1] == 1;
      for (String selector : new String[] {".active", "[class~=\"active\"]"}) {
        assertEquals(
            expected,
            Selector.matchesSelector(element, Selector.parseSelector(selector)),
            selector + " with separator " + Integer.toHexString(entry[0]));
      }
    }
  }

  @Test
  void parsesTagClassId() {
    assertEquals(ParsedSelector.Kind.TAG, Selector.parseSelector("div").kind);
    assertEquals("div", Selector.parseSelector("div").text);
    assertEquals(ParsedSelector.Kind.CLASS, Selector.parseSelector(".card").kind);
    assertEquals("card", Selector.parseSelector(".card").text);
    assertEquals(ParsedSelector.Kind.ID, Selector.parseSelector("#main").kind);
    assertEquals("main", Selector.parseSelector("#main").text);
  }

  @Test
  void parsesBareAttribute() {
    ParsedSelector selector = Selector.parseSelector("[data-x]");
    assertEquals(ParsedSelector.Kind.ATTRIBUTE, selector.kind);
    assertEquals("data-x", selector.name);
    assertNull(selector.operator);
    assertNull(selector.value);
  }

  @Test
  void parsesAttributeOperators() {
    String[] operators = {"=", "^=", "$=", "*=", "~=", "|="};
    for (String op : operators) {
      ParsedSelector selector = Selector.parseSelector("[a" + op + "b]");
      assertEquals("a", selector.name, op);
      assertEquals(op, selector.operator, op);
      assertEquals("b", selector.value, op);
    }
  }

  @Test
  void emptyOperandsNeverMatch() {
    for (String op : new String[] {"^=", "$=", "*=", "~="}) {
      ParsedSelector selector = Selector.parseSelector("[data-value" + op + "\"\"]");
      for (String value : new String[] {"", "real"}) {
        ElementNode element = new ElementNode();
        element.attributes.insertCustom("data-value", value);
        assertFalse(Selector.matchesSelector(element, selector), op + " " + value);
      }
    }
  }

  @Test
  void stripsQuotesFromAttributeValue() {
    assertEquals("hello", Selector.parseSelector("[alt=\"hello\"]").value);
  }

  @Test
  void preservesSingleQuoteAttributeValues() {
    for (char quote : new char[] {'"', '\''}) {
      ParsedSelector selector = Selector.parseSelector("[a" + quote + "=" + quote + "]");
      assertEquals("a" + quote, selector.name);
      assertEquals(String.valueOf(quote), selector.value);
    }
  }

  @Test
  void trimsOnlyCssWhitespaceAroundAttributeValues() {
    char[] separators = {' ', '\t', '\n', '\r', 0x0C, '\u00a0', 0x0B};
    for (char separator : separators) {
      String value = separator + "value" + separator;
      ParsedSelector selector = Selector.parseSelector("[data=" + value + "]");
      String expected =
          (separator == '\u00a0' || separator == 0x0B) ? value : "value";
      assertEquals(expected, selector.value, Integer.toHexString(separator));
    }
  }

  @Test
  void parsesCompoundSelector() {
    ParsedSelector selector = Selector.parseSelector("div.card[data-x]");
    assertEquals(ParsedSelector.Kind.COMPOUND, selector.kind);
    assertEquals(3, selector.parts.length);
  }

  @Test
  void preservesQuotedAndEscapedAttributeDelimiters() {
    String[][] cases = {
      {"a[href=\"/docs/[name].html#section\"].active", "/docs/[name].html#section"},
      {"a[href='/docs/[name].html#section'].active", "/docs/[name].html#section"},
      {"a[href=\"a\\\"]b.c#d\"].active", "a\\\"]b.c#d"},
      {"a[href='a\\']b.c#d'].active", "a\\']b.c#d"},
      {"a[href=a\\]b.c#d].active", "a\\]b.c#d"},
      {"a[href=\"a\\\\\"].active", "a\\\\"},
    };
    for (String[] testCase : cases) {
      ParsedSelector selector = Selector.parseSelector(testCase[0]);
      assertEquals(ParsedSelector.Kind.COMPOUND, selector.kind, testCase[0]);
      assertEquals(3, selector.parts.length, testCase[0]);
      assertEquals("a", selector.parts[0].text);
      assertEquals("href", selector.parts[1].name);
      assertEquals("=", selector.parts[1].operator);
      assertEquals(testCase[1], selector.parts[1].value, testCase[0]);
      assertEquals("active", selector.parts[2].text);
    }
  }

  @Test
  void preservesEscapesInSelectorLists() {
    String[][] cases = {
      {"[data=\"a,b\"]", "a,b"},
      {"[data=\"a]b\"]", "a]b"},
      {"[data=a\\]b\\,c]", "a\\]b\\,c"},
      {"[data=a\\]b,c]", "a\\]b,c"},
      {"[data=a\\\"b,c]", "a\\\"b,c"},
      {"[data=a\\'b,c]", "a\\'b,c"},
      {"[data=\"a\\\"]b,c\"]", "a\\\"]b,c"},
      {"[data='a\\']b,c']", "a\\']b,c"},
      {"[data=a\\\\]", "a\\\\"},
      {"[data=ação\\\\\\]b,c]", "ação\\\\\\]b,c"},
      {"[data=a\\çb]", "a\\çb"},
      {"[data=a\\🦀b]", "a\\🦀b"},
    };
    for (String[] testCase : cases) {
      ParsedSelector single = Selector.parseSelectorList(testCase[0]);
      assertEquals(ParsedSelector.Kind.ATTRIBUTE, single.kind, testCase[0]);
      assertEquals("data", single.name);
      assertEquals(testCase[1], single.value, testCase[0]);

      ParsedSelector list = Selector.parseSelectorList(testCase[0] + ".active, span");
      assertEquals(ParsedSelector.Kind.LIST, list.kind, testCase[0]);
      assertEquals(2, list.parts.length, testCase[0]);
      ParsedSelector compound = list.parts[0];
      assertEquals(ParsedSelector.Kind.COMPOUND, compound.kind, testCase[0]);
      assertEquals(2, compound.parts.length, testCase[0]);
      assertEquals(testCase[1], compound.parts[0].value, testCase[0]);
      assertEquals("active", compound.parts[1].text);
      assertEquals("span", list.parts[1].text);
    }
  }

  @Test
  void unterminatedAttributePartsStayLiteral() {
    for (String attribute : new String[] {"[data=", "[data=\"a,b", "[data=ação\\"}) {
      ParsedSelector selector = Selector.parseSelector("p" + attribute);
      assertEquals(ParsedSelector.Kind.COMPOUND, selector.kind, attribute);
      assertEquals(2, selector.parts.length, attribute);
      assertEquals("p", selector.parts[0].text);
      assertEquals(attribute, selector.parts[1].text);

      ParsedSelector list = Selector.parseSelectorList("span, p" + attribute + ", a");
      assertEquals(ParsedSelector.Kind.LIST, list.kind, attribute);
      assertEquals(2, list.parts.length, attribute);
      assertEquals("span", list.parts[0].text);
      assertEquals(attribute + ", a", list.parts[1].parts[1].text);
    }
  }

  @Test
  void emptySelectorIsNotAMatchAll() {
    for (String sel : new String[] {"", "   "}) {
      ParsedSelector selector = Selector.parseSelector(sel);
      assertEquals(ParsedSelector.Kind.TAG, selector.kind);
      assertTrue(selector.text.isEmpty());
    }
  }

  @Test
  void matchesAttributeOperators() {
    ElementNode element = new ElementNode();
    element.attributes.insertCustom("data-code", "prefix-value-suffix");
    assertTrue(match("[data-code]", element));
    assertTrue(match("[data-code=prefix-value-suffix]", element));
    assertTrue(match("[data-code^=prefix]", element));
    assertTrue(match("[data-code$=suffix]", element));
    assertTrue(match("[data-code*=value]", element));
    assertFalse(match("[data-code*=absent]", element));
    assertFalse(match("[data-code|=other]", element));

    element.attributes.insertCustom("lang", "en-US");
    assertTrue(match("[lang|=en]", element));
    assertTrue(match("[lang*=US]", element));
  }

  private static boolean match(String selector, ElementNode element) {
    return Selector.matchesSelector(element, Selector.parseSelector(selector));
  }
}
