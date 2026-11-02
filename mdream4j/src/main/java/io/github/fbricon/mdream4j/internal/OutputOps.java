/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.TagOverride;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown output: tag enter/exit emission, buffer writing, spacing.
 *
 * <p>Mirrors {@code crates/core/src/convert/output.rs}. The upstream module is split across
 * several passes; this port keeps the same order and naming so a diff stays readable. Byte offsets
 * become Java {@code char} indices: every structural marker is ASCII, and the port never emits a
 * non-BMP code point from generated markup.
 */
final class OutputOps {

  private OutputOps() {}

  // === Constants ===

  private static final int ESCAPE_HEADROOM = 8;

  private static final int DESTINATION_ESCAPE_BIT = 1;
  private static final int TITLE_ESCAPE_BIT = 2;
  private static final int IMAGE_DESCRIPTION_ESCAPE_BIT = 4;
  private static final int DESTINATION_NEEDS_ANGLE_BIT = 8;

  /** Escape-set bits for link and image writers, one byte per character. */
  private static final int[] ESCAPE_CLASS = buildEscapeClass();

  /** Tags the simple element emitters handle, indexed by tag id. */
  private static final boolean[] SIMPLE_EMIT_TAG = buildSimpleEmitTag();

  private static final int MAX_BORROWED_INDENT = 62;
  private static final int ORDERED_MARKER_CAPACITY = 6;

  // === Small helpers ===

  private static int[] buildEscapeClass() {
    int[] table = new int[256];
    // DESTINATION_ESCAPES: '<', '>', '\\'
    for (int c : new int[] {'<', '>', '\\'}) {
      table[c] |= DESTINATION_ESCAPE_BIT;
    }
    // TITLE_ESCAPES: '"', '\\'
    for (int c : new int[] {'"', '\\'}) {
      table[c] |= TITLE_ESCAPE_BIT;
    }
    // IMAGE_DESCRIPTION_ESCAPES: '&', '*', '<', '[', '\\', ']', '_', '`', '~'
    for (int c : new int[] {'&', '*', '<', '[', '\\', ']', '_', '`', '~'}) {
      table[c] |= IMAGE_DESCRIPTION_ESCAPE_BIT;
    }
    // DESTINATION_NEEDS_ANGLE: tab, LF, FF, CR, space, '(', ')', '<', '>', '\\'
    for (int c : new int[] {'\t', '\n', 0x0C, '\r', ' ', '(', ')', '<', '>', '\\'}) {
      table[c] |= DESTINATION_NEEDS_ANGLE_BIT;
    }
    return table;
  }

  private static boolean[] buildSimpleEmitTag() {
    boolean[] table = new boolean[Consts.MAX_TAG_ID];
    Arrays.fill(table, true);
    int[] excluded = {
      Consts.TAG_A,
      Consts.TAG_LI,
      Consts.TAG_PRE,
      Consts.TAG_CODE,
      Consts.TAG_TABLE,
      Consts.TAG_TR,
      Consts.TAG_TH,
      Consts.TAG_TD,
      Consts.TAG_IMG,
      Consts.TAG_BR,
      Consts.TAG_HR,
      Consts.TAG_BLOCKQUOTE,
      Consts.TAG_FIGCAPTION,
      Consts.TAG_H1,
      Consts.TAG_H2,
      Consts.TAG_H3,
      Consts.TAG_H4,
      Consts.TAG_H5,
      Consts.TAG_H6,
      Consts.TAG_DETAILS,
      Consts.TAG_SUMMARY,
      Consts.TAG_ADDRESS,
      Consts.TAG_DL,
      Consts.TAG_DT,
      Consts.TAG_DD
    };
    for (int id : excluded) {
      table[id] = false;
    }
    return table;
  }

  private static String joinGenerated(String prefix, String text, String suffix) {
    return prefix + text + suffix;
  }

  private static void writeAsciiEscaped(StringBuilder output, String value, int escapes) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 256 && (ESCAPE_CLASS[c] & escapes) != 0) {
        output.append('\\');
      }
      output.append(c);
    }
  }

  private static void writeMarkdownDestination(StringBuilder output, String destination) {
    boolean needsAngle = false;
    for (int i = 0; i < destination.length(); i++) {
      char c = destination.charAt(i);
      if (c < 256 && (ESCAPE_CLASS[c] & DESTINATION_NEEDS_ANGLE_BIT) != 0) {
        needsAngle = true;
        break;
      }
    }
    if (!needsAngle) {
      output.append(destination);
      return;
    }
    output.append('<');
    writeAsciiEscaped(output, destination, DESTINATION_ESCAPE_BIT);
    output.append('>');
  }

  private static void writeMarkdownResource(
      StringBuilder output, String destination, String title) {
    output.append('(');
    if (title != null && !title.isEmpty()) {
      if (destination.isEmpty()) {
        output.append("<>");
      } else {
        writeMarkdownDestination(output, destination);
      }
      output.append(" \"");
      writeAsciiEscaped(output, title, TITLE_ESCAPE_BIT);
      output.append('"');
    } else {
      writeMarkdownDestination(output, destination);
    }
    output.append(')');
  }

  private static void writeImageDescription(StringBuilder output, String alt) {
    writeAsciiEscaped(output, alt, IMAGE_DESCRIPTION_ESCAPE_BIT);
  }

  private static boolean isEcmascriptWhitespace(char c) {
    return (c >= 0x0009 && c <= 0x000D)
        || c == 0x0020
        || c == 0x00A0
        || c == 0x1680
        || (c >= 0x2000 && c <= 0x200A)
        || c == 0x2028
        || c == 0x2029
        || c == 0x202F
        || c == 0x205F
        || c == 0x3000
        || c == 0xFEFF;
  }

  // === Language detection ===

  static boolean isUnsafeFenceInfoByte(char c) {
    return c < 0x20 || c == 0x7F || c == '`' || c == '~' || c == '"' || c == '\'' || c == '<'
        || c == '>' || c == '&';
  }

  private static String languageFromClass(String className) {
    if (className == null) {
      return "";
    }
    int start = 0;
    int len = className.length();
    while (start <= len) {
      int end = start;
      while (end < len) {
        char c = className.charAt(end);
        if (Chars.isAsciiWhitespace(c)) {
          break;
        }
        end++;
      }
      String part = className.substring(start, end);
      if (part.startsWith("language-")) {
        String lang = part.substring("language-".length());
        if (!lang.isEmpty() && !containsUnsafeFenceInfo(lang)) {
          return lang;
        }
      }
      if (end >= len) {
        break;
      }
      start = end + 1;
    }
    return "";
  }

  private static boolean containsUnsafeFenceInfo(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (isUnsafeFenceInfoByte(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static String languageFromDataAttribute(String value) {
    if (value == null) {
      return null;
    }
    int start = 0;
    int end = value.length();
    while (start < end && Scan.isWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && Scan.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    String trimmed = value.substring(start, end);
    if (trimmed.isEmpty()) {
      return null;
    }
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == ' ' || isUnsafeFenceInfoByte(c)) {
        return null;
      }
    }
    return trimmed;
  }

  static String getLanguageFromAttributes(ElementNode node) {
    String classLanguage = languageFromClass(node.attributes.getBit(Consts.ATTR_CLASS));
    if (!classLanguage.isEmpty()) {
      return classLanguage;
    }
    String language = languageFromDataAttribute(node.attributes.getBit(Consts.ATTR_DATA_LANGUAGE));
    if (language != null) {
      return language;
    }
    String lang = languageFromDataAttribute(node.attributes.getBit(Consts.ATTR_DATA_LANG));
    return lang == null ? "" : lang;
  }

  // === Fast path predicates ===

  private static boolean commonFastPathOk(ConvertState self) {
    return self.isMarkdown()
        && !self.hasTagOverrides
        && !self.preFencePending
        && self.captionBoundary == ConvertState.CAPTION_BOUNDARY_NONE
        && self.captionFrames.isEmpty();
  }

  static boolean elementFastPathOk(ConvertState self, int tagId) {
    return tagId >= 0 && tagId < SIMPLE_EMIT_TAG.length && SIMPLE_EMIT_TAG[tagId]
        && commonFastPathOk(self);
  }

  static boolean linkFastPathOk(ConvertState self, int tagId) {
    return tagId == Consts.TAG_A
        && commonFastPathOk(self)
        && self.maxNodeBytes == 0
        && !self.rawHtmlBlockOpen
        && self.depthMap[Consts.TAG_PRE] == 0;
  }

  // === Buffer primitives ===

  private static int lastOutputByte(ConvertState self) {
    int len = self.buffer.length();
    if (len > 0) {
      return self.buffer.charAt(len - 1);
    }
    if (self.hasFlushedTail()) {
      return self.flushedTail[1];
    }
    return -1;
  }

  private static int trailingNewLines(ConvertState self) {
    StringBuilder buffer = self.buffer;
    int len = buffer.length();
    char last;
    boolean tailKnown = self.hasFlushedTail();
    if (len > 0) {
      last = buffer.charAt(len - 1);
    } else if (tailKnown) {
      last = self.flushedTail[1];
    } else {
      last = 0;
    }
    if (last != '\n') {
      return 0;
    }
    char second;
    if (len > 1) {
      second = buffer.charAt(len - 2);
    } else if (len == 1 && tailKnown) {
      second = self.flushedTail[1];
    } else if (tailKnown) {
      second = self.flushedTail[0];
    } else {
      second = 0;
    }
    return 1 + (second == '\n' ? 1 : 0);
  }

  private static int trimFloor(ConvertState self) {
    int floor = 0;
    if (self.codeFence != null) {
      floor = Math.max(floor, self.codeFence.contentStart);
    }
    if (!self.codeSpans.isEmpty()) {
      floor = Math.max(floor, self.codeSpans.get(self.codeSpans.size() - 1).contentStart);
    }
    if (!self.blockquotes.isEmpty()) {
      floor = Math.max(floor, self.blockquotes.get(self.blockquotes.size() - 1).contentStart);
    }
    return floor;
  }

  private static void clampItemMarkerEnd(ConvertState self, int len) {
    if (self.emptyItemLen > len) {
      self.emptyItemLen = len;
    }
  }

  static void truncateBuffer(ConvertState self, int len) {
    self.buffer.setLength(Math.min(len, self.buffer.length()));
  }

  /**
   * Prepends the generated frontmatter to the Markdown output.
   *
   * <p>Mirrors {@code emit_frontmatter}: no open code span exists when the head closes, so the
   * content lands in the buffer untruncated and becomes the new trailing-content length.
   */
  static void emitFrontmatter(ConvertState self, String content) {
    if (self.isMarkdown() && !content.isEmpty()) {
      self.buffer.append(content);
      self.lastContentCacheLen = content.length();
    }
  }

  private static void trimTrailingSpaces(ConvertState self) {
    int floor = trimFloor(self);
    int len = self.buffer.length();
    if (len > floor) {
      int trimmed = floor;
      while (trimmed < len && self.buffer.charAt(len - 1 - (trimmed - floor)) == ' ') {
        trimmed++;
      }
      // Recompute directly: drop trailing spaces above the floor.
      int end = len;
      while (end > floor && self.buffer.charAt(end - 1) == ' ') {
        end--;
      }
      if (end < len) {
        self.lastContentCacheLen = Math.max(0, self.lastContentCacheLen - (len - end));
        clampItemMarkerEnd(self, end);
        truncateBuffer(self, end);
      }
    }
  }

  private static void trimTrailingBlanks(ConvertState self) {
    int floor = trimFloor(self);
    int end = self.buffer.length();
    int before = end;
    while (end > floor) {
      char c = self.buffer.charAt(end - 1);
      if (c != ' ' && c != '\t') {
        break;
      }
      end--;
    }
    if (end < before) {
      self.lastContentCacheLen = Math.max(0, self.lastContentCacheLen - (before - end));
      clampItemMarkerEnd(self, end);
      truncateBuffer(self, end);
    }
  }

  private static int trailingSpaces(StringBuilder buffer) {
    int count = 0;
    for (int i = buffer.length() - 1; i >= 0 && buffer.charAt(i) == ' '; i--) {
      count++;
    }
    return count;
  }

  private static boolean isMarkdownMarkerWhitespace(int b) {
    return b < 0 || b == ' ' || b == '\t' || b == '\n' || b == '\r';
  }

  private static boolean isDashLine(String text, int start) {
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        break;
      }
      if (c != '-' && c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }

  private static boolean isEqualsLine(String text, int start) {
    int i = start;
    while (i < text.length() && text.charAt(i) == '=') {
      i++;
    }
    for (; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        break;
      }
      if (c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }

  private static boolean isEntityReferenceAfterAmpersand(String text, int amp) {
    int index = amp + 1;
    if (index < text.length() && text.charAt(index) == '#') {
      index++;
      boolean hex = index < text.length() && (text.charAt(index) == 'x' || text.charAt(index) == 'X');
      if (hex) {
        index++;
      }
      int start = index;
      while (index < text.length()) {
        char c = text.charAt(index);
        if ((c >= '0' && c <= '9')
            || (hex && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))) {
          index++;
        } else {
          break;
        }
      }
      return index > start && index < text.length() && text.charAt(index) == ';';
    }
    int start = index;
    while (index < text.length()) {
      char c = text.charAt(index);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
        index++;
      } else {
        break;
      }
    }
    return index > start && index < text.length() && text.charAt(index) == ';';
  }

  private static boolean listMarkerLineStart(
      ConvertState self, StringBuilder bytes, int end, int maxSpaces) {
    if (end <= 0 || end > bytes.length()) {
      return false;
    }
    int index = end - 1;
    char marker = bytes.charAt(index);
    if (marker == '.' || marker == ')') {
      int digits = index;
      while (index > 0 && Character.isDigit(bytes.charAt(index - 1))) {
        index--;
      }
      if (index == digits || digits - index > 9) {
        return false;
      }
    } else if (marker != '-' && marker != '*' && marker != '+') {
      return false;
    }
    int spaces = 0;
    while (index > 0 && bytes.charAt(index - 1) == ' ') {
      boolean separator = index >= 2 && bytes.charAt(index - 2) == '\r';
      if (!separator) {
        if (spaces >= maxSpaces) {
          break;
        }
        spaces++;
      }
      index--;
    }
    if (index == 0) {
      return !self.hasFlushedTail()
          || self.flushedTail[1] == '\n'
          || self.flushedTail[1] == '\r';
    }
    char before = bytes.charAt(index - 1);
    return before == '\n' || before == '\r';
  }

  private static int pendingMarkerIndent(ConvertState self) {
    if (self.listIndentWidths.isEmpty()) {
      return 3;
    }
    int width = self.listIndentWidths.get(self.listIndentWidths.size() - 1);
    return Math.max(0, self.listIndent.length() - width);
  }

  /** Current leading-space count when a GFM block marker may start here, or -1. */
  private static int markdownLineIndent(ConvertState self) {
    StringBuilder bytes = self.buffer;
    int spaces = 0;
    int structuralIndent = self.listIndent.length();
    for (int offset = bytes.length() - 1; offset >= 0; offset--) {
      char c = bytes.charAt(offset);
      if (c == '\n' || c == '\r') {
        return Math.max(0, spaces - structuralIndent);
      }
      if (c != ' ' || spaces == structuralIndent + 3) {
        return listMarkerLineStart(self, bytes, offset + 1, 3) ? 0 : -1;
      }
      spaces++;
    }
    if (self.buffer.length() == 0 && self.hasStreamedOutput) {
      return (self.flushedTail[1] == '\n' || self.flushedTail[1] == '\r') ? 0 : -1;
    }
    return Math.max(0, spaces - structuralIndent);
  }

  private static boolean startsWithGfmBlockCandidate(ConvertState self, String text) {
    if (text.isEmpty()) {
      return false;
    }
    char first = text.charAt(0);
    if (!(first == ' '
        || first == '#'
        || first == '-'
        || first == '+'
        || first == '>'
        || first == '='
        || (first >= '0' && first <= '9'))) {
      return false;
    }
    int indent = markdownLineIndent(self);
    if (indent < 0) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == ' ' && indent < 3) {
        indent++;
        continue;
      }
      return c == '#'
          || c == '-'
          || c == '+'
          || c == '>'
          || c == '='
          || (c >= '0' && c <= '9');
    }
    return false;
  }

  private static boolean contextGfmHazard(
      String text, boolean inTable, boolean inLink, boolean inQuote) {
    if (!inTable && !inLink && !inQuote) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '|' && inTable) {
        return true;
      }
      if (c == ']' && inLink) {
        return true;
      }
      if (c == '>' && inQuote) {
        return true;
      }
    }
    return false;
  }

  /** Escape GFM syntax; returns the escaped text, or {@code null} when unchanged. */
  private static String escapeGfmTextInto(ConvertState self, String text) {
    boolean inTable = self.depthMap[Consts.TAG_TABLE] > 0;
    boolean inLink = self.depthMap[Consts.TAG_A] > 0;
    boolean inBlockquote = self.depthMap[Consts.TAG_BLOCKQUOTE] > 0;
    int lineIndent = markdownLineIndent(self);
    int orderedDigits = 0;
    boolean escaped = false;
    StringBuilder out = null;
    int copiedUntil = 0;
    int index = 0;
    int len = text.length();

    while (index < len) {
      char c = text.charAt(index);
      int flags = c < 256 ? ConvertState.GFM_BYTE_FLAGS[c] : 0;

      if ((flags & ConvertState.GFM_TEXT_ACTIVE_BIT) == 0) {
        while (index < len) {
          char n = text.charAt(index);
          int f = n < 256 ? ConvertState.GFM_BYTE_FLAGS[n] : 0;
          if ((f & ConvertState.GFM_TEXT_ACTIVE_BIT) != 0) {
            break;
          }
          index++;
        }
        lineIndent = -1;
        orderedDigits = 0;
        continue;
      }

      if (c == '\\'
          && index + 1 < len
          && text.charAt(index + 1) == '&'
          && isEntityReferenceAfterAmpersand(text, index + 1)) {
        lineIndent = -1;
        orderedDigits = 0;
        index += 2;
        continue;
      }

      if (inTable && (c == '\n' || c == '\r')) {
        if (out == null) {
          out = new StringBuilder(text.length() + ESCAPE_HEADROOM);
        }
        out.append(text, copiedUntil, index);
        out.append(c == '\n' ? "&#10;" : "&#13;");
        copiedUntil = index + 1;
        index++;
        escaped = true;
        continue;
      }

      if (c == '\r') {
        lineIndent = 0;
        orderedDigits = 0;
        index++;
        continue;
      }

      boolean shouldEscape =
          c == '\\'
              || c == '*'
              || c == '_'
              || c == '~'
              || c == '`'
              || c == '['
              || (c == ']' && inLink)
              || (c == '|' && inTable)
              || (c == '>' && inBlockquote)
              || (c == '<'
                  && index + 1 < len
                  && (Chars.isAsciiAlphabetic(text.charAt(index + 1))
                      || text.charAt(index + 1) == '!'
                      || text.charAt(index + 1) == '/'
                      || text.charAt(index + 1) == '?'));

      if (!shouldEscape && lineIndent >= 0) {
        if (c == '#') {
          int end = index + 1;
          while (end < len && text.charAt(end) == '#') {
            end++;
          }
          shouldEscape =
              end - index <= 6
                  && isMarkdownMarkerWhitespace(end < len ? text.charAt(end) : -1);
        } else if (c == '-' || c == '+') {
          shouldEscape =
              isMarkdownMarkerWhitespace(index + 1 < len ? text.charAt(index + 1) : -1)
                  || (c == '-' && isDashLine(text, index));
        } else if (c == '>') {
          shouldEscape = true;
        } else if (c == '=') {
          shouldEscape = isEqualsLine(text, index);
        }
      } else if (!shouldEscape && orderedDigits > 0 && (c == '.' || c == ')')) {
        shouldEscape =
            isMarkdownMarkerWhitespace(index + 1 < len ? text.charAt(index + 1) : -1);
      }

      if (shouldEscape) {
        if (out == null) {
          out = new StringBuilder(text.length() + ESCAPE_HEADROOM);
        }
        out.append(text, copiedUntil, index);
        out.append('\\').append(c);
        copiedUntil = index + 1;
        escaped = true;
      }

      if (c == '\n') {
        lineIndent = 0;
        orderedDigits = 0;
      } else if (lineIndent >= 0) {
        if (c == ' ' && lineIndent < 3) {
          lineIndent++;
        } else {
          orderedDigits = (c >= '0' && c <= '9') ? 1 : 0;
          lineIndent = -1;
        }
      } else if (orderedDigits > 0) {
        orderedDigits =
            ((c >= '0' && c <= '9') && orderedDigits < 9) ? orderedDigits + 1 : 0;
      }
      index++;
    }

    if (escaped) {
      out.append(text, copiedUntil, len);
      return out.toString();
    }
    return null;
  }

  private static boolean collapsesIntoSpace(String text, int lastChar) {
    return text.length() == 1
        && text.charAt(0) == ' '
        && (lastChar == ' ' || lastChar == '\n' || lastChar == '\t' || lastChar == '\r');
  }

  private static void pushTextUncapped(ConvertState self, String text, boolean addSpace) {
    if (addSpace) {
      self.buffer.append(' ');
      self.lastContentCacheLen = text.length() + 1;
    } else {
      self.lastContentCacheLen = text.length();
    }
    self.buffer.append(text);
  }

  /** Whether prose here may be hard-wrapped: not code, a table cell or a heading. */
  private static boolean canWrapHere(ConvertState self) {
    return self.depthMap[Consts.TAG_PRE] == 0
        && self.depthMap[Consts.TAG_CODE] == 0
        && !self.inTableCell()
        && !self.inHeading();
  }

  /** Character count of the current buffer line, including any block prefix already written. */
  private static int currentColumn(ConvertState self) {
    int lineStart = self.buffer.length();
    while (lineStart > 0 && self.buffer.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }
    int buffered = Character.codePointCount(self.buffer, lineStart, self.buffer.length());
    return lineStart == 0 ? self.bufferStartColumn + buffered : buffered;
  }

  /** A word that would turn into a setext underline if it ever started a line. */
  private static boolean isSetextMarkerRun(String word) {
    if (word.isEmpty()) {
      return false;
    }
    return word.charAt(0) == '-'
        ? isDashLine(word, 0)
        : word.charAt(0) == '=' && isEqualsLine(word, 0);
  }

  /**
   * Pushes prose, breaking on spaces so no line exceeds {@code wrapWidth}.
   *
   * <p>Words are never split, so an over-long token overflows instead. A break only replaces an
   * inter-word space, keeping words joined across inline boundaries intact.
   */
  private static void pushTextWrapped(
      ConvertState self,
      String text,
      int lastChar,
      boolean ownsLeadingSpace,
      boolean relocatedSpace) {
    int width = self.wrapWidth;
    boolean leadingSpace = text.startsWith(" ");
    boolean trailingSpace = text.endsWith(" ");
    boolean firstNeedsSpace =
        relocatedSpace
            || leadingSpace
            || (!ownsLeadingSpace && shouldAddSpacingBeforeText(self, lastChar, text));
    String prefix = continuationPrefix(self);
    int prefixLen = Character.codePointCount(prefix, 0, prefix.length());
    int bufferStart = self.buffer.length();
    int column = currentColumn(self);
    boolean first = true;

    int wordStart = 0;
    while (wordStart <= text.length()) {
      int wordEnd = text.indexOf(' ', wordStart);
      if (wordEnd < 0) {
        wordEnd = text.length();
      }
      String word = text.substring(wordStart, wordEnd);
      wordStart = wordEnd + 1;
      if (word.isEmpty()) {
        if (wordEnd == text.length()) {
          break;
        }
        continue;
      }
      int wordLen = Character.codePointCount(word, 0, word.length());
      boolean needSpace = first ? firstNeedsSpace : true;
      if (needSpace
          && column > prefixLen
          && column + 1 + wordLen > width
          && (self.plainText || !isSetextMarkerRun(word))) {
        self.buffer.append('\n');
        self.buffer.append(prefix);
        column = prefixLen;
      } else if (needSpace) {
        self.buffer.append(' ');
        column++;
      }
      self.buffer.append(word);
      column += wordLen;
      first = false;
      if (wordEnd == text.length()) {
        break;
      }
    }

    if (trailingSpace
        && self.buffer.length() > bufferStart
        && self.buffer.charAt(self.buffer.length() - 1) != ' '
        && self.buffer.charAt(self.buffer.length() - 1) != '\n') {
      self.buffer.append(' ');
    }
    self.lastContentCacheLen = self.buffer.length() - bufferStart;
  }

  /** Inline marker kind a tag pairs, or 0 when its output is not a matched marker. */
  private static int inlineMarkerType(int tagId) {
    switch (tagId) {
      case Consts.TAG_STRONG:
      case Consts.TAG_B:
      case Consts.TAG_DFN:
        return 1;
      case Consts.TAG_EM:
      case Consts.TAG_I:
        return 2;
      case Consts.TAG_DEL:
      case Consts.TAG_S:
      case Consts.TAG_STRIKE:
        return 3;
      case Consts.TAG_CITE:
        return 4;
      case Consts.TAG_KBD:
      case Consts.TAG_CODE:
      case Consts.TAG_SAMP:
      case Consts.TAG_VAR:
        return 5;
      case Consts.TAG_Q:
        return 6;
      default:
        return 0;
    }
  }

  /** Open the inline marker an enter wrote, or drop the markers its text closes. */
  private static void recordEnterMarker(
      ConvertState self, int markerTag, String output, int outputStart, boolean keepsMarkers) {
    int kind = inlineMarkerType(markerTag);
    if (kind != 0
        && output != null
        && !output.isEmpty()
        && self.buffer.length() > outputStart
        && self.buffer.toString().endsWith(output)) {
      ConvertState.OpenMarker marker = new ConvertState.OpenMarker();
      marker.outputStart = self.buffer.length() - output.length();
      marker.contentStart = self.buffer.length();
      marker.kind = kind;
      marker.startsAtHardBreak = self.hardBreakEnd == marker.outputStart;
      self.openMarkers.add(marker);
    } else if (!keepsMarkers
        && !self.openMarkers.isEmpty()
        && output != null
        && Chars.hasVisible(output)) {
      self.openMarkers.clear();
    }
  }

  private static void releaseMarkersOnExit(ConvertState self, boolean closesBlock, String output) {
    if (!self.openMarkers.isEmpty()
        && (closesBlock || (output != null && Chars.hasVisible(output)))) {
      self.openMarkers.clear();
    }
  }

  /** Drop an empty enter/exit marker pair; returns whether it did. */
  private static boolean dropEmptyMarkerPair(
      ConvertState self, int markerTag, String emitted, boolean isInline) {
    int kind = inlineMarkerType(markerTag);
    if (kind == 0 || emitted == null || emitted.isEmpty()) {
      return false;
    }
    if (self.openMarkers.isEmpty()) {
      return false;
    }
    ConvertState.OpenMarker marker = self.openMarkers.remove(self.openMarkers.size() - 1);
    if (marker.kind == kind
        && marker.contentStart <= self.buffer.length()
        && allWhitespaceFrom(self.buffer, marker.contentStart)) {
      truncateBuffer(self, marker.outputStart);
      if (marker.startsAtHardBreak) {
        self.hardBreakEnd = marker.outputStart;
      }
      self.lastContentCacheLen = 0;
      resetEmptyTentativeCaptionFrames(self);
      self.lastNodeIsInline = isInline;
      return true;
    }
    self.openMarkers.clear();
    return false;
  }

  private static boolean allWhitespaceFrom(StringBuilder buffer, int start) {
    return !Chars.hasVisible(buffer, Math.max(0, start), buffer.length());
  }

  /** Settle a pending empty-text link once visible text follows it. */
  private static void settleEmptyTextPending(
      ConvertState self, int outputStart, boolean keepsPending) {
    if (!self.link.emptyTextPending || keepsPending) {
      return;
    }
    if (outputStart <= self.buffer.length()) {
      String content = self.buffer.substring(outputStart);
      if (Chars.hasVisible(content)) {
        self.link.emptyTextPending = false;
      }
    }
  }

  private static void finishTextNode(
      ConvertState self, String text, boolean containsWhitespace, int depth, int index) {
    if (!self.openMarkers.isEmpty() || self.heldBreakBytes != 0) {
      if (Chars.hasVisible(text)) {
        self.openMarkers.clear();
        self.heldBreakBytes = 0;
      }
    }
    self.lastTextNodeContainsWhitespace = containsWhitespace;
    self.hasLastTextNode = true;
    self.lastTextNodeDepth = depth;
    self.lastTextNodeIndex = index;
    self.lastNodeIsInline = false;
  }

  // === Text emission ===

  static void emitText(
      ConvertState self, String text, boolean containsWhitespace, int depth, int index) {
    if (self.htmlFormat) {
      HtmlOutputOps.emitHtmlText(self, text);
      return;
    }
    boolean hazard = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 256
          && (ConvertState.GFM_BYTE_FLAGS[c]
                  & (ConvertState.GFM_HAZARD_BIT | ConvertState.GFM_NEWLINE_BIT))
              != 0) {
        hazard = true;
        break;
      }
    }
    self.textBufferHasInlineGfmHazard |= hazard;
    emitTextWithGeneratedMarkdown(
        self, text, containsWhitespace, depth, index, null, null);
  }

  static void emitTextWithGeneratedMarkdown(
      ConvertState self,
      String text,
      boolean containsWhitespace,
      int depth,
      int index,
      String generatedPrefix,
      String generatedSuffix) {
    if (self.htmlFormat) {
      HtmlOutputOps.emitHtmlText(self, text);
      return;
    }
    if (self.pendingRawHtmlBoundary) {
      finishRawHtmlBlock(self, text, true);
      if (!self.pendingRawHtmlBoundary) {
        int start = 0;
        while (start < text.length()) {
          char c = text.charAt(start);
          if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            start++;
          } else {
            break;
          }
        }
        text = text.substring(start);
      }
    }
    boolean hasInlineGfmHazard = self.textBufferHasInlineGfmHazard;
    self.textBufferHasInlineGfmHazard = false;
    if (text.isEmpty()) {
      return;
    }

    boolean captionActive =
        self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE
            || !self.captionFrames.isEmpty();
    boolean ownsLeadingSpace = false;
    if (captionActive) {
      ownsLeadingSpace =
          self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE;
      if (!ownsLeadingSpace) {
        for (ConvertState.CaptionFrame frame : self.captionFrames) {
          if (frame.state != ConvertState.CAPTION_COMMITTED) {
            ownsLeadingSpace = true;
            break;
          }
        }
      }
      if (ownsLeadingSpace && text.charAt(0) == ' ') {
        text = text.substring(1);
        if (text.isEmpty()) {
          return;
        }
      }
      boolean visible = Chars.hasVisible(text);
      if (!visible && generatedPrefix != null && Chars.hasVisible(generatedPrefix)) {
        visible = true;
      }
      if (!visible && generatedSuffix != null && Chars.hasVisible(generatedSuffix)) {
        visible = true;
      }
      if (ownsLeadingSpace && !visible) {
        return;
      }
      if (visible) {
        expandDeferredCaptionBreaks(self);
        prepareCaptionBoundary(self, true);
        materializeCaptionFrames(self, CAPTION_MATERIALIZE_COMMIT);
        self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
      }
    }

    if (self.inTableCell() && self.tableCurrentRowCells >= self.tableColumnCap) {
      self.truncated = true;
      return;
    }

    if (self.listRulePending) {
      if (!Chars.hasVisible(text)) {
        return;
      }
      flushListRule(self);
    }

    boolean relocatedSpace = false;
    if (self.pendingInlineWhitespace) {
      if (!Chars.hasVisible(text)) {
        return;
      }
      int last = lastOutputByte(self);
      char first = text.charAt(0);
      if (last >= 0
          && last != ' '
          && last != '\n'
          && last != '\t'
          && !Scan.isWhitespace(first)) {
        if (self.wrapWidth != 0 && canWrapHere(self)) {
          relocatedSpace = true;
        } else {
          self.buffer.append(' ');
        }
      }
      self.pendingInlineWhitespace = false;
    }

    boolean hasVisibleText = false;
    int start = 0;
    int end = text.length();
    while (start < end && Scan.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Scan.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    hasVisibleText = start < end;

    if (self.preFencePending && hasVisibleText) {
      flushPreFence(self);
    }
    if (self.preFencePending) {
      return;
    }

    if (self.plainText && self.depthMap[Consts.TAG_PRE] > 0 && self.buffer.length() == 0) {
      self.preserveLeadingWhitespace = true;
    }

    int lastChar = lastOutputByte(self);
    if (collapsesIntoSpace(text, lastChar)) {
      return;
    }

    // Indent code block content inside a list item.
    int liDepth = self.depthMap[Consts.TAG_LI];
    if (!self.plainText
        && self.depthMap[Consts.TAG_PRE] > 0
        && liDepth > 0
        && (text.indexOf('\n') >= 0 || lastChar == '\n')) {
      String indent = self.listIndent.toString();
      StringBuilder out = new StringBuilder(text.length() + indent.length() * 2);
      if (lastChar == '\n' && !text.isEmpty() && text.charAt(0) != '\n') {
        out.append(indent);
      }
      int prev = 0;
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          out.append(text, prev, i + 1);
          int next = i + 1;
          if (next < text.length() && text.charAt(next) != '\n') {
            out.append(indent);
          }
          prev = next;
        }
      }
      out.append(text, prev, text.length());
      text = out.toString();
    }

    if (!self.plainText && self.depthMap[Consts.TAG_PRE] > 0 && self.inTableCell()) {
      text = foldPreLinesToBr(text);
    }

    boolean insideRawHtmlBlock = self.inRawHtmlBlock();
    if (insideRawHtmlBlock) {
      trackRawHtmlMarkdownContext(self, self.buffer.length());
    } else {
      self.rawHtmlMarkdown = false;
      self.rawHtmlScannedTo = self.buffer.length();
    }
    if (!self.plainText && self.depthMap[Consts.TAG_PRE] == 0 && insideRawHtmlBlock) {
      text = escapeRawHtmlText(self, text);
    }

    boolean inTable = self.depthMap[Consts.TAG_TABLE] > 0;
    boolean inLink = self.depthMap[Consts.TAG_A] > 0;
    boolean inQuote = self.depthMap[Consts.TAG_BLOCKQUOTE] > 0;
    boolean contextHasGfmHazard =
        !insideRawHtmlBlock && contextGfmHazard(text, inTable, inLink, inQuote);
    boolean hazard =
        !self.plainText
            && self.depthMap[Consts.TAG_PRE] == 0
            && self.depthMap[Consts.TAG_CODE] == 0
            && (hasInlineGfmHazard
                || contextHasGfmHazard
                || startsWithGfmBlockCandidate(self, text));
    boolean rawHtmlEscapeAllowed =
        !insideRawHtmlBlock
            || (self.rawHtmlMarkdown && !lineOpensRawHtmlBlock(self));
    String escaped = hazard && rawHtmlEscapeAllowed ? escapeGfmTextInto(self, text) : null;
    if (escaped != null) {
      text = escaped;
    }

    if (generatedPrefix != null || generatedSuffix != null) {
      text =
          joinGenerated(
              generatedPrefix == null ? "" : generatedPrefix,
              text,
              generatedSuffix == null ? "" : generatedSuffix);
    }

    boolean ownsSpace =
        ownsLeadingSpace || (self.plainText && self.depthMap[Consts.TAG_PRE] > 0);
    if (self.wrapWidth != 0 && canWrapHere(self)) {
      pushTextWrapped(self, text, lastChar, ownsLeadingSpace, relocatedSpace);
    } else if (!ownsSpace && shouldAddSpacingBeforeText(self, lastChar, text)) {
      pushTextUncapped(self, " ", false);
      self.lastContentCacheLen += text.length();
      self.buffer.append(text);
    } else {
      pushTextUncapped(self, text, false);
    }

    finishTextNode(self, text, containsWhitespace, depth, index);
  }

  /**
   * Escapes text that lives inside an HTML block so decoded entities stay inert: markup bytes,
   * line breaks, table pipes, and (inside a raw anchor) brackets that would otherwise close a link.
   */
  private static String escapeRawHtmlText(ConvertState self, String value) {
    boolean inTable = self.depthMap[Consts.TAG_TABLE] > 0;
    boolean rawLink = self.link.rawHtmlOpen;
    StringBuilder output = null;
    int copiedUntil = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      String replacement = null;
      switch (c) {
        case '&':
          replacement = "&amp;";
          break;
        case '<':
          replacement = "&lt;";
          break;
        case '>':
          replacement = "&gt;";
          break;
        case '\n':
          replacement = "&#10;";
          break;
        case '\r':
          replacement = "&#13;";
          break;
        case '|':
          if (inTable) {
            replacement = "&#124;";
          }
          break;
        case '[':
          if (rawLink) {
            replacement = "&#91;";
          }
          break;
        case ']':
          if (rawLink) {
            replacement = "&#93;";
          }
          break;
        default:
          break;
      }
      if (replacement != null) {
        if (output == null) {
          output = new StringBuilder(value.length() + 8);
        }
        output.append(value, copiedUntil, i).append(replacement);
        copiedUntil = i + 1;
      }
    }
    if (output == null) {
      return value;
    }
    output.append(value, copiedUntil, value.length());
    return output.toString();
  }

  private static String foldPreLinesToBr(String value) {
    int start = 0;
    while (start < value.length()
        && (value.charAt(start) == '\n' || value.charAt(start) == '\r')) {
      start++;
    }
    int end = value.length();
    while (end > start
        && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
      end--;
    }
    StringBuilder out = new StringBuilder(end - start);
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      if (c == '\r') {
        out.append("<br>");
        // A CRLF pair folds to one break; the LF alone would add a second.
        if (i + 1 < end && value.charAt(i + 1) == '\n') {
          i++;
        }
      } else if (c == '\n') {
        out.append("<br>");
      } else if (c == '&') {
        out.append("&amp;");
      } else if (c == '<') {
        out.append("&lt;");
      } else if (c == '>') {
        out.append("&gt;");
      } else if (c == '|') {
        out.append("&#124;");
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  // === Spacing ===

  static boolean needsSpacing(ConvertState self, int lastByte, int firstByte) {
    if (lastByte == ' ' || lastByte == '\n' || lastByte == '\t') {
      return false;
    }
    if (firstByte == ' ' || firstByte == '\n' || firstByte == '\t') {
      return false;
    }
    if (lastByte == '|' && firstByte == '<' && self.buffer.length() > 0) {
      return true;
    }
    if (lastByte == '['
        || lastByte == '('
        || lastByte == '>'
        || lastByte == '*'
        || lastByte == '_'
        || lastByte == '`') {
      return false;
    }
    return firstByte != ']'
        && firstByte != ')'
        && firstByte != '<'
        && firstByte != '.'
        && firstByte != ','
        && firstByte != '!'
        && firstByte != '?'
        && firstByte != ':'
        && firstByte != ';'
        && firstByte != '*'
        && firstByte != '_'
        && firstByte != '`';
  }

  static boolean shouldAddSpacingBeforeText(ConvertState self, int lastByte, String text) {
    if (lastByte < 0) {
      return false;
    }
    if (lastByte == '\n' || lastByte == ' ' || lastByte == '\t' || lastByte == '['
        || lastByte == '>') {
      return false;
    }
    if (self.lastNodeIsInline) {
      return false;
    }
    if (text.isEmpty()) {
      return false;
    }
    char first = text.charAt(0);
    if (first == ' ') {
      return false;
    }
    return first != '.'
        && first != ','
        && first != '!'
        && first != '?'
        && first != ':'
        && first != ';'
        && first != '_'
        && first != '*'
        && first != '`'
        && first != ')'
        && first != ']';
  }

  private static boolean wrapperEmitsInlineMarkdown(ConvertState self, ElementNode node) {
    if (node.tagId == Consts.TAG_A) {
      return self.inRawHtmlBlock() || node.attributes.containsBit(Consts.ATTR_HREF);
    }
    int id = node.tagId;
    return id == Consts.TAG_STRONG
        || id == Consts.TAG_B
        || id == Consts.TAG_EM
        || id == Consts.TAG_I
        || id == Consts.TAG_DEL
        || id == Consts.TAG_S
        || id == Consts.TAG_STRIKE
        || id == Consts.TAG_INS
        || id == Consts.TAG_SUB
        || id == Consts.TAG_SUP
        || id == Consts.TAG_CODE
        || id == Consts.TAG_KBD
        || id == Consts.TAG_SAMP
        || id == Consts.TAG_VAR
        || id == Consts.TAG_MARK
        || id == Consts.TAG_U
        || id == Consts.TAG_CITE
        || id == Consts.TAG_DFN
        || id == Consts.TAG_Q;
  }

  private static boolean collapseWrapperEmitsInlineMarkdown(ConvertState self) {
    for (int i = self.stack.size() - 1; i >= 0; i--) {
      ElementNode node = self.stack.get(i);
      if (node.collapsesInnerWhiteSpace && !node.excludedFromMarkdown
          && node.tagId != Consts.TAG_SPAN) {
        return wrapperEmitsInlineMarkdown(self, node);
      }
    }
    return false;
  }

  private static boolean insideOpenInlineDelimiter(ConvertState self) {
    if (self.collapseNonSpanDepth == 0 && self.collapseSpanDepth == 0) {
      return false;
    }
    boolean rawHtml = self.inRawHtmlBlock();
    for (int i = self.stack.size() - 2; i >= 0; i--) {
      ElementNode node = self.stack.get(i);
      if (!node.collapsesInnerWhiteSpace || node.excludedFromMarkdown) {
        continue;
      }
      int id = node.tagId;
      if (id == Consts.TAG_INS
          || id == Consts.TAG_SUB
          || id == Consts.TAG_SUP
          || id == Consts.TAG_MARK
          || id == Consts.TAG_U) {
        continue;
      }
      if (rawHtml && id == Consts.TAG_A) {
        continue;
      }
      if (wrapperEmitsInlineMarkdown(self, node)) {
        return true;
      }
    }
    return false;
  }

  static int[] calculateNewLineConfig(
      ConvertState self, int tagId, int[] nodeSpacing, boolean isEnter) {
    if (nodeSpacing != null
        && nodeSpacing[0] == 0
        && nodeSpacing[1] == 0
        && tagId != Consts.TAG_PRE
        && tagId != Consts.TAG_LI) {
      return Consts.NO_SPACING;
    }
    if (self.plainText
        && tagId == Consts.TAG_PRE
        && (self.depthMap[Consts.TAG_LI] > 0 || self.depthMap[Consts.TAG_BLOCKQUOTE] > 0)) {
      return new int[] {1, 1};
    }
    if (tagId >= 0) {
      if ((tagId != Consts.TAG_LI && self.depthMap[Consts.TAG_LI] > 0)
          || (self.plainText
              && tagId != Consts.TAG_BLOCKQUOTE
              && self.depthMap[Consts.TAG_BLOCKQUOTE] > 0)) {
        return Consts.NO_SPACING;
      }
    } else if (self.depthMap[Consts.TAG_LI] > 0
        || self.depthMap[Consts.TAG_BLOCKQUOTE] > 0) {
      return Consts.NO_SPACING;
    }
    if (self.depthMap[Consts.TAG_LI] > 1
        && tagId == Consts.TAG_LI
        && isEnter
        && !self.plainText
        && !self.stack.isEmpty()
        && self.stack.get(self.stack.size() - 1).index == 0
        && self.stack.size() >= 2
        && self.stack.get(self.stack.size() - 2).tagId == Consts.TAG_OL
        && self.orderedItemNumber(0) != 1
        && !self.inTableCell()
        && !insideOpenInlineDelimiter(self)
        && (blockOpenPrefix(self, "").length() > 0
            || self.hardBreakEnd == self.buffer.length())) {
      return Consts.DEFAULT_BLOCK_SPACING;
    }

    boolean isHeading = tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6;
    boolean headingOwnsCollapse =
        isEnter && isHeading && self.collapseNonSpanDepth == 1 && !self.inTableCell();
    boolean headingExitInMarkerlessCollapse =
        !isEnter
            && isHeading
            && self.collapseNonSpanDepth == 1
            && !self.inTableCell()
            && !collapseWrapperEmitsInlineMarkdown(self);
    boolean countedSelf =
        isEnter
            && !self.stack.isEmpty()
            && self.stack.get(self.stack.size() - 1).collapsesInnerWhiteSpace;
    boolean isSpan = tagId == Consts.TAG_SPAN;
    int nonSpanDepth =
        Math.max(
            0,
            self.collapseNonSpanDepth - ((countedSelf && !isSpan && !isHeading) ? 1 : 0));
    int spanDepth =
        Math.max(0, self.collapseSpanDepth - ((countedSelf && isSpan) ? 1 : 0));
    if (nonSpanDepth > 0 && !headingOwnsCollapse && !headingExitInMarkerlessCollapse) {
      return Consts.NO_SPACING;
    }
    if (spanDepth > 0) {
      boolean isBlock =
          (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6)
              || tagId == Consts.TAG_P
              || tagId == Consts.TAG_DIV
              || tagId == Consts.TAG_LI;
      if (!isBlock) {
        return Consts.NO_SPACING;
      }
    }
    return nodeSpacing == null ? Consts.DEFAULT_BLOCK_SPACING : nodeSpacing;
  }

  // === Prefixes ===

  static boolean blockOpenNeedsBreak(ConvertState self) {
    StringBuilder bytes = self.buffer;
    int len = bytes.length();
    if (len == 0) {
      return false;
    }
    char last = bytes.charAt(len - 1);
    if (last == ' ') {
      int end = len - trailingSpaces(bytes);
      if (end == 0) {
        return false;
      }
      return bytes.charAt(end - 1) != '\n'
          && !listMarkerLineStart(self, bytes, end, pendingMarkerIndent(self));
    }
    return last != '\n';
  }

  static String blockOpenPrefix(ConvertState self, String prefix) {
    if (blockOpenNeedsBreak(self)) {
      return "\n\n" + prefix;
    }
    if (!prefix.isEmpty() && self.buffer.length() > 0
        && self.buffer.charAt(self.buffer.length() - 1) == '\n') {
      return prefix;
    }
    return "";
  }

  static String continuationPrefix(ConvertState self) {
    if (self.plainText
        || (self.depthMap[Consts.TAG_LI] == 0 && self.depthMap[Consts.TAG_BLOCKQUOTE] == 0)) {
      return "";
    }
    StringBuilder p = new StringBuilder();
    int liIndex = 0;
    for (int i = 0; i < self.stack.size(); i++) {
      ElementNode node = self.stack.get(i);
      if (node.tagId == Consts.TAG_BLOCKQUOTE && self.blockquotes.isEmpty()) {
        p.append("> ");
      } else if (node.tagId == Consts.TAG_LI) {
        int w =
            liIndex < self.listIndentWidths.size() ? self.listIndentWidths.get(liIndex) : 2;
        for (int k = 0; k < w; k++) {
          p.append(' ');
        }
        liIndex++;
      }
    }
    return p.toString();
  }

  private static boolean quoteAtStart(ConvertState self) {
    return self.hardBreakEnd != self.buffer.length()
        && !self.blockquotes.isEmpty()
        && self.blockquotes.get(self.blockquotes.size() - 1).contentStart
            == self.buffer.length();
  }

  private static void flushListRule(ConvertState self) {
    self.listRulePending = false;
    String prefix = continuationPrefix(self);
    self.buffer.append("\n\n").append(prefix);
    self.lastContentCacheLen = 2 + prefix.length();
  }

  // === Enter/exit output ===

  static String getEnterOutput(ConvertState self, ElementNode node) {
    if (self.plainText) {
      return getTextEnterOutput(self, node);
    }
    int tagId = node.tagId;
    if (tagId < 0) {
      return null;
    }
    if (self.depthMap[Consts.TAG_PRE] > 0 && suppressesFormattingInPre(tagId)) {
      return null;
    }
    switch (tagId) {
      case Consts.TAG_DETAILS:
        return "<details>";
      case Consts.TAG_SUMMARY:
        return "<summary>";
      case Consts.TAG_PRE:
        if (self.inTableCell()) {
          return "<pre>";
        }
        return null;
      case Consts.TAG_BR:
        if (self.inTableCell() || self.inHeading() || self.inRawHtmlBlock()) {
          return "<br>";
        }
        if (self.depthMap[Consts.TAG_PRE] > 0 || self.depthMap[Consts.TAG_CODE] > 0) {
          return "\n";
        }
        {
          String prefix = continuationPrefix(self);
          return prefix.isEmpty() ? "  \n" : "  \n" + prefix;
        }
      case Consts.TAG_H1:
      case Consts.TAG_H2:
      case Consts.TAG_H3:
      case Consts.TAG_H4:
      case Consts.TAG_H5:
      case Consts.TAG_H6:
        {
          int depth = tagId - Consts.TAG_H1;
          if (self.depthMap[Consts.TAG_A] > 0 || self.inTableCell()) {
            return H_OPEN[depth];
          }
          if (self.listIndent.length() > 0
              && lastOutputByte(self) == '\n'
              && !quoteAtStart(self)) {
            String prefix = continuationPrefix(self);
            return "\n" + prefix + HEADING_PREFIXES[depth];
          }
          return HEADING_PREFIXES[depth];
        }
      case Consts.TAG_HR:
        if (self.inTableCell()) {
          return "<hr>";
        }
        if (self.depthMap[Consts.TAG_LI] == 0 && self.depthMap[Consts.TAG_BLOCKQUOTE] == 0) {
          return Consts.MARKDOWN_HORIZONTAL_RULE;
        }
        {
          String prefix = continuationPrefix(self);
          if (prefix.isEmpty()) {
            return Consts.MARKDOWN_HORIZONTAL_RULE;
          }
          String open = blockOpenPrefix(self, prefix);
          return open.isEmpty()
              ? Consts.MARKDOWN_HORIZONTAL_RULE_ALT
              : open + Consts.MARKDOWN_HORIZONTAL_RULE;
        }
      case Consts.TAG_STRONG:
      case Consts.TAG_B:
        return self.depthMap[Consts.TAG_B] > 1 ? "" : Consts.MARKDOWN_STRONG;
      case Consts.TAG_EM:
      case Consts.TAG_I:
        return self.depthMap[Consts.TAG_I] > 1 ? "" : Consts.MARKDOWN_EMPHASIS;
      case Consts.TAG_DEL:
      case Consts.TAG_S:
      case Consts.TAG_STRIKE:
        return Consts.MARKDOWN_STRIKETHROUGH;
      case Consts.TAG_SUB:
        return "<sub>";
      case Consts.TAG_SUP:
        return "<sup>";
      case Consts.TAG_INS:
        return "<ins>";
      case Consts.TAG_P:
        if (self.depthMap[Consts.TAG_LI] > 0
            && !self.inTableCell()
            && lastOutputByte(self) >= 0
            && lastOutputByte(self) != ' '
            && lastOutputByte(self) != '\n') {
          return "\n\n" + self.listIndent;
        }
        return null;
      case Consts.TAG_BLOCKQUOTE:
        return (self.depthMap[Consts.TAG_LI] > 0
                && self.captionBoundary != ConvertState.CAPTION_BOUNDARY_PENDING)
            ? "\n"
            : null;
      case Consts.TAG_CAPTION:
        // A caption has no handler of its own, so inside a list item nothing
        // writes its content column: glued to preceding text it swallows the
        // table's first row, and at column 0 it takes the table out of the item.
        if (self.depthMap[Consts.TAG_LI] > 0 && !self.inTableCell()) {
          return blockOpenPrefix(self, self.listIndent.toString());
        }
        return null;
      case Consts.TAG_CODE:
        if (self.depthMap[Consts.TAG_PRE] > 0) {
          if (self.inTableCell()) {
            return "<code>";
          }
          if (self.preFenceOwnerDepth != 0) {
            return null;
          }
          String lang = getLanguageFromAttributes(node);
          if (lang.isEmpty()) {
            lang = self.preFenceLang == null ? "" : self.preFenceLang;
          }
          if (self.depthMap[Consts.TAG_LI] > 0) {
            String indent = self.listIndent.toString();
            String open = blockOpenPrefix(self, indent);
            return open + "```" + lang + "\n" + indent;
          }
          return lang.isEmpty() ? "```\n" : "```" + lang + "\n";
        }
        if (self.inRawHtmlBlock()) {
          return "<code>";
        }
        if (!self.codeSpans.isEmpty()) {
          return null;
        }
        if (self.depthMap[Consts.TAG_LI] > 0) {
          int last = lastOutputByte(self);
          if (last >= 0
              && last != ' '
              && last != '\n'
              && last != '\t'
              && last != '*'
              && last != '_'
              && last != '~'
              && last != '['
              && last != '>') {
            return " `";
          }
        }
        return Consts.MARKDOWN_INLINE_CODE;
      case Consts.TAG_UL:
        return self.inTableCell() ? "<ul>" : null;
      case Consts.TAG_OL:
        return self.inTableCell() ? "<ol>" : null;
      case Consts.TAG_LI:
        {
          if (self.inTableCell()) {
            return "<li>";
          }
          boolean ordered = false;
          if (self.stack.size() >= 2) {
            ordered = self.stack.get(self.stack.size() - 2).tagId == Consts.TAG_OL;
          }
          if (!ordered && self.listIndent.length() <= MAX_BORROWED_INDENT) {
            StringBuilder bullet = new StringBuilder(self.listIndent.length() + 2);
            for (int i = 0; i < self.listIndent.length(); i++) {
              bullet.append(' ');
            }
            bullet.append("- ");
            return bullet.toString();
          }
          StringBuilder s = new StringBuilder(self.listIndent.length() + ORDERED_MARKER_CAPACITY);
          s.append(self.listIndent);
          if (ordered) {
            s.append(self.orderedItemNumber(node.index)).append(". ");
          } else {
            s.append("- ");
          }
          return s.toString();
        }
      case Consts.TAG_A:
        if (self.inRawHtmlBlock()) {
          return HtmlOutputOps.htmlElementOutput(self, node, true, true);
        }
        return node.attributes.containsBit(Consts.ATTR_HREF) ? "[" : null;
      case Consts.TAG_IMG:
        {
          String alt = node.attributes.getBit(Consts.ATTR_ALT);
          if (alt == null) {
            alt = "";
          }
          String src = node.attributes.getBit(Consts.ATTR_SRC);
          if (src == null || Url.isDataUrl(src)) {
            src = "";
          }
          String resolved = Url.resolveUrl(src, self.origin, self.cleanUrls);
          String title = node.attributes.getBit(Consts.ATTR_TITLE);
          StringBuilder s = new StringBuilder();
          s.append("![");
          writeImageDescription(s, alt);
          s.append(']');
          writeMarkdownResource(s, resolved, title);
          return s.toString();
        }
      case Consts.TAG_TABLE:
        return self.inTableCell() ? "<table>" : null;
      case Consts.TAG_THEAD:
        return self.inTableCell() ? "<thead>" : null;
      case Consts.TAG_TR:
        if (self.inTableCell()) {
          return "<tr>";
        }
        if (self.tableRowOpenerPending) {
          return null;
        }
        return tableRowOpener(self, node.isInline);
      case Consts.TAG_TH:
      case Consts.TAG_TD:
        if (self.depthMap[Consts.TAG_TABLE] > 1) {
          return tagId == Consts.TAG_TH ? "<th>" : "<td>";
        }
        if (node.index == 0) {
          return "";
        }
        if (self.tableCurrentRowCells >= self.tableColumnCap) {
          return "";
        }
        if (self.tableHeaderCells > 0 && self.tableCurrentRowCells >= self.tableHeaderCells) {
          return " ";
        }
        return " | ";
      case Consts.TAG_CENTER:
        return self.depthMap[Consts.TAG_TABLE] > 1 ? "<center>" : null;
      case Consts.TAG_KBD:
      case Consts.TAG_SAMP:
      case Consts.TAG_VAR:
        return "`";
      case Consts.TAG_ABBR:
      case Consts.TAG_SMALL:
      case Consts.TAG_TIME:
      case Consts.TAG_BDO:
      case Consts.TAG_RUBY:
      case Consts.TAG_RT:
      case Consts.TAG_RP:
        return "";
      case Consts.TAG_MARK:
        return "<mark>";
      case Consts.TAG_Q:
        return "\"";
      case Consts.TAG_U:
        return "<u>";
      case Consts.TAG_CITE:
        return "*";
      case Consts.TAG_FIGCAPTION:
        return null;
      case Consts.TAG_DFN:
        return "**";
      case Consts.TAG_ADDRESS:
        return "<address>";
      case Consts.TAG_DL:
        return "<dl>";
      case Consts.TAG_DT:
        return "<dt>";
      case Consts.TAG_DD:
        return "<dd>";
      default:
        return null;
    }
  }

  static String getExitOutput(ConvertState self, ElementNode node, int cellSpan) {
    if (self.plainText) {
      return getTextExitOutput(node);
    }
    int tagId = node.tagId;
    if (tagId < 0) {
      return null;
    }
    if (self.depthMap[Consts.TAG_PRE] > 0 && suppressesFormattingInPre(tagId)) {
      return null;
    }
    switch (tagId) {
      case Consts.TAG_DETAILS:
        return self.inTableCell() ? "</details>" : "</details>\n\n";
      case Consts.TAG_SUMMARY:
        return self.inTableCell() ? "</summary>" : "</summary>\n\n";
      case Consts.TAG_H1:
      case Consts.TAG_H2:
      case Consts.TAG_H3:
      case Consts.TAG_H4:
      case Consts.TAG_H5:
      case Consts.TAG_H6:
        {
          int depth = tagId - Consts.TAG_H1;
          if (self.depthMap[Consts.TAG_A] > 0 || self.inTableCell()) {
            return H_CLOSE[depth];
          }
          return null;
        }
      case Consts.TAG_STRONG:
      case Consts.TAG_B:
        return self.depthMap[Consts.TAG_B] > 1 ? "" : Consts.MARKDOWN_STRONG;
      case Consts.TAG_EM:
      case Consts.TAG_I:
        return self.depthMap[Consts.TAG_I] > 1 ? "" : Consts.MARKDOWN_EMPHASIS;
      case Consts.TAG_DEL:
      case Consts.TAG_S:
      case Consts.TAG_STRIKE:
        return Consts.MARKDOWN_STRIKETHROUGH;
      case Consts.TAG_SUB:
        return "</sub>";
      case Consts.TAG_SUP:
        return "</sup>";
      case Consts.TAG_INS:
        return "</ins>";
      case Consts.TAG_A:
        if (self.inRawHtmlBlock()) {
          return HtmlOutputOps.htmlElementOutput(self, node, false, true);
        }
        return null;
      case Consts.TAG_CODE:
        if (self.depthMap[Consts.TAG_PRE] > 0) {
          return self.inTableCell() ? "</code>" : null;
        }
        if (self.inRawHtmlBlock()) {
          return "</code>";
        }
        return Consts.MARKDOWN_INLINE_CODE;
      case Consts.TAG_PRE:
        if (self.inTableCell()) {
          return "</pre>";
        }
        if (self.preFenceOwnerDepth != self.depthMap[Consts.TAG_PRE]) {
          return null;
        }
        if (self.depthMap[Consts.TAG_LI] > 0) {
          String indent = self.listIndent.toString();
          return "\n" + indent + "```\n\n" + indent;
        }
        return "\n```";
      case Consts.TAG_UL:
        return self.inTableCell() ? "</ul>" : null;
      case Consts.TAG_OL:
        return self.inTableCell() ? "</ol>" : null;
      case Consts.TAG_LI:
        return self.inTableCell() ? "</li>" : null;
      case Consts.TAG_TR:
        return (self.inTableCell() || self.depthMap[Consts.TAG_TABLE] > 1)
            ? "</tr>"
            : " |";
      case Consts.TAG_TABLE:
        return self.inTableCell() ? "</table>" : null;
      case Consts.TAG_THEAD:
        return self.inTableCell() ? "</thead>" : null;
      case Consts.TAG_TH:
      case Consts.TAG_TD:
        if (self.depthMap[Consts.TAG_TABLE] > 1) {
          return tagId == Consts.TAG_TH ? "</th>" : "</td>";
        }
        return spanFiller(cellSpan);
      case Consts.TAG_CENTER:
        return self.depthMap[Consts.TAG_TABLE] > 1 ? "</center>" : null;
      case Consts.TAG_KBD:
      case Consts.TAG_SAMP:
      case Consts.TAG_VAR:
        return "`";
      case Consts.TAG_ABBR:
      case Consts.TAG_SMALL:
      case Consts.TAG_TIME:
      case Consts.TAG_BDO:
      case Consts.TAG_RUBY:
      case Consts.TAG_RT:
      case Consts.TAG_RP:
        return "";
      case Consts.TAG_MARK:
        return "</mark>";
      case Consts.TAG_Q:
        return "\"";
      case Consts.TAG_U:
        return "</u>";
      case Consts.TAG_CITE:
        return "*";
      case Consts.TAG_FIGCAPTION:
        return Consts.MARKDOWN_EMPHASIS;
      case Consts.TAG_DFN:
        return "**";
      case Consts.TAG_ADDRESS:
        return "</address>";
      case Consts.TAG_DL:
        return "</dl>";
      case Consts.TAG_DT:
        return "</dt>";
      case Consts.TAG_DD:
        return "</dd>";
      default:
        return null;
    }
  }

  private static String getTextEnterOutput(ConvertState self, ElementNode node) {
    int tagId = node.tagId;
    if (tagId < 0) {
      return null;
    }
    switch (tagId) {
      case Consts.TAG_BR:
        return "\n";
      case Consts.TAG_P:
        if ((self.depthMap[Consts.TAG_BLOCKQUOTE] > 0
                || (self.depthMap[Consts.TAG_LI] > 0 && !self.inTableCell()))
            && lastOutputByte(self) >= 0
            && lastOutputByte(self) != ' '
            && lastOutputByte(self) != '\n') {
          return "\n\n";
        }
        return null;
      case Consts.TAG_TD:
      case Consts.TAG_TH:
        if (self.depthMap[Consts.TAG_TABLE] > 1) {
          return null;
        }
        return node.index == 0 ? "" : "\t";
      case Consts.TAG_IMG:
        {
          String alt = node.attributes.getBit(Consts.ATTR_ALT);
          if (alt != null) {
            return alt.isEmpty() ? null : alt;
          }
          String title = node.attributes.getBit(Consts.ATTR_TITLE);
          if (title != null && !title.isEmpty()) {
            return title;
          }
          String src = node.attributes.getBit(Consts.ATTR_SRC);
          if (src == null || src.isEmpty() || Url.isDataUrl(src)) {
            return null;
          }
          return Url.resolveUrl(src, self.origin, self.cleanUrls);
        }
      case Consts.TAG_Q:
        return "\"";
      default:
        return null;
    }
  }

  private static String getTextExitOutput(ElementNode node) {
    return node.tagId == Consts.TAG_Q ? "\"" : null;
  }

  private static String spanFiller(int span) {
    if (span <= 1) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 1; i < span; i++) {
      out.append(" |");
    }
    return out.toString();
  }

  static boolean suppressesFormattingInPre(int tagId) {
    return tagId == Consts.TAG_A
        || tagId == Consts.TAG_KBD
        || tagId == Consts.TAG_S
        || tagId == Consts.TAG_STRIKE
        || tagId == Consts.TAG_FIGCAPTION
        || (tagId >= Consts.TAG_STRONG && tagId <= Consts.TAG_INS)
        || (tagId >= Consts.TAG_ABBR && tagId <= Consts.TAG_SMALL)
        || (tagId >= Consts.TAG_U && tagId <= Consts.TAG_BDO);
  }

  private static final String[] HEADING_PREFIXES = {
    "# ", "## ", "### ", "#### ", "##### ", "###### "
  };
  private static final String[] H_OPEN = {"<h1>", "<h2>", "<h3>", "<h4>", "<h5>", "<h6>"};
  private static final String[] H_CLOSE = {"</h1>", "</h2>", "</h3>", "</h4>", "</h5>", "</h6>"};

  // === Tables ===

  private enum LineBeforeRow {
    OPEN,
    ROW,
    CONTENT
  }

  private static LineBeforeRow lineStateBeforeRow(ConvertState self) {
    StringBuilder bytes = self.buffer;
    int len = bytes.length();
    if (len == 0 || bytes.charAt(len - 1) == '\n') {
      return LineBeforeRow.OPEN;
    }
    int index = len;
    while (index > 0 && bytes.charAt(index - 1) != '\n') {
      index--;
    }
    int start = index;
    while (start < len && (bytes.charAt(start) == ' ' || bytes.charAt(start) == '\t')) {
      start++;
    }
    boolean afterMarker = false;
    int end = len - trailingSpaces(bytes);
    if (end != 0) {
      afterMarker = listMarkerLineStart(self, bytes, end, pendingMarkerIndent(self));
    }
    if (start >= len) {
      return LineBeforeRow.OPEN;
    }
    if (afterMarker) {
      return LineBeforeRow.OPEN;
    }
    return bytes.charAt(start) == '|' ? LineBeforeRow.ROW : LineBeforeRow.CONTENT;
  }

  private static String tableRowOpener(ConvertState self, boolean isInline) {
    String indent = self.depthMap[Consts.TAG_LI] > 0 ? self.listIndent.toString() : "";
    LineBeforeRow state = lineStateBeforeRow(self);
    if (state == LineBeforeRow.ROW) {
      return indent.isEmpty() ? "\n| " : "\n" + indent + "| ";
    }
    if (state == LineBeforeRow.CONTENT) {
      return indent.isEmpty() ? "\n\n| " : "\n\n" + indent + "| ";
    }
    if (!indent.isEmpty()
        && self.buffer.length() > 0
        && self.buffer.charAt(self.buffer.length() - 1) == '\n') {
      return indent + "| ";
    }
    return "| ";
  }

  static int cellSpan(ElementNode node) {
    String value = node.attributes.getBit(Consts.ATTR_COLSPAN);
    if (value == null) {
      return 1;
    }
    Long parsed = ParseOps.parseBoundedU32(value, 255);
    return parsed == null ? 1 : (int) Math.max(1, Math.min(parsed, 16));
  }

  // === Write ===

  /**
   * Marks rendered child content (such as an image) as occupying a child slot on its ancestors, so
   * whitespace-only text that follows it is no longer treated as leading whitespace and dropped.
   */
  static void markRenderedChildContent(ConvertState self) {
    int imageIndex = Math.max(0, self.stack.size() - 1);
    int scopeStart = self.firstBlockParentIndex >= 0 ? self.firstBlockParentIndex : 0;
    if (scopeStart >= imageIndex) {
      scopeStart =
          self.blockParentIndices.size() > 1
              ? self.blockParentIndices.get(self.blockParentIndices.size() - 2)
              : 0;
    }

    boolean anchorInScope = false;
    for (int i = scopeStart; i < imageIndex; i++) {
      ElementNode parent = self.stack.get(i);
      parent.childTextNodeIndex++;
      if (parent.tagId == Consts.TAG_A) {
        anchorInScope = true;
      }
    }
    if (anchorInScope) {
      return;
    }

    boolean anchorFound = false;
    for (int i = scopeStart - 1; i >= 0; i--) {
      ElementNode parent = self.stack.get(i);
      if (!anchorFound) {
        if (parent.tagId != Consts.TAG_A) {
          continue;
        }
        anchorFound = true;
      }
      parent.childTextNodeIndex++;
      if (!parent.isInline) {
        return;
      }
    }
  }

  static void writeOutput(
      ConvertState self,
      boolean isEnter,
      boolean isInline,
      int configuredNewLines,
      String output,
      boolean literal) {
    String outputStr = output == null ? "" : output;
    if (isEnter) {
      if (configuredNewLines >= 2) {
        self.pendingRawHtmlBoundary = false;
      } else if (self.pendingRawHtmlBoundary) {
        finishRawHtmlBlock(self, outputStr, true);
      }
    }
    boolean isLineBoundary =
        !literal && (outputStr.startsWith("\n") || outputStr.startsWith("\\\n"));

    if (self.pendingInlineWhitespace && isEnter) {
      int firstOutput = outputStr.isEmpty() ? -1 : outputStr.charAt(0);
      if (!isInline
          || isLineBoundary
          || configuredNewLines > 0
          || firstOutput == '\n'
          || firstOutput == '\r') {
        self.pendingInlineWhitespace = false;
      } else if (firstOutput >= 0) {
        int last = lastOutputByte(self);
        if (last >= 0
            && last != ' '
            && last != '\n'
            && last != '\t'
            && !Scan.isWhitespace((char) firstOutput)) {
          self.buffer.append(' ');
        }
        self.pendingInlineWhitespace = false;
      }
    } else if (self.pendingInlineWhitespace && (!isInline || configuredNewLines > 0)) {
      self.pendingInlineWhitespace = false;
    }

    if (configuredNewLines == 0 && outputStr.isEmpty()
        && !self.lastTextNodeContainsWhitespace) {
      self.lastNodeIsInline = isInline;
      return;
    }

    if (configuredNewLines == 0 && !self.lastTextNodeContainsWhitespace) {
      int bufLen = self.buffer.length();
      int lastChar = lastOutputByte(self);
      if (isEnter
          && !literal
          && !isLineBoundary
          && !outputStr.isEmpty()
          && lastChar >= 0
          && needsSpacing(self, lastChar, outputStr.charAt(0))) {
        self.buffer.append(' ');
        self.lastContentCacheLen = 1;
      }
      if (!outputStr.isEmpty()) {
        self.buffer.append(outputStr);
        self.lastContentCacheLen = outputStr.length();
      }
      if (isEnter && !literal && outputStr.startsWith("  \n")) {
        self.hardBreakEnd = self.buffer.length();
      }
      self.lastNodeIsInline = isInline;
      return;
    }

    int lastChar = lastOutputByte(self);
    boolean measuresFromTail =
        !isEnter && (outputStr.endsWith("```") || outputStr.endsWith("~~~"));
    int lastNewLines =
        lastChar != '\n' || measuresFromTail ? 0 : trailingNewLines(self);
    int newLines = Math.max(0, configuredNewLines - lastNewLines);

    if (newLines > 0) {
      if (self.buffer.length() == 0 && !self.hasStreamedOutput) {
        if (!outputStr.isEmpty()) {
          self.buffer.append(outputStr);
          self.lastContentCacheLen = outputStr.length();
        }
        self.lastNodeIsInline = isInline;
        return;
      }

      boolean trimsTabs =
          (lastChar == ' ' || lastChar == '\t')
              && self.plainText
              && self.depthMap[Consts.TAG_TABLE] == 0
              && (self.depthMap[Consts.TAG_PRE] == 0);
      if ((lastChar == ' ' || trimsTabs) && self.buffer.length() > 0) {
        if (trimsTabs) {
          trimTrailingBlanks(self);
        } else {
          trimTrailingSpaces(self);
        }
        self.lastTextNodeContainsWhitespace = false;
        self.hasLastTextNode = false;
        if (!measuresFromTail) {
          newLines = Math.max(0, configuredNewLines - trailingNewLines(self));
        }
      }

      if (isEnter) {
        for (int i = 0; i < newLines; i++) {
          self.buffer.append('\n');
        }
        if (!outputStr.isEmpty()) {
          self.buffer.append(outputStr);
          self.lastContentCacheLen = outputStr.length();
        }
      } else {
        if (!outputStr.isEmpty()) {
          self.buffer.append(outputStr);
          self.lastContentCacheLen = outputStr.length();
        }
        for (int i = 0; i < newLines; i++) {
          self.buffer.append('\n');
        }
      }
    } else {
      if (self.lastTextNodeContainsWhitespace && (isInline || self.stack.size() > 0)
          && (self.depthMap[Consts.TAG_PRE] == 0
              || (!self.stack.isEmpty()
                  && self.stack.get(self.stack.size() - 1).tagId == Consts.TAG_PRE)
              || (isEnter
                  && !self.stack.isEmpty()
                  && self.stack.get(self.stack.size() - 1).tagId == Consts.TAG_BR))) {
        boolean collapses =
            !self.stack.isEmpty()
                && self.stack.get(self.stack.size() - 1).collapsesInnerWhiteSpace;
        boolean hasSpacing =
            !self.stack.isEmpty() && self.stack.get(self.stack.size() - 1).spacing != null;
        boolean isBlock = !isInline && !collapses && configuredNewLines > 0;
        boolean shouldTrim =
            !(isBlock || (isInline && isEnter) || (isEnter && collapses))
                && !(hasSpacing && isEnter);
        if (shouldTrim && self.lastContentCacheLen > 0) {
          int cacheLen = self.lastContentCacheLen;
          int bufLen = self.buffer.length();
          int start = Math.max(bufLen - cacheLen, trimFloor(self));
          if (cacheLen <= bufLen && start <= bufLen) {
            int trimmedLen = start;
            while (trimmedLen < bufLen
                && Scan.isWhitespace(self.buffer.charAt(bufLen - 1 - (trimmedLen - start)))) {
              trimmedLen++;
            }
            // Compute the actual end.
            int end = bufLen;
            while (end > start && Scan.isWhitespace(self.buffer.charAt(end - 1))) {
              end--;
            }
            if (end < bufLen) {
              clampItemMarkerEnd(self, end);
              truncateBuffer(self, end);
              self.lastContentCacheLen = end - start;
              if (!isEnter && isInline) {
                self.pendingInlineWhitespace = true;
              }
            }
          }
        }
        self.lastTextNodeContainsWhitespace = false;
        self.hasLastTextNode = false;
      }

      if (isEnter
          && !literal
          && !isLineBoundary
          && !outputStr.isEmpty()
          && lastChar >= 0
          && needsSpacing(self, lastChar, outputStr.charAt(0))) {
        self.buffer.append(' ');
        self.lastContentCacheLen = 1;
      }
      if (!outputStr.isEmpty()) {
        self.buffer.append(outputStr);
        self.lastContentCacheLen = outputStr.length();
      }
    }

    if (isEnter && !literal && outputStr.startsWith("  \n")) {
      self.hardBreakEnd = self.buffer.length();
    }
    self.lastNodeIsInline = isInline;
  }

  // === Raw HTML blocks ===

  static void trackRawHtmlMarkdownContext(ConvertState self, int end) {
    if (self.rawHtmlMarkdown) {
      return;
    }
    int len = Math.min(end, self.buffer.length());
    int from = Math.max(0, Math.min(self.rawHtmlScannedTo, len) - 1);
    for (int i = from; i + 1 < len; i++) {
      if (self.buffer.charAt(i) == '\n' && self.buffer.charAt(i + 1) == '\n') {
        self.rawHtmlMarkdown = true;
        break;
      }
    }
    self.rawHtmlScannedTo = len;
  }

  private static boolean lineOpensRawHtmlBlock(ConvertState self) {
    int len = self.buffer.length();
    int lineStart = len;
    while (lineStart > 0 && self.buffer.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }
    // A `lineStart` of zero is the drained buffer's front, not the line's, once a drain has
    // taken this line's beginning: the fragment can open with a tag the real line only
    // continues, which would suspend Markdown and drop the escapes a one-shot conversion writes.
    if (lineStart == 0 && self.hasFlushedTail() && self.flushedTail[1] != '\n') {
      switch (self.cutLineLeadKind) {
        case ConvertState.CUT_LEAD_RAW_HTML:
          return true;
        case ConvertState.CUT_LEAD_BLANK:
          // The cut fell inside this line's indent, so the fragment's own spaces continue a run
          // that started before it: three is all the run may total, however it is split.
          int own = 0;
          while (own < len && self.buffer.charAt(own) == ' ') {
            own++;
          }
          return self.cutLineLeadIndent + own <= ConvertState.RAW_HTML_MAX_INDENT
              && own < len
              && self.buffer.charAt(own) == '<';
        default:
          return false;
      }
    }
    int indent = lineStart;
    while (indent < len && indent - lineStart < 3 && self.buffer.charAt(indent) == ' ') {
      indent++;
    }
    return indent < len && self.buffer.charAt(indent) == '<';
  }

  static void enterRawHtmlBoundary(
      ConvertState self, boolean literal, boolean hasOutput, int newLines) {
    if (self.rawHtmlNeedsBoundary) {
      trackRawHtmlMarkdownContext(self, self.buffer.length());
      if (self.rawHtmlMarkdown && !lineOpensRawHtmlBlock(self)) {
        self.rawHtmlNeedsBoundary = false;
      }
    }
    if (!literal
        && !self.inTableCell()
        && self.depthMap[Consts.TAG_PRE] == 0
        && hasOutput
        && (self.pendingRawHtmlBoundary || newLines > 0 || !blockOpenNeedsBreak(self))) {
      self.rawHtmlNeedsBoundary = true;
      self.rawHtmlMarkdown = false;
      self.rawHtmlScannedTo = self.buffer.length();
      self.pendingRawHtmlBoundary = false;
    }
  }

  static void exitRawHtmlBoundary(ConvertState self, boolean hasOverride, boolean hasOutput) {
    trackRawHtmlMarkdownContext(self, self.buffer.length());
    if (self.rawHtmlNeedsBoundary && self.rawHtmlMarkdown && !lineOpensRawHtmlBlock(self)) {
      self.rawHtmlNeedsBoundary = false;
    }
    if (!hasOverride && hasOutput && !blockOpenNeedsBreak(self)) {
      self.rawHtmlNeedsBoundary = true;
    }
    if (self.rawHtmlBlockDepth() == 1) {
      self.pendingRawHtmlBoundary = self.rawHtmlNeedsBoundary && !hasOverride;
      self.rawHtmlNeedsBoundary = false;
    }
  }

  private static void finishRawHtmlBlock(ConvertState self, String value, boolean indent) {
    if (!self.pendingRawHtmlBoundary
        || self.inRawHtmlBlock()
        || !Chars.hasVisible(value)) {
      return;
    }
    int newlines = 0;
    for (int i = self.buffer.length() - 1; i >= 0; i--) {
      char c = self.buffer.charAt(i);
      if (c == '\n') {
        newlines++;
        if (newlines == 2) {
          self.pendingRawHtmlBoundary = false;
          return;
        }
      } else if (!Scan.isWhitespace(c)) {
        break;
      }
    }
    int indentLen = indent ? self.listIndent.length() : 0;
    for (int i = newlines; i < 2; i++) {
      self.buffer.append('\n');
    }
    self.buffer.append(self.listIndent, 0, indentLen);
    self.pendingRawHtmlBoundary = false;
    self.lastContentCacheLen = 2 - newlines + indentLen;
    self.hasLastTextNode = false;
    self.lastTextNodeContainsWhitespace = false;
  }

  // === Pre fences ===

  private static int maxLineLeadingRun(
      StringBuilder buffer, int contentStart, char marker, String indent) {
    int max = 0;
    int index = Math.max(0, contentStart);
    int len = buffer.length();
    while (index <= len) {
      int lineEnd = index;
      while (lineEnd < len && buffer.charAt(lineEnd) != '\n') {
        lineEnd++;
      }
      int start = index;
      if (!indent.isEmpty() && lineHasPrefix(buffer, start, lineEnd, indent)) {
        start += indent.length();
      }
      int spaces = 0;
      while (start < lineEnd && spaces < 3 && buffer.charAt(start) == ' ') {
        start++;
        spaces++;
      }
      int run = 0;
      while (start + run < lineEnd && buffer.charAt(start + run) == marker) {
        run++;
      }
      max = Math.max(max, run);
      if (lineEnd >= len) {
        break;
      }
      index = lineEnd + 1;
    }
    return max;
  }

  private static boolean lineHasPrefix(StringBuilder buffer, int start, int end, String prefix) {
    if (start + prefix.length() > end) {
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      if (buffer.charAt(start + i) != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static void startCodeFence(
      ConvertState self, int outputStart, int contentStart, String language, String indent) {
    String emitted = self.buffer.substring(outputStart, contentStart);
    int markerOffset = emitted.lastIndexOf(Consts.MARKDOWN_CODE_BLOCK);
    if (markerOffset < 0) {
      markerOffset = 0;
    }
    ConvertState.CodeFenceState fence = new ConvertState.CodeFenceState();
    fence.outputStart = outputStart;
    fence.markerOffset = markerOffset;
    fence.contentStart = contentStart;
    fence.indent = indent;
    fence.language = language;
    self.codeFence = fence;
  }

  /** Compute the closing delimiter, rewriting any too-short opener in place. */
  private static String finalizeCodeFence(ConvertState self) {
    ConvertState.CodeFenceState fence = self.codeFence;
    if (fence == null) {
      return null;
    }
    self.codeFence = null;
    char marker = fence.language != null && fence.language.indexOf('`') >= 0 ? '~' : '`';
    int maxRun =
        maxLineLeadingRun(
            self.buffer,
            fence.contentStart,
            marker,
            fence.indent == null ? "" : fence.indent);
    int count = Math.max(maxRun + 1, 3);
    StringBuilder delimiter = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      delimiter.append(marker);
    }
    int markerStart = fence.outputStart + fence.markerOffset;
    int markerEnd = markerStart + Consts.MARKDOWN_CODE_BLOCK.length();
    if (markerStart >= 0 && markerEnd <= self.buffer.length()) {
      self.buffer.replace(markerStart, markerEnd, delimiter.toString());
    }
    return delimiter.toString();
  }

  private static int maxBacktickRun(String value) {
    int max = 0;
    int run = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == '`') {
        run++;
        max = Math.max(max, run);
      } else {
        run = 0;
      }
    }
    return max;
  }

  /** Compute the closing delimiter for an inline code span and pad the opener. */
  private static String finalizeCodeSpan(ConvertState self, ConvertState.CodeSpanState span) {
    if (self.depthMap[Consts.TAG_TABLE] > 0
        && span.contentStart <= self.buffer.length()
        && self.buffer.substring(span.contentStart).indexOf('|') >= 0) {
      int start = span.contentStart;
      StringBuilder escaped = new StringBuilder(self.buffer.length() - start);
      for (int i = start; i < self.buffer.length(); i++) {
        char c = self.buffer.charAt(i);
        if (c == '|') {
          escaped.append('\\');
        }
        escaped.append(c);
      }
      self.buffer.replace(start, self.buffer.length(), escaped.toString());
    }
    String content = self.buffer.substring(span.contentStart);
    int maxRun = maxBacktickRun(content);
    if (maxRun == 0
        && span.contentStart > 0
        && self.buffer.charAt(span.contentStart - 1) == '`') {
      return "`";
    }
    int count = Math.max(maxRun + 1, 1);
    StringBuilder delimiter = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      delimiter.append('`');
    }
    boolean padded = content.startsWith("`") || content.endsWith("`");
    StringBuilder opening = new StringBuilder();
    opening.append(self.buffer, span.outputStart, span.contentStart - 1);
    opening.append(delimiter);
    if (padded) {
      opening.append(' ');
    }
    self.buffer.replace(span.outputStart, span.contentStart, opening.toString());
    return padded ? " " + delimiter : delimiter.toString();
  }

  private static void escapeTrailingHeadingHashes(ConvertState self) {
    StringBuilder bytes = self.buffer;
    int end = bytes.length();
    while (end > 0 && (bytes.charAt(end - 1) == ' ' || bytes.charAt(end - 1) == '\t')) {
      end--;
    }
    if (end == 0 || bytes.charAt(end - 1) != '#') {
      return;
    }
    int line = 0;
    for (int i = end - 1; i >= 0; i--) {
      if (bytes.charAt(i) == '\n') {
        line = i + 1;
        break;
      }
    }
    int start = line;
    while (start < bytes.length() && bytes.charAt(start) == '#') {
      start++;
    }
    if (start < bytes.length() && bytes.charAt(start) == ' ') {
      start++;
    }
    if (end < start) {
      return;
    }
    int run = end;
    while (run > start && bytes.charAt(run - 1) == '#') {
      run--;
    }
    if (run < end
        && (run == start || bytes.charAt(run - 1) == ' ' || bytes.charAt(run - 1) == '\t')) {
      bytes.insert(run, '\\');
    }
  }

  private static void finalizeBlockquote(ConvertState self) {
    if (self.blockquotes.isEmpty()) {
      return;
    }
    ConvertState.BlockquoteFrame frame = self.blockquotes.remove(self.blockquotes.size() - 1);
    int contentEnd = self.buffer.length();
    while (contentEnd > 0 && Chars.isAsciiWhitespace(self.buffer.charAt(contentEnd - 1))) {
      contentEnd--;
    }
    if (contentEnd < frame.contentStart) {
      return;
    }
    String content = self.buffer.substring(frame.contentStart, contentEnd);
    StringBuilder quoted = new StringBuilder(content.length() + 16);
    int lineStart = 0;
    int lineIndex = 0;
    while (lineStart <= content.length()) {
      int lineEnd = content.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = content.length();
      }
      if (lineIndex > 0) {
        quoted.append('\n');
      }
      quoted.append(frame.listIndent).append('>');
      String line = content.substring(lineStart, lineEnd);
      String unindented =
          frame.listIndent.isEmpty() || !line.startsWith(frame.listIndent)
              ? line
              : line.substring(frame.listIndent.length());
      if (!unindented.isEmpty()) {
        quoted.append(' ').append(unindented);
      }
      lineIndex++;
      if (lineEnd >= content.length()) {
        break;
      }
      lineStart = lineEnd + 1;
    }
    truncateBuffer(self, frame.contentStart);
    self.buffer.append(quoted);
    self.lastContentCacheLen = quoted.length();
  }

  // === Captions ===

  static boolean figcaptionOwnsBlockSpacing(ConvertState self) {
    return !self.inTableCell() && (self.plainText || self.collapseNonSpanDepth == 1);
  }

  private static void pushCaptionContent(ConvertState self, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    self.buffer.append(value);
    self.lastContentCacheLen = value.length();
  }

  private static String captionOpenPrefix(
      ConvertState self, String prefix, int configuredNewLines) {
    if (configuredNewLines == 0) {
      return null;
    }
    String open = blockOpenPrefix(self, prefix);
    int contentEnd = self.buffer.length();
    if (!prefix.isEmpty() && endsWith(self.buffer, prefix)) {
      contentEnd -= prefix.length();
    }
    int trailingNewLines = 0;
    while (contentEnd - trailingNewLines > 0
        && self.buffer.charAt(contentEnd - trailingNewLines - 1) == '\n') {
      trailingNewLines++;
    }
    boolean openPresent = open != null && !open.isEmpty();
    if (!openPresent && trailingNewLines == 0) {
      return null;
    }
    int missing = Math.max(0, configuredNewLines - trailingNewLines);
    if (missing == 0) {
      return openPresent ? open : null;
    }
    StringBuilder output = new StringBuilder(missing + prefix.length());
    for (int i = 0; i < missing; i++) {
      output.append('\n');
    }
    output.append(prefix);
    return output.toString();
  }

  private static void writeCaptionBoundary(
      ConvertState self, int configuredNewLines, boolean includeListIndent) {
    String prefix = includeListIndent ? self.listIndent.toString() : "";
    String value = captionOpenPrefix(self, prefix, configuredNewLines);
    if (value != null) {
      pushCaptionContent(self, value);
    }
  }

  static boolean prepareCaptionBoundary(ConvertState self, boolean includeListIndent) {
    boolean consumesBoundary = self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE;
    if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_PENDING) {
      writeCaptionBoundary(self, self.captionBoundaryNewLines, includeListIndent);
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE;
    }
    return consumesBoundary;
  }

  static void setCaptionBoundaryPending(ConvertState self, int newLines) {
    self.captionBoundary = ConvertState.CAPTION_BOUNDARY_PENDING;
    self.captionBoundaryNewLines = newLines;
  }

  private static void deferCaptionBreak(ConvertState self, String fragment) {
    if (self.captionFrames.isEmpty()) {
      return;
    }
    ConvertState.CaptionFrame frame = self.captionFrames.get(self.captionFrames.size() - 1);
    if (frame.state == ConvertState.CAPTION_COMMITTED) {
      return;
    }
    for (ConvertState.CaptionFrame current : self.captionFrames) {
      if (current.state != ConvertState.CAPTION_COMMITTED) {
        current.hasInternalBreak = true;
      }
    }
    ConvertState.CaptionFrame last = self.captionFrames.get(self.captionFrames.size() - 1);
    if (last.deferredBreaks != null && !last.deferredBreaks.fragment.equals(fragment)) {
      expandDeferredCaptionBreaks(self);
    }
    last = self.captionFrames.get(self.captionFrames.size() - 1);
    if (last.deferredBreaks != null) {
      last.deferredBreaks.count++;
    } else {
      ConvertState.DeferredBreakRun run = new ConvertState.DeferredBreakRun();
      run.fragment = fragment;
      run.count = 1;
      last.deferredBreaks = run;
    }
    self.heldBreakBytes += fragment.length();
  }

  static boolean tryDeferCaptionBreak(ConvertState self, String fragment) {
    if (self.plainText || self.captionFrames.isEmpty()) {
      return false;
    }
    ConvertState.CaptionFrame frame = self.captionFrames.get(self.captionFrames.size() - 1);
    if (frame.state == ConvertState.CAPTION_COMMITTED) {
      return false;
    }
    deferCaptionBreak(self, fragment);
    return true;
  }

  private static void writeDeferredBreakRun(ConvertState self, ConvertState.DeferredBreakRun run) {
    if (run == null) {
      return;
    }
    trimTrailingSpaces(self);
    for (int i = 0; i < run.count; i++) {
      if (run.fragment.equals("\n") && endsWith(self.buffer, "\n\n")) {
        continue;
      }
      writeOutput(self, true, true, 0, run.fragment, false);
    }
  }

  static void expandDeferredCaptionBreaksUntil(ConvertState self, int end) {
    int limit = Math.min(end, self.captionFrames.size());
    for (int index = 0; index < limit; index++) {
      ConvertState.DeferredBreakRun run = self.captionFrames.get(index).deferredBreaks;
      self.captionFrames.get(index).deferredBreaks = null;
      writeDeferredBreakRun(self, run);
    }
  }

  static void expandDeferredCaptionBreaks(ConvertState self) {
    expandDeferredCaptionBreaksUntil(self, self.captionFrames.size());
  }

  static void materializeCaptionFrames(ConvertState self, int mode) {
    boolean explicitTop = mode == CAPTION_MATERIALIZE_EXPLICIT_COMMIT;
    boolean commit = mode != CAPTION_MATERIALIZE_TENTATIVE;
    int top = self.captionFrames.size() - 1;
    for (int index = 0; index < self.captionFrames.size(); index++) {
      ConvertState.CaptionFrame frame = self.captionFrames.get(index);
      if (frame.state == ConvertState.CAPTION_COMMITTED) {
        continue;
      }
      if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        if (commit) {
          frame.state = ConvertState.CAPTION_COMMITTED;
        }
        continue;
      }
      String prefix =
          frame.hasInternalBreak
              ? null
              : captionOpenPrefix(self, self.listIndent.toString(), frame.spacing[0]);
      boolean trimmedSpace =
          prefix != null && self.buffer.length() > 0
              && self.buffer.charAt(self.buffer.length() - 1) == ' ';
      if (trimmedSpace) {
        trimTrailingSpaces(self);
        self.lastTextNodeContainsWhitespace = false;
        self.hasLastTextNode = false;
      }
      int outputStart = self.buffer.length();
      if (prefix != null) {
        pushCaptionContent(self, prefix);
      }
      if (!self.plainText && (!explicitTop || index != top)) {
        pushCaptionContent(self, Consts.MARKDOWN_EMPHASIS);
      }
      if (commit) {
        frame.state = ConvertState.CAPTION_COMMITTED;
      } else {
        frame.state = ConvertState.CAPTION_TENTATIVE;
        frame.tentativeOutputStart = outputStart;
        frame.tentativeContentStart = self.buffer.length();
        frame.tentativeRestoreSpace = trimmedSpace;
      }
    }
  }

  static void resetEmptyTentativeCaptionFrames(ConvertState self) {
    for (int index = self.captionFrames.size() - 1; index >= 0; index--) {
      ConvertState.CaptionFrame frame = self.captionFrames.get(index);
      if (frame.state != ConvertState.CAPTION_TENTATIVE) {
        continue;
      }
      if (Chars.hasVisible(self.buffer, frame.tentativeContentStart, self.buffer.length())) {
        continue;
      }
      frame.state = ConvertState.CAPTION_PENDING;
      truncateBuffer(self, frame.tentativeOutputStart);
      self.lastContentCacheLen = 0;
      if (frame.tentativeRestoreSpace) {
        self.buffer.append(' ');
        self.lastContentCacheLen = 1;
      }
    }
  }

  static int firstTentativeCaptionStart(ConvertState self) {
    for (ConvertState.CaptionFrame frame : self.captionFrames) {
      if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        return frame.tentativeOutputStart;
      }
    }
    return -1;
  }

  private static final int CAPTION_MATERIALIZE_TENTATIVE = 0;
  private static final int CAPTION_MATERIALIZE_COMMIT = 1;
  private static final int CAPTION_MATERIALIZE_EXPLICIT_COMMIT = 2;

  private static void flushPreFence(ConvertState self) {
    if (!self.preFencePending) {
      return;
    }
    String lang = self.preFenceLang == null ? "" : self.preFenceLang;
    String fence;
    if (self.depthMap[Consts.TAG_LI] > 0) {
      String indent = self.listIndent.toString();
      String open = blockOpenPrefix(self, indent);
      fence = open + "```" + lang + "\n" + indent;
    } else {
      fence = "```" + lang + "\n";
    }
    int outputStart = self.buffer.length();
    self.buffer.append(fence);
    self.lastContentCacheLen = fence.length();
    startCodeFence(self, outputStart, self.buffer.length(), lang, self.listIndent.toString());
    self.preFenceOwnerDepth = self.depthMap[Consts.TAG_PRE];
    self.preFencePending = false;
    self.lastNodeIsInline = false;
  }

  // === Links ===

  private static void beginLink(
      ConvertState self, int bracketPos, boolean skipped, boolean rawHtmlOpen) {
    if (self.link.open) {
      self.parentLinks.add(self.link);
    }
    ConvertState.LinkOutputState state = new ConvertState.LinkOutputState();
    state.bracketPos = bracketPos;
    state.skipped = skipped;
    state.rawHtmlOpen = rawHtmlOpen;
    state.open = true;
    state.beginDepth = self.depth;
    state.startsAtHardBreak = self.hardBreakEnd == bracketPos;
    self.link = state;
  }

  private static void endLink(ConvertState self) {
    if (self.parentLinks.isEmpty()) {
      self.link = new ConvertState.LinkOutputState();
    } else {
      self.link = self.parentLinks.remove(self.parentLinks.size() - 1);
    }
  }

  /**
   * Clean-mode rewrite of a closing link; {@code true} when it handled the exit.
   *
   * <p>Mirrors {@code try_link_clean_rewrite}: an empty link is dropped whole, a self-link in a
   * heading or a link whose text is its own URL keeps only its text.
   */
  private static boolean tryLinkCleanRewrite(
      ConvertState self, ElementNode node, boolean isInline) {
    if (self.link.skipped) {
      endLink(self);
      self.lastNodeIsInline = isInline;
      return true;
    }
    // Rewrites need the `[` this anchor wrote. Without one, only the plain close remains.
    int bracketPos = -1;
    if (!self.plainText
        && self.cleanFlags != 0
        && !self.link.rawHtmlOpen
        && !self.link.holdReleased
        && self.link.bracketEmitted) {
      int found = self.buffer.indexOf("[", self.link.bracketPos);
      if (found >= 0) {
        bracketPos = found;
      }
    }
    if (bracketPos < 0) {
      return false;
    }
    int textStart = bracketPos + 1;
    int bufLen = self.buffer.length();
    String linkText = textStart <= bufLen ? self.buffer.substring(textStart) : "";
    int textLen = Math.max(0, bufLen - textStart);

    if ((self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) != 0
        && Chars.trimUnicode(linkText).isEmpty()) {
      dropEmptyLinkText(self, bracketPos, isInline);
      return true;
    }

    String href = node.attributes.getBit(Consts.ATTR_HREF);
    if ((self.cleanFlags & ConvertState.CLEAN_SELF_LINK_HEADINGS) != 0
        && self.inHeading()
        && href != null
        && href.startsWith("#")
        && textLen > 0) {
      keepLinkText(self, bracketPos, textLen, isInline);
      return true;
    }

    if ((self.cleanFlags & ConvertState.CLEAN_REDUNDANT_LINKS) != 0 && href != null) {
      String resolved = Url.resolveUrl(href, self.origin, self.cleanUrls);
      if (linkText.equals(resolved) && textLen > 0) {
        keepLinkText(self, bracketPos, textLen, isInline);
        return true;
      }
    }
    return false;
  }

  /** Clean-mode empty link: drop the whole {@code [](url)}. */
  private static void dropEmptyLinkText(ConvertState self, int bracketPos, boolean isInline) {
    truncateBuffer(self, bracketPos);
    if (self.link.startsAtHardBreak) {
      self.hardBreakEnd = self.link.bracketPos;
    }
    resetEmptyTentativeCaptionFrames(self);
    endLink(self);
    self.lastNodeIsInline = isInline;
  }

  /** Clean-mode link reduced to its text: drop the {@code [}, keeping the text. */
  private static void keepLinkText(ConvertState self, int bracketPos, int textLen, boolean isInline) {
    noteBufferRewrite(self, bracketPos);
    self.buffer.deleteCharAt(bracketPos);
    self.lastContentCacheLen = textLen;
    endLink(self);
    self.lastNodeIsInline = isInline;
  }

  /**
   * Writes {@code <uri>} for an autolink whose text is its own URL. {@code true} when it wrote the
   * exit.
   */
  private static boolean tryAutolinkRewrite(ConvertState self, String resolved, boolean isInline) {
    if (!Url.isAutolinkUri(resolved) || self.link.holdReleased) {
      return false;
    }
    int bracketPos = self.link.bracketPos;
    if (bracketPos >= self.buffer.length()
        || self.buffer.charAt(bracketPos) != '['
        || !self.buffer.substring(bracketPos + 1).equals(resolved)) {
      return false;
    }
    truncateBuffer(self, bracketPos);
    self.buffer.append('<').append(resolved).append('>');
    self.lastContentCacheLen = self.buffer.length() - bracketPos;
    endLink(self);
    self.lastNodeIsInline = isInline;
    return true;
  }

  /** Remember a same-document link so the fragment pass can remap it. */
  private static void recordFragmentLink(
      ConvertState self, String fragment, boolean hasTitle, int textEnd) {
    self.fragmentLinks.add(
        new ConvertState.FragmentLink(
            self.link.bracketPos, textEnd, self.buffer.length(), fragment, hasTitle));
  }

  /**
   * Invalidate the measured state a rewrite at {@code offset} can reach.
   *
   * <p>The port keeps no held-run caches, so only the hard-break and blockquote-line offsets
   * remain to reset.
   */
  private static void noteBufferRewrite(ConvertState self, int offset) {
    if (offset < self.blockquoteLineScannedTo) {
      self.blockquoteLineScannedTo = 0;
      self.blockquoteLineEnd = 0;
    }
    if (self.hardBreakEnd >= 0 && offset < self.hardBreakEnd) {
      self.hardBreakEnd = -1;
    }
  }

  private static void writeLinkExit(
      ConvertState self, ElementNode node, boolean isInline, int newLines) {
    if (!self.link.open) {
      return;
    }
    if (self.link.skipped) {
      endLink(self);
      self.lastNodeIsInline = isInline;
      return;
    }
    self.link.emptyTextPending = false;
    if (self.cleanFlags != 0 && tryLinkCleanRewrite(self, node, isInline)) {
      return;
    }
    String href = node.attributes.getBit(Consts.ATTR_HREF);
    if (href == null) {
      endLink(self);
      self.lastNodeIsInline = isInline;
      return;
    }
    // clean.emptyLinks: an href without navigation value is dropped.
    if ((self.cleanFlags & ConvertState.CLEAN_EMPTY_LINKS) != 0 && Url.isEmptyLinkHref(href)) {
      endLink(self);
      self.lastNodeIsInline = isInline;
      return;
    }
    String resolved = Url.resolveUrl(href, self.origin, self.cleanUrls);
    int textEnd = self.buffer.length();
    while (textEnd > 0 && self.buffer.charAt(textEnd - 1) == ' ') {
      textEnd--;
    }
    if (self.link.bracketEmitted
        && textEnd > self.link.bracketPos + 1
        && textEnd < self.buffer.length()
        && !Scan.isWhitespace(self.buffer.charAt(textEnd - 1))
        && self.codeSpans.isEmpty()) {
      int removed = self.buffer.length() - textEnd;
      self.lastContentCacheLen = Math.max(0, self.lastContentCacheLen - removed);
      truncateBuffer(self, textEnd);
      self.pendingInlineWhitespace = true;
    }
    String title = node.attributes.getBit(Consts.ATTR_TITLE);
    if (title == null) {
      title = "";
    }
    if (!title.isEmpty() && self.lastContentCacheLen > 0) {
      int bufLen = self.buffer.length();
      int start = Math.max(0, bufLen - self.lastContentCacheLen);
      String cache = self.buffer.substring(start);
      String trimmedTitle = title;
      int titleEnd = trimmedTitle.length();
      while (titleEnd > 0 && Chars.isAsciiWhitespace(trimmedTitle.charAt(titleEnd - 1))) {
        titleEnd--;
      }
      if (cache.equals(trimmedTitle.substring(0, titleEnd))) {
        title = "";
      }
    }
    int linkTextEnd = self.buffer.length();
    // A released link no longer has a valid bracket anchor.
    if (title.isEmpty() && tryAutolinkRewrite(self, resolved, isInline)) {
      return;
    }
    StringBuilder output = new StringBuilder();
    output.append(']');
    writeMarkdownResource(output, resolved, title);
    writeOutput(self, false, isInline, newLines, output.toString(), false);
    // A length, not an offset; bracketPos is buffer.length() when no `[` was emitted.
    self.lastContentCacheLen = Math.max(0, self.buffer.length() - self.link.bracketPos);
    if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0
        && self.depthMap[Consts.TAG_CODE] == 0
        // A skipped anchor has no link of its own; self.link is an ancestor's.
        && self.link.beginDepth == self.depth
        && resolved.startsWith("#")
        && resolved.length() > 1) {
      recordFragmentLink(self, resolved.substring(1), !title.isEmpty(), linkTextEnd);
    }
    endLink(self);
  }

  // === Element emitters ===

  static void emitEnterSimple(ConvertState self, boolean link) {
    int stackLen = self.stack.size();
    if (stackLen == 0) {
      return;
    }
    ElementNode node = self.stack.get(stackLen - 1);
    if (node.excludedFromMarkdown) {
      self.lastNodeIsInline = node.isInline;
      return;
    }
    if (self.listRulePending && !node.excludesTextNodes) {
      flushListRule(self);
    }
    int tagId = node.tagId;
    boolean isInline = node.isInline;
    int[] nodeSpacing = node.spacing;
    String output = getEnterOutput(self, node);

    if (self.hardBreakEnd == self.buffer.length()
        && (tagId == Consts.TAG_P || tagId == Consts.TAG_DIV)
        && self.depthMap[Consts.TAG_LI] > 0
        && !self.inTableCell()
        && self.depthMap[Consts.TAG_PRE] == 0) {
      output = prependListBreak(self, output);
    }

    int[] config = calculateNewLineConfig(self, tagId, nodeSpacing, true);
    int configuredNewLines = quoteAtStart(self) ? 0 : config[0];

    if (link
        && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINKS) != 0
        && node.attributes.getBit(Consts.ATTR_HREF) != null
        && Url.isEmptyLinkHref(node.attributes.getBit(Consts.ATTR_HREF))) {
      beginLink(self, self.buffer.length(), true, false);
      if (self.streaming) {
        self.link.holdReleased = true;
      }
      self.lastNodeIsInline = isInline;
      return;
    }

    int outputStart = self.buffer.length();
    writeOutput(self, true, isInline, configuredNewLines, output, false);

    if (link) {
      int bufLen = self.buffer.length();
      boolean emittedBracket =
          output != null
              && output.endsWith("[")
              && self.buffer.length() > outputStart;
      int bracketPos = emittedBracket ? bufLen - 1 : bufLen;
      beginLink(self, bracketPos, false, false);
      self.link.bracketEmitted = emittedBracket;
      prepareStreamingLink(self, node, emittedBracket);
      if (!self.openMarkers.isEmpty()
          && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) == 0
          && output != null
          && Chars.hasVisible(output)) {
        self.openMarkers.clear();
      }
      if (!isInline && !self.openMarkers.isEmpty()) {
        self.openMarkers.clear();
      }
      return;
    }

    settleEmptyTextPending(self, outputStart, inlineMarkerType(tagId) != 0);
    recordEnterMarker(self, tagId, output, outputStart, false);
    if (!isInline && !self.openMarkers.isEmpty()) {
      self.openMarkers.clear();
    }
    self.lastNodeIsInline = isInline;
  }

  private static String prependListBreak(ConvertState self, String output) {
    self.hardBreakEnd = Integer.MAX_VALUE;
    trimTrailingSpaces(self);
    int newLines = Math.max(0, 2 - trailingNewLines(self));
    StringBuilder separator = new StringBuilder();
    for (int i = 0; i < newLines; i++) {
      separator.append('\n');
    }
    separator.append(self.listIndent);
    return output == null ? separator.toString() : separator + output;
  }

  static void emitExitSimple(ConvertState self, boolean link) {
    ElementNode node = self.stack.retired();
    if (node.excludedFromMarkdown || node.enterSkipped) {
      self.lastNodeIsInline = node.isInline;
      return;
    }
    int tagId = node.tagId;
    boolean isInline = node.isInline;
    int[] spacing = node.spacing;
    if (link) {
      int[] config = calculateNewLineConfig(self, tagId, spacing, false);
      self.link.emptyTextPending = false;
      writeLinkExit(self, node, isInline, config[1]);
      return;
    }
    String output = getExitOutput(self, node, 1);
    int[] config = calculateNewLineConfig(self, tagId, spacing, false);
    if (dropEmptyMarkerPair(self, tagId, output, isInline)) {
      return;
    }
    releaseMarkersOnExit(self, !isInline, output);
    int outputStart = self.buffer.length();
    writeOutput(self, false, isInline, config[1], output, false);
    settleEmptyTextPending(self, outputStart, false);
  }

  static void emitEnterElement(ConvertState self) {
    int stackLen = self.stack.size();
    if (stackLen == 0) {
      return;
    }
    ElementNode node = self.stack.get(stackLen - 1);
    if (node.excludedFromMarkdown) {
      self.lastNodeIsInline = node.isInline;
      return;
    }
    if (self.htmlFormat) {
      HtmlOutputOps.emitHtmlEnter(self);
      return;
    }
    if (self.listRulePending && !node.excludesTextNodes) {
      flushListRule(self);
    }

    int tagId = node.tagId;
    if (!self.plainText
        && tagId == Consts.TAG_PRE
        && self.preFenceOwnerDepth == 0
        && !self.preFencePending
        && !self.inTableCell()) {
      self.preFencePending = true;
      self.preFencePendingDepth = self.depthMap[Consts.TAG_PRE];
      self.preFenceLang = getLanguageFromAttributes(node);
    }

    if (tagId == Consts.TAG_TABLE) {
      if (self.depthMap[Consts.TAG_TABLE] <= 1) {
        self.tableRenderedTable = false;
        self.tableHeaderCells = 0;
      }
      self.tableColumnAlignments.clear();
    } else if (tagId == Consts.TAG_TR) {
      self.tableCurrentRowCells = 0;
      if (self.depthMap[Consts.TAG_TABLE] <= 1 && !self.inTableCell()) {
        self.tableRowOpenerPending = !self.plainText && !self.tableRenderedTable;
      }
    } else if (tagId == Consts.TAG_TH) {
      String alignValue = node.attributes.getBit(Consts.ATTR_ALIGN);
      int align = 0;
      if (alignValue != null && !alignValue.isEmpty()) {
        char c = (char) (alignValue.charAt(0) | 0x20);
        if (c == 'l') {
          align = 1;
        } else if (c == 'c') {
          align = 2;
        } else if (c == 'r') {
          align = 3;
        }
      }
      if (self.tableCurrentRowCells < self.tableColumnCap
          && self.tableColumnAlignments.size() < self.tableColumnCap
          && (align != 0 || self.tableColumnAlignments.size() <= self.tableCurrentRowCells)) {
        self.tableColumnAlignments.add(align);
      }
    }

    TagOverride override = self.hasTagOverrides ? self.overrideForNode(node) : null;
    boolean enterIsLiteral = false;
    String output;
    if (override != null && override.enter() != null) {
      enterIsLiteral = true;
      output = override.enter();
    } else {
      output = getEnterOutput(self, node);
    }

    if (self.tableRowOpenerPending
        && (tagId == Consts.TAG_TH || tagId == Consts.TAG_TD)
        && self.depthMap[Consts.TAG_TABLE] <= 1) {
      self.tableRowOpenerPending = false;
      String opener = tableRowOpener(self, node.isInline);
      if (enterIsLiteral && output != null && !output.isEmpty()) {
        output = opener + output;
      } else {
        output = opener;
      }
    }

    if (!self.plainText && self.preFencePending) {
      boolean codeOwnsFence =
          stackLen >= 2
              && self.stack.get(stackLen - 1).tagId == Consts.TAG_CODE
              && self.stack.get(stackLen - 2).tagId == Consts.TAG_PRE
              && self.depthMap[Consts.TAG_PRE] == self.preFencePendingDepth;
      if (codeOwnsFence && !enterIsLiteral) {
        self.preFencePending = false;
      } else if (tagId != Consts.TAG_PRE
          && (!suppressesFormattingInPre(tagId) || enterIsLiteral)) {
        if (enterIsLiteral && codeOwnsFence) {
          String language = getLanguageFromAttributes(node);
          if (!language.isEmpty()) {
            self.preFenceLang = language;
          }
        }
        flushPreFence(self);
        if (tagId == Consts.TAG_CODE && !enterIsLiteral) {
          output = null;
        }
      }
    }

    if (!self.plainText
        && self.hardBreakEnd == self.buffer.length()
        && !enterIsLiteral
        && (tagId == Consts.TAG_P
            || tagId == Consts.TAG_DIV
            || tagId == Consts.TAG_PRE
            || tagId == Consts.TAG_TABLE)
        && self.depthMap[Consts.TAG_LI] > 0
        && !self.inTableCell()
        && self.depthMap[Consts.TAG_PRE] <= (tagId == Consts.TAG_PRE ? 1 : 0)) {
      output = prependListBreak(self, output);
    }

    if ((self.cleanFlags & ConvertState.CLEAN_EMPTY_IMAGES) != 0
        && tagId == Consts.TAG_IMG) {
      String alt = node.attributes.get("alt");
      if (alt == null || allEcmascriptWhitespace(alt)) {
        self.lastNodeIsInline = node.isInline;
        return;
      }
    }

    int[] nodeSpacing = override != null ? spacingOverride(override) : null;
    if (nodeSpacing == null) {
      nodeSpacing = node.spacing;
    }
    int[] captionSpacing =
        tagId == Consts.TAG_FIGCAPTION
            ? (override != null && spacingOverride(override) != null
                ? spacingOverride(override)
                : Consts.DEFAULT_BLOCK_SPACING)
            : Consts.NO_SPACING;
    int[] config = calculateNewLineConfig(self, tagId, nodeSpacing, true);

    boolean captionEnter =
        tagId == Consts.TAG_FIGCAPTION && self.depthMap[Consts.TAG_PRE] == 0;
    if (captionEnter) {
      ConvertState.CaptionFrame frame = new ConvertState.CaptionFrame();
      frame.state = ConvertState.CAPTION_PENDING;
      if (figcaptionOwnsBlockSpacing(self)) {
        frame.spacing = new int[] {captionSpacing[0], captionSpacing[1]};
      } else {
        frame.spacing = new int[] {0, 0};
      }
      self.captionFrames.add(frame);
    }
    boolean captionActive =
        self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE || !self.captionFrames.isEmpty();
    boolean captionVisibleOutput =
        captionActive && output != null && Chars.hasVisible(output);
    boolean defaultInlineMarker =
        !enterIsLiteral && captionVisibleOutput && inlineMarkerType(tagId) != 0;
    boolean tentativeCaptionOutput =
        defaultInlineMarker || (!enterIsLiteral && tagId == Consts.TAG_A);
    if (captionVisibleOutput || tagId == Consts.TAG_BLOCKQUOTE) {
      expandDeferredCaptionBreaks(self);
    }
    boolean consumesCaptionBoundary =
        (captionVisibleOutput || tagId == Consts.TAG_BLOCKQUOTE)
            && prepareCaptionBoundary(
                self, tagId != Consts.TAG_BLOCKQUOTE && tagId != Consts.TAG_LI);
    if (captionVisibleOutput
        && (tagId != Consts.TAG_BLOCKQUOTE || enterIsLiteral)) {
      int mode =
          captionEnter && enterIsLiteral
              ? CAPTION_MATERIALIZE_EXPLICIT_COMMIT
              : (tentativeCaptionOutput
                  ? CAPTION_MATERIALIZE_TENTATIVE
                  : CAPTION_MATERIALIZE_COMMIT);
      materializeCaptionFrames(self, mode);
    }
    int configuredNewLines =
        quoteAtStart(self) || consumesCaptionBoundary || captionEnter ? 0 : config[0];

    if (!self.plainText && ConvertState.isRawHtmlBlockTag(tagId)) {
      enterRawHtmlBoundary(self, enterIsLiteral, output != null, configuredNewLines);
    }

    if (tagId == Consts.TAG_BR
        && !enterIsLiteral
        && self.depthMap[Consts.TAG_PRE] == 0
        && output != null
        && output.endsWith("\n")) {
      if (self.plainText && self.depthMap[Consts.TAG_TABLE] == 0) {
        trimTrailingBlanks(self);
      } else {
        trimTrailingSpaces(self);
      }
      if (output.equals("\n") && endsWith(self.buffer, "\n\n")) {
        output = null;
      }
    }
    if (!self.plainText
        && !enterIsLiteral
        && tagId == Consts.TAG_BR
        && configuredNewLines == 0
        && output != null
        && !output.isEmpty()
        && !Chars.hasVisible(output)
        && tryDeferCaptionBreak(self, output)) {
      self.pendingInlineWhitespace = false;
      self.lastTextNodeContainsWhitespace = false;
      self.hasLastTextNode = false;
      self.lastNodeIsInline = node.isInline;
      return;
    }
    if (self.plainText
        && !enterIsLiteral
        && tagId == Consts.TAG_BR
        && configuredNewLines == 0
        && output != null
        && !Chars.hasVisible(output)) {
      for (ConvertState.CaptionFrame frame : self.captionFrames) {
        if (frame.state != ConvertState.CAPTION_COMMITTED) {
          frame.hasInternalBreak = true;
        }
      }
    }

    if (tagId == Consts.TAG_LI && !self.plainText && !self.inTableCell()) {
      self.listRulePending = false;
      int outputStart = self.buffer.length();
      writeOutput(self, true, node.isInline, configuredNewLines, output, enterIsLiteral);
      recordItemMarker(self, node.index, outputStart);
      return;
    }

    int outputStart = self.buffer.length();
    writeOutput(self, true, node.isInline, configuredNewLines, output, enterIsLiteral);

    if (tagId == Consts.TAG_IMG
        && !enterIsLiteral
        && output != null
        && self.buffer.length() > outputStart
        && self.lastContentCacheLen == output.length()
        && (!self.plainText || Chars.hasVisible(output))) {
      markRenderedChildContent(self);
    }

    settleEmptyTextPending(
        self,
        outputStart,
        tagId == Consts.TAG_A
            || tagId == Consts.TAG_CODE
            || inlineMarkerType(tagId) != 0);

    // The blank line that re-enables Markdown must be looked for inside the region.
    if (!self.plainText && ConvertState.isRawHtmlBlockTag(tagId) && self.rawHtmlBlockDepth() == 1) {
      self.rawHtmlMarkdown = false;
      self.rawHtmlScannedTo = self.buffer.length();
    }

    if (!self.plainText && !enterIsLiteral && tagId == Consts.TAG_BLOCKQUOTE) {
      boolean rawHtmlQuoteBoundary =
          self.pendingRawHtmlBoundary && !enterIsLiteral && tagId == Consts.TAG_BLOCKQUOTE;
      if (!self.blockquotes.isEmpty()
          && endsWith(self.buffer, "\n\n")
          && (!rawHtmlQuoteBoundary || endsWith(self.buffer, "\n\n\n"))) {
        truncateBuffer(self, self.buffer.length() - 1);
        for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
          frame.contentStart = Math.min(frame.contentStart, self.buffer.length());
        }
      }
      if (self.pendingRawHtmlBoundary) {
        finishRawHtmlBlock(self, ">", false);
      }
      self.blockquotes.add(
          new ConvertState.BlockquoteFrame(self.buffer.length(), self.listIndent.toString()));
    }

    if (!enterIsLiteral && tagId == Consts.TAG_CODE) {
      if (self.depthMap[Consts.TAG_PRE] == 0) {
        if (!self.inRawHtmlBlock() && output != null) {
          boolean openerEmitted =
              self.buffer.length() > outputStart && endsWith(self.buffer, output);
          int contentStart = self.buffer.length();
          ConvertState.CodeSpanState span = new ConvertState.CodeSpanState();
          span.depth = node.depth;
          span.outputStart = openerEmitted ? contentStart - output.length() : contentStart;
          span.contentStart = contentStart;
          span.openerEmitted = openerEmitted;
          span.exhausted = false;
          self.codeSpans.add(span);
        }
      } else if (self.preFenceOwnerDepth == 0
          && !self.inTableCell()
          && output != null
          && self.buffer.length() > outputStart
          && endsWith(self.buffer, output)) {
        int fenceStart = self.buffer.length() - output.length();
        String codeLanguage = getLanguageFromAttributes(node);
        String language =
            codeLanguage.isEmpty()
                ? (self.preFenceLang == null ? "" : self.preFenceLang)
                : codeLanguage;
        startCodeFence(self, fenceStart, self.buffer.length(), language, self.listIndent.toString());
        self.preFenceOwnerDepth = self.depthMap[Consts.TAG_PRE];
      }
    }

    if (tagId == Consts.TAG_A && !self.plainText) {
      boolean exitIsOverridden = override != null && override.exit() != null;
      boolean rawHtmlLinkOpen =
          !exitIsOverridden
              && self.inRawHtmlBlock()
              && self.buffer.length() > outputStart
              && output != null
              && !output.isEmpty()
              && endsWith(self.buffer, output);
      String href = node.attributes.getBit(Consts.ATTR_HREF);
      if (href != null) {
        boolean emittedBracket =
            output != null
                && output.endsWith("[")
                && self.buffer.length() > outputStart;
        int bracketPos = emittedBracket ? self.buffer.length() - 1 : self.buffer.length();
        beginLink(self, bracketPos, false, rawHtmlLinkOpen);
        self.link.bracketEmitted = emittedBracket;
        prepareStreamingLink(self, node, emittedBracket && !exitIsOverridden);
      } else {
        beginLink(self, self.buffer.length(), false, rawHtmlLinkOpen);
        prepareStreamingLink(self, node, false);
      }
      if (!self.openMarkers.isEmpty()
          && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) == 0
          && output != null
          && Chars.hasVisible(output)) {
        self.openMarkers.clear();
      }
      if (!node.isInline && !self.openMarkers.isEmpty()) {
        self.openMarkers.clear();
      }
      return;
    }

    settleEmptyTextPending(self, outputStart, inlineMarkerType(tagId) != 0);
    recordEnterMarker(self, tagId, output, outputStart, false);
    if (!node.isInline && !self.openMarkers.isEmpty()) {
      self.openMarkers.clear();
    }

    // Clean: remember where the heading text starts so its slug can be collected on exit.
    if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0
        && tagId >= Consts.TAG_H1
        && tagId <= Consts.TAG_H6
        && self.depthMap[Consts.TAG_A] == 0) {
      self.inHeading = true;
      self.headingBufferStart = self.buffer.length();
    }
  }

  private static boolean endsWith(StringBuilder buffer, String suffix) {
    int len = buffer.length();
    int suffixLen = suffix.length();
    return suffixLen <= len && Chars.regionEquals(buffer, len - suffixLen, len, suffix);
  }

  private static boolean allEcmascriptWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!isEcmascriptWhitespace(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static int[] spacingOverride(TagOverride override) {
    if (override.spacingBefore() == 0 && override.spacingAfter() == 0) {
      return null;
    }
    return new int[] {override.spacingBefore() & 0xFF, override.spacingAfter() & 0xFF};
  }

  static void emitExitElement(ConvertState self, ElementNode node) {
    int tagId = node.tagId;
    boolean isInline = node.isInline;
    if (tagId == Consts.TAG_LI) {
      self.listRulePending = false;
    }
    if (node.excludedFromMarkdown
        || (node.enterSkipped
            && (tagId != Consts.TAG_PRE
                || self.preFenceOwnerDepth != self.depthMap[Consts.TAG_PRE]))) {
      self.lastNodeIsInline = isInline;
      return;
    }
    if (self.htmlFormat) {
      HtmlOutputOps.emitHtmlExit(self, node);
      self.lastNodeIsInline = isInline;
      return;
    }
    boolean ownsPreFence = tagId == Consts.TAG_PRE;
    boolean closesOwnPreFence =
        tagId == Consts.TAG_PRE && self.preFenceOwnerDepth == self.depthMap[Consts.TAG_PRE];
    TagOverride override = self.hasTagOverrides ? self.overrideForNode(node) : null;
    boolean rawHtmlAnchor =
        tagId == Consts.TAG_A
            && self.inRawHtmlBlock()
            && (override == null || override.enter() == null);
    String output = null;
    boolean hasOverride = false;
    if (override != null && override.exit() != null) {
      output = override.exit();
      hasOverride = true;
    }
    int cellSpan = 1;
    if ((tagId == Consts.TAG_TH || tagId == Consts.TAG_TD)
        && self.depthMap[Consts.TAG_TABLE] <= 1) {
      int want = cellSpan(node);
      int room = Math.max(0, self.tableColumnCap - self.tableCurrentRowCells);
      int span = Math.min(want, room);
      if (span != want) {
        self.truncated = true;
      }
      self.tableCurrentRowCells += span;
      cellSpan = span;
    }
    String tableSeparator = null;

    if (!hasOverride) {
      if (tagId == Consts.TAG_TR && !self.plainText) {
        if (self.tableRowOpenerPending && self.depthMap[Consts.TAG_TABLE] <= 1) {
          self.tableRowOpenerPending = false;
        } else if (!self.tableRenderedTable && self.depthMap[Consts.TAG_TABLE] <= 1) {
          self.tableRenderedTable = true;
          int colCount = Math.max(self.tableCurrentRowCells, self.tableColumnAlignments.size());
          String indent = self.depthMap[Consts.TAG_LI] > 0 ? self.listIndent.toString() : "";
          StringBuilder sep = new StringBuilder();
          sep.append(" |\n").append(indent).append('|');
          for (int i = 0; i < colCount; i++) {
            int align =
                i < self.tableColumnAlignments.size()
                    ? self.tableColumnAlignments.get(i)
                    : 0;
            sep.append(' ');
            if (align == 1) {
              sep.append(":---");
            } else if (align == 2) {
              sep.append(":---:");
            } else if (align == 3) {
              sep.append("---:");
            } else {
              sep.append("---");
            }
            sep.append(" |");
          }
          self.tableHeaderCells = colCount;
          tableSeparator = sep.toString();
        } else {
          output = getExitOutput(self, node, cellSpan);
        }
      } else if (self.plainText || tagId != Consts.TAG_A || rawHtmlAnchor) {
        output = getExitOutput(self, node, cellSpan);
      }
    }

    if (!self.plainText && self.preFencePending && tagId != Consts.TAG_PRE && hasOverride) {
      flushPreFence(self);
    }

    ConvertState.CodeSpanState closingCodeSpan = null;
    if (tagId == Consts.TAG_CODE
        && self.depthMap[Consts.TAG_PRE] == 0
        && !self.inRawHtmlBlock()
        && !self.codeSpans.isEmpty()) {
      ConvertState.CodeSpanState top = self.codeSpans.get(self.codeSpans.size() - 1);
      if (top.depth == node.depth) {
        closingCodeSpan = self.codeSpans.remove(self.codeSpans.size() - 1);
      }
    }

    if (!self.plainText && tagId == Consts.TAG_BLOCKQUOTE) {
      if (!self.blockquotes.isEmpty()) {
        finalizeBlockquote(self);
      }
      self.pendingRawHtmlBoundary = false;
    }

    if (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6 && self.depthMap[Consts.TAG_A] == 0) {
      if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && self.inHeading) {
        // The recorded start can sit past the buffer end when a following
        // element that emits nothing trimmed the heading's trailing space.
        if (self.headingBufferStart <= self.buffer.length()) {
          String slug = Url.slugifyHeading(self.buffer.substring(self.headingBufferStart));
          if (!slug.isEmpty()) {
            self.headingSlugs.add(slug);
          }
        }
        self.inHeading = false;
      }
      if (!self.plainText && !self.inTableCell()) {
        escapeTrailingHeadingHashes(self);
      }
    }

    if (tagId == Consts.TAG_A && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) == 0) {
      expandDeferredCaptionBreaks(self);
    }

    if (tagId == Consts.TAG_A
        && !self.plainText
        && !rawHtmlAnchor
        && !hasOverride
        && tableSeparator == null
        && self.depthMap[Consts.TAG_PRE] == 0) {
      int[] config = calculateNewLineConfig(self, tagId, effectiveSpacing(node, override), false);
      writeLinkExit(self, node, isInline, config[1]);
      return;
    }

    if (!hasOverride
        && !self.plainText
        && tagId == Consts.TAG_HR
        && !self.inTableCell()
        && self.depthMap[Consts.TAG_LI] > 0) {
      self.listRulePending = true;
    }

    int[] spacing = effectiveSpacing(node, override);
    int[] config = calculateNewLineConfig(self, tagId, spacing, false);

    boolean captionExit =
        tagId == Consts.TAG_FIGCAPTION && self.depthMap[Consts.TAG_PRE] == 0;
    boolean captionActive =
        captionExit
            || self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE
            || !self.captionFrames.isEmpty();
    String captionCandidate = tableSeparator != null ? tableSeparator : output;
    boolean captionVisibleOutput =
        captionActive && captionCandidate != null && Chars.hasVisible(captionCandidate);
    boolean defaultInlineMarker =
        !hasOverride && captionVisibleOutput && inlineMarkerType(tagId) != 0;
    boolean consumesCaptionBoundary = false;
    if (!captionExit) {
      if (captionVisibleOutput) {
        expandDeferredCaptionBreaks(self);
        consumesCaptionBoundary =
            prepareCaptionBoundary(
                self, tagId != Consts.TAG_BLOCKQUOTE && tagId != Consts.TAG_LI);
        materializeCaptionFrames(
            self,
            defaultInlineMarker ? CAPTION_MATERIALIZE_TENTATIVE : CAPTION_MATERIALIZE_COMMIT);
      }
    } else if (hasOverride && captionVisibleOutput) {
      expandDeferredCaptionBreaks(self);
      consumesCaptionBoundary = prepareCaptionBoundary(self, true);
      materializeCaptionFrames(self, CAPTION_MATERIALIZE_COMMIT);
    }

    int captionExitSpacing = 0;
    if (captionExit) {
      expandDeferredCaptionBreaksUntil(
          self, Math.max(0, self.captionFrames.size() - 1));
    }
    if (captionExit && !self.captionFrames.isEmpty()) {
      ConvertState.CaptionFrame frame = self.captionFrames.remove(self.captionFrames.size() - 1);
      ConvertState.DeferredBreakRun deferredBreaks = frame.deferredBreaks;
      if (frame.state == ConvertState.CAPTION_PENDING) {
        if (!hasOverride) {
          output = null;
        }
      } else if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        boolean committed = false;
        for (int i = frame.tentativeContentStart; i < self.buffer.length(); i++) {
          char c = self.buffer.charAt(i);
          if (!Scan.isWhitespace(c) && c != '*' && c != '~' && c != '`' && c != '"') {
            committed = true;
            break;
          }
        }
        if (committed) {
          captionExitSpacing = frame.spacing[1];
        } else {
          truncateBuffer(self, frame.tentativeOutputStart);
          if (frame.tentativeRestoreSpace) {
            self.buffer.append(' ');
            self.lastContentCacheLen = 1;
          } else {
            self.lastContentCacheLen = 0;
          }
          if (!hasOverride) {
            output = null;
          }
        }
      } else {
        captionExitSpacing = frame.spacing[1];
      }
      writeDeferredBreakRun(self, deferredBreaks);
    }

    int newLines;
    if (captionExit || consumesCaptionBoundary) {
      newLines = 0;
    } else if (tagId == Consts.TAG_HR && !self.blockquotes.isEmpty()) {
      newLines = Math.min(1, config[1]);
    } else {
      newLines = config[1];
    }
    boolean codeSpanMarker =
        self.depthMap[Consts.TAG_PRE] == 0 && !self.inRawHtmlBlock();
    int markerTag = (!hasOverride && (tagId != Consts.TAG_CODE || codeSpanMarker)) ? tagId : -1;
    if (dropEmptyMarkerPair(self, markerTag, output, isInline)) {
      if (tagId == Consts.TAG_LI) {
        self.listRulePending = false;
        resolveItemMarker(self, true);
      }
      return;
    }
    releaseMarkersOnExit(self, hasOverride || !isInline, output);
    if (closingCodeSpan != null && !hasOverride) {
      if (closingCodeSpan.openerEmitted
          && closingCodeSpan.exhausted
          && self.buffer.length() == closingCodeSpan.contentStart) {
        truncateBuffer(self, closingCodeSpan.outputStart);
        output = null;
      } else if (closingCodeSpan.openerEmitted) {
        output = finalizeCodeSpan(self, closingCodeSpan);
      } else {
        output = null;
      }
    }
    if (!hasOverride && closesOwnPreFence) {
      String delimiter = finalizeCodeFence(self);
      if (delimiter != null && output != null) {
        int at = output.indexOf(Consts.MARKDOWN_CODE_BLOCK);
        if (at >= 0) {
          output =
              output.substring(0, at)
                  + delimiter
                  + output.substring(at + Consts.MARKDOWN_CODE_BLOCK.length());
        }
      }
    }
    if (!self.plainText
        && ConvertState.isRawHtmlBlockTag(tagId)
        && !self.inTableCell()
        && self.depthMap[Consts.TAG_PRE] == 0) {
      exitRawHtmlBoundary(self, hasOverride, output != null);
    }

    int outputStart = self.buffer.length();
    writeOutput(self, false, isInline, newLines, tableSeparator != null ? tableSeparator : output,
        false);
    if (consumesCaptionBoundary
        && (!isInline || tagId == Consts.TAG_IMG || tagId == Consts.TAG_BR)) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    }
    if (captionExitSpacing > 0) {
      setCaptionBoundaryPending(self, captionExitSpacing);
    }
    settleEmptyTextPending(self, outputStart, false);

    if (ownsPreFence) {
      if (closesOwnPreFence) {
        self.lastTextNodeContainsWhitespace = false;
        self.hasLastTextNode = false;
        self.preFenceOwnerDepth = 0;
      }
      boolean closesPendingPre = self.preFencePendingDepth == self.depthMap[Consts.TAG_PRE];
      if (closesPendingPre) {
        self.preFencePending = false;
      }
      if (closesPendingPre || closesOwnPreFence) {
        self.preFenceLang = "";
      }
    }

    if (tagId == Consts.TAG_LI) {
      self.listRulePending = false;
      resolveItemMarker(self, true);
    }
  }

  private static int[] effectiveSpacing(ElementNode node, TagOverride override) {
    int[] nodeSpacing = override != null ? spacingOverride(override) : null;
    return nodeSpacing != null ? nodeSpacing : node.spacing;
  }

  // === Empty item guard ===

  private static void recordItemMarker(ConvertState self, int index, int outputStart) {
    if (index != 0) {
      self.emptyItemHazard = false;
      return;
    }
    int start = Math.min(outputStart, self.buffer.length());
    int lineStart = start;
    for (int i = self.buffer.length() - 1; i >= start; i--) {
      if (self.buffer.charAt(i) == '\n') {
        lineStart = i + 1;
        break;
      }
    }
    boolean startsDocument = lineStart == 0 && !self.hasStreamedOutput;
    char lineEnding = lineStart > 0 ? self.buffer.charAt(lineStart - 1) : 0;
    boolean blankAbove;
    if (lineEnding == '\n') {
      blankAbove = lineStart >= 2 && self.buffer.charAt(lineStart - 2) == '\n';
    } else if (lineEnding == '\r') {
      blankAbove =
          lineStart >= 2
              && (self.buffer.charAt(lineStart - 2) == '\n'
                  || self.buffer.charAt(lineStart - 2) == '\r');
    } else {
      blankAbove = true;
    }
    self.emptyItemHazard = !startsDocument && !blankAbove;
    self.emptyItemAfterCr = lineEnding == '\r';
    self.emptyItemLineStart = lineStart;
    self.emptyItemLen = self.buffer.length();
  }

  static void settleItemMarker(ConvertState self) {
    if (self.buffer.length() > self.emptyItemLen
        && self.buffer.length() > 0
        && !Scan.isWhitespace(self.buffer.charAt(self.buffer.length() - 1))
        && self.openMarkers.isEmpty()
        && self.codeSpans.isEmpty()
        && self.codeFence == null
        && self.depthMap[Consts.TAG_A] == 0
        && firstTentativeCaptionStart(self) < 0
        && (self.blockquotes.isEmpty()
            || self.blockquotes.get(self.blockquotes.size() - 1).contentStart
                <= self.emptyItemLineStart)) {
      resolveItemMarker(self, true);
    }
  }

  static void resolveItemMarker(ConvertState self, boolean atExit) {
    if (!self.emptyItemHazard) {
      return;
    }
    int end = Math.min(self.emptyItemLen, self.buffer.length());
    int tailStart = end;
    while (tailStart < self.buffer.length() && self.buffer.charAt(tailStart) == ' ') {
      tailStart++;
    }
    String tail = self.buffer.substring(tailStart);
    if (tail.isEmpty() && !atExit) {
      return;
    }
    int itemContentStart = self.buffer.length() - tail.length();
    if (!atExit) {
      if (!self.openMarkers.isEmpty()) {
        ConvertState.OpenMarker marker = self.openMarkers.get(0);
        if (marker.outputStart >= end && marker.outputStart <= itemContentStart) {
          return;
        }
      }
      int tentative = firstTentativeCaptionStart(self);
      if (tentative >= end && tentative <= itemContentStart) {
        return;
      }
    }
    if (!tail.isEmpty() && tail.charAt(0) != '\n' && tail.charAt(0) != '\r') {
      self.emptyItemHazard = false;
      return;
    }
    if (!atExit) {
      return;
    }
    self.emptyItemHazard = false;
    if (tail.isEmpty()
        && self.emptyItemAfterCr
        && self.link.open
        && self.link.bracketEmitted) {
      return;
    }
    boolean breakAtEnd = self.hardBreakEnd == self.buffer.length();
    int at = Math.max(0, Math.min(self.emptyItemLineStart, self.buffer.length()));
    boolean spaced =
        self.emptyItemAfterCr
            && at < self.buffer.length()
            && self.buffer.charAt(at) == ' '
            && (at + 1 >= self.buffer.length() || self.buffer.charAt(at + 1) != ' ');
    int replaced = spaced ? 1 : 0;
    String separator = self.emptyItemAfterCr ? "\n\n" : "\n";
    int removeEnd = Math.min(at + replaced, self.buffer.length());
    if (removeEnd > at) {
      self.buffer.delete(at, removeEnd);
    }
    self.buffer.insert(at, separator);
    shiftOffsetsAtOrAfter(self, at, separator.length() - replaced);
    if (breakAtEnd) {
      self.hardBreakEnd = self.buffer.length();
    }
    self.rawHtmlScannedTo = Math.min(self.rawHtmlScannedTo, at);
    self.lastContentCacheLen = separator.length();
  }

  /** Move every cached offset at or past {@code at} by {@code amount} (may be negative). */
  private static void shiftOffsetsAtOrAfter(ConvertState self, int at, int amount) {
    if (amount == 0) {
      return;
    }
    for (ConvertState.OpenMarker marker : self.openMarkers) {
      if (marker.outputStart >= at) {
        marker.outputStart += amount;
      }
      if (marker.contentStart >= at) {
        marker.contentStart += amount;
      }
    }
    for (ConvertState.CodeSpanState span : self.codeSpans) {
      if (span.outputStart >= at) {
        span.outputStart += amount;
      }
      if (span.contentStart >= at) {
        span.contentStart += amount;
      }
    }
    if (self.codeFence != null) {
      if (self.codeFence.outputStart >= at) {
        self.codeFence.outputStart += amount;
      }
      if (self.codeFence.contentStart >= at) {
        self.codeFence.contentStart += amount;
      }
    }
    for (ConvertState.CaptionFrame frame : self.captionFrames) {
      if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        if (frame.tentativeOutputStart >= at) {
          frame.tentativeOutputStart += amount;
        }
        if (frame.tentativeContentStart >= at) {
          frame.tentativeContentStart += amount;
        }
      }
    }
    for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
      // Upstream deliberately does not shift blockquote content starts.
    }
  }

  static void flushSettledBlockquoteLines(ConvertState self) {
    // Blockquote frame materialisation is part of the full output port; the
    // queue is empty until frames are created.
  }

  // === Streaming ===

  /** Slack for the separators {@code resolveUrl} may add around an origin and an href. */
  private static final int URL_RESOLVE_SLACK = 6;

  /** Start of the ASCII whitespace run ending at {@code end}. */
  private static int trailingAsciiWhitespaceStart(CharSequence buffer, int end) {
    int start = Math.min(end, buffer.length());
    while (start > 0 && Chars.isAsciiWhitespace(buffer.charAt(start - 1))) {
      start--;
    }
    return start;
  }

  /**
   * Whether {@code index} splits a surrogate pair, which no chunk or drain cut may do.
   *
   * <p>Offsets are char indices, so unlike the Rust byte offsets they cannot fall inside a
   * multi-byte character; a surrogate pair is the only remaining place a slice can land.
   */
  private static boolean splitsSurrogatePair(CharSequence buffer, int index) {
    return index > 0
        && index < buffer.length()
        && Character.isLowSurrogate(buffer.charAt(index))
        && Character.isHighSurrogate(buffer.charAt(index - 1));
  }

  /** Highest offset that still holds the whitespace run before {@code at}. */
  private static int holdBefore(CharSequence buffer, int at) {
    int index = Math.min(at, buffer.length());
    while (splitsSurrogatePair(buffer, index)) {
      index--;
    }
    return trailingAsciiWhitespaceStart(buffer, index);
  }

  /** Highest offset that still keeps the two bytes before {@code at}. */
  private static int keepTwoBefore(CharSequence buffer, int at) {
    int index = Math.max(0, Math.min(at, buffer.length()) - 2);
    while (splitsSurrogatePair(buffer, index)) {
      index--;
    }
    return index;
  }

  /**
   * Whether the close of {@code link} can still rewrite the bytes before its bracket, so the
   * stream must hold them back.
   */
  private static boolean linkHoldRequired(ConvertState self, ConvertState.LinkOutputState link) {
    if (!link.open || link.holdReleased) {
      return false;
    }
    if (link.holdForever || link.emptyTextPending) {
      return true;
    }
    if (!self.openMarkers.isEmpty() || !self.codeSpans.isEmpty()) {
      return true;
    }
    int textStart = link.bracketPos + 1;
    int textEnd = trailingAsciiWhitespaceStart(self.buffer, self.buffer.length());
    return textEnd - textStart <= link.urlMaxLen;
  }

  /** Earliest bracket position among open links the stream still holds. */
  private static int openLinkHoldFloor(ConvertState self) {
    int floor = Integer.MAX_VALUE;
    if (linkHoldRequired(self, self.link)) {
      floor = self.link.bracketPos;
    }
    for (ConvertState.LinkOutputState parent : self.parentLinks) {
      if (linkHoldRequired(self, parent)) {
        floor = Math.min(floor, parent.bracketPos);
      }
    }
    return floor == Integer.MAX_VALUE ? -1 : floor;
  }

  /** Records the link bookkeeping that only streaming needs. */
  static void prepareStreamingLink(
      ConvertState self, ElementNode node, boolean rewriteAnchor) {
    if (!self.streaming) {
      return;
    }
    String href = node.attributes.getBit(Consts.ATTR_HREF);
    boolean pinsOutput =
        rewriteAnchor
            && href != null
            && href.startsWith("#")
            && (((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && href.length() > 1)
                || ((self.cleanFlags & ConvertState.CLEAN_SELF_LINK_HEADINGS) != 0
                    && self.inHeading()));
    self.link.pinsOutput = pinsOutput;
    self.link.emptyTextPending =
        rewriteAnchor && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) != 0;
    self.link.holdForever = pinsOutput;
    self.link.holdReleased = !rewriteAnchor;
    int originLen = self.origin == null ? 0 : self.origin.length();
    self.link.urlMaxLen = href == null ? 0 : URL_RESOLVE_SLACK + originLen + 1 + href.length();
  }

  private static boolean isHeadingRunChar(char c) {
    return c == '#' || c == ' ' || c == '\t';
  }

  /**
   * The bytes that are already final. Mirrors {@code get_markdown_chunk}: hold back trailing
   * whitespace and everything a still-open construct can rewrite, then yield what precedes it.
   */
  static String getMarkdownChunk(ConvertState self) {
    if (self.htmlFormat) {
      int len = self.buffer.length();
      if (len > 0) {
        self.flushedTail[1] = self.buffer.charAt(len - 1);
      }
      String out = self.buffer.toString();
      self.buffer.setLength(0);
      self.lastYieldedLength = 0;
      return out;
    }
    if (!self.plainText && (self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0) {
      return "";
    }
    flushSettledBlockquoteLines(self);

    int bufLen = self.buffer.length();
    boolean inPre = self.depthMap[Consts.TAG_PRE] != 0 && self.preFenceOwnerDepth != 0;
    int stableEnd = trailingAsciiWhitespaceStart(self.buffer, bufLen);
    if (inPre && !self.lastTextNodeContainsWhitespace) {
      // Other trailing spaces inside <pre> are significant code. A line-leading run is the
      // exception: list continuation indentation can still be replaced by its list marker.
      int spaces = bufLen;
      while (spaces > 0 && self.buffer.charAt(spaces - 1) == ' ') {
        spaces--;
      }
      stableEnd = spaces;
      if (stableEnd > 0 && self.buffer.charAt(stableEnd - 1) != '\n') {
        stableEnd = bufLen;
      }
    }

    int leading;
    if (self.preserveLeadingWhitespace || self.hasStreamedOutput) {
      leading = 0;
    } else {
      int start = 0;
      while (start < bufLen && Chars.isUnicodeWhitespace(self.buffer.charAt(start))) {
        start++;
      }
      leading = start;
    }

    if (!self.openMarkers.isEmpty()) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, self.openMarkers.get(0).outputStart));
    }
    int tentativeCaption = firstTentativeCaptionStart(self);
    if (tentativeCaption >= 0) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, tentativeCaption));
    }
    if (!self.codeSpans.isEmpty()) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, self.codeSpans.get(0).outputStart));
    }
    if (self.codeFence != null) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, self.codeFence.outputStart));
    }
    if (!self.blockquotes.isEmpty()) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, self.blockquotes.get(0).contentStart));
    }
    int linkFloor = openLinkHoldFloor(self);
    if (linkFloor >= 0) {
      stableEnd = Math.min(stableEnd, holdBefore(self.buffer, linkFloor));
    }
    if (self.emptyItemHazard) {
      stableEnd = Math.min(stableEnd, keepTwoBefore(self.buffer, self.emptyItemLineStart));
    }
    if (self.inHeading()) {
      int run = Math.min(stableEnd, bufLen);
      while (run > 0 && isHeadingRunChar(self.buffer.charAt(run - 1))) {
        run--;
      }
      stableEnd = run;
    }

    int start = Math.max(self.lastYieldedLength, leading);
    while (stableEnd > start && splitsSurrogatePair(self.buffer, stableEnd)) {
      stableEnd--;
    }
    while (start > 0 && splitsSurrogatePair(self.buffer, start)) {
      start--;
    }
    if (start >= stableEnd) {
      drainStreamedPrefix(self);
      return "";
    }
    String out = self.buffer.substring(start, stableEnd);
    self.hasStreamedOutput = true;
    self.lastYieldedLength = stableEnd;
    if (self.link.open && stableEnd > self.link.bracketPos) {
      self.link.holdReleased = true;
    }
    for (ConvertState.LinkOutputState parent : self.parentLinks) {
      if (stableEnd > parent.bracketPos) {
        parent.holdReleased = true;
      }
    }
    drainStreamedPrefix(self);
    return out;
  }

  /**
   * Free already-yielded output so streaming memory stays O(window), not O(document).
   *
   * <p>Mirrors {@code drain_streamed_prefix}: keep the tail a late rewrite may still touch and
   * never drop the bytes before an open link bracket or another live offset, then rebase every
   * stored offset. Fragment cleaning keeps the whole document and never reaches here.
   */
  private static void drainStreamedPrefix(ConvertState self) {
    if (self.disableDrain || self.retainsWholeDocument()) {
      return;
    }
    // Keep the tail a late rewrite may still touch, and never drop the `[` of an open link.
    // Formatting also inspects the last two bytes, and row separation may need the widest GFM
    // list marker: up to three spaces, nine digits, a delimiter and a trailing space.
    int markerTail = 14 + self.listIndent.length();
    int retainedTailStart =
        Math.max(0, self.buffer.length() - Math.max(self.lastContentCacheLen, markerTail));
    while (retainedTailStart > 0 && splitsSurrogatePair(self.buffer, retainedTailStart)) {
      retainedTailStart--;
    }
    int drainEnd = Math.min(self.lastYieldedLength, retainedTailStart);

    // Preserve newline context before every live reach-back point.
    int bracketFloor = openLinkHoldFloor(self);
    if (bracketFloor >= 0) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, bracketFloor));
    }
    if (!self.openMarkers.isEmpty()) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, self.openMarkers.get(0).outputStart));
    }
    if (!self.codeSpans.isEmpty()) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, self.codeSpans.get(0).outputStart));
    }
    if (self.codeFence != null) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, self.codeFence.outputStart));
    }
    if (!self.blockquotes.isEmpty()) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, self.blockquotes.get(0).contentStart));
    }

    // Settle a pending marker-line guard when the item's first content already answers it.
    if (self.buffer.length() > 0
        && !Chars.isAsciiWhitespace(self.buffer.charAt(self.buffer.length() - 1))) {
      resolveItemMarker(self, false);
    }
    if (self.emptyItemHazard) {
      drainEnd =
          Math.min(drainEnd, keepTwoBefore(self.buffer, self.emptyItemLineStart));
    }
    if (drainEnd <= 0) {
      return;
    }
    if (self.wrapWidth != 0) {
      int lastNewline = -1;
      for (int i = drainEnd - 1; i >= 0; i--) {
        if (self.buffer.charAt(i) == '\n') {
          lastNewline = i;
          break;
        }
      }
      if (lastNewline >= 0) {
        self.bufferStartColumn = drainEnd - lastNewline - 1;
      } else {
        self.bufferStartColumn += drainEnd;
      }
    }
    // The blank line that ends a raw-HTML region may sit in the bytes about to leave.
    if (self.rawHtmlScannedTo < drainEnd
        && !self.rawHtmlMarkdown
        && self.inRawHtmlBlock()) {
      trackRawHtmlMarkdownContext(self, drainEnd);
    }
    char first = self.buffer.charAt(0);
    if (drainEnd >= 2) {
      self.flushedTail[0] = self.buffer.charAt(drainEnd - 2);
      self.flushedTail[1] = self.buffer.charAt(drainEnd - 1);
    } else if (self.cutLineLeadKind == ConvertState.CUT_LEAD_UNCUT) {
      // Nothing precedes a first cut this small: shifting the document-start sentinel in would
      // invent a newline that cancels a real block separator.
      self.flushedTail[0] = 0;
      self.flushedTail[1] = first;
    } else {
      self.flushedTail[0] = self.flushedTail[1];
      self.flushedTail[1] = first;
    }
    classifyCutLine(self, drainEnd);
    if (self.link.open && drainEnd > self.link.bracketPos) {
      self.link.holdReleased = true;
    }
    for (ConvertState.LinkOutputState parent : self.parentLinks) {
      if (drainEnd > parent.bracketPos) {
        parent.holdReleased = true;
      }
    }
    self.buffer.delete(0, drainEnd);
    noteBufferDrain(self, drainEnd);
    self.lastYieldedLength -= drainEnd;
    self.link.bracketPos = Math.max(0, self.link.bracketPos - drainEnd);
    for (ConvertState.LinkOutputState parent : self.parentLinks) {
      parent.bracketPos = Math.max(0, parent.bracketPos - drainEnd);
    }
    for (ConvertState.OpenMarker marker : self.openMarkers) {
      marker.outputStart -= drainEnd;
      marker.contentStart -= drainEnd;
    }
    for (ConvertState.CaptionFrame frame : self.captionFrames) {
      if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        frame.tentativeOutputStart -= drainEnd;
        frame.tentativeContentStart -= drainEnd;
      }
    }
    for (ConvertState.CodeSpanState span : self.codeSpans) {
      span.outputStart -= drainEnd;
      span.contentStart -= drainEnd;
    }
    if (self.codeFence != null) {
      self.codeFence.outputStart -= drainEnd;
      self.codeFence.contentStart -= drainEnd;
    }
    for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
      frame.contentStart -= drainEnd;
    }
    self.emptyItemLineStart = Math.max(0, self.emptyItemLineStart - drainEnd);
    self.emptyItemLen = Math.max(0, self.emptyItemLen - drainEnd);
    self.rawHtmlScannedTo = Math.max(0, self.rawHtmlScannedTo - drainEnd);
    self.lineStart = Math.max(0, self.lineStart - drainEnd);
    self.lineStartScannedTo = Math.max(0, self.lineStartScannedTo - drainEnd);
  }

  /** Rebases the offset-indexed state that is not tied to a construct. */
  private static void noteBufferDrain(ConvertState self, int removed) {
    if (self.blockquoteLineScannedTo != 0) {
      self.blockquoteLineScannedTo = Math.max(0, self.blockquoteLineScannedTo - removed);
      self.blockquoteLineEnd = Math.max(0, self.blockquoteLineEnd - removed);
    }
    if (self.hardBreakEnd >= 0) {
      self.hardBreakEnd = self.hardBreakEnd >= removed ? self.hardBreakEnd - removed : -1;
    }
  }

  /**
   * What the line ending at {@code drainEnd} leads with, given what an earlier cut of the same
   * line already found. A line's leading spaces can be split across any number of drains, and
   * only three of them may precede the {@code <} suspending Markdown.
   */
  private static void classifyCutLine(ConvertState self, int drainEnd) {
    StringBuilder buffer = self.buffer;
    if (buffer.charAt(drainEnd - 1) == '\n') {
      // The cut ends the line, so the next one starts fresh at column zero.
      self.cutLineLeadKind = ConvertState.CUT_LEAD_BLANK;
      self.cutLineLeadIndent = 0;
      return;
    }
    int newline = -1;
    for (int i = drainEnd - 2; i >= 0; i--) {
      if (buffer.charAt(i) == '\n') {
        newline = i;
        break;
      }
    }
    int lineStart;
    int already;
    if (newline >= 0) {
      // The line starts inside the drained bytes, so its indent starts there.
      lineStart = newline + 1;
      already = 0;
    } else {
      switch (self.cutLineLeadKind) {
        case ConvertState.CUT_LEAD_RAW_HTML:
        case ConvertState.CUT_LEAD_ROW:
        case ConvertState.CUT_LEAD_CONTENT:
          return;
        case ConvertState.CUT_LEAD_BLANK:
          lineStart = 0;
          already = self.cutLineLeadIndent;
          break;
        default:
          lineStart = 0;
          already = 0;
          break;
      }
    }
    int own = 0;
    while (lineStart + own < drainEnd && buffer.charAt(lineStart + own) == ' ') {
      own++;
    }
    int indent = already + own;
    int next = lineStart + own;
    if (next >= drainEnd) {
      // Still nothing but spaces, so the count continues past this cut too.
      self.cutLineLeadKind = ConvertState.CUT_LEAD_BLANK;
      self.cutLineLeadIndent = Math.min(indent, ConvertState.RAW_HTML_MAX_INDENT + 1);
    } else if (buffer.charAt(next) == '<' && indent <= ConvertState.RAW_HTML_MAX_INDENT) {
      self.cutLineLeadKind = ConvertState.CUT_LEAD_RAW_HTML;
      self.cutLineLeadIndent = 0;
    } else {
      int content = next;
      while (content < drainEnd
          && (buffer.charAt(content) == ' ' || buffer.charAt(content) == '\t')) {
        content++;
      }
      if (content >= drainEnd) {
        // Blank, but with a tab in it: no space count describes this indent, so record one that
        // can never open a raw block.
        self.cutLineLeadKind = ConvertState.CUT_LEAD_BLANK;
        self.cutLineLeadIndent = ConvertState.RAW_HTML_MAX_INDENT + 1;
      } else if (buffer.charAt(content) == '|') {
        self.cutLineLeadKind = ConvertState.CUT_LEAD_ROW;
        self.cutLineLeadIndent = 0;
      } else {
        self.cutLineLeadKind = ConvertState.CUT_LEAD_CONTENT;
        self.cutLineLeadIndent = 0;
      }
    }
  }

  static String getFinalMarkdownChunk(ConvertState self) {
    if (!self.plainText
        && !self.htmlFormat
        && (self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0) {
      return getMarkdown(self);
    }
    return getMarkdownChunk(self);
  }

  // === Final output ===

  /**
   * Applies {@code clean.fragments} using the positions recorded at each link's close.
   *
   * <p>Removing only the broken link's wrappers preserves nested link text without shifting the
   * whole buffer after every match; the removals are compacted in place.
   */
  private static void applyFragmentRemovals(ConvertState self, int trimOffset) {
    self.headingSlugs.sort(Chars::compareCodePoints);

    List<ConvertState.FragmentLink> links = self.fragmentLinks;
    // A nested <a> across a block boundary reuses the outer anchor's bracket
    // position, so two entries can point at the same bracket. Rewriting either
    // risks corrupting the other's text; skip any bracket used more than once.
    int[] bracketStarts = new int[links.size()];
    for (int i = 0; i < links.size(); i++) {
      bracketStarts[i] = links.get(i).bracketStart;
    }
    Arrays.sort(bracketStarts);

    StringBuilder buffer = self.buffer;
    List<int[]> removals = new ArrayList<>();
    for (ConvertState.FragmentLink link : links) {
      if (hasAliasedBracket(bracketStarts, link.bracketStart)) {
        continue;
      }
      int bracketStart = Math.max(0, link.bracketStart - trimOffset);
      int textEnd = Math.max(0, link.textEnd - trimOffset);
      int linkEnd = Math.max(0, link.linkEnd - trimOffset);
      // A shifted buffer can leave a range that still looks like `[..]..)`
      // without belonging to this link. Only an exact `](#fragment)` tail
      // proves the entry survived every rewrite intact.
      if (linkEnd > buffer.length()
          || bracketStart >= textEnd
          || textEnd >= linkEnd
          || linkEnd - textEnd < 2
          || buffer.charAt(bracketStart) != '['
          || !Chars.startsWith(buffer, textEnd, "](")
          || buffer.charAt(linkEnd - 1) != ')') {
        continue;
      }
      // `](#fragment)` exactly, or the titled close `](#fragment "title")`
      // recorded by hasTitle: the title itself is not compared, only its shape.
      String fragment = link.fragment;
      int fragmentEnd = textEnd + 3 + fragment.length();
      boolean matched;
      if (link.hasTitle) {
        matched =
            fragmentEnd + 1 < buffer.length()
                && buffer.charAt(fragmentEnd) == ' '
                && buffer.charAt(fragmentEnd + 1) == '"'
                && buffer.charAt(linkEnd - 2) == '"'
                && Chars.regionEquals(buffer, textEnd + 3, fragmentEnd, fragment);
      } else {
        matched =
            linkEnd - textEnd == fragment.length() + 4
                && Chars.regionEquals(buffer, textEnd + 3, linkEnd - 1, fragment);
      }
      if (!matched || containsSortedHeadingSlug(self.headingSlugs, fragment)) {
        continue;
      }
      removals.add(new int[] {bracketStart, bracketStart + 1});
      removals.add(new int[] {textEnd, linkEnd});
    }

    // Links are recorded at their close, so a link lands after the ones it
    // wraps. Only nesting breaks the ascending order, so insertion sort keeps
    // the pass linear on flat documents.
    for (int index = 1; index < removals.size(); index++) {
      int[] entry = removals.get(index);
      int slot = index;
      while (slot > 0 && removals.get(slot - 1)[0] > entry[0]) {
        removals.set(slot, removals.get(slot - 1));
        slot--;
      }
      removals.set(slot, entry);
    }

    // Only deletes, so compact in place: write trails read by the chars dropped.
    int bufLen = buffer.length();
    char[] chars = new char[bufLen];
    buffer.getChars(0, bufLen, chars, 0);
    int read = 0;
    int write = 0;
    for (int[] removal : removals) {
      if (removal[1] <= read) {
        continue;
      }
      if (removal[0] > read) {
        System.arraycopy(chars, read, chars, write, removal[0] - read);
        write += removal[0] - read;
      }
      read = removal[1];
    }
    if (read > 0) {
      if (read < bufLen) {
        System.arraycopy(chars, read, chars, write, bufLen - read);
        write += bufLen - read;
      }
      buffer.setLength(0);
      buffer.append(chars, 0, write);
    }
  }

  private static boolean hasAliasedBracket(int[] sorted, int value) {
    int low = 0;
    int high = sorted.length - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (sorted[mid] < value) {
        low = mid + 1;
      } else if (sorted[mid] > value) {
        high = mid - 1;
      } else {
        return (mid > 0 && sorted[mid - 1] == value)
            || (mid + 1 < sorted.length && sorted[mid + 1] == value);
      }
    }
    return false;
  }

  private static boolean containsSortedHeadingSlug(List<String> slugs, String target) {
    int start = 0;
    int end = slugs.size();
    while (start < end) {
      int midpoint = start + (end - start) / 2;
      int order = Chars.compareCodePoints(slugs.get(midpoint), target);
      if (order < 0) {
        start = midpoint + 1;
      } else if (order > 0) {
        end = midpoint;
      } else {
        return true;
      }
    }
    return false;
  }

  /**
   * Final output: trailing ASCII whitespace, then leading whitespace unless it is content.
   * Mirrors {@code get_markdown} in {@code convert/mod.rs}.
   */
  static String getMarkdown(ConvertState self) {
    if (self.htmlFormat) {
      String out = self.buffer.toString();
      self.buffer.setLength(0);
      return out;
    }
    int end = self.buffer.length();
    while (end > 0 && Chars.isAsciiWhitespace(self.buffer.charAt(end - 1))) {
      end--;
    }
    truncateBuffer(self, end);
    int start = 0;
    if (!self.preserveLeadingWhitespace) {
      while (start < self.buffer.length() && Chars.isUnicodeWhitespace(self.buffer.charAt(start))) {
        start++;
      }
      if (start > 0) {
        self.buffer.delete(0, start);
        noteBufferDrain(self, start);
      }
    }
    if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && !self.fragmentLinks.isEmpty()) {
      applyFragmentRemovals(self, start);
    }
    String out = self.buffer.toString();
    self.buffer.setLength(0);
    return out;
  }
}
