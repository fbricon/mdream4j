/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TailwindTest {

  private static final int SLOT_WEIGHT = 0;
  private static final int SLOT_EMPHASIS = 1;
  private static final int SLOT_DECORATION = 2;
  private static final int SLOT_DISPLAY_HIDDEN = 3;
  private static final int SLOT_POSITION_HIDDEN = 4;

  private static final List<String> WEIGHT_CLASSES = List.of(
      "font-bold", "font-semibold", "font-black", "font-extrabold", "font-medium", "bold");

  @Test
  void fontWeightMapsToBold() {
    assertResult("font-bold", "**", "**", false);
  }

  @Test
  void italicMapsToEmphasis() {
    assertResult("italic", "*", "*", false);
  }

  @Test
  void lineThroughMapsToStrikethrough() {
    assertResult("line-through", "~~", "~~", false);
  }

  @Test
  void decorationModifiersDoNotEnableStrikethrough() {
    for (String classes : List.of("underline-offset-4", "no-underline", "decoration-underline")) {
      Tailwind.Result result = Tailwind.processTailwindClasses(classes);
      assertNull(result.prefix(), classes);
      assertNull(result.suffix(), classes);
    }
  }

  @Test
  void decorationResetsFollowBreakpointPriority() {
    assertNull(Tailwind.processTailwindClasses("line-through no-underline").prefix());
    assertEquals("~~", Tailwind.processTailwindClasses("no-underline line-through").prefix());
    assertEquals("~~", Tailwind.processTailwindClasses("md:line-through no-underline").prefix());
    assertNull(Tailwind.processTailwindClasses("line-through md:no-underline").prefix());
  }

  @Test
  void hiddenDisplayFlagsHidden() {
    assertTrue(Tailwind.processTailwindClasses("hidden").hidden());
    assertTrue(Tailwind.processTailwindClasses("invisible").hidden());
    assertTrue(Tailwind.processTailwindClasses("absolute").hidden());
    // a later display class overrides hidden
    assertFalse(Tailwind.processTailwindClasses("hidden block").hidden());
    assertFalse(Tailwind.processTailwindClasses("absolute relative").hidden());
  }

  @Test
  void responsivePrefixSorting() {
    // base class (no breakpoint) wins over later breakpoint variants
    Tailwind.Result result = Tailwind.processTailwindClasses("md:block hidden");
    // "hidden" (weight 0) sorts before "md:block" (weight 2): block wins last
    assertFalse(result.hidden());

    assertTrue(Tailwind.processTailwindClasses("md:block md:hidden").hidden());
    assertFalse(Tailwind.processTailwindClasses("md:hidden md:block").hidden());
    assertTrue(Tailwind.processTailwindClasses("md:hidden block").hidden());
  }

  @Test
  void responsiveTiesPreserveSourceOrder() {
    StringBuilder classes = new StringBuilder();
    for (int index = 0; index < 35; index++) {
      if (index > 0) {
        classes.append(' ');
      }
      if (index == 3) {
        classes.append("sm:hidden");
      } else if (index == 33) {
        classes.append("sm:block");
      } else if (index % 2 == 0) {
        classes.append("noop");
      } else {
        classes.append("sm:noop");
      }
    }
    assertFalse(Tailwind.processTailwindClasses(classes).hidden());
  }

  @Test
  void combinedClassesNestDelimiters() {
    assertResult("font-bold italic", "***", "***", false);
  }

  @Test
  void noRecognisedClassesYieldsNone() {
    assertResult("text-lg p-4", null, null, false);
  }

  @Test
  void notItalicDoesNotEmphasise() {
    // `not-italic` must not match like `italic`
    assertResult("not-italic", null, null, false);
  }

  @Test
  void everyMappedClassMatchesExpectedMarkup() {
    String[] prefixes = {"", "sm:", "md:", "lg:", "xl:", "2xl:"};
    for (String prefix : prefixes) {
      assertResult(prefix + "italic", "*", "*", false);
      assertResult(prefix + "not-italic", null, null, false);

      assertResult(prefix + "font-bold", "**", "**", false);
      assertResult(prefix + "font-semibold", "**", "**", false);
      assertResult(prefix + "font-black", "**", "**", false);
      assertResult(prefix + "font-extrabold", "**", "**", false);
      assertResult(prefix + "font-medium", "**", "**", false);
      assertResult(prefix + "bold", "**", "**", false);

      assertResult(prefix + "line-through", "~~", "~~", false);
      assertResult(prefix + "no-underline", null, null, false);
      assertResult(prefix + "underline", null, null, false);

      assertResult(prefix + "hidden", null, null, true);
      assertResult(prefix + "block", null, null, false);
      assertResult(prefix + "flex", null, null, false);
      assertResult(prefix + "inline", null, null, false);

      assertResult(prefix + "absolute", null, null, true);
      assertResult(prefix + "fixed", null, null, true);
      assertResult(prefix + "sticky", null, null, true);
      assertResult(prefix + "static", null, null, false);
      assertResult(prefix + "relative", null, null, false);
    }
  }

  @Test
  void infixProbesMatchTheRustTable() {
    assertResult("font-light", null, null, false);
    assertResult("font-thin", null, null, false);
    assertResult("xfont-y", null, null, false);
    assertResult("sm:font-light", null, null, false);
    assertResult("invisible", null, null, true);
    assertResult("md:invisible-x", null, null, true);
    assertResult("headerlink", null, null, false);
    assertResult("", null, null, false);
    assertResult("sm:", null, null, false);
  }

  @Test
  void unknownBreakpointPrefixesAreNotStripped() {
    for (String prefix : List.of("3xl:", "s:", "XS:", "2md:", "smitalic")) {
      assertResult(prefix + "italic", null, null, false);
      assertResult(prefix + "hidden", null, null, false);
    }
  }

  @Test
  void breakpointPrecedenceIsPerSlot() {
    // lower breakpoint never overrides a higher one
    assertResult("lg:italic sm:not-italic", "*", "*", false);
    assertResult("sm:italic lg:not-italic", null, null, false);
    assertResult("md:hidden block", null, null, true);
    assertResult("hidden md:block", null, null, false);
    assertResult("2xl:hidden block", null, null, true);
    assertResult("hidden 2xl:block", null, null, false);

    // ties keep source order
    assertResult("md:italic md:not-italic", null, null, false);
    assertResult("md:not-italic md:italic", "*", "*", false);
    assertResult("sm:font-bold sm:font-light", null, null, false);
    assertResult("sm:font-light sm:font-bold", "**", "**", false);

    // slots are independent
    assertResult("font-bold md:not-italic", "**", "**", false);
    assertResult("md:italic 2xl:font-black", "***", "***", false);
  }

  @Test
  void delimitersNestInSlotOrder() {
    assertResult("font-bold line-through", "**~~", "~~**", false);
    assertResult("italic line-through", "*~~", "~~*", false);
    assertResult("font-bold italic line-through", "***~~", "~~***", false);
    // source order does not change the delimiter nesting order
    assertResult("line-through italic font-bold", "***~~", "~~***", false);
  }

  @Test
  void splitsOnRustWhitespace() {
    String separators = "\t\n\u000B\f\r \u0085\u00A0\u1680"
        + "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A"
        + "\u2028\u2029\u202F\u205F\u3000";
    for (int i = 0; i < separators.length(); i++) {
      char separator = separators.charAt(i);
      assertResult("font-bold" + separator + "italic", "***", "***", false);
    }

    // Java's Character.isWhitespace would wrongly split on these.
    for (char separator : new char[] {'\u001C', '\u001D', '\u001E', '\u001F', '\uFEFF'}) {
      assertResult("font-bold" + separator + "italic", null, null, false);
    }
  }

  @Test
  void trimsLeadingAndTrailingWhitespace() {
    assertResult("  font-bold  italic  ", "***", "***", false);
    assertResult("\n\tfont-bold\r\n", "**", "**", false);
  }

  @Test
  void rejectsNullClasses() {
    assertThrows(NullPointerException.class, () -> Tailwind.processTailwindClasses(null));
  }

  @Test
  void breakpointOfMatchesPrefixTable() {
    assertEquals(0, Tailwind.breakpointOf("italic"));
    assertEquals(1, Tailwind.breakpointOf("sm:italic"));
    assertEquals(2, Tailwind.breakpointOf("md:italic"));
    assertEquals(3, Tailwind.breakpointOf("lg:italic"));
    assertEquals(4, Tailwind.breakpointOf("xl:italic"));
    assertEquals(5, Tailwind.breakpointOf("2xl:italic"));
    assertEquals(0, Tailwind.breakpointOf("3xl:italic"));
    assertEquals(0, Tailwind.breakpointOf("s:italic"));
    assertEquals(0, Tailwind.breakpointOf("sm"));
    assertEquals(1, Tailwind.breakpointOf("sm:"));
  }

  @Test
  void resultValueSemantics() {
    Tailwind.Result a = Tailwind.processTailwindClasses("font-bold");
    Tailwind.Result b = Tailwind.processTailwindClasses("bold");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertTrue(a.toString().contains("prefix=**"));
  }

  /** Reference if-chain: the behaviour {@code processTailwindClasses} must match. */
  @Test
  void tableDispatchMatchesReference() {
    String[] words = {
      "italic",
      "not-italic",
      "font-bold",
      "font-semibold",
      "font-black",
      "font-extrabold",
      "font-medium",
      "bold",
      "font-light",
      "xfont-y",
      "line-through",
      "underline",
      "no-underline",
      "hidden",
      "invisible",
      "md:invisible-x",
      "block",
      "flex",
      "inline",
      "absolute",
      "fixed",
      "sticky",
      "static",
      "relative",
      "headerlink",
      "",
      "sm:",
      "2xl:bold",
      "xl:hidden",
    };
    String[] prefixes = {"", "sm:", "md:", "lg:", "xl:", "2xl:", "3xl:", "s:"};
    List<String> classes = new ArrayList<>();
    for (String prefix : prefixes) {
      for (String word : words) {
        classes.add(prefix + word);
      }
    }
    for (String a : classes) {
      reference(a).assertMatches(a);
      for (int index = 0; index < classes.size(); index += 7) {
        String pair = a + " " + classes.get(index);
        reference(pair).assertMatches(pair);
      }
    }
  }

  private static void assertResult(String classes, String prefix, String suffix, boolean hidden) {
    Tailwind.Result result = Tailwind.processTailwindClasses(classes);
    assertEquals(prefix, result.prefix(), classes);
    assertEquals(suffix, result.suffix(), classes);
    assertEquals(hidden, result.hidden(), classes);
  }

  private static Reference reference(String classesAttr) {
    int[] breakpoints = {-1, -1, -1, -1, -1};
    boolean[] values = new boolean[5];
    for (String cls : splitWhitespace(classesAttr)) {
      String base = cls;
      int breakpoint = 0;
      if (cls.startsWith("sm:")) {
        base = cls.substring(3);
        breakpoint = 1;
      } else if (cls.startsWith("md:")) {
        base = cls.substring(3);
        breakpoint = 2;
      } else if (cls.startsWith("lg:")) {
        base = cls.substring(3);
        breakpoint = 3;
      } else if (cls.startsWith("xl:")) {
        base = cls.substring(3);
        breakpoint = 4;
      } else if (cls.startsWith("2xl:")) {
        base = cls.substring(4);
        breakpoint = 5;
      }

      if (base.equals("italic")) {
        update(breakpoints, values, SLOT_EMPHASIS, breakpoint, true);
      } else if (base.equals("not-italic")) {
        update(breakpoints, values, SLOT_EMPHASIS, breakpoint, false);
      } else if (WEIGHT_CLASSES.contains(base)) {
        update(breakpoints, values, SLOT_WEIGHT, breakpoint, true);
      } else if (base.contains("font-")) {
        update(breakpoints, values, SLOT_WEIGHT, breakpoint, false);
      } else if (base.equals("line-through")) {
        update(breakpoints, values, SLOT_DECORATION, breakpoint, true);
      } else if (base.equals("no-underline") || base.equals("underline")) {
        update(breakpoints, values, SLOT_DECORATION, breakpoint, false);
      } else if (base.equals("hidden") || base.contains("invisible")) {
        update(breakpoints, values, SLOT_DISPLAY_HIDDEN, breakpoint, true);
      } else if (base.equals("block") || base.equals("flex") || base.equals("inline")) {
        update(breakpoints, values, SLOT_DISPLAY_HIDDEN, breakpoint, false);
      } else if (base.equals("absolute") || base.equals("fixed") || base.equals("sticky")) {
        update(breakpoints, values, SLOT_POSITION_HIDDEN, breakpoint, true);
      } else if (base.equals("static") || base.equals("relative")) {
        update(breakpoints, values, SLOT_POSITION_HIDDEN, breakpoint, false);
      }
    }

    StringBuilder prefix = new StringBuilder();
    StringBuilder suffix = new StringBuilder();
    if (values[SLOT_WEIGHT]) {
      prefix.append("**");
      suffix.append("**");
    }
    if (values[SLOT_EMPHASIS]) {
      prefix.append('*');
      suffix.insert(0, '*');
    }
    if (values[SLOT_DECORATION]) {
      prefix.append("~~");
      suffix.insert(0, "~~");
    }
    return new Reference(
        prefix.length() == 0 ? null : prefix.toString(),
        suffix.length() == 0 ? null : suffix.toString(),
        values[SLOT_DISPLAY_HIDDEN] || values[SLOT_POSITION_HIDDEN]);
  }

  private static void update(
      int[] breakpoints, boolean[] values, int slot, int breakpoint, boolean value) {
    if (breakpoints[slot] < 0 || breakpoint >= breakpoints[slot]) {
      breakpoints[slot] = breakpoint;
      values[slot] = value;
    }
  }

  private static List<String> splitWhitespace(String value) {
    List<String> tokens = new ArrayList<>();
    int index = 0;
    int length = value.length();
    while (index < length) {
      while (index < length && isAsciiWhitespace(value.charAt(index))) {
        index++;
      }
      int start = index;
      while (index < length && !isAsciiWhitespace(value.charAt(index))) {
        index++;
      }
      if (index > start) {
        tokens.add(value.substring(start, index));
      }
    }
    return tokens;
  }

  private static boolean isAsciiWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
  }

  private static final class Reference {

    private final String prefix;
    private final String suffix;
    private final boolean hidden;

    Reference(String prefix, String suffix, boolean hidden) {
      this.prefix = prefix;
      this.suffix = suffix;
      this.hidden = hidden;
    }

    void assertMatches(String classes) {
      Tailwind.Result actual = Tailwind.processTailwindClasses(classes);
      assertEquals(prefix, actual.prefix(), classes);
      assertEquals(suffix, actual.suffix(), classes);
      assertEquals(hidden, actual.hidden(), classes);
    }
  }
}
