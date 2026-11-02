/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.util.ArrayList;
import java.util.List;

/** CSS selector parsing and element matching. */
final class Selector {

  private Selector() {}

  private static int attributeEnd(String selector, int open) {
    int index = open + 1;
    char quote = 0;
    int len = selector.length();
    while (index < len) {
      char c = selector.charAt(index);
      if (c == '\\') {
        index += 2;
        continue;
      }
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == ']') {
        return index;
      }
      index++;
    }
    return -1;
  }

  /** Parses a comma-separated selector list while preserving attribute values. */
  static ParsedSelector parseSelectorList(String selector) {
    String trimmed = Chars.trimAscii(selector);
    if (trimmed.isEmpty()) {
      return parseSelector(trimmed);
    }

    List<ParsedSelector> selectors = new ArrayList<>();
    int start = 0;
    int index = 0;
    int len = trimmed.length();

    while (index < len) {
      char c = trimmed.charAt(index);
      if (c == '[') {
        int end = attributeEnd(trimmed, index);
        if (end < 0) {
          break;
        }
        index = end;
      } else if (c == ',') {
        String part = Chars.trimAscii(trimmed.substring(start, index));
        if (part.isEmpty()) {
          return ParsedSelector.tag(trimmed);
        }
        selectors.add(parseSelector(part));
        start = index + 1;
      }
      index++;
    }

    if (selectors.isEmpty()) {
      return parseSelector(trimmed);
    }

    String last = Chars.trimAscii(trimmed.substring(start));
    if (last.isEmpty()) {
      return ParsedSelector.tag(trimmed);
    }
    selectors.add(parseSelector(last));
    return ParsedSelector.list(selectors.toArray(new ParsedSelector[0]));
  }

  static ParsedSelector parseSelector(String selector) {
    String trimmed = Chars.trimAscii(selector);
    if (trimmed.isEmpty()) {
      return ParsedSelector.tag("");
    }
    List<ParsedSelector> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int index = 0;
    int len = trimmed.length();

    while (index < len) {
      char ch = trimmed.charAt(index);
      if (ch == '[') {
        if (current.length() > 0) {
          parts.add(parseSimpleSelector(current.toString()));
          current.setLength(0);
        }
        int end = attributeEnd(trimmed, index);
        if (end < 0) {
          current.append(trimmed, index, len);
          break;
        }
        parts.add(parseAttrSelector(trimmed.substring(index, end + 1)));
        index = end + 1;
        continue;
      }
      index++;
      if (ch == ']') {
        continue;
      }
      if ((ch == '.' || ch == '#') && current.length() > 0) {
        parts.add(parseSimpleSelector(current.toString()));
        current.setLength(0);
      }
      current.append(ch);
    }
    if (current.length() > 0) {
      parts.add(parseSimpleSelector(current.toString()));
    }

    if (parts.isEmpty()) {
      return ParsedSelector.tag("");
    }
    if (parts.size() == 1) {
      return parts.get(0);
    }
    return ParsedSelector.compound(parts.toArray(new ParsedSelector[0]));
  }

  private static ParsedSelector parseSimpleSelector(String s) {
    if (s.startsWith(".")) {
      return ParsedSelector.clazz(s.substring(1));
    }
    if (s.startsWith("#")) {
      return ParsedSelector.id(s.substring(1));
    }
    return ParsedSelector.tag(s);
  }

  private static ParsedSelector parseAttrSelector(String s) {
    if (s.length() < 2) {
      return ParsedSelector.tag(s);
    }
    String inner = Chars.trimAscii(s.substring(1, s.length() - 1));
    int eqIndex = inner.indexOf('=');
    if (eqIndex >= 0) {
      int opStart =
          eqIndex > 0 && isOperatorChar(inner.charAt(eqIndex - 1)) ? eqIndex - 1 : eqIndex;
      String val = Chars.trimAscii(inner.substring(eqIndex + 1));
      if (val.length() >= 2
          && (val.charAt(0) == '"' || val.charAt(0) == '\'')
          && val.charAt(0) == val.charAt(val.length() - 1)) {
        val = val.substring(1, val.length() - 1);
      }
      return ParsedSelector.attribute(
          Chars.trimAscii(inner.substring(0, opStart)), inner.substring(opStart, eqIndex + 1), val);
    }
    return ParsedSelector.attribute(inner, null, null);
  }

  private static boolean isOperatorChar(char c) {
    return c == '^' || c == '$' || c == '*' || c == '~' || c == '|';
  }

  static boolean matchesSelector(ElementNode tag, ParsedSelector selector) {
    switch (selector.kind) {
      case TAG:
        return tag.name().equals(selector.text);
      case CLASS:
        {
          String classes = tag.attributes.get("class");
          if (classes == null) {
            return false;
          }
          for (String cls : splitAsciiWhitespace(classes)) {
            if (cls.equals(selector.text)) {
              return true;
            }
          }
          return false;
        }
      case ID:
        {
          String id = tag.attributes.get("id");
          return id != null && id.equals(selector.text);
        }
      case ATTRIBUTE:
        {
          String attrVal = tag.attributes.get(selector.name);
          if (attrVal == null) {
            return false;
          }
          if (selector.operator == null || selector.value == null) {
            return true;
          }
          String v = selector.value;
          switch (selector.operator) {
            case "=":
              return attrVal.equals(v);
            case "^=":
              return !v.isEmpty() && attrVal.startsWith(v);
            case "$=":
              return !v.isEmpty() && attrVal.endsWith(v);
            case "*=":
              return !v.isEmpty() && attrVal.contains(v);
            case "~=":
              if (v.isEmpty()) {
                return false;
              }
              for (String word : splitAsciiWhitespace(attrVal)) {
                if (word.equals(v)) {
                  return true;
                }
              }
              return false;
            case "|=":
              return attrVal.equals(v) || attrVal.startsWith(v + "-");
            default:
              return false;
          }
        }
      case COMPOUND:
        for (ParsedSelector part : selector.parts) {
          if (!matchesSelector(tag, part)) {
            return false;
          }
        }
        return true;
      case LIST:
        for (ParsedSelector part : selector.parts) {
          if (matchesSelector(tag, part)) {
            return true;
          }
        }
        return false;
      default:
        return false;
    }
  }

  private static List<String> splitAsciiWhitespace(String value) {
    List<String> words = new ArrayList<>();
    int index = 0;
    int len = value.length();
    while (index < len) {
      while (index < len && Chars.isAsciiWhitespace(value.charAt(index))) {
        index++;
      }
      int start = index;
      while (index < len && !Chars.isAsciiWhitespace(value.charAt(index))) {
        index++;
      }
      if (index > start) {
        words.add(value.substring(start, index));
      }
    }
    return words;
  }
}
