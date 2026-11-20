/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.mdream4j.internal;

import io.github.mdream4j.TagOverride;
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
  /**
   * How many bytes of blanks and openers may follow an emphasis closer for an opener to still take
   * it back. More than a few empty elements never write that much, and without a bound a run of
   * quotes would hold a stream.
   */
  private static final int CLOSED_MARKER_REACH = 32;

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
      Consts.TAG_KBD,
      Consts.TAG_SAMP,
      Consts.TAG_VAR,
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
    int target = Math.min(len, self.buffer.length());
    // Blanks and openers that closed empty leave the closers before them as
    // they were; any other output taken back came between them and an opener.
    int lastCloserEnd =
        self.closedMarkers.isEmpty()
            ? -1
            : self.closedMarkers.get(self.closedMarkers.size() - 1)[1];
    boolean keepClosers =
        lastCloserEnd >= 0
            && lastCloserEnd <= target
            && self.buffer.length() - lastCloserEnd <= CLOSED_MARKER_REACH
            && allTransient(self.buffer, target, self.buffer.length());
    List<int[]> closers = keepClosers ? new ArrayList<>(self.closedMarkers) : null;
    noteBufferRewrite(self, target);
    if (keepClosers) {
      self.closedMarkers.clear();
      self.closedMarkers.addAll(closers);
    }
    self.buffer.setLength(target);
    // Output that grows back to a trimmed position is not what ended there.
    clampStreamPositions(self, target);
    // A truncated break run is no longer contiguous with the buffer: drop the runs entirely
    // beyond the cut and clip the one the cut lands in, or the next yield holds a stale
    // `outputEnd` past the live bytes.
    while (!self.streamingBreakRuns.isEmpty()
        && self.streamingBreakRuns.get(self.streamingBreakRuns.size() - 1).outputStart > target) {
      self.streamingBreakRuns.remove(self.streamingBreakRuns.size() - 1);
    }
    if (!self.streamingBreakRuns.isEmpty()) {
      ConvertState.StreamingBreakRun run =
          self.streamingBreakRuns.get(self.streamingBreakRuns.size() - 1);
      if (run.outputStart == target) {
        if (run.count == 1) {
          self.streamingBreakRuns.remove(self.streamingBreakRuns.size() - 1);
        } else {
          run.count--;
          self.buffer.append(run.unit);
          run.outputEnd = self.buffer.length();
        }
        return;
      }
      if (run.outputEnd > target) {
        run.outputEnd = target;
      }
    }
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

  /** Whether the buffer ends just past a list marker that is all its line holds. */
  private static boolean onItemMarkerLine(ConvertState self) {
    if (self.listIndent.length() == 0) {
      return false;
    }
    StringBuilder bytes = self.buffer;
    int end = bytes.length() - trailingSpaces(bytes);
    return end > 0 && listMarkerLineStart(self, bytes, end, pendingMarkerIndent(self));
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

  /**
   * Digits that end the buffer at a line start. The escape pass counts them as the start of an
   * ordered list marker that {@code text} may complete. After a soft break, which is what wrapping
   * writes, only a list starting at one interrupts the paragraph. After a blank line or a hard
   * break any number counts, as it does for a marker inside one text node.
   */
  private static int lineStartDigits(ConvertState self, String text) {
    StringBuilder bytes = self.buffer;
    int len = bytes.length();
    int digits = 0;
    int spaces = 0;
    int leadLen = 0;
    while (leadLen < text.length() && isAsciiDigit(text.charAt(leadLen))) {
      leadLen++;
    }
    // Whether the buffered digits and those `text` adds read as one.
    boolean one = true;
    if (leadLen > 0) {
      if (text.charAt(leadLen - 1) != '1') {
        one = false;
      } else {
        for (int i = 0; i < leadLen - 1; i++) {
          if (text.charAt(i) != '0') {
            one = false;
            break;
          }
        }
      }
    }
    for (int offset = len - 1; offset >= 0; offset--) {
      char c = bytes.charAt(offset);
      if (spaces == 0 && isAsciiDigit(c)) {
        if (digits == 9) {
          return 0;
        }
        // Only the number's final digit may be the one.
        if (digits == 0 && leadLen == 0) {
          one &= c == '1';
        } else {
          one &= c == '0';
        }
        digits++;
        continue;
      }
      if (c == ' ') {
        spaces++;
        if (spaces > self.listIndent.length() + 3) {
          return outputStartsAt(self, offset) ? digits : 0;
        }
        continue;
      }
      boolean opens;
      if (c == '\n') {
        // A blank line or the spaces or backslash of a hard break; any other byte ends the line at
        // a soft break.
        int before;
        if (offset == 0 && self.hasFlushedTail()) {
          before = self.flushedTail[1];
        } else if (offset == 0) {
          before = '\n';
        } else {
          before = bytes.charAt(offset - 1);
        }
        opens =
            ((spaces <= 3 || spaces >= self.listIndent.length())
                    && (one
                        || before == '\n'
                        || before == ' '
                        || before == '\t'
                        || before == '\\'))
                || outputStartsAt(self, offset);
      } else {
        // A list item's content opens a block, so `- 1` + `. x` nests a list.
        opens =
            ((spaces >= 1 && spaces <= 3) && listMarkerLineStart(self, bytes, offset + 1, 3))
                || outputStartsAt(self, offset + 1);
      }
      return opens ? digits : 0;
    }
    boolean opens =
        !self.hasFlushedTail()
            || (self.flushedTail[1] == '\n'
                && (one
                    || self.flushedTail[0] == '\n'
                    || self.flushedTail[0] == ' '
                    || self.flushedTail[0] == '\t'
                    || self.flushedTail[0] == '\\'));
    if (opens && (spaces <= 3 || spaces >= self.listIndent.length())) {
      return digits;
    }
    return 0;
  }

  /**
   * Offset in {@code text} of a block marker that the output trim leaves first in the document: the
   * trim removes all the whitespace before it, in {@code text} and in the buffer alike, so no
   * amount of it works as indentation there. The marker test goes first, keeping the buffer scan to
   * text that ends the blank output it reads.
   */
  private static int outputStartMarker(ConvertState self, String text) {
    if (text.isEmpty()
        || Chars.isAsciiAlphabetic(text.charAt(0))
        || self.hasStreamedOutput
        || (self.buffer.length() > 0
            && isAsciiGraphic(self.buffer.charAt(self.buffer.length() - 1)))) {
      return -1;
    }
    int lead;
    char first = text.charAt(0);
    if (isBlockMarkerChar(first)) {
      lead = 0;
    } else if ((first >= '\t' && first <= '\r') || first == ' ' || first >= 0x80) {
      int asciiLead = 0;
      while (asciiLead < text.length()) {
        char c = text.charAt(asciiLead);
        if (!((c >= '\t' && c <= '\r') || c == ' ')) {
          break;
        }
        asciiLead++;
      }
      if (asciiLead < text.length() && text.charAt(asciiLead) >= 0x80) {
        lead = 0;
        while (lead < text.length() && Chars.isUnicodeWhitespace(text.charAt(lead))) {
          lead++;
        }
      } else {
        lead = asciiLead;
      }
      if (lead >= text.length() || !isBlockMarkerChar(text.charAt(lead))) {
        return -1;
      }
    } else {
      return -1;
    }
    // A table writes a line ending as a character reference, which is content.
    if (self.depthMap[Consts.TAG_TABLE] > 0) {
      for (int i = 0; i < lead; i++) {
        char c = text.charAt(i);
        if (c == '\n' || c == '\r') {
          return -1;
        }
      }
    }
    return outputStartsAt(self, self.buffer.length()) ? lead : -1;
  }

  /**
   * Whether the output trim removes all of {@code buffer[..end]}: only whitespace is there, with no
   * output drained before it. An open blockquote writes its {@code >} ahead of that whitespace
   * later, which keeps it. Read from {@code end} back, so the content closest to it ends the scan.
   */
  private static boolean outputStartsAt(ConvertState self, int end) {
    return !self.drainedContent
        && !self.preserveLeadingWhitespace
        && self.blockquotes.isEmpty()
        && !hasVisibleUnicode(self.buffer, end);
  }

  /** Whether {@code value[0, end)} holds a char {@code str::trim_end} keeps. */
  private static boolean hasVisibleUnicode(CharSequence value, int end) {
    for (int i = end - 1; i >= 0; i--) {
      if (!Chars.isUnicodeWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isAsciiGraphic(char c) {
    return c >= 0x21 && c <= 0x7E;
  }

  private static boolean isBlockMarkerChar(char c) {
    return c == '#' || c == '-' || c == '+' || c == '>' || c == '=' || isAsciiDigit(c);
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
  private static String escapeGfmTextInto(
      ConvertState self, String text, int orderedDigits, int outputStart) {
    boolean inTable = self.depthMap[Consts.TAG_TABLE] > 0;
    boolean inLink = self.depthMap[Consts.TAG_A] > 0;
    boolean inBlockquote = self.depthMap[Consts.TAG_BLOCKQUOTE] > 0;
    // Only a leading active char reads the line's indent: an inactive first run resets it before
    // any use, so most text nodes skip the buffer scan.
    int lineIndent;
    if (outputStart >= 0) {
      lineIndent = 0;
    } else if (!text.isEmpty()
        && (ConvertState.GFM_BYTE_FLAGS[text.charAt(0) < 256 ? text.charAt(0) : 0]
                & ConvertState.GFM_TEXT_ACTIVE_BIT)
            != 0) {
      lineIndent = markdownLineIndent(self);
    } else {
      lineIndent = -1;
    }
    boolean escaped = false;
    StringBuilder out = null;
    int copiedUntil = 0;
    int index = outputStart >= 0 ? outputStart : 0;
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
    if (self.pendingEmphasis != 0) {
      pushEmphasisText(self, text, addSpace);
      return;
    }
    if (addSpace) {
      self.buffer.append(' ');
      self.lastContentCacheLen = text.length() + 1;
    } else {
      self.lastContentCacheLen = text.length();
    }
    self.buffer.append(text);
  }

  /**
   * Takes the pending closer if {@code text} would touch it. Returns the text's first char when it
   * must be written as a numeric reference, or {@code 0}.
   */
  private static int takeEmphasisReference(ConvertState self, String text) {
    int mark = self.pendingEmphasis;
    self.pendingEmphasis = 0;
    if (!text.isEmpty()
        && Chars.isAsciiAlphanumeric(text.charAt(0))
        && lastOutputByte(self) == mark) {
      return text.charAt(0);
    }
    return 0;
  }

  /** Appends {@code text} with its first char as a numeric reference. */
  private static void writeEmphasisReference(StringBuilder out, int code, String text) {
    out.append("&#").append(code).append(';').append(text, 1, text.length());
  }

  private static void pushEmphasisText(ConvertState self, String text, boolean addSpace) {
    int start = self.buffer.length();
    if (addSpace) {
      self.pendingEmphasis = 0;
      self.buffer.append(' ');
      self.buffer.append(text);
    } else {
      int code = takeEmphasisReference(self, text);
      if (code != 0) {
        writeEmphasisReference(self.buffer, code, text);
      } else {
        self.buffer.append(text);
      }
    }
    self.lastContentCacheLen = self.buffer.length() - start;
  }

  /**
   * {@code text} with its first char as a numeric reference when it would follow the pending
   * closer directly. Call it once no separator precedes {@code text}.
   */
  private static String emphasisText(ConvertState self, String text) {
    int code = takeEmphasisReference(self, text);
    if (code == 0) {
      return text;
    }
    StringBuilder referenced = new StringBuilder(text.length() + 5);
    writeEmphasisReference(referenced, code, text);
    return referenced.toString();
  }

  /** Whether an open inline code span past its cap drops everything written to it. */
  static boolean dropsCodeSpanContent(ConvertState self) {
    if (self.maxNodeBytesInt == 0 || self.codeSpans.isEmpty()) {
      return false;
    }
    ConvertState.CodeSpanState span = self.codeSpans.get(0);
    return !span.openerEmitted || span.exhausted;
  }

  /**
   * Writes content that may sit behind an open inline-code delimiter.
   *
   * <p>Tag output is atomic, so a cap cannot leave half a Markdown marker; text may be cut at a
   * character boundary. Once an append cannot fit, later content never refills the spare bytes.
   */
  private static int pushCodeSpanContent(ConvertState self, String value, boolean atomic) {
    if (value.isEmpty()) {
      return 0;
    }
    int cap = self.maxNodeBytesInt;
    if (cap == 0 || self.codeSpans.isEmpty()) {
      self.buffer.append(value);
      return value.length();
    }
    ConvertState.CodeSpanState span = self.codeSpans.get(0);
    if (!span.openerEmitted || span.exhausted) {
      self.truncated = true;
      return 0;
    }
    int held = Scan.utf8Length(self.buffer, span.contentStart, self.buffer.length());
    int room = Math.max(0, cap - held);
    int kept;
    if (atomic && Scan.utf8Length(value) > room) {
      kept = 0;
    } else {
      kept = clampToCharBoundary(value, room);
    }
    if (kept != value.length()) {
      span.exhausted = true;
      self.truncated = true;
    }
    if (kept > 0) {
      self.buffer.append(value, 0, kept);
    }
    return kept;
  }

  /** Truncates {@code value} to the longest prefix whose UTF-8 length fits {@code maxBytes}. */
  private static int clampToCharBoundary(String value, int maxBytes) {
    int bytes = 0;
    int end = 0;
    while (end < value.length()) {
      int cp = value.codePointAt(end);
      int len = ParseOps.utf8Length(cp);
      if (bytes + len > maxBytes) {
        break;
      }
      bytes += len;
      end += Character.charCount(cp);
    }
    return end;
  }

  /** Whether prose here may be hard-wrapped: not code, a table cell or a heading. */
  private static boolean canWrapHere(ConvertState self) {
    return self.depthMap[Consts.TAG_PRE] == 0
        && self.depthMap[Consts.TAG_CODE] == 0
        && self.codeSpans.isEmpty()
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
    // A separator or a break already lets the closer flank.
    if (self.pendingEmphasis != 0) {
      if (firstNeedsSpace) {
        self.pendingEmphasis = 0;
      } else {
        text = emphasisText(self, text);
      }
    }
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
      ConvertState self,
      int markerTag,
      String output,
      int outputStart,
      boolean keepsMarkers,
      boolean reopens,
      int pending) {
    int separatorEnd = self.pendingSpaceEnd;
    self.pendingSpaceEnd = Integer.MAX_VALUE;
    boolean separatorOwned = self.pendingSpaceOwned;
    self.pendingSpaceOwned = false;
    int kind = inlineMarkerType(markerTag);
    if (kind != 0
        && output != null
        && !output.isEmpty()
        && self.buffer.length() > outputStart
        && endsWith(self.buffer, output)) {
      int markerStart = self.buffer.length() - output.length();
      // A quote that began after the closer writes its prefix between the two
      // delimiters, so they do not touch.
      boolean merges =
          reopens
              && (kind == 1 || kind == 2 || kind == 4)
              && (output.equals("*") || output.equals("**"))
              && (self.blockquotes.isEmpty()
                  || self.blockquotes.get(self.blockquotes.size() - 1).contentStart < markerStart);
      // A separator moved in front of the reopened pair parts the two runs.
      ConvertState.OpenMarker last =
          self.openMarkers.isEmpty()
              ? null
              : self.openMarkers.get(self.openMarkers.size() - 1);
      if (merges
          && last != null
          && last.reopened == output.length()
          && last.contentStart == markerStart
          && !last.followsSeparator) {
        // A pair that opens with a pair of its own delimiter writes a longer
        // run, which CommonMark may read apart, so it is written as before.
        // The three delimiters count against a code span's budget as they did
        // when first written.
        int unmerged = last.reopened;
        int cache = self.lastContentCacheLen;
        truncateBuffer(self, markerStart);
        int closer = pushCodeSpanContent(self, output, true);
        int openerStart = self.buffer.length();
        int opener = pushCodeSpanContent(self, output, true);
        int inner = pushCodeSpanContent(self, output, true);
        self.lastContentCacheLen = inner == 0 ? 0 : cache;
        if (opener == 0) {
          self.openMarkers.remove(self.openMarkers.size() - 1);
        } else if (!self.openMarkers.isEmpty()) {
          ConvertState.OpenMarker reopened =
              self.openMarkers.get(self.openMarkers.size() - 1);
          reopened.reopened = 0;
          reopened.outputStart = openerStart;
          reopened.contentStart = openerStart + opener;
          // The closer stays on record in case the inner pair closes empty.
          if (closer != 0) {
            reopened.unmerged = unmerged;
            if (!self.closedMarkers.isEmpty()
                && self.closedMarkers.get(self.closedMarkers.size() - 1)[1] != markerStart) {
              self.closedMarkers.clear();
            }
            self.closedMarkers.add(new int[] {markerStart, markerStart + closer});
          }
        }
        if (inner == 0) {
          return;
        }
        markerStart = self.buffer.length() - output.length();
      } else if (merges
          && !self.closedMarkers.isEmpty()
          && self.closedMarkers.get(self.closedMarkers.size() - 1)[1] == markerStart
          && self.closedMarkers.get(self.closedMarkers.size() - 1)[0] >= self.lastYieldedLength
          && self.buffer
              .substring(
                  self.closedMarkers.get(self.closedMarkers.size() - 1)[0],
                  self.closedMarkers.get(self.closedMarkers.size() - 1)[1])
              .equals(output)
          && !insideSameDelimiter(self, kind)) {
        int start = self.closedMarkers.get(self.closedMarkers.size() - 1)[0];
        self.closedMarkers.remove(self.closedMarkers.size() - 1);
        truncateBuffer(self, start);
        self.lastContentCacheLen = 0;
        // A pair reopened around this one begins where its closer stood.
        for (int i = self.openMarkers.size() - 1; i >= 0; i--) {
          ConvertState.OpenMarker marker = self.openMarkers.get(i);
          if (marker.outputStart <= start) {
            break;
          }
          marker.outputStart = start;
          marker.contentStart = start;
        }
        boolean blankOutside =
            !self.openMarkers.isEmpty()
                && (self.openMarkers.get(self.openMarkers.size() - 1).decodedBlank
                    || self.openMarkers.get(self.openMarkers.size() - 1).blankOutside);
        if (self.silentMarkers > self.openMarkers.size()) {
          self.silentMarkers = self.openMarkers.size();
        }
        ConvertState.OpenMarker marker = new ConvertState.OpenMarker();
        marker.outputStart = start;
        marker.contentStart = start;
        marker.kind = kind;
        marker.startsAtHardBreak = false;
        marker.pending = pending;
        marker.followsSeparator = false;
        marker.separatorTrimmed = false;
        marker.runLen = 1;
        marker.restoresHeadingBreak = false;
        marker.reopened = output.length();
        marker.unmerged = 0;
        marker.decodedBlank = false;
        marker.blankOutside = blankOutside;
        self.openMarkers.add(marker);
        return;
      }
      boolean separatorWritten = separatorEnd == self.drained + markerStart;
      // An opener that starts with its own space, as inline code in a list item does, stood in for
      // the pending separator.
      boolean followsSeparator = separatorWritten || (separatorOwned && output.charAt(0) == ' ');
      int previous = self.openMarkers.size() - 1;
      int runLen = 1;
      if (kind <= 4
          && previous >= 0
          && self.openMarkers.get(previous).kind <= 4
          // A reopened pair wrote no opener, so it is no part of a run.
          && self.openMarkers.get(previous).reopened == 0
          && self.openMarkers.get(previous).contentStart == markerStart) {
        runLen = self.openMarkers.get(previous).runLen + 1;
      }
      boolean blankOutside =
          previous >= 0
              && (self.openMarkers.get(previous).decodedBlank
                  || self.openMarkers.get(previous).blankOutside);
      // Markers that closed since the count was taken no longer hold it up.
      if (self.silentMarkers > self.openMarkers.size()) {
        self.silentMarkers = self.openMarkers.size();
      }
      ConvertState.OpenMarker marker = new ConvertState.OpenMarker();
      marker.outputStart = markerStart;
      marker.contentStart = self.buffer.length();
      marker.kind = kind;
      marker.startsAtHardBreak = self.hardBreakEnd == markerStart;
      marker.pending = pending;
      marker.followsSeparator = followsSeparator;
      marker.separatorTrimmed = followsSeparator && self.pendingSpaceTrimmed;
      marker.runLen = runLen;
      // No marker opens with a newline; only a prefixed heading break does.
      marker.restoresHeadingBreak = output.startsWith("\n");
      marker.reopened = 0;
      marker.unmerged = 0;
      marker.decodedBlank = false;
      marker.blankOutside = blankOutside;
      self.openMarkers.add(marker);
    } else if (!keepsMarkers
        && !self.openMarkers.isEmpty()
        && output != null
        && Chars.hasVisible(output)) {
      self.openMarkers.clear();
    }
  }

  /** Whether an emphasis closer here may need the word after it escaped. */
  private static boolean emphasisContext(ConvertState self) {
    return !self.hasTagOverrides
        && !self.plainText
        && self.captionFrames.isEmpty()
        && self.depthMap[Consts.TAG_PRE] == 0
        && self.depthMap[Consts.TAG_CODE] == 0
        && self.depthMap[Consts.TAG_KBD] == 0
        && self.depthMap[Consts.TAG_SAMP] == 0
        && self.depthMap[Consts.TAG_VAR] == 0
        && !self.inRawHtmlBlock();
  }

  /** Whether the emphasis element being entered sits in one of its own delimiter. */
  private static boolean insideSameDelimiter(ConvertState self, int kind) {
    int open;
    if (kind == 1) {
      open =
          self.depthMap[Consts.TAG_STRONG]
              + self.depthMap[Consts.TAG_B]
              + self.depthMap[Consts.TAG_DFN];
    } else {
      // A caption is written in `*` as well.
      open =
          self.depthMap[Consts.TAG_EM]
              + self.depthMap[Consts.TAG_I]
              + self.depthMap[Consts.TAG_CITE]
              + self.depthMap[Consts.TAG_FIGCAPTION];
    }
    return open > 1;
  }

  /**
   * The empty pair just dropped was all that kept the pair around it from continuing the one
   * before, so that pair is reopened after all.
   */
  private static void reopenUnmergedMarker(ConvertState self) {
    if (self.openMarkers.isEmpty()) {
      return;
    }
    ConvertState.OpenMarker marker = self.openMarkers.get(self.openMarkers.size() - 1);
    if (marker.unmerged != 0
        && marker.contentStart == self.buffer.length()
        && !self.closedMarkers.isEmpty()) {
      int[] closer = self.closedMarkers.get(self.closedMarkers.size() - 1);
      if (closer[1] == marker.outputStart && closer[0] >= self.lastYieldedLength) {
        marker.reopened = marker.unmerged;
        marker.unmerged = 0;
        marker.outputStart = closer[0];
        marker.contentStart = closer[0];
        self.closedMarkers.remove(self.closedMarkers.size() - 1);
        truncateBuffer(self, closer[0]);
        self.lastContentCacheLen = 0;
      }
    }
  }

  /** Remember the emphasis closer an exit wrote at the end of the output. */
  private static void noteClosedMarker(ConvertState self, int markerTag, String emitted) {
    int kind = inlineMarkerType(markerTag);
    if ((kind == 1 || kind == 2 || kind == 4)
        && emitted != null
        && !emitted.isEmpty()
        && endsWith(self.buffer, emitted)) {
      int end = self.buffer.length();
      int start = end - emitted.length();
      if (!self.closedMarkers.isEmpty()
          && self.closedMarkers.get(self.closedMarkers.size() - 1)[1] != start) {
        self.closedMarkers.clear();
      }
      // A pair that closes behind a pair of its own delimiter ends in a longer
      // run, which CommonMark may read apart.
      if (!self.closedMarkers.isEmpty()
          && self.buffer
              .substring(
                  self.closedMarkers.get(self.closedMarkers.size() - 1)[0],
                  self.closedMarkers.get(self.closedMarkers.size() - 1)[1])
              .equals(emitted)) {
        self.closedMarkers.clear();
        return;
      }
      // A closer behind a blank or a hard break closes nothing in CommonMark,
      // and one behind a code span keeps that span from touching the next.
      if (start != 0
          && self.buffer.charAt(start - 1) != '`'
          && !Chars.isAsciiWhitespace(self.buffer.charAt(start - 1))) {
        self.closedMarkers.add(new int[] {start, end});
      }
    }
  }

  private static void releaseMarkersOnExit(ConvertState self, boolean closesBlock, String output) {
    if (!self.openMarkers.isEmpty()) {
      if (closesBlock) {
        releaseMarkersAtBlock(self);
      } else if (output != null && Chars.hasVisible(output)) {
        self.openMarkers.clear();
      }
    }
  }

  /**
   * Make the open inline markers permanent at a block boundary. Their openers were written while
   * they could still retract, so as in the JS engine they never end a closed list's run; output
   * from the boundary on still can.
   */
  private static void releaseMarkersAtBlock(ConvertState self) {
    if (self.siblingListMarker != 0 && !self.openMarkers.isEmpty()) {
      int contentStart = self.openMarkers.get(self.openMarkers.size() - 1).contentStart;
      if (!scanListGap(self, self.openMarkers.get(0).outputStart)) {
        self.listGapFrom = Math.max(self.listGapFrom, contentStart);
      }
    }
    self.openMarkers.clear();
  }

  // === Emphasis opener spacing ===

  /**
   * A space opening emphasis content stops its delimiter from opening, as the space after a
   * dropped {@code <svg>} does. When the output ends with a run of such openers, the space goes in
   * front of the run, merged with any whitespace already there, and this returns true so the
   * caller drops it from the text.
   */
  private static boolean hoistOpenerSpace(ConvertState self) {
    if (self.openMarkers.isEmpty()) {
      return false;
    }
    ConvertState.OpenMarker innermost = self.openMarkers.get(self.openMarkers.size() - 1);
    // Code-like spans keep their content as written. A reopened pair wrote no
    // opener, so it is a run of its own and the space goes where it begins.
    if (innermost.kind > 4
        || innermost.contentStart != self.buffer.length()
        || self.depthMap[Consts.TAG_CODE] != 0
        || self.depthMap[Consts.TAG_PRE] != 0
        || self.depthMap[Consts.TAG_KBD] != 0
        || self.depthMap[Consts.TAG_SAMP] != 0
        || self.depthMap[Consts.TAG_VAR] != 0) {
      return false;
    }
    int outermost = self.openMarkers.size() - innermost.runLen;
    int start = self.openMarkers.get(outermost).outputStart;
    // At the start of link text, whitespace before the `[` already parts the words.
    int at =
        self.link.open && self.link.bracketEmitted && start == self.link.bracketPos + 1
            ? self.link.bracketPos
            : start;
    // Moved in front of the run, it would split a literal opener from it.
    if (followsLiteralOpener(self, at)) {
      return true;
    }
    int before;
    if (at > 0) {
      before = self.buffer.charAt(at - 1);
    } else if (self.hasFlushedTail()) {
      before = self.flushedTail[1];
    } else {
      before = -1;
    }
    // A quote that is this run's content writes its own prefix at that position.
    int runEnd = self.openMarkers.get(self.openMarkers.size() - 1).contentStart;
    if (!self.blockquotes.isEmpty()
        && self.blockquotes.get(self.blockquotes.size() - 1).contentStart == runEnd) {
      return false;
    }
    if (before >= 0 && before != ' ' && before != '\n' && before != '\t') {
      // A closer that ends where the space goes in stays on record: the run
      // may still drop with the space and leave it ending the output again.
      List<int[]> closers = null;
      if (!self.closedMarkers.isEmpty()
          && self.closedMarkers.get(self.closedMarkers.size() - 1)[1] == start) {
        closers = new ArrayList<>(self.closedMarkers);
        self.closedMarkers.clear();
      }
      boolean moved = replaceCodeSpanContent(self, start, start, " ");
      if (closers != null) {
        self.closedMarkers.clear();
        self.closedMarkers.addAll(closers);
      }
      if (moved) {
        // Only the run follows `start`, so this moves nothing but the run's own markers. An
        // enclosing `<q>` that starts with the run keeps its content start, so the space is its
        // content and that pair still reads as empty once the run drops with it.
        for (int i = outermost; i < self.openMarkers.size(); i++) {
          ConvertState.OpenMarker marker = self.openMarkers.get(i);
          marker.outputStart += 1;
          marker.contentStart += 1;
        }
        self.openMarkers.get(outermost).followsSeparator = true;
      }
    }
    return true;
  }

  /**
   * A blank the parser kept at the start of an inline element parts the word before it from the
   * content after it. In front of an emphasis opener run that ends the output it moves before the
   * run.
   */
  static void placeLeadingBlank(ConvertState self) {
    boolean captionOwnsSpace =
        self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE;
    if (!captionOwnsSpace) {
      for (ConvertState.CaptionFrame frame : self.captionFrames) {
        if (frame.state != ConvertState.CAPTION_COMMITTED) {
          captionOwnsSpace = true;
          break;
        }
      }
    }
    if (self.pendingInlineWhitespace
        || captionOwnsSpace
        || (!self.plainText
            && (self.depthMap[Consts.TAG_CODE] != 0
                || self.depthMap[Consts.TAG_PRE] != 0
                || self.depthMap[Consts.TAG_KBD] != 0
                || self.depthMap[Consts.TAG_SAMP] != 0
                || self.depthMap[Consts.TAG_VAR] != 0
                || (!self.openMarkers.isEmpty() && hoistOpenerSpace(self))))) {
      return;
    }
    int end = self.buffer.length();
    boolean followsOpener =
        (!self.openMarkers.isEmpty()
                && self.openMarkers.get(self.openMarkers.size() - 1).reopened == 0
                && self.openMarkers.get(self.openMarkers.size() - 1).contentStart == end)
            || (self.link.open && self.link.bracketEmitted && self.link.bracketPos + 1 == end)
            || followsLiteralOpener(self, end);
    if (!followsOpener) {
      self.pendingInlineWhitespace = true;
      self.pendingLeadingBlank = true;
      self.pendingPairSeparator = false;
      self.pendingDecodedBlank = false;
    }
  }

  /** A literal {@code tagOverrides} opener is not held back, so nothing may split it. */
  private static boolean followsLiteralOpener(ConvertState self, int at) {
    if (self.literalOpenerEnds.isEmpty()) {
      return false;
    }
    int[] opener = self.literalOpenerEnds.get(self.literalOpenerEnds.size() - 1);
    return opener[1] == self.drained + at;
  }

  /**
   * Replace bytes a code span holds, refusing a growth past the cap. Mirrors {@code
   * replace_code_span_content}.
   */
  private static boolean replaceCodeSpanContent(
      ConvertState self, int start, int end, String value) {
    if (self.maxNodeBytesInt != 0
        && !self.codeSpans.isEmpty()
        && start >= self.codeSpans.get(0).contentStart) {
      int rangeBytes = Scan.utf8Length(self.buffer, start, end);
      int valueBytes = Scan.utf8Length(value);
      if (valueBytes > rangeBytes) {
        ConvertState.CodeSpanState span = self.codeSpans.get(0);
        int held = Scan.utf8Length(self.buffer, span.contentStart, self.buffer.length());
        if (span.exhausted || held - rangeBytes + valueBytes > self.maxNodeBytesInt) {
          span.exhausted = true;
          self.truncated = true;
          return false;
        }
      }
    }
    noteBufferRewrite(self, start);
    self.buffer.replace(start, end, value);
    return true;
  }

  /** Whether the text about to be written follows a separator written on its own. */
  private static boolean takeFollowsSeparator(ConvertState self) {
    boolean follows = self.separatorEnd == self.drained + self.buffer.length();
    self.separatorEnd = Integer.MAX_VALUE;
    return follows;
  }

  /** Map each stored stream position through {@code map}, where {@code null} drops it. */
  private static void moveStreamPositions(ConvertState self, int oldEnd, int newEnd) {
    if (oldEnd == newEnd) {
      return;
    }
    if (self.separatorEnd != Integer.MAX_VALUE && self.separatorEnd >= oldEnd) {
      self.separatorEnd += newEnd - oldEnd;
    }
    for (int[] opener : self.literalOpenerEnds) {
      if (opener[1] != Integer.MAX_VALUE && opener[1] >= oldEnd) {
        opener[1] += newEnd - oldEnd;
      }
    }
  }

  /**
   * Position a stream position may not pass once the buffer is trimmed to {@code end}. Output that
   * grows back to a trimmed position is not what ended there.
   */
  private static void clampStreamPositions(ConvertState self, int end) {
    int limit = self.drained + end;
    if (self.separatorEnd != Integer.MAX_VALUE && self.separatorEnd > limit) {
      self.separatorEnd = Integer.MAX_VALUE;
    }
    for (int[] opener : self.literalOpenerEnds) {
      if (opener[1] != Integer.MAX_VALUE && opener[1] > limit) {
        opener[1] = Integer.MAX_VALUE;
      }
    }
  }

  /** Follow a quote rewrite that moved the bytes from {@code oldEnd} on to {@code newEnd}. */
  private static void requoteStreamPositions(ConvertState self, int oldEnd, int newEnd) {
    moveStreamPositions(self, self.drained + oldEnd, self.drained + newEnd);
  }

  /**
   * A block that writes nothing closed inside the open spans. That leaves an empty pair empty,
   * but a code-like span holding a decoded blank has content.
   */
  private static void noteSilentBlock(ConvertState self) {
    if (!self.openMarkers.isEmpty()
        && (self.openMarkers.get(self.openMarkers.size() - 1).decodedBlank
            || self.openMarkers.get(self.openMarkers.size() - 1).blankOutside)) {
      int index = -1;
      for (int i = self.openMarkers.size() - 1; i >= 0; i--) {
        if (self.openMarkers.get(i).decodedBlank) {
          index = i;
          break;
        }
      }
      if (index >= 0) {
        self.openMarkers.subList(0, index + 1).clear();
        for (ConvertState.OpenMarker marker : self.openMarkers) {
          marker.blankOutside = false;
        }
      }
    }
    self.silentMarkers = self.openMarkers.size();
  }

  // === Adjacent list separation ===

  /** List marker character opening the last visible line of {@code output}, or 0. */
  private static int writtenListMarker(String output) {
    int last = output.length() - 1;
    while (last >= 0 && Chars.isAsciiWhitespace(output.charAt(last))) {
      last--;
    }
    if (last < 0) {
      return 0;
    }
    int line = 0;
    for (int i = last - 1; i >= 0; i--) {
      if (output.charAt(i) == '\n') {
        line = i + 1;
        break;
      }
    }
    int first = line;
    while (first < output.length() && Chars.isAsciiWhitespace(output.charAt(first))) {
      first++;
    }
    int digits = 0;
    while (first + digits < output.length()
        && output.charAt(first + digits) >= '0'
        && output.charAt(first + digits) <= '9') {
      digits++;
    }
    int end = first + digits;
    char marker = end < output.length() ? output.charAt(end) : 0;
    boolean opens;
    if (digits == 0) {
      opens = marker == '-' || marker == '*' || marker == '+';
    } else {
      opens = digits <= 9 && (marker == '.' || marker == ')');
    }
    if (opens && (end + 1 >= output.length() || Chars.isAsciiWhitespace(output.charAt(end + 1)))) {
      return marker;
    }
    return 0;
  }

  /**
   * List marker character a list's exit output leaves for the next list, or {@code null} to keep
   * the marker the list already has.
   */
  private static Integer exitListMarker(String output, boolean continuesItem) {
    int end = output.length() - 1;
    while (end >= 0 && Chars.isAsciiWhitespace(output.charAt(end))) {
      end--;
    }
    if (end < 0) {
      return null;
    }
    end++;
    for (;;) {
      int start = 0;
      for (int i = end - 1; i >= 0; i--) {
        char c = output.charAt(i);
        if (c == '\n' || c == '\r') {
          start = i + 1;
          break;
        }
      }
      if (start == 0 && continuesItem) {
        return null;
      }
      int marker = writtenListMarker(output.substring(start, end));
      if (marker != 0
          || start == 0
          || (start < end && !Chars.isAsciiWhitespace(output.charAt(start)))) {
        return marker;
      }
      end = start - 1;
    }
  }

  /** Record the marker the open {@code <li>} wrote when overrides are in play. */
  static void noteItemWithOverrides(ConvertState self) {
    TagOverride override =
        self.hasTagOverrides && !self.stack.isEmpty()
            ? self.overrideForNode(self.stack.get(self.stack.size() - 1))
            : null;
    String enter = override == null ? null : override.enter();
    int selected =
        self.listMarkers.isEmpty()
            ? 0
            : self.listMarkers.get(self.listMarkers.size() - 1).selected;
    int written = enter == null ? selected : writtenListMarker(enter);
    if (written != 0 && !self.listMarkers.isEmpty()) {
      self.listMarkers.get(self.listMarkers.size() - 1).written = written;
    }
    self.lastItemStar = written == '*';
  }

  /**
   * Marker character for a list opening here: the other one of its kind when the list before it
   * ended on the default one, sits at the same depth under the same quotes and nothing visible came
   * between.
   */
  static int nextListMarker(ConvertState self, boolean ordered) {
    int sibling = self.siblingListMarker;
    if (sibling != 0
        && self.siblingListDepth == self.depthMap[Consts.TAG_LI]
        && self.siblingListQuotes == self.blockquotes.size()
        && ((sibling == '.' || sibling == ')') == ordered)
        && !listGapVisible(self)) {
      if (sibling == '-' && listItemsOpenLines(self)) {
        return '*';
      }
      if (sibling == '.') {
        return ')';
      }
    }
    return ordered ? '.' : '-';
  }

  /** Whether the items of a list opening here start their lines. */
  private static boolean listItemsOpenLines(ConvertState self) {
    if (!self.captionFrames.isEmpty()) {
      return false;
    }
    if (self.collapseNonSpanDepth != 0) {
      int last = lastOutputByte(self);
      return last < 0 || last == '\n';
    }
    return true;
  }

  /**
   * Whether output since the closed list ends its run. An empty inline wrapper, code span or
   * cleaned link can still retract its opener, so nothing from the first one still open counts yet.
   */
  private static boolean listGapVisible(ConvertState self) {
    int end = self.buffer.length();
    if (!self.openMarkers.isEmpty()) {
      end = Math.min(end, self.openMarkers.get(0).outputStart);
    }
    if (!self.codeSpans.isEmpty()) {
      end = Math.min(end, self.codeSpans.get(0).outputStart);
    }
    if (self.link.emptyTextPending) {
      end = Math.min(end, self.link.bracketPos);
    }
    return scanListGap(self, end);
  }

  /**
   * Whether the gap holds visible output before {@code end}. Moves its start to that output, or to
   * {@code end}, so a gap that keeps growing is never scanned twice.
   */
  static boolean scanListGap(ConvertState self, int end) {
    int start = Math.min(self.listGapFrom, end);
    int visible = -1;
    for (int i = start; i < end; i++) {
      if (!Chars.isAsciiWhitespace(self.buffer.charAt(i))) {
        visible = i;
        break;
      }
    }
    if (visible < 0) {
      self.listGapFrom = Math.max(self.listGapFrom, end);
      return false;
    }
    self.listGapFrom = visible;
    return true;
  }

  /** Visible output leaving the buffer before {@code drainEnd} ends the closed list's run. */
  static void noteListGapDrain(ConvertState self, int drainEnd) {
    if (self.siblingListMarker != 0
        && self.listGapFrom < drainEnd
        && Chars.hasVisible(self.buffer, self.listGapFrom, drainEnd)) {
      self.siblingListMarker = 0;
    }
  }

  /** Settle the run before quoting rewrites the region ending at {@code unquotedEnd}. */
  private static int listGapBeforeRequote(ConvertState self, int unquotedEnd) {
    int from = self.listGapFrom;
    if (self.siblingListMarker != 0
        && from < unquotedEnd
        && Chars.hasVisible(self.buffer, from, unquotedEnd)) {
      self.siblingListMarker = 0;
    }
    return from;
  }

  /** Move the run past the quoted lines, or along with the bytes after them. */
  private static void listGapAfterRequote(
      ConvertState self, int from, int unquotedEnd, int quotedEnd) {
    self.listGapFrom = from >= unquotedEnd ? from - unquotedEnd + quotedEnd : quotedEnd;
  }

  /** Drop an empty enter/exit marker pair; returns whether it did. */
  private static boolean dropEmptyMarkerPair(
      ConvertState self, int markerTag, String emitted, boolean isInline) {
    int kind = inlineMarkerType(markerTag);
    if (kind == 0 || emitted == null || emitted.isEmpty()) {
      return false;
    }
    if (kind <= 4) {
      char mark = kind == 3 ? '~' : '*';
      int last = lastOutputByte(self);
      if (last >= 0
          && last != mark
          && Chars.isAsciiPunctuation((char) last)
          && emitted.charAt(0) == mark
          && emphasisContext(self)) {
        self.pendingEmphasis = mark;
      }
    }
    if (self.openMarkers.isEmpty()) {
      return false;
    }
    ConvertState.OpenMarker marker = self.openMarkers.remove(self.openMarkers.size() - 1);
    if (marker.reopened != 0) {
      // A reopened pair left empty gets back the closer it took, where it
      // stood, and what an empty pair would drop goes.
      if (marker.kind == kind
          && marker.contentStart <= self.buffer.length()
          && allWhitespaceFrom(self.buffer, marker.contentStart)) {
        // A separator moved in front of the pair is owed to what follows it.
        int end = marker.contentStart;
        boolean owesSeparator =
            marker.followsSeparator && end > 0 && self.buffer.charAt(end - 1) == ' ';
        if (owesSeparator) {
          end -= 1;
        }
        dropHeldBreakRuns(self, end);
        truncateBuffer(self, end);
        self.lastContentCacheLen = pushCodeSpanContent(self, emitted, true);
        self.lastNodeIsInline = isInline;
        self.pendingEmphasis = marker.pending;
        noteClosedMarker(self, markerTag, emitted);
        if (owesSeparator) {
          self.pendingInlineWhitespace = true;
          self.pendingLeadingBlank = true;
          self.pendingPairSeparator = marker.separatorTrimmed;
          self.pendingDecodedBlank = false;
        }
        return true;
      }
    } else if (marker.kind == kind
        && marker.contentStart <= self.buffer.length()
        && allWhitespaceFrom(self.buffer, marker.contentStart)) {
      int end = marker.outputStart;
      if (marker.followsSeparator) {
        // The separator is the opener's own space or the one in front of it.
        boolean owned = end < self.buffer.length() && self.buffer.charAt(end) == ' ';
        if (owned || (end > 0 && self.buffer.charAt(end - 1) == ' ')) {
          if (!owned) {
            end -= 1;
          }
          self.pendingInlineWhitespace = true;
          self.pendingLeadingBlank = true;
          self.pendingPairSeparator = marker.separatorTrimmed;
          self.pendingDecodedBlank = false;
        }
      }
      dropHeldBreakRuns(self, end);
      truncateBuffer(self, end);
      if (marker.startsAtHardBreak) {
        self.hardBreakEnd = marker.outputStart;
      }
      if (marker.restoresHeadingBreak) {
        self.headingBreakPending = true;
      }
      self.lastContentCacheLen = 0;
      self.pendingEmphasis = marker.pending;
      resetEmptyTentativeCaptionFrames(self);
      restoreQuoteParentBoundary(self);
      self.lastNodeIsInline = isInline;
      reopenUnmergedMarker(self);
      return true;
    } else if (marker.kind == kind
        && marker.contentStart <= self.buffer.length()
        && kind <= 4
        && allWhitespaceOrVerticalTabFrom(self.buffer, marker.contentStart)) {
      dropVerticalTabMarkerPair(self, marker, isInline);
      return true;
    }
    self.openMarkers.clear();
    return false;
  }

  /**
   * The closer would move this run of whitespace and vertical tabs after itself and leave the
   * pair empty, so drop both delimiters and keep the run.
   */
  private static void dropVerticalTabMarkerPair(
      ConvertState self, ConvertState.OpenMarker marker, boolean isInline) {
    MovedTail tail = takeTailWithBreaks(self, marker.contentStart);
    // An opener that took a list item's pending heading break carries it as a
    // prefix. The run is content after the heading, so only the delimiter goes.
    int target =
        marker.restoresHeadingBreak
            ? marker.outputStart
                + Math.min(
                    2 + self.listIndent.length(), marker.contentStart - marker.outputStart)
            : marker.outputStart;
    // The run is written back unchanged without the opener, so a hard break
    // ending in it still ends the same bytes. A block after it in a list item
    // reads that end to keep its blank line once trailing whitespace is trimmed.
    int breakEnd = tail.offset(self.hardBreakEnd);
    if (breakEnd >= 0) {
      breakEnd += target;
    }
    truncateBuffer(self, target);
    if (marker.startsAtHardBreak) {
      self.hardBreakEnd = marker.outputStart;
    }
    self.buffer.append(tail.text);
    // Only the last write stays trimmable, as in JS. The whole run would let a
    // block's trim reach back past the hard breaks to the vertical tab.
    self.lastContentCacheLen = Math.min(self.lastContentCacheLen, tail.text.length());
    restoreTail(self, tail, target);
    if (breakEnd >= 0) {
      self.hardBreakEnd = breakEnd;
    }
    self.lastNodeIsInline = isInline;
  }

  private static boolean allWhitespaceOrVerticalTabFrom(StringBuilder buffer, int start) {
    for (int i = Math.max(0, start); i < buffer.length(); i++) {
      char c = buffer.charAt(i);
      if (!Chars.isAsciiWhitespace(c) && c != 0x0B) {
        return false;
      }
    }
    return true;
  }

  private static boolean allWhitespaceFrom(StringBuilder buffer, int start) {
    return !Chars.hasVisible(buffer, Math.max(0, start), buffer.length());
  }

  /** Whether every char in {@code [start, end)} is a blank or an inline opener/closer. */
  private static boolean allTransient(StringBuilder buffer, int start, int end) {
    for (int i = Math.max(0, start); i < end && i < buffer.length(); i++) {
      if (!Chars.isTransientOutput(buffer.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Take back the held runs of breaks that start at or after {@code len}, which a retraction to
   * {@code len} removes whole. {@link #truncateBuffer} takes back one copy of a run starting at
   * {@code len} and writes the rest back, which is right for a trim that eats a single break and
   * wrong for a dropped pair whose content was a run of them.
   */
  private static void dropHeldBreakRuns(ConvertState self, int len) {
    while (!self.streamingBreakRuns.isEmpty()
        && self.streamingBreakRuns.get(self.streamingBreakRuns.size() - 1).outputStart >= len) {
      self.streamingBreakRuns.remove(self.streamingBreakRuns.size() - 1);
    }
  }

  /** Settle a pending empty-text link once visible text follows it. */
  private static void settleEmptyTextPending(
      ConvertState self, int outputStart, boolean keepsPending) {
    if (!self.link.emptyTextPending || keepsPending) {
      return;
    }
    if (outputStart <= self.buffer.length()) {
      if (Chars.hasVisible(self.buffer, outputStart, self.buffer.length())) {
        self.link.emptyTextPending = false;
      }
    }
  }

  private static void finishTextNode(
      ConvertState self, String text, boolean containsWhitespace, int depth, int index) {
    if (!self.openMarkers.isEmpty() || self.heldBreakBytes != 0) {
      int first = 0;
      while (first < text.length() && Chars.isAsciiWhitespace(text.charAt(first))) {
        first++;
      }
      if (first < text.length()) {
        // Vertical tabs alone leave an emphasis pair empty once its closer
        // moves them out, so `dropEmptyMarkerPair` still has to see it.
        boolean verticalTabOnly =
            text.charAt(first) == 0x0B
                && !self.openMarkers.isEmpty()
                && self.openMarkers.get(self.openMarkers.size() - 1).kind <= 4;
        if (verticalTabOnly) {
          for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Chars.isAsciiWhitespace(c) && c != 0x0B) {
              verticalTabOnly = false;
              break;
            }
          }
        }
        if (!verticalTabOnly) {
          self.openMarkers.clear();
        } else if (self.openMarkers.get(0).verticalTabStart < 0) {
          self.openMarkers.get(0).verticalTabStart = self.buffer.length() - text.length();
        }
        // A tentative caption may still retract the held breaks.
        if (self.heldBreakBytes != 0 && firstTentativeCaptionStart(self) < 0) {
          self.heldBreakBytes = 0;
        }
      } else if (!containsWhitespace
          && !text.isEmpty()
          && !self.openMarkers.isEmpty()
          && self.openMarkers.get(self.openMarkers.size() - 1).kind > 4) {
        // An entity-decoded blank is content to a code-like span, but alone it
        // still leaves the pair droppable: only a block closing inside settles it.
        if (self.silentMarkers >= self.openMarkers.size()) {
          self.openMarkers.clear();
        } else {
          self.openMarkers.get(self.openMarkers.size() - 1).decodedBlank = true;
        }
      }
    }
    self.lastTextNodeContainsWhitespace = containsWhitespace;
    self.hasLastTextNode = true;
    self.lastTextNodeDepth = depth;
    self.lastTextNodeIndex = index;
    self.breakAfterLastText = false;
    self.lastNodeIsInline = false;
  }

  /** Context for {@link #emitPlainTextInline}: no clamp, defer, indent or wrap state. */
  static boolean textFastPathOk(ConvertState self) {
    return self.isMarkdown()
        && self.maxNodeBytesInt == 0
        && self.wrapWidth == 0
        && self.captionBoundary == ConvertState.CAPTION_BOUNDARY_NONE
        && self.captionFrames.isEmpty()
        && !self.listRulePending
        && !self.headingBreakPending
        && !self.pendingInlineWhitespace
        && !self.pendingRawHtmlBoundary
        && !self.preFencePending
        && self.depthMap[Consts.TAG_PRE] == 0
        && self.depthMap[Consts.TAG_CODE] == 0
        && self.codeSpans.isEmpty()
        && self.fastPathsEnabled();
  }

  /**
   * Specialisation of {@link #emitTextWithGeneratedMarkdown} for {@link #textFastPathOk} nodes:
   * the general path's clamp, defer, indent, wrap, caption and tailwind branches are all inert
   * here, so skip them.
   */
  static void emitPlainTextInline(
      ConvertState self, String text, boolean containsWhitespace, int depth, int index) {
    boolean hasInlineGfmHazard = self.textBufferHasInlineGfmHazard;
    self.textBufferHasInlineGfmHazard = false;
    if (text.isEmpty()) {
      return;
    }
    // Text inside a cell the row cap already dropped.
    if (self.inTableCell() && self.tableCurrentRowCells >= self.tableColumnCap) {
      self.truncated = true;
      return;
    }
    int lastChar = lastOutputByte(self);
    if (collapsesIntoSpace(text, lastChar)) {
      return;
    }
    boolean insideRawHtmlBlock = self.rawHtmlBlockOpen;
    if (insideRawHtmlBlock) {
      trackRawHtmlMarkdownContext(self, self.buffer.length());
      text = escapeRawHtmlText(self, text);
    } else {
      self.rawHtmlMarkdown = false;
      self.rawHtmlScannedTo = self.buffer.length();
    }

    boolean inTable = self.depthMap[Consts.TAG_TABLE] > 0;
    boolean inLink = self.depthMap[Consts.TAG_A] > 0;
    boolean inQuote = self.depthMap[Consts.TAG_BLOCKQUOTE] > 0;
    boolean contextHasGfmHazard =
        !insideRawHtmlBlock && contextGfmHazard(text, inTable, inLink, inQuote);
    int orderedDigits =
        lastChar >= '0'
                && lastChar <= '9'
                && !text.isEmpty()
                && (isAsciiDigit(text.charAt(0)) || text.charAt(0) == '.' || text.charAt(0) == ')')
                && !shouldAddSpacingBeforeText(self, lastChar, text)
            ? lineStartDigits(self, text)
            : 0;
    int outputStart = outputStartMarker(self, text);
    boolean hazard =
        orderedDigits > 0
            || hasInlineGfmHazard
            || contextHasGfmHazard
            || outputStart >= 0
            || startsWithGfmBlockCandidate(self, text);
    boolean rawHtmlEscapeAllowed =
        !insideRawHtmlBlock || (self.rawHtmlMarkdown && !lineOpensRawHtmlBlock(self));
    if (hazard && rawHtmlEscapeAllowed) {
      String escaped = escapeGfmTextInto(self, text, orderedDigits, outputStart);
      if (escaped != null) {
        text = escaped;
      }
    }

    boolean addSpace = shouldAddSpacingBeforeText(self, lastChar, text);
    if (!self.openMarkers.isEmpty()
        && (addSpace || text.startsWith(" "))
        && hoistOpenerSpace(self)) {
      text = trimLeadingSpaces(text);
      if (text.isEmpty()) {
        return;
      }
      addSpace = false;
    }
    boolean followedSeparator = takeFollowsSeparator(self);
    pushTextUncapped(self, text, addSpace);
    self.cacheFollowsSeparator = followedSeparator;
    finishTextNode(self, text, containsWhitespace, depth, index);
  }

  /** Drop the spaces a hoisted opener separator replaced from the start of {@code text}. */
  private static String trimLeadingSpaces(String text) {
    int start = 0;
    while (start < text.length() && text.charAt(start) == ' ') {
      start++;
    }
    return start == 0 ? text : text.substring(start);
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
      finishRawHtmlBlock(self, text, true, 0);
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

    // Before a caption opens on this text, so its marker follows the break.
    if (self.headingBreakPending) {
      String flushed = flushHeadingBreakBeforeText(self, text);
      if (flushed == null) {
        return;
      }
      text = flushed;
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
      if (ownsLeadingSpace
          && !(self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE
              && self.captionBoundaryNewLines == ConvertState.QUOTE_CLOSED)
          && text.charAt(0) == ' ') {
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
        expandDeferredCaptionBreaks(self, true);
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
      // A blank that opened an inline element only stands in for a separator
      // the source does not write. A space it does write takes that place and
      // is output like any other, so an element that starts no line cannot
      // take it. A separator an empty pair gave back stood in the output
      // already, so a blank stays with it. A decoded blank is content an
      // emphasis closer would keep inside, so it stays with the pending one
      // too, which only a line end then takes.
      if (!Chars.hasVisible(text)) {
        if (!self.pendingLeadingBlank || self.pendingPairSeparator) {
          return;
        }
        if (!text.equals(" ") || !containsWhitespace) {
          self.pendingDecodedBlank |= !text.isEmpty();
          return;
        }
      }
      int last = lastOutputByte(self);
      char first = text.charAt(0);
      // An empty opener run ending the output takes the separator in front.
      // A separator trimmed from a closed element is a wrap boundary. One a
      // blank that opened an inline element owes, or one an empty pair gave
      // back, stays outside the text, where a line break cannot set the text
      // at a line start. A deferred fence this text opens starts a line, which
      // takes the separator.
      if (last >= 0
          && last != ' '
          && last != '\n'
          && last != '\t'
          && !Scan.isWhitespace(first)
          && !self.preFencePending
          && !followsLiteralOpener(self, self.buffer.length())
          && (self.openMarkers.isEmpty() || !hoistOpenerSpace(self))) {
        // Wrapped text writes the separator itself, so it can become the break.
        if (generatedPrefix == null
            && (!self.pendingLeadingBlank || self.plainText)
            && self.wrapWidth != 0
            && canWrapHere(self)) {
          relocatedSpace = true;
        } else {
          pushCodeSpanContent(self, " ", true);
          self.separatorEnd = self.drained + self.buffer.length();
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
      int indentBytes = Scan.utf8Length(indent);
      // The fence holds what is written, indentation included, so that is what
      // its cap measures. A deep list's indent multiplies each line, and the
      // check above saw only the raw text: stop building once past the cap.
      long budget = Long.MAX_VALUE;
      if (self.codeFence != null && self.maxNodeBytes != 0) {
        long used = Math.max(0L, (long) self.buffer.length() - self.codeFence.contentStart);
        budget = Math.max(0L, self.maxNodeBytes - used);
      }
      StringBuilder out = new StringBuilder(text.length() + indent.length() * 2);
      long outBytes = 0;
      if (lastChar == '\n' && !text.isEmpty() && text.charAt(0) != '\n') {
        out.append(indent);
        outBytes += indentBytes;
      }
      int prev = 0;
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          out.append(text, prev, i + 1);
          outBytes += Scan.utf8Length(text, prev, i + 1);
          int next = i + 1;
          if (next < text.length() && text.charAt(next) != '\n') {
            out.append(indent);
            outBytes += indentBytes;
          }
          prev = next;
          if (outBytes > budget) {
            break;
          }
        }
      }
      if (outBytes <= budget) {
        out.append(text, prev, text.length());
        outBytes += Scan.utf8Length(text, prev, text.length());
      }
      if (outBytes > budget) {
        self.truncated = true;
        int kept = clampToCharBoundary(out.toString(), (int) budget);
        if (kept == 0) {
          return;
        }
        out.setLength(kept);
      }
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
    int orderedDigits =
        lastChar >= '0'
                && lastChar <= '9'
                && !text.isEmpty()
                && (isAsciiDigit(text.charAt(0)) || text.charAt(0) == '.' || text.charAt(0) == ')')
                && !self.plainText
                && !relocatedSpace
                && (generatedPrefix == null || generatedPrefix.isEmpty())
                && (ownsLeadingSpace || !shouldAddSpacingBeforeText(self, lastChar, text))
            ? lineStartDigits(self, text)
            : 0;
    // Spacing was decided on the raw text; the escape may put `\` first.
    ownsLeadingSpace = ownsLeadingSpace || orderedDigits > 0;
    // A generated prefix goes ahead of the text and keeps its whitespace.
    int outputStart =
        generatedPrefix == null || generatedPrefix.isEmpty() ? outputStartMarker(self, text) : -1;
    boolean hazard =
        !self.plainText
            && self.depthMap[Consts.TAG_PRE] == 0
            && self.depthMap[Consts.TAG_CODE] == 0
            && self.codeSpans.isEmpty()
            && (orderedDigits > 0
                || hasInlineGfmHazard
                || contextHasGfmHazard
                || outputStart >= 0
                || startsWithGfmBlockCandidate(self, text));
    boolean rawHtmlEscapeAllowed =
        !insideRawHtmlBlock
            || (self.rawHtmlMarkdown && !lineOpensRawHtmlBlock(self));
    String escaped =
        hazard && rawHtmlEscapeAllowed
            ? escapeGfmTextInto(self, text, orderedDigits, outputStart)
            : null;
    if (escaped != null) {
      text = escaped;
    }

    if (!self.openMarkers.isEmpty()
        && (text.startsWith(" ")
            || (!ownsLeadingSpace && shouldAddSpacingBeforeText(self, lastChar, text)))
        && hoistOpenerSpace(self)) {
      text = trimLeadingSpaces(text);
      if (text.isEmpty()) {
        return;
      }
      ownsLeadingSpace = true;
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
    int writtenFrom = self.buffer.length();
    // Read before any write, so every path counts the separator the same way.
    self.cacheFollowsSeparator = takeFollowsSeparator(self);
    if (self.wrapWidth != 0 && canWrapHere(self)) {
      pushTextWrapped(self, text, lastChar, ownsLeadingSpace, relocatedSpace);
    } else if (self.maxNodeBytesInt == 0) {
      if (!ownsSpace && shouldAddSpacingBeforeText(self, lastChar, text)) {
        pushTextUncapped(self, " ", false);
        self.lastContentCacheLen += text.length();
        self.buffer.append(text);
      } else {
        pushTextUncapped(self, text, false);
      }
    } else if (!ownsSpace && shouldAddSpacingBeforeText(self, lastChar, text)) {
      self.pendingEmphasis = 0;
      int written = pushCodeSpanContent(self, " ", true);
      self.lastContentCacheLen = written + pushCodeSpanContent(self, text, false);
    } else if (self.pendingEmphasis != 0) {
      String referenced = emphasisText(self, text);
      self.lastContentCacheLen = pushCodeSpanContent(self, referenced, false);
    } else {
      self.lastContentCacheLen = pushCodeSpanContent(self, text, false);
    }
    self.imageTextPending = false;

    if (self.maxNodeBytesInt != 0 && self.inHeading() && writtenFrom <= self.buffer.length()) {
      int written = self.buffer.length() - writtenFrom;
      int run = 0;
      int i = self.buffer.length();
      while (i > writtenFrom && isHeadingRunChar(self.buffer.charAt(i - 1))) {
        run++;
        i--;
      }
      self.headingHashRun = run == written ? self.headingHashRun + run : run;
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
    // A strikethrough opener touches its content; a closer or a literal
    // tilde keeps the separator.
    if (lastByte == '~' && !self.openMarkers.isEmpty()) {
      return firstByte == '~';
    }
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
    // Image text ends a word, as a block boundary does.
    if (self.lastNodeIsInline && !self.imageTextPending) {
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
      if (node.collapsesInnerWhiteSpace
          && !node.excludedFromMarkdown
          && node.tagId != Consts.TAG_SPAN
          && wrapperEmitsInlineMarkdown(self, node)) {
        return true;
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
            && (self.collapseNonSpanDepth == 1
                || (self.collapseNonSpanDepth > 1 && !self.plainText))
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
    if (nonSpanDepth > 0
        && !headingOwnsCollapse
        && !headingExitInMarkerlessCollapse
        && (!self.hasTagOverrides || !blockChildOfOpenVoid(self, tagId, isEnter))) {
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
    if (self.plainText || self.depthMap[Consts.TAG_LI] == 0) {
      return "";
    }
    StringBuilder p = new StringBuilder();
    int liIndex = 0;
    for (int i = 0; i < self.stack.size(); i++) {
      ElementNode node = self.stack.get(i);
      if (node.tagId == Consts.TAG_LI) {
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

  private static String joinBlockFragment(String prefix, String indent, String fragment) {
    StringBuilder output = new StringBuilder(prefix.length() + indent.length() + fragment.length());
    output.append(prefix).append(indent).append(fragment);
    return output.toString();
  }

  private static void flushListRule(ConvertState self) {
    self.listRulePending = false;
    String prefix = continuationPrefix(self);
    if (self.maxNodeBytesInt == 0 || self.codeSpans.isEmpty()) {
      self.buffer.append("\n\n").append(prefix);
      self.lastContentCacheLen = 2 + prefix.length();
    } else {
      String output = joinBlockFragment("\n\n", prefix, "");
      self.lastContentCacheLen = pushCodeSpanContent(self, output, true);
    }
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
        if (!self.codeSpans.isEmpty() && self.depthMap[Consts.TAG_PRE] == 0) {
          return " ";
        }
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
          if (self.listIndent.length() > 0
              && !headingKeptInlineInItem(self, true)
              && self.stack.size() >= 2
              && self.stack.get(self.stack.size() - 2).tagId == Consts.TAG_LI) {
            // A heading directly inside a list item must break away from the
            // item text and keep the item's content column.
            String prefix = continuationPrefix(self);
            return blockOpenPrefix(self, prefix) + HEADING_PREFIXES[depth];
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
          if (open.isEmpty()) {
            // Sharing the marker's line, where a rule of the bullet's own
            // character would make the whole line a thematic break and take the
            // item with it.
            return self.lastItemStar
                ? Consts.MARKDOWN_HORIZONTAL_RULE
                : Consts.MARKDOWN_HORIZONTAL_RULE_ALT;
          }
          return open + Consts.MARKDOWN_HORIZONTAL_RULE;
        }
      case Consts.TAG_STRONG:
      case Consts.TAG_B:
        // A code span renders delimiters as literal text.
        if (writesCodeText(self)) {
          return null;
        }
        return self.depthMap[Consts.TAG_B] > 1 ? "" : Consts.MARKDOWN_STRONG;
      case Consts.TAG_EM:
      case Consts.TAG_I:
        if (writesCodeText(self)) {
          return null;
        }
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
      case Consts.TAG_DIV:
        // A drain can empty the buffer mid-document, so the check reads the
        // flushed tail like the close-side twin below, not just the buffer. A
        // pending `<pre>` fence is written after this output and opens the
        // block itself, so a separator here would land inside the code.
        if (self.depthMap[Consts.TAG_LI] > 0
            && !self.inTableCell()
            && !self.preFencePending
            && lastOutputByte(self) >= 0
            && lastOutputByte(self) != ' '
            && lastOutputByte(self) != '\n') {
          // Divs otherwise keep their existing list spacing. Only a bare
          // heading marker needs a boundary before the div's first text.
          if (tagId == Consts.TAG_DIV) {
            StringBuilder bytes = self.buffer;
            // Seven hashes already rule out a marker, so a long retained run
            // is not rescanned at every div.
            int marker = bytes.length();
            while (marker > 0
                && bytes.length() - marker < 7
                && bytes.charAt(marker - 1) == '#') {
              marker--;
            }
            int run = bytes.length() - marker;
            if (run < 1 || run > 6 || !opensBlockLine(self, marker)) {
              return null;
            }
          }
          return "\n\n" + self.listIndent;
        }
        return null;
      case Consts.TAG_BLOCKQUOTE:
        // The completed subtree receives quote prefixes once every structural
        // newline is known. Preserve the list marker's trailing space here. A
        // table cell renders its list as raw HTML on the row's own line.
        return (self.depthMap[Consts.TAG_LI] > 0
                && !self.inTableCell()
                && self.captionBoundary != ConvertState.CAPTION_BOUNDARY_PENDING
                && self.captionBoundary != ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT)
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
        return inlineCodeOpener(self, self.depthMap[Consts.TAG_LI] > 0);
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
          // The innermost list's character; an item outside any list takes `-`.
          int marker =
              self.listMarkers.isEmpty()
                  ? 0
                  : self.listMarkers.get(self.listMarkers.size() - 1).selected;
          if (!ordered && self.listIndent.length() <= MAX_BORROWED_INDENT) {
            StringBuilder bullet = new StringBuilder(self.listIndent.length() + 2);
            for (int i = 0; i < self.listIndent.length(); i++) {
              bullet.append(' ');
            }
            bullet.append(marker == '*' ? '*' : '-').append(' ');
            return bullet.toString();
          }
          StringBuilder s = new StringBuilder(self.listIndent.length() + ORDERED_MARKER_CAPACITY);
          s.append(self.listIndent);
          if (ordered) {
            s.append(self.orderedItemNumber(node.index))
                .append(marker == ')' ? ") " : ". ");
          } else {
            s.append(marker == '*' ? "* " : "- ");
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
        if (self.inRawHtmlBlock()) {
          return Consts.MARKDOWN_INLINE_CODE;
        }
        if (!self.codeSpans.isEmpty()) {
          return null;
        }
        return inlineCodeOpener(self, false);
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
        return writesCodeText(self) ? null : "*";
      case Consts.TAG_FIGCAPTION:
        return null;
      case Consts.TAG_DFN:
        return writesCodeText(self) ? null : "**";
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
        // A code span renders delimiters as literal text.
        if (writesCodeText(self)) {
          return null;
        }
        return self.depthMap[Consts.TAG_B] > 1 ? "" : Consts.MARKDOWN_STRONG;
      case Consts.TAG_EM:
      case Consts.TAG_I:
        if (writesCodeText(self)) {
          return null;
        }
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
        if (!self.codeSpans.isEmpty()
            && self.codeSpans.get(self.codeSpans.size() - 1).depth != node.depth) {
          // Code inside a code span is part of it: a nested <code> adds no
          // delimiter of its own.
          return null;
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
        if (self.inRawHtmlBlock()
            || self.codeSpans.isEmpty()
            || self.codeSpans.get(self.codeSpans.size() - 1).depth == node.depth) {
          return Consts.MARKDOWN_INLINE_CODE;
        }
        return null;
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
        return writesCodeText(self) ? null : "*";
      case Consts.TAG_FIGCAPTION:
        return Consts.MARKDOWN_EMPHASIS;
      case Consts.TAG_DFN:
        return writesCodeText(self) ? null : "**";
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
    // Inside a code span past its cap nothing more is written, so classifying the
    // line only re-reads it: one that never ends, once per row.
    if (!isInline && dropsCodeSpanContent(self)) {
      return "| ";
    }
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
    return parsed == null ? 1 : (int) Math.max(1, Math.min(parsed, ConvertState.MAX_CELL_SPAN));
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

  /**
   * Inside {@code <pre>} the whitespace before an element boundary is code: only the boundary of a
   * direct child of a {@code <pre>} trims it, as in JS. On exit the node is already popped, so the
   * top of the stack is its parent. On enter the node tops the stack: its parent decides, and a
   * {@code <pre>} with no {@code <pre>} above it still opens outside the code. Testing the entering
   * node itself made a {@code <pre>} nested in another trim a space of the outer code. Plain text
   * keeps testing the top of the stack, as the JS text engine does.
   *
   * <p>The parent rule skips a text that a block boundary already closed with newlines: the
   * reach-back would cross them and trim a tab, CR or form feed that JS keeps, since JS trims only
   * its last fragment, the newlines.
   */
  private static boolean atPreWhitespaceBoundary(ConvertState self, boolean isEnter) {
    int len = self.stack.size();
    boolean topIsPre = !self.stack.isEmpty() && self.stack.get(len - 1).tagId == Consts.TAG_PRE;
    if (!isEnter || self.plainText) {
      return topIsPre;
    }
    return self.depthMap[Consts.TAG_PRE] == (topIsPre ? 1 : 0)
        || ((topIsPre || !self.breakAfterLastText)
            && len >= 2
            && self.stack.get(len - 2).tagId == Consts.TAG_PRE);
  }

  static void writeOutput(
      ConvertState self,
      boolean isEnter,
      boolean isInline,
      int configuredNewLines,
      String output,
      boolean literal) {
    writeOutputBody(self, isEnter, isInline, configuredNewLines, output, literal, false);
  }

  /** A closer following whitespace may hold vertical tabs it has to move outside the delimiter. */
  private static void writeEmphasisExit(
      ConvertState self, boolean isInline, int configuredNewLines, String output) {
    writeOutputBody(self, false, isInline, configuredNewLines, output, false, true);
  }

  /** An emphasis delimiter closing after whitespace, which may hold a vertical tab. */
  private static boolean emphasisExitFollowsWhitespace(
      ConvertState self, int tagId, String output) {
    if (!"*".equals(output) && !"**".equals(output) && !"~~".equals(output)) {
      return false;
    }
    if (tagId != Consts.TAG_STRONG
        && tagId != Consts.TAG_B
        && tagId != Consts.TAG_DFN
        && tagId != Consts.TAG_EM
        && tagId != Consts.TAG_I
        && tagId != Consts.TAG_DEL
        && tagId != Consts.TAG_S
        && tagId != Consts.TAG_STRIKE
        && tagId != Consts.TAG_CITE) {
      return false;
    }
    int last = lastOutputByte(self);
    return last == ' '
        || last == '\t'
        || last == '\n'
        || last == '\r'
        || last == 0x0C
        || last == 0x0B;
  }

  private static void writeOutputBody(
      ConvertState self,
      boolean isEnter,
      boolean isInline,
      int configuredNewLines,
      String output,
      boolean literal,
      boolean closesEmphasis) {
    String outputStr = output == null ? "" : output;
    // Image text no longer ends the output.
    if (self.imageTextPending && !outputStr.isEmpty()) {
      self.imageTextPending = false;
    }
    if (isEnter) {
      if (configuredNewLines >= 2) {
        self.pendingRawHtmlBoundary = false;
      } else if (self.pendingRawHtmlBoundary) {
        // Line breaks the output opens with count towards the boundary.
        int leadingNewLines = 0;
        for (int i = 0; i < outputStr.length(); i++) {
          char c = outputStr.charAt(i);
          if (c == '\n') {
            leadingNewLines++;
          } else if (!(c == ' ' || c == '\t' || c == '\r')) {
            break;
          }
        }
        finishRawHtmlBlock(self, outputStr, true, leadingNewLines);
      }
    }
    boolean isLineBoundary =
        !literal && (outputStr.startsWith("\n") || outputStr.startsWith("\\\n"));

    // A separator trimmed from inside a previously closed inline element must
    // sit outside its Markdown delimiter. Resolve it only when later visible
    // inline output begins; block boundaries and line breaks subsume it.
    self.pendingSpaceEnd = Integer.MAX_VALUE;
    self.pendingSpaceOwned = false;
    int topTag = self.stack.isEmpty() ? -1 : self.stack.get(self.stack.size() - 1).tagId;
    boolean pendingCaption =
        outputStr.isEmpty()
            && configuredNewLines == 0
            && (!isEnter
                || topTag == Consts.TAG_SVG
                || topTag == Consts.TAG_SCRIPT
                || topTag == Consts.TAG_STYLE)
            && !self.captionFrames.isEmpty()
            && self.captionFrames.get(self.captionFrames.size() - 1).state
                == ConvertState.CAPTION_PENDING;
    // A trimmed separator an empty pair gave back stood in the output, and a
    // decoded blank is content, so a block that starts no line takes neither.
    // A quote does: it writes its `>` where it opens, which the separator
    // would end up behind.
    boolean opensQuote = isEnter && topTag == Consts.TAG_BLOCKQUOTE;
    boolean pairOwed = self.pendingPairSeparator && !opensQuote;
    boolean owed = pairOwed || (self.pendingDecodedBlank && !opensQuote);
    if (self.pendingInlineWhitespace && isEnter && !pendingCaption) {
      // A div enter with no output only groups content on the same line.
      boolean transparentDiv = outputStr.isEmpty() && topTag == Consts.TAG_DIV;
      int firstOutput = outputStr.isEmpty() ? -1 : outputStr.charAt(0);
      if ((!isInline && !transparentDiv && !owed)
          || isLineBoundary
          || configuredNewLines > 0
          || firstOutput == '\n'
          || firstOutput == '\r') {
        self.pendingInlineWhitespace = false;
      } else if (firstOutput >= 0) {
        int last = lastOutputByte(self);
        // An empty opener run ending the output takes the separator in front.
        if (last >= 0
            && last != ' '
            && last != '\n'
            && last != '\t'
            && !Scan.isWhitespace((char) firstOutput)
            && !followsLiteralOpener(self, self.buffer.length())
            && (self.openMarkers.isEmpty() || !hoistOpenerSpace(self))) {
          self.pendingEmphasis = 0;
          pushCodeSpanContent(self, " ", true);
          self.separatorEnd = self.drained + self.buffer.length();
          // Only Markdown drops an empty pair with the separator in front of it.
          if (self.isMarkdown()) {
            self.pendingSpaceEnd = self.separatorEnd;
            // A decoded blank does not make it one: given back as trimmed, it
            // would keep a space written after the pair from being output.
            self.pendingSpaceTrimmed = !self.pendingLeadingBlank || pairOwed;
          }
        } else if (firstOutput == ' ' && self.isMarkdown()) {
          // The output's own space takes the separator's place. An empty pair dropped with it owes
          // the separator again.
          self.pendingSpaceOwned = true;
          self.pendingSpaceTrimmed = !self.pendingLeadingBlank || pairOwed;
        }
        self.pendingInlineWhitespace = false;
      }
    } else if (self.pendingInlineWhitespace
        && !pendingCaption
        && ((!isInline && !owed) || configuredNewLines > 0)) {
      self.pendingInlineWhitespace = false;
    }

    if (configuredNewLines == 0 && outputStr.isEmpty()
        && !self.lastTextNodeContainsWhitespace) {
      self.lastNodeIsInline = isInline;
      return;
    }

    // Only contiguous closing delimiters may extend a pending closer. Events
    // that returned above write nothing, so they leave it pending.
    if (self.pendingEmphasis != 0
        && (configuredNewLines != 0
            || (!outputStr.isEmpty()
                && (isEnter
                    || outputStr.charAt(0) != self.pendingEmphasis
                    || outputStr.charAt(outputStr.length() - 1) != self.pendingEmphasis)))) {
      self.pendingEmphasis = 0;
    }

    int emphasisWhitespaceStart = self.buffer.length();
    boolean emphasisHasVerticalTab = false;
    if (closesEmphasis) {
      int start = self.buffer.length();
      boolean hasTab = false;
      while (start > 0) {
        char c = self.buffer.charAt(start - 1);
        if (!(c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0C || c == 0x0B)) {
          break;
        }
        if (c == 0x0B) {
          hasTab = true;
        }
        start--;
      }
      emphasisWhitespaceStart = start;
      emphasisHasVerticalTab = hasTab;
    }

    if (configuredNewLines == 0
        && !self.lastTextNodeContainsWhitespace
        && !emphasisHasVerticalTab) {
      int lastChar = lastOutputByte(self);
      if (isEnter
          && !literal
          && !isLineBoundary
          && !outputStr.isEmpty()
          && lastChar >= 0
          && needsSpacing(self, lastChar, outputStr.charAt(0))) {
        self.lastContentCacheLen = pushCodeSpanContent(self, " ", true);
      }
      if (!outputStr.isEmpty()) {
        self.lastContentCacheLen = pushCodeSpanContent(self, outputStr, true);
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
          self.lastContentCacheLen = pushCodeSpanContent(self, outputStr, true);
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
          pushCodeSpanContent(self, "\n", true);
        }
        if (!outputStr.isEmpty()) {
          self.lastContentCacheLen = pushCodeSpanContent(self, outputStr, true);
        }
      } else {
        if (!outputStr.isEmpty()) {
          self.lastContentCacheLen = pushCodeSpanContent(self, outputStr, true);
        }
        for (int i = 0; i < newLines; i++) {
          pushCodeSpanContent(self, "\n", true);
        }
      }
      if (newLines > 0 && (!isEnter || outputStr.isEmpty())) {
        self.breakAfterLastText = true;
      }
    } else {
      if (self.lastTextNodeContainsWhitespace && (isInline || self.stack.size() > 0)
          && (self.depthMap[Consts.TAG_PRE] == 0
              || atPreWhitespaceBoundary(self, isEnter)
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
        if (shouldTrim
            && self.lastContentCacheLen > 0
            && !(self.breakAfterLastText && outputStr.isEmpty())) {
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
        self.lastContentCacheLen = pushCodeSpanContent(self, " ", true);
      }
      if (!outputStr.isEmpty()) {
        if (emphasisHasVerticalTab && emphasisWhitespaceStart < self.buffer.length()) {
          pushOutputBeforeEmphasisWhitespace(self, outputStr, emphasisWhitespaceStart);
        } else {
          self.lastContentCacheLen = pushCodeSpanContent(self, outputStr, true);
        }
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

  private static void finishRawHtmlBlock(
      ConvertState self, String value, boolean indent, int leadingNewLines) {
    if (!self.pendingRawHtmlBoundary
        || self.inRawHtmlBlock()
        || !Chars.hasVisible(value)) {
      return;
    }
    int newlines = leadingNewLines;
    int tailStart = 0;
    for (int i = self.buffer.length() - 1; i >= 0; i--) {
      char c = self.buffer.charAt(i);
      if (c == '\n') {
        newlines++;
        if (newlines == 2) {
          self.pendingRawHtmlBoundary = false;
          return;
        }
      } else if (!Scan.isWhitespace(c)) {
        tailStart = i + 1;
        break;
      }
    }
    // A streamed break run buffers one copy of its unit for all `count` breaks.
    for (int i = self.streamingBreakRuns.size() - 1; i >= 0; i--) {
      ConvertState.StreamingBreakRun run = self.streamingBreakRuns.get(i);
      if (run.outputStart < tailStart) {
        break;
      }
      int unitNewLines = 0;
      for (int k = 0; k < run.unit.length(); k++) {
        if (run.unit.charAt(k) == '\n') {
          unitNewLines++;
        }
      }
      newlines += (run.count - 1) * unitNewLines;
    }
    if (newlines >= 2 && leadingNewLines == 0) {
      self.pendingRawHtmlBoundary = false;
      return;
    }
    int missing = Math.max(0, 2 - newlines);
    // An output that starts its own line brings the indent with it.
    int indentLen = indent && leadingNewLines == 0 ? self.listIndent.length() : 0;
    if (missing + indentLen == 0) {
      // The output's own line breaks are the whole boundary.
    } else if (self.maxNodeBytesInt != 0 && !self.codeSpans.isEmpty()) {
      // Charge the complete separator to an open code span. A rejected write leaves
      // the boundary pending until content can be emitted again.
      StringBuilder separator = new StringBuilder(missing + indentLen);
      for (int i = 0; i < missing; i++) {
        separator.append('\n');
      }
      separator.append(self.listIndent, 0, indentLen);
      if (pushCodeSpanContent(self, separator.toString(), true) == 0) {
        return;
      }
    } else {
      // The separator belongs after the HTML close, even when it had no spacing.
      for (int i = 0; i < missing; i++) {
        self.buffer.append('\n');
      }
      self.buffer.append(self.listIndent, 0, indentLen);
    }
    self.pendingRawHtmlBoundary = false;
    self.lastContentCacheLen = missing + indentLen;
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
      while (lineEnd < len
          && buffer.charAt(lineEnd) != '\n'
          && buffer.charAt(lineEnd) != '\r') {
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

  private static boolean preInsideInline(ConvertState self) {
    for (int i = 0; i < self.stack.size(); i++) {
      if (self.stack.get(i).tagId == Consts.TAG_PRE) {
        for (int j = 0; j < i; j++) {
          if (self.stack.get(j).isInline) {
            return true;
          }
        }
        return false;
      }
    }
    return false;
  }

  /** Whether output lands inside the code block of a {@code <pre>} fence. */
  private static boolean inPreCodeFence(ConvertState self) {
    return self.codeFence != null
        && !self.fencesUnreliable
        && self.depthMap[Consts.TAG_PRE] > 0;
  }

  private static void startCodeFence(
      ConvertState self, int outputStart, int contentStart, String language, String indent) {
    String emitted = self.buffer.substring(outputStart, contentStart);
    int markerOffset = emitted.lastIndexOf(Consts.MARKDOWN_CODE_BLOCK);
    if (markerOffset < 0) {
      markerOffset = 0;
    }
    // The fence took the tentative quote separator; restoring it would cut the fence.
    settleQuoteBoundaryAtFence(self);
    if (!self.fencesUnreliable
        && (preInsideInline(self) || !opensBlockLine(self, outputStart + markerOffset))) {
      self.fencesUnreliable = true;
    }
    ConvertState.CodeFenceState fence = new ConvertState.CodeFenceState();
    fence.outputStart = outputStart;
    fence.markerOffset = markerOffset;
    fence.contentStart = contentStart;
    fence.indent = indent;
    fence.language = language;
    self.codeFence = fence;
  }

  /** A quote that closed inside the fence may have trimmed the reservation already. */
  private static void dropEmptyFenceReservation(ConvertState self, String indent) {
    if (self.buffer.toString().endsWith(indent)) {
      truncateBuffer(self, self.buffer.length() - indent.length());
    }
  }

  private static void settleQuoteBoundaryAtFence(ConvertState self) {
    if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    }
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
    // The opener reserves the first content line's list indent. With no body,
    // remove that reservation so the empty line stays blank, as in JS.
    if (self.buffer.length() == fence.contentStart
        && fence.indent != null
        && !fence.indent.isEmpty()) {
      dropEmptyFenceReservation(self, fence.indent);
    }
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

  /**
   * Whether a tag's content is inline code: {@code <code>}, {@code <kbd>}, {@code <samp>}, {@code
   * <var>}.
   */
  private static boolean isInlineCodeTag(int tagId) {
    return tagId == Consts.TAG_CODE
        || tagId == Consts.TAG_KBD
        || tagId == Consts.TAG_SAMP
        || tagId == Consts.TAG_VAR;
  }

  /** Whether the output holds code text, where emphasis delimiters stay literal. */
  private static boolean writesCodeText(ConvertState self) {
    return !self.codeSpans.isEmpty() || self.rawCodeOwnerDepth >= 0;
  }

  /**
   * Whether a {@code <figcaption>} is transparent: inside a code span or raw code it writes
   * nothing, unless an override gives it an enter or exit of its own.
   */
  private static boolean transparentCodeCaption(ConvertState self, ElementNode node) {
    if (!writesCodeText(self)) {
      return false;
    }
    TagOverride override = self.hasTagOverrides ? self.overrideForNode(node) : null;
    return override == null || (override.enter() == null && override.exit() == null);
  }

  /**
   * Opener of an inline code span. Adjacent delimiters would form one backtick run; in a list,
   * {@code <code>} also separates from prose while staying next to wrapper openers.
   */
  private static String inlineCodeOpener(ConvertState self, boolean separateFromListText) {
    int last = lastOutputByte(self);
    boolean adjacent =
        last == '`'
            || (separateFromListText
                && last >= 0
                && last != ' '
                && last != '\n'
                && last != '\t'
                && last != '*'
                && last != '_'
                && last != '~'
                && last != '['
                && last != '>');
    boolean captionCommitted =
        self.captionFrames.isEmpty()
            || self.captionFrames.get(self.captionFrames.size() - 1).state
                == ConvertState.CAPTION_COMMITTED;
    if (adjacent
        && self.captionBoundary != ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT
        && captionCommitted) {
      return " `";
    }
    return Consts.MARKDOWN_INLINE_CODE;
  }

  /** Output cut by a rewrite that writes it back, from {@link #takeTailWithBreaks}. */
  private static final class MovedTail {
    String text;
    List<ConvertState.StreamingBreakRun> runs;
    int[] repeatStarts;
    int[] repeatAdds;
    int start;
    int end;

    /** Where the span ending at buffer offset {@code at} ends in the moved text, if cut. */
    int offset(int at) {
      if (at <= this.start || at > this.end) {
        return -1;
      }
      return offsetFrom(at);
    }

    int offsetFrom(int at) {
      int added = 0;
      for (int i = 0; i < repeatStarts.length; i++) {
        if (repeatStarts[i] >= at) {
          break;
        }
        added = repeatAdds[i];
      }
      return at - this.start + added;
    }
  }

  /**
   * Copy the bytes from {@code start} on with their break runs written out, as one-shot charges
   * them.
   */
  private static MovedTail takeTailWithBreaks(ConvertState self, int start) {
    int end = self.buffer.length();
    int first = -1;
    for (int i = 0; i < self.streamingBreakRuns.size(); i++) {
      if (self.streamingBreakRuns.get(i).outputStart >= start) {
        first = i;
        break;
      }
    }
    List<ConvertState.StreamingBreakRun> runs = new ArrayList<>();
    if (first >= 0) {
      runs.addAll(self.streamingBreakRuns.subList(first, self.streamingBreakRuns.size()));
      self.streamingBreakRuns.subList(first, self.streamingBreakRuns.size()).clear();
    }
    StringBuilder text = new StringBuilder(end - start);
    int[] repeatStarts = new int[runs.size()];
    int[] repeatAdds = new int[runs.size()];
    int repeatCount = 0;
    int cursor = start;
    for (ConvertState.StreamingBreakRun run : runs) {
      text.append(self.buffer, cursor, run.outputStart);
      if (run.count > 1) {
        for (int i = 1; i < run.count; i++) {
          text.append(run.unit);
        }
        repeatStarts[repeatCount] = run.outputStart;
        repeatAdds[repeatCount] = text.length() - (run.outputStart - start);
        repeatCount++;
      }
      int unitStart = text.length();
      text.append(self.buffer, run.outputStart, run.outputEnd);
      cursor = run.outputEnd;
      run.outputStart = unitStart;
      run.outputEnd = text.length();
      run.count = 1;
    }
    text.append(self.buffer, cursor, end);
    MovedTail tail = new MovedTail();
    tail.text = text.toString();
    tail.runs = runs;
    tail.repeatStarts = Arrays.copyOf(repeatStarts, repeatCount);
    tail.repeatAdds = Arrays.copyOf(repeatAdds, repeatCount);
    tail.start = start;
    tail.end = end;
    return tail;
  }

  /** Move the trailing whitespace at {@code start} behind {@code output}, which closes a marker. */
  private static void pushOutputBeforeEmphasisWhitespace(
      ConvertState self, String output, int start) {
    MovedTail trailing = takeTailWithBreaks(self, start);
    boolean breakAtEnd = trailing.offset(self.hardBreakEnd) == trailing.text.length();
    truncateBuffer(self, start);
    if (!output.isEmpty()) {
      self.lastContentCacheLen = pushCodeSpanContent(self, output, true);
    }
    int target = self.buffer.length();
    self.buffer.append(trailing.text);
    self.lastContentCacheLen = trailing.text.length();
    restoreTail(self, trailing, target);
    if (breakAtEnd) {
      self.hardBreakEnd = self.buffer.length();
    }
  }

  /** Reattach the break runs and marker tab offsets of a tail written back at {@code to}. */
  private static void restoreTail(ConvertState self, MovedTail tail, int to) {
    for (ConvertState.OpenMarker marker : self.openMarkers) {
      if (marker.verticalTabStart >= tail.start && marker.verticalTabStart < tail.end) {
        marker.verticalTabStart = tail.offsetFrom(marker.verticalTabStart) + to;
      }
    }
    for (ConvertState.StreamingBreakRun run : tail.runs) {
      run.outputStart += to;
      run.outputEnd += to;
      self.streamingBreakRuns.add(run);
    }
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
    // A line can begin a Markdown block before the backtick closes.
    String rawContent = self.buffer.substring(span.contentStart);
    if (rawContent.indexOf('\n') >= 0 || rawContent.indexOf('\r') >= 0) {
      String flattened = rawContent.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
      replaceCodeSpanContent(self, span.contentStart, self.buffer.length(), flattened);
    }
    if (self.depthMap[Consts.TAG_TABLE] > 0
        && span.contentStart <= self.buffer.length()
        && self.buffer.substring(span.contentStart).indexOf('|') >= 0) {
      String content = self.buffer.substring(span.contentStart);
      // A pipe splits the row even inside a code span, and its escaped form still
      // has to fit the aggregate budget.
      long escapedCap = Integer.MAX_VALUE;
      if (self.maxNodeBytesInt != 0) {
        if (!self.codeSpans.isEmpty()) {
          int held =
              Scan.utf8Length(
                  self.buffer, self.codeSpans.get(0).contentStart, self.buffer.length());
          escapedCap =
              (long) Scan.utf8Length(content) + Math.max(0, self.maxNodeBytesInt - held);
        } else {
          escapedCap = self.maxNodeBytesInt;
        }
      }
      StringBuilder escaped = new StringBuilder(content.length() + 8);
      long escapedBytes = 0;
      for (int i = 0; i < content.length(); ) {
        int cp = content.codePointAt(i);
        int addition = cp == '|' ? 2 : ParseOps.utf8Length(cp);
        if (escapedBytes + addition > escapedCap) {
          self.truncated = true;
          break;
        }
        escapedBytes += addition;
        if (cp == '|') {
          escaped.append('\\');
        }
        escaped.appendCodePoint(cp);
        i += Character.charCount(cp);
      }
      self.buffer.replace(span.contentStart, self.buffer.length(), escaped.toString());
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
    // A run that only the line's block lead precedes is the heading's own marker
    // instead: the heading is empty, and the marker's trailing space would read
    // as a list item's open content column.
    int marker = end - 1;
    while (marker > 0 && bytes.charAt(marker - 1) == '#') {
      marker--;
    }
    if (opensBlockLine(self, marker)) {
      if (bytes.charAt(bytes.length() - 1) == ' ') {
        trimTrailingSpaces(self);
      }
      return;
    }
    // Past the block lead, only a run after whitespace closes the heading. Inside
    // a code fence the heading is code, where a backslash would be literal text.
    if (marker > 0
        && (bytes.charAt(marker - 1) == ' ' || bytes.charAt(marker - 1) == '\t')
        && !inPreCodeFence(self)) {
      replaceCodeSpanContent(self, marker, marker, "\\");
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
    boolean contentLeftBuffer = self.lastYieldedLength > frame.contentStart;
    if (contentEnd < frame.contentStart
        || (contentEnd == frame.contentStart && contentLeftBuffer)) {
      // A hard break is structural even when the quote has no visible text.
      if (frame.itemSeparatorLen > 0
          && (self.hardBreakEnd <= frame.contentStart || self.hardBreakEnd == Integer.MAX_VALUE)) {
        truncateBuffer(self, frame.contentStart - frame.itemSeparatorLen);
        self.lastContentCacheLen = 0;
        self.hasLastTextNode = false;
        self.lastTextNodeContainsWhitespace = false;
      }
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
    if (self.maxNodeBytesInt != 0 && !self.codeSpans.isEmpty()) {
      ConvertState.CodeSpanState span = self.codeSpans.get(0);
      if (frame.contentStart >= span.contentStart && quoted.length() > content.length()) {
        int held = Scan.utf8Length(self.buffer, span.contentStart, self.buffer.length());
        if (span.exhausted
            || held - Scan.utf8Length(content) + Scan.utf8Length(quoted)
                > self.maxNodeBytesInt) {
          span.exhausted = true;
          self.truncated = true;
          // Structural growth is atomic: never retain the unquoted payload.
          truncateBuffer(self, frame.contentStart);
          return;
        }
      }
    }
    // The quote prefix shifts every recorded offset inside the content, so the
    // fragment pass and the offset-keyed state below must be remapped too.
    for (ConvertState.FragmentLink link : self.fragmentLinks) {
      if (link.bracketStart >= frame.contentStart && link.linkEnd <= contentEnd) {
        link.bracketStart =
            frame.contentStart
                + blockquoteOffset(
                    content, frame.listIndent, link.bracketStart - frame.contentStart);
        link.textEnd =
            frame.contentStart
                + blockquoteOffset(content, frame.listIndent, link.textEnd - frame.contentStart);
        link.linkEnd =
            frame.contentStart
                + blockquoteOffset(content, frame.listIndent, link.linkEnd - frame.contentStart);
      }
    }
    int quotedEnd = frame.contentStart + quoted.length();
    for (ConvertState.CodeSpanState span : self.codeSpans) {
      // A span opened before the quote holds the prefix written at its content
      // start, as in JS, so `<code><blockquote>` keeps its opener in place.
      if (span.outputStart < frame.contentStart && span.contentStart == frame.contentStart) {
        continue;
      }
      span.outputStart = blockquoteRemap(span.outputStart, frame, content, contentEnd, quotedEnd);
      span.contentStart = blockquoteRemap(span.contentStart, frame, content, contentEnd, quotedEnd);
    }
    for (ConvertState.CaptionFrame frameState : self.captionFrames) {
      if (frameState.state == ConvertState.CAPTION_TENTATIVE) {
        frameState.tentativeOutputStart =
            blockquoteRemap(
                frameState.tentativeOutputStart, frame, content, contentEnd, quotedEnd);
        frameState.tentativeContentStart =
            blockquoteRemap(
                frameState.tentativeContentStart, frame, content, contentEnd, quotedEnd);
      }
    }
    if (self.codeFence != null) {
      int marker = self.codeFence.outputStart + self.codeFence.markerOffset;
      self.codeFence.outputStart =
          blockquoteRemap(self.codeFence.outputStart, frame, content, contentEnd, quotedEnd);
      self.codeFence.contentStart =
          blockquoteRemap(self.codeFence.contentStart, frame, content, contentEnd, quotedEnd);
      marker = blockquoteRemap(marker, frame, content, contentEnd, quotedEnd);
      self.codeFence.markerOffset = marker - self.codeFence.outputStart;
    }
    if (self.rawHtmlScannedTo < contentEnd && self.inRawHtmlBlock()) {
      trackRawHtmlMarkdownContext(self, contentEnd);
    }
    int listGap = listGapBeforeRequote(self, contentEnd);
    truncateBuffer(self, frame.contentStart);
    self.buffer.append(quoted);
    requoteStreamPositions(self, contentEnd, quotedEnd);
    listGapAfterRequote(self, listGap, contentEnd, quotedEnd);
    self.rawHtmlScannedTo =
        self.rawHtmlScannedTo < contentEnd
            ? quotedEnd
            : self.rawHtmlScannedTo + quotedEnd - contentEnd;
    self.lastContentCacheLen = quoted.length();
  }

  /** Maps an offset inside a blockquote's content to the same offset after `> ` quoting. */
  private static int blockquoteOffset(String content, String listIndent, int offset) {
    int sourceStart = 0;
    int outputStart = 0;
    int index = 0;
    while (index < content.length()) {
      int newline = content.indexOf('\n', index);
      int lineEnd = newline < 0 ? content.length() : newline;
      String lineContent = content.substring(index, lineEnd);
      int removed =
          !listIndent.isEmpty() && lineContent.startsWith(listIndent)
              ? listIndent.length()
              : 0;
      int unindentedLength = Math.max(0, lineContent.length() - removed);
      int prefixLength = listIndent.length() + 1 + (unindentedLength > 0 ? 1 : 0);
      int sourceLineEnd = sourceStart + lineContent.length();
      if (offset <= sourceLineEnd) {
        return outputStart + prefixLength + Math.max(0, offset - sourceStart - removed);
      }
      sourceStart += lineContent.length() + (newline < 0 ? 0 : 1);
      outputStart += prefixLength + unindentedLength + (newline < 0 ? 0 : 1);
      index = newline < 0 ? content.length() : newline + 1;
    }
    return outputStart;
  }

  private static int blockquoteRemap(
      int offset,
      ConvertState.BlockquoteFrame frame,
      String content,
      int contentEnd,
      int quotedEnd) {
    if (offset < frame.contentStart) {
      return offset;
    }
    if (offset <= contentEnd) {
      return frame.contentStart
          + blockquoteOffset(content, frame.listIndent, offset - frame.contentStart);
    }
    return quotedEnd;
  }

  // === Captions ===

  static boolean figcaptionOwnsBlockSpacing(ConvertState self) {
    return !self.inTableCell() && (self.plainText || self.collapseNonSpanDepth == 1);
  }

  private static void pushCaptionContent(ConvertState self, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    self.lastContentCacheLen = pushCodeSpanContent(self, value, true);
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
    int spacing;
    if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_PENDING) {
      spacing = self.captionBoundaryNewLines;
    } else if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT) {
      spacing = 2;
    } else {
      spacing = -1;
    }
    if (spacing >= 0) {
      writeCaptionBoundary(self, spacing, includeListIndent);
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE;
    }
    return consumesBoundary;
  }

  static boolean quoteBoundaryPending(ConvertState self) {
    return self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT
        || self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE;
  }

  // Default inline output takes the separator without settling it, so the next
  // list or block transition still spaces itself. Literal output settles it.
  private static boolean prepareInlineQuoteBoundary(ConvertState self, boolean literal) {
    int separatorStart = self.buffer.length();
    boolean consumesBoundary = prepareCaptionBoundary(self, true);
    if (literal) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    } else if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE
        || (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE
            && self.captionBoundaryNewLines == ConvertState.QUOTE_CLOSED)) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE;
      self.captionBoundaryNewLines = self.buffer.length() - separatorStart;
    }
    return consumesBoundary;
  }

  // Text after a closing delimiter continues its line, so its first space
  // separates words. It is not the block's first text, which the parser trims.
  private static void closeQuoteWrapper(ConvertState self) {
    self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE;
    self.captionBoundaryNewLines = ConvertState.QUOTE_CLOSED;
    if (self.firstBlockParentIndex >= 0) {
      self.stack.get(self.firstBlockParentIndex).childTextNodeIndex++;
    }
  }

  // Marker streaming holds already retain the preceding whitespace. Only a
  // fully dropped wrapper exposes this tentative separator at the buffer tail.
  static void restoreQuoteParentBoundary(ConvertState self) {
    if (self.captionBoundary != ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE) {
      return;
    }
    int length = self.captionBoundaryNewLines;
    if (length <= 0 || length > self.buffer.length()) {
      return;
    }
    int start = self.buffer.length() - length;
    for (int i = start; i < self.buffer.length(); i++) {
      if (!Chars.isAsciiWhitespace(self.buffer.charAt(i))) {
        return;
      }
    }
    truncateBuffer(self, start);
    self.lastContentCacheLen = 0;
    self.lastTextNodeContainsWhitespace = false;
    self.hasLastTextNode = false;
    self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT;
  }

  /**
   * A quote's pending continuation meets the next element's enter. Out of line: runs for every
   * element in the text and HTML formats.
   */
  private static String settleQuoteBoundaryAtEnter(
      ConvertState self,
      int tagId,
      boolean isInline,
      boolean enterIsLiteral,
      String output) {
    if (tagId == Consts.TAG_LI) {
      restoreQuoteParentBoundary(self);
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    } else if (!isInline && self.openMarkers.isEmpty()) {
      // Once its wrappers closed, a block settles the tentative spacing. One
      // that survived took the separator, so the block spaces itself; if all
      // dropped, the boundary is pending again and writes the quote's break.
      restoreQuoteParentBoundary(self);
      if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE) {
        self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
      } else if (tagId == Consts.TAG_BLOCKQUOTE && !enterIsLiteral) {
        if (onItemMarkerLine(self)) {
          self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
          output = "\n";
        } else {
          output = null;
        }
      }
    } else if (tagId == Consts.TAG_BR
        && self.captionBoundary != ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT) {
      // The break's whitespace would pass for the spacing at the buffer tail.
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE;
      self.captionBoundaryNewLines = 0;
    }
    // Paragraph, HR and fence fragments can already carry their block separator.
    if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT
        && output != null
        && output.startsWith("\n\n")) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE;
    }
    return output;
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
      expandDeferredCaptionBreaks(self, false);
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

  /**
   * Hold a repeated whitespace {@code <br>} fragment instead of growing the buffer.
   *
   * <p>The byte count is kept in one-shot conversion too, so the cap cuts both at the same tag.
   * While streaming, consecutive identical fragments collapse into one copy plus a count that is
   * expanded when the run is yielded.
   */
  private static boolean deferStreamingBreak(ConvertState self, String fragment) {
    if (!self.blockquotes.isEmpty()
        || self.retainsWholeDocument()
        || self.depthMap[Consts.TAG_PRE] != 0
        || self.depthMap[Consts.TAG_CODE] != 0) {
      return false;
    }
    if (fragment.isEmpty()) {
      return false;
    }
    self.heldBreakBytes += Scan.utf8Length(fragment);
    // A run per break would grow with the held tabs; the bytes are charged to the cap instead.
    if (!self.streaming
        || (self.emptyItemHazard && chargesMarkerLine(self))
        || verticalTabHoldStart(self) >= 0) {
      return false;
    }
    ConvertState.StreamingBreakRun last =
        self.streamingBreakRuns.isEmpty()
            ? null
            : self.streamingBreakRuns.get(self.streamingBreakRuns.size() - 1);
    if (last != null
        && last.outputEnd == self.buffer.length()
        && last.unit.equals(fragment)
        && self.buffer.substring(last.outputStart, last.outputEnd).equals(fragment)) {
      last.count++;
      self.lastContentCacheLen = fragment.length();
      return true;
    }

    int outputStart = self.buffer.length();
    writeOutput(self, true, true, 0, fragment, false);
    if (self.buffer.length() == outputStart + fragment.length()) {
      ConvertState.StreamingBreakRun run = new ConvertState.StreamingBreakRun();
      run.outputStart = outputStart;
      run.outputEnd = self.buffer.length();
      run.unit = fragment;
      run.count = 1;
      self.streamingBreakRuns.add(run);
    }
    return true;
  }

  private static void writeDeferredBreakRun(
      ConvertState self, ConvertState.DeferredBreakRun run, boolean beforeText) {
    if (run == null) {
      return;
    }
    trimTrailingSpaces(self);
    // Keep a leading break before tentative emphasis, where its whitespace
    // cannot invalidate the delimiter's CommonMark left-flanking rule.
    // Code spans keep their order so separate breaks cannot form a blank line.
    ConvertState.OpenMarker opener =
        self.openMarkers.isEmpty() ? null : self.openMarkers.get(self.openMarkers.size() - 1);
    boolean atOpener =
        opener != null
            && opener.kind <= 4
            && opener.reopened == 0
            && opener.contentStart == self.buffer.length();
    int anchor = -1;
    if (atOpener && beforeText && self.codeSpans.isEmpty()) {
      for (ConvertState.CaptionFrame frame : self.captionFrames) {
        if (frame.state == ConvertState.CAPTION_TENTATIVE) {
          int start = Math.max(0, frame.tentativeContentStart - 1);
          boolean emphasisOnly = true;
          for (int i = start; i < self.buffer.length(); i++) {
            char c = self.buffer.charAt(i);
            if (c != '*' && c != '~') {
              emphasisOnly = false;
              break;
            }
          }
          if (emphasisOnly) {
            anchor = start;
          }
          break;
        }
      }
    }
    if (anchor >= 0) {
      StringBuilder output = new StringBuilder(run.fragment.length() * run.count);
      for (int i = 0; i < run.count; i++) {
        output.append(run.fragment);
      }
      if (replaceCodeSpanContent(self, anchor, anchor, output.toString())) {
        shiftOffsetsAtOrAfter(self, anchor, output.length());
        self.lastContentCacheLen = 0;
      }
      return;
    }
    for (int i = 0; i < run.count; i++) {
      if (run.fragment.equals("\n") && endsWith(self.buffer, "\n\n")) {
        continue;
      }
      writeOutput(self, true, true, 0, run.fragment, false);
    }
  }

  static void expandDeferredCaptionBreaksUntil(ConvertState self, int end, boolean beforeText) {
    int limit = Math.min(end, self.captionFrames.size());
    for (int index = 0; index < limit; index++) {
      ConvertState.DeferredBreakRun run = self.captionFrames.get(index).deferredBreaks;
      self.captionFrames.get(index).deferredBreaks = null;
      writeDeferredBreakRun(self, run, beforeText);
    }
  }

  static void expandDeferredCaptionBreaks(ConvertState self, boolean beforeText) {
    expandDeferredCaptionBreaksUntil(self, self.captionFrames.size(), beforeText);
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
      boolean restorePendingWhitespace = self.pendingInlineWhitespace;
      // The separator and opener are tentative together. An empty caption
      // returns the separator to pending state instead of leaving a literal
      // space before an enclosing emphasis closer.
      if (prefix == null && self.pendingInlineWhitespace) {
        self.pendingInlineWhitespace = false;
        int last = lastOutputByte(self);
        if (last != -1 && last != ' ' && last != '\n' && last != '\t' && last != '\r') {
          pushCaptionContent(self, " ");
        }
      }
      if (prefix != null) {
        // The caption boundary consumes the separator before its opener.
        self.pendingInlineWhitespace = false;
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
        frame.tentativeRestorePendingWhitespace = restorePendingWhitespace;
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
      self.pendingInlineWhitespace |= frame.tentativeRestorePendingWhitespace;
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
    self.preFencePending = false;
    // The fence opens its own block, which is the break a heading before it
    // owed; written later, that break would land inside the code.
    self.headingBreakPending = false;
    // The fence belongs to the `<pre>` that requested it, not to whatever `<pre>`
    // is open when its first content arrives: a nested `<pre>` must not steal it.
    self.preFenceOwnerDepth = self.preFencePendingDepth;
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
    self.lastContentCacheLen = pushCodeSpanContent(self, fence, true);
    if (self.lastContentCacheLen != fence.length()) {
      self.preFenceOwnerDepth = 0;
      return;
    }
    startCodeFence(self, outputStart, self.buffer.length(), lang, self.listIndent.toString());
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
        && Url.trimUrl(href).startsWith("#")
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
    int prefixLen = self.link.headingBreakPrefixLen;
    int start = bracketPos - prefixLen;
    truncateBuffer(self, start);
    if (self.link.startsAtHardBreak) {
      self.hardBreakEnd = start;
    }
    if (prefixLen != 0) {
      self.headingBreakPending = true;
    }
    resetEmptyTentativeCaptionFrames(self);
    restoreQuoteParentBoundary(self);
    endLink(self);
    self.lastNodeIsInline = isInline;
  }

  /** Clean-mode link reduced to its text: drop the {@code [}, keeping the text. */
  private static void keepLinkText(ConvertState self, int bracketPos, int textLen, boolean isInline) {
    noteBufferRewrite(self, bracketPos);
    self.buffer.deleteCharAt(bracketPos);
    self.lastContentCacheLen = textLen;
    int closer = self.link.closerBefore;
    if (closer != 0
        && bracketPos > 0
        && self.buffer.charAt(bracketPos - 1) == closer
        && Chars.isAsciiAlphanumeric(self.buffer.charAt(bracketPos))) {
      String reference = "&#" + (int) self.buffer.charAt(bracketPos) + ";";
      self.buffer.replace(bracketPos, bracketPos + 1, reference);
      self.lastContentCacheLen += reference.length() - 1;
    }
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
            self.link.bracketPos,
            textEnd,
            self.buffer.length(),
            fragment,
            hasTitle,
            self.link.closerBefore,
            self.link.closerAfter));
  }

  /**
   * Invalidate the measured state a rewrite at {@code offset} can reach.
   *
   * <p>The port keeps no held-run caches, so only the hard-break and blockquote-line offsets
   * remain to reset.
   */
  private static void noteBufferRewrite(ConvertState self, int offset) {
    self.listGapFrom = Math.min(self.listGapFrom, offset);
    if (offset < self.blockquoteLineScannedTo) {
      self.blockquoteLineScannedTo = 0;
      self.blockquoteLineEnd = 0;
    }
    if (self.hardBreakEnd >= 0 && offset < self.hardBreakEnd) {
      self.hardBreakEnd = -1;
    }
    // A rewrite that reaches a closer, or the output right after it, comes
    // between it and the next opener. {@link #truncateBuffer} keeps the closers
    // when only blanks and openers go.
    if (!self.closedMarkers.isEmpty()
        && self.buffer.length() - self.closedMarkers.get(self.closedMarkers.size() - 1)[1]
            > CLOSED_MARKER_REACH) {
      self.closedMarkers.clear();
    }
    while (!self.closedMarkers.isEmpty()
        && offset <= self.closedMarkers.get(self.closedMarkers.size() - 1)[1]) {
      self.closedMarkers.remove(self.closedMarkers.size() - 1);
    }
    if (offset < self.captionLineSpacesStart) {
      self.captionLineSpacesStart = 0;
      self.captionLineSpacesEnd = -1;
    } else if (self.captionLineSpacesEnd > offset) {
      self.captionLineSpacesEnd = offset;
    }
    if (offset < self.captionLineOpensStart) {
      self.captionLineOpensStart = -1;
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
    // Settle the text before the close: trailing whitespace trims here, before the title check
    // reads the cached run.
    writeOutput(self, false, isInline, newLines, null, false);
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
    if (!title.isEmpty() && self.lastContentCacheLen > 0 && !self.cacheFollowsSeparator) {
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
    if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0) {
      self.link.closerAfter = self.pendingEmphasis;
    }
    self.pendingEmphasis = 0;
    StringBuilder output = new StringBuilder();
    output.append(']');
    writeMarkdownResource(output, resolved, title);
    writeOutput(self, false, isInline, 0, output.toString(), false);
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

  // === Heading break in a list item ===

  private static boolean startsWithHeadingPrefix(String value) {
    if (value == null) {
      return false;
    }
    int hashes = 0;
    while (hashes < 7 && hashes < value.length() && value.charAt(hashes) == '#') {
      hashes++;
    }
    return hashes > 0
        && hashes <= 6
        && hashes < value.length()
        && (value.charAt(hashes) == ' ' || value.charAt(hashes) == '\t');
  }

  /** A settled heading break: the output to write and the break bytes it took as a prefix. */
  private static final class SettledHeadingBreak {
    String output;
    int prefixLen;
  }

  /**
   * Resolve a pending heading break at an element's enter. Output that starts its own line already
   * ends the heading's, and an item or a caption writes its own separation. Visible output takes
   * the break as a prefix, so an empty marker pair dropped later takes it back; a leading space
   * meant to separate it from text on the same line goes. Output with nothing visible leaves the
   * break to whatever follows.
   */
  private static SettledHeadingBreak settleHeadingBreak(
      ConvertState self, int tagId, String output) {
    String fragment = output == null ? "" : output;
    SettledHeadingBreak result = new SettledHeadingBreak();
    result.output = output;
    if (tagId == Consts.TAG_LI
        || tagId == Consts.TAG_FIGCAPTION
        || stripLeadingSpaces(fragment).startsWith("\n")) {
      self.headingBreakPending = false;
      // The caption's frame is already pushed; an empty caption hands it back.
      if (tagId == Consts.TAG_FIGCAPTION
          && self.depthMap[Consts.TAG_PRE] == 0
          && !self.captionFrames.isEmpty()) {
        self.captionFrames.get(self.captionFrames.size() - 1).restoresHeadingBreak = true;
      }
    } else if (Chars.hasVisible(fragment)) {
      self.headingBreakPending = false;
      String trimmed = stripLeadingSpaces(fragment);
      result.output = "\n\n" + self.listIndent + trimmed;
      result.prefixLen = 2 + self.listIndent.length();
    }
    return result;
  }

  private static String stripLeadingSpaces(String value) {
    int index = 0;
    while (index < value.length() && value.charAt(index) == ' ') {
      index++;
    }
    return value.substring(index);
  }

  /**
   * Write a pending heading break before a text node. Returns the text that follows it, or
   * {@code null} when the node is only whitespace and keeps waiting.
   */
  private static String flushHeadingBreakBeforeText(ConvertState self, String text) {
    if (!Chars.hasVisible(text)) {
      return null;
    }
    flushHeadingBreak(self);
    // The text opens the item's next block, where a leading space indents it.
    if (self.depthMap[Consts.TAG_PRE] == 0) {
      int index = 0;
      while (index < text.length() && Chars.isAsciiWhitespace(text.charAt(index))) {
        index++;
      }
      return text.substring(index);
    }
    return text;
  }

  private static void flushHeadingBreak(ConvertState self) {
    self.headingBreakPending = false;
    String separator = "\n\n" + self.listIndent;
    self.lastContentCacheLen = pushCodeSpanContent(self, separator, true);
  }

  /**
   * Inside a list item a heading opens its own block unless a wrapper keeps it inline: after text
   * on the item's line, even past a space, its {@code #} prefix would stay text. The space that
   * ended the text goes too, which the reach-back trim keeps before a whitespace-collapsing
   * element.
   */
  private static String openItemHeadingBlock(ConvertState self, String output) {
    if (self.plainText
        || self.depthMap[Consts.TAG_PRE] != 0
        || self.inTableCell()
        || !startsWithHeadingPrefix(output)
        || headingKeptInlineInItem(self, true)
        || opensBlockLine(self, self.buffer.length())) {
      return output;
    }
    trimTrailingSpaces(self);
    return "\n\n" + self.listIndent + (output == null ? "" : output);
  }

  /** A heading opens inside a list item: keep its block on its own line. */
  private static String enterItemHeading(
      ConvertState self, boolean enterIsLiteral, String output) {
    if (!enterIsLiteral
        && output != null
        && output.startsWith("\n")
        && !opensBlockLine(self, self.buffer.length())) {
      trimTrailingSpaces(self);
    }
    return openItemHeadingBlock(self, output);
  }

  /**
   * A heading entering a list item's pending caption. Materializing the caption already places its
   * opening emphasis before the heading, so no separator goes inside that inline run.
   */
  private static boolean captionHeadingEnter(ConvertState self, int tagId) {
    if (self.depthMap[Consts.TAG_LI] == 0
        || tagId < Consts.TAG_H1
        || tagId > Consts.TAG_H6) {
      return false;
    }
    for (ConvertState.CaptionFrame frame : self.captionFrames) {
      if (frame.state != ConvertState.CAPTION_COMMITTED) {
        return true;
      }
    }
    return false;
  }

  /**
   * At a heading's exit in a list item: only an ATX prefix can take in the text after it. Inside
   * {@code <pre>} that text is code, and inside a caption the emphasis wrapping it, either of
   * which a break would split.
   */
  private static void oweItemHeadingBreak(ConvertState self, ElementNode node) {
    if (self.depthMap[Consts.TAG_PRE] != 0) {
      return;
    }
    // The same override the exit resolved: none for a skipped node.
    boolean enterIsOverridden = false;
    boolean enterIsHeading = false;
    if (!node.enterSkipped && self.hasTagOverrides) {
      TagOverride override = self.overrideForNode(node);
      String enter = override == null ? null : override.enter();
      enterIsOverridden = enter != null;
      enterIsHeading = startsWithHeadingPrefix(enter);
    }
    if ((!enterIsOverridden || enterIsHeading) && !headingKeptInlineInItem(self, false)) {
      if (enterIsHeading) {
        trimTrailingSpaces(self);
      }
      self.headingBreakPending = true;
    }
  }

  /**
   * Whether only a block lead precedes {@code at} on its line: indentation, then optionally a list
   * marker and its space. Nested items indent past the three spaces a list marker line accepts.
   */
  static boolean opensBlockLine(ConvertState self, int at) {
    StringBuilder bytes = self.buffer;
    int index = Math.min(at, bytes.length());
    int gap = 0;
    while (index > 0 && bytes.charAt(index - 1) == ' ') {
      index--;
      gap++;
    }
    if (index > 0 && bytes.charAt(index - 1) != '\n') {
      if (gap == 0) {
        return false;
      }
      char marker = bytes.charAt(index - 1);
      if (marker == '-' || marker == '*' || marker == '+') {
        index--;
      } else if (marker == '.' || marker == ')') {
        int digits = index - 1;
        index = digits;
        while (index > 0 && bytes.charAt(index - 1) >= '0' && bytes.charAt(index - 1) <= '9') {
          index--;
        }
        if (index == digits || digits - index > 9) {
          return false;
        }
      } else {
        return false;
      }
      while (index > 0 && bytes.charAt(index - 1) == ' ') {
        index--;
      }
    }
    if (index == 0) {
      return !self.hasFlushedTail() || self.flushedTail[1] == '\n';
    }
    return bytes.charAt(index - 1) == '\n';
  }

  /**
   * {@link #opensBlockLine} at the buffer end, for a dropped caption's exit. Past its first space
   * a gap only counts as present, so the answer holds while the bytes before the space run stay
   * put: a line that empty captions in a row leave untouched is not read again at each exit.
   */
  private static boolean captionExitOpensBlockLine(ConvertState self) {
    int end = self.buffer.length();
    int start;
    if (self.captionLineSpacesEnd == end) {
      start = self.captionLineSpacesStart;
    } else {
      start = end;
      while (start > 0 && self.buffer.charAt(start - 1) == ' ') {
        start--;
      }
      self.captionLineSpacesEnd = end;
      self.captionLineSpacesStart = start;
    }
    if (start == end) {
      self.captionLineOpensStart = -1;
      return opensBlockLine(self, end);
    }
    if (self.captionLineOpensStart == start) {
      return self.captionLineOpens;
    }
    boolean opens = opensBlockLine(self, start + 1);
    self.captionLineOpensStart = start;
    self.captionLineOpens = opens;
    return opens;
  }

  /**
   * A built-in void tag that an override holds open ({@code isSelfClosing: false}).
   */
  private static boolean isOpenVoid(int tagId, TagOverride override) {
    return override != null
        && Boolean.FALSE.equals(override.selfClosing())
        && isVoidHandler(tagId);
  }

  private static boolean isVoidHandler(int tagId) {
    if (tagId < 0) {
      return false;
    }
    TagHandler handler = Tags.getTagHandler(tagId);
    return handler != null && handler.isSelfClosing;
  }

  /**
   * An open void writes its block spacing after its enter, as the void it replaces would. A custom
   * exit has output of its own, so the spacing stays after that exit.
   */
  private static boolean voidSpacingFollowsEnter(TagOverride override) {
    String exit = override.exit();
    return exit == null || exit.isEmpty();
  }

  /**
   * Whether a block child keeps its block spacing because every collapsing ancestor is an open
   * built-in void override. An override holds content, so it does not wrap inline text the way
   * {@code <a>} or {@code <strong>} do. Any other collapsing ancestor keeps the suppression. A
   * heading is a block child too, so the spec's block boundary after it applies. Runs only where
   * the caller already found a collapsing ancestor.
   *
   * <p>The node's own {@code isInline} includes an {@code isInline} override. On enter the node is
   * the stack top; on exit it is the retired slot, where {@code emitExitElement} keeps the flag.
   */
  private static boolean blockChildOfOpenVoid(ConvertState self, int tagId, boolean isEnter) {
    ElementNode node;
    int ancestors;
    if (isEnter) {
      if (self.stack.isEmpty()) {
        return false;
      }
      node = self.stack.get(self.stack.size() - 1);
      ancestors = self.stack.size() - 1;
    } else {
      node = self.stack.retired();
      ancestors = self.stack.size();
    }
    return openVoidsKeepBlockChild(tagId, node, self.stack, ancestors);
  }

  /** {@link #blockChildOfOpenVoid} for any open node, given its ancestors. */
  private static boolean openVoidsKeepBlockChild(
      int tagId, ElementNode node, NodeStack stack, int ancestorCount) {
    if (tagId < 0) {
      return false;
    }
    if (node.isInline) {
      return false;
    }
    // A span keeps only the block children its own exemption keeps.
    boolean spanExempt =
        tagId == Consts.TAG_P || tagId == Consts.TAG_DIV || tagId == Consts.TAG_LI;
    for (int i = 0; i < ancestorCount; i++) {
      ElementNode ancestor = stack.get(i);
      if (!ancestor.collapsesInnerWhiteSpace || ancestor.excludedFromMarkdown) {
        continue;
      }
      if (isVoidHandler(ancestor.tagId)) {
        continue;
      }
      if (ancestor.tagId == Consts.TAG_SPAN && spanExempt) {
        continue;
      }
      return false;
    }
    return true;
  }

  /**
   * Whether the heading at the top of the stack ({@code isEnter}) or just popped stays inline in
   * its list item: a caption's emphasis wraps it, or a wrapper around it writes delimiters that
   * pair across its content, which a block break would split. A raw tag ({@code <u>},
   * {@code <sup>}) or a wrapper that writes nothing ({@code <label>}, {@code <small>}) reads the
   * same either side of it.
   *
   * <p>The search stops at an open void with a block below it, between it and the heading, that
   * kept its block spacing: the void's output, and that of any wrapper the block's spacing passed,
   * sits outside that block.
   */
  private static boolean headingKeptInlineInItem(ConvertState self, boolean isEnter) {
    if (!self.captionFrames.isEmpty()) {
      return true;
    }
    int end = self.stack.size() - (isEnter ? 1 : 0);
    boolean blockKept = false;
    for (int depth = end - 1; depth >= 0; depth--) {
      ElementNode node = self.stack.get(depth);
      if (blockKept && isVoidHandler(node.tagId)) {
        return false;
      }
      if (node.collapsesInnerWhiteSpace && wrapperWritesDelimiters(self, node)) {
        return true;
      }
      blockKept =
          blockKept
              || (self.hasTagOverrides
                  && openVoidsKeepBlockChild(node.tagId, node, self.stack, depth));
    }
    return false;
  }

  /**
   * Emphasis, code, strikethrough and quote wrappers, and an override with output of its own,
   * whose markers are taken as a pair.
   */
  private static boolean wrapperWritesDelimiters(ConvertState self, ElementNode node) {
    if (self.hasTagOverrides) {
      TagOverride override = self.overrideForNode(node);
      if (override != null) {
        String enter = override.enter();
        String exit = override.exit();
        if ("".equals(enter) && "".equals(exit)) {
          return false;
        }
        if ((node.tagId != Consts.TAG_SPAN
                && ((enter != null && !enter.isEmpty()) || (exit != null && !exit.isEmpty())))
            || inlineMarkerType(self.aliasId(override)) != 0) {
          return true;
        }
      }
    }
    return inlineMarkerType(node.tagId) != 0;
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

    int headingBreakPrefixLen = 0;
    if (self.headingBreakPending) {
      SettledHeadingBreak settled = settleHeadingBreak(self, tagId, output);
      output = settled.output;
      headingBreakPrefixLen = settled.prefixLen;
    }

    int outputStart = self.buffer.length();
    int pending = self.pendingEmphasis;
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
      self.link.rewritesBracket = emittedBracket;
      if (emittedBracket
          && (self.cleanFlags
                  & (ConvertState.CLEAN_FRAGMENTS
                      | ConvertState.CLEAN_SELF_LINK_HEADINGS
                      | ConvertState.CLEAN_REDUNDANT_LINKS))
              != 0) {
        self.link.closerBefore = pending;
      }
      if (emittedBracket && headingBreakPrefixLen != 0) {
        self.link.headingBreakPrefixLen = headingBreakPrefixLen;
        self.link.startsAtHardBreak =
            self.hardBreakEnd == bracketPos - self.link.headingBreakPrefixLen;
      }
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
    recordEnterMarker(self, tagId, output, outputStart, false, true, pending);
    // An element that writes nothing, like a dropped `<svg>`, is no boundary.
    boolean hasOwnOutput = output != null && !output.isEmpty();
    if (!isInline
        && (configuredNewLines > 0 || hasOwnOutput)
        && !self.openMarkers.isEmpty()) {
      releaseMarkersAtBlock(self);
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
    if (!self.openMarkers.isEmpty()) {
      boolean writes = config[1] > 0 || (output != null && !output.isEmpty());
      if (isInline || writes) {
        releaseMarkersOnExit(self, !isInline, output);
      } else {
        noteSilentBlock(self);
      }
    }
    int outputStart = self.buffer.length();
    if (emphasisExitFollowsWhitespace(self, tagId, output)) {
      writeEmphasisExit(self, isInline, config[1], output);
    } else {
      writeOutput(self, false, isInline, config[1], output, false);
    }
    noteClosedMarker(self, tagId, output);
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
    if (node.tagId == Consts.TAG_FIGCAPTION && transparentCodeCaption(self, node)) {
      return;
    }
    if (self.listRulePending && !node.excludesTextNodes) {
      flushListRule(self);
    }

    int tagId = node.tagId;
    TagOverride override = self.hasTagOverrides ? self.overrideForNode(node) : null;
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
      // An override with neither enter nor exit renders the row like the
      // built-in handler and keeps the empty-row deferral; an explicit-output
      // override takes over the row's own edges.
      if (self.depthMap[Consts.TAG_TABLE] <= 1 && !self.inTableCell()) {
        self.tableRowOpenerPending =
            !self.plainText
                && !self.tableRenderedTable
                && (override == null || (override.enter() == null && override.exit() == null));
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

    int[] overrideSpacing = override != null ? spacingOverride(override) : null;
    int[] nodeSpacing = overrideSpacing != null ? overrideSpacing : node.spacing;
    int[] captionSpacing =
        tagId == Consts.TAG_FIGCAPTION
            ? (overrideSpacing != null ? overrideSpacing : Consts.DEFAULT_BLOCK_SPACING)
            : Consts.NO_SPACING;

    boolean openVoidEnter = false;
    boolean enterIsLiteral = false;
    String output;
    if (override != null) {
      output = override.enter() != null ? override.enter() : getEnterOutput(self, node);
      enterIsLiteral = override.enter() != null;
      // A built-in void tag an override holds open has no exit before its
      // children, so the block spacing its exit would write follows its enter
      // here. Only the newlines the enter output does not already end with.
      // The enter-side call keeps the node out of its own ancestors.
      openVoidEnter = isOpenVoid(tagId, override);
      if (openVoidEnter) {
        // A rule an override keeps open owes the separator before its children.
        if (tagId == Consts.TAG_HR
            && override.enter() == null
            && !self.plainText
            && !self.inTableCell()
            && self.depthMap[Consts.TAG_LI] > 0) {
          self.listRulePending = true;
        }
        if (voidSpacingFollowsEnter(override)) {
          int after = calculateNewLineConfig(self, tagId, nodeSpacing, true)[1];
          if (tagId == Consts.TAG_HR && !self.blockquotes.isEmpty()) {
            after = Math.min(after, 1);
          }
          String text = output == null ? "" : output;
          int have = 0;
          while (have < text.length() && text.charAt(text.length() - 1 - have) == '\n') {
            have++;
          }
          int owed = Math.max(0, after - Math.min(have, 255));
          if (have == text.length()) {
            // Nothing visible separates these newlines from the ones before the
            // enter, so they join that run and merge with what the buffer ends
            // on. The spacing call above returned this spacing, so the enter's
            // own call returns the raised value too.
            int[] spacing = nodeSpacing == null ? Consts.DEFAULT_BLOCK_SPACING : nodeSpacing;
            nodeSpacing = new int[] {Math.max(spacing[0], owed), spacing[1]};
          } else if (owed > 0) {
            StringBuilder text2 = new StringBuilder(text);
            for (int k = 0; k < owed; k++) {
              text2.append('\n');
            }
            output = text2.toString();
          }
        }
      }
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
    // Empty list containers emit nothing. Only an actual item replaces the
    // quote's pending continuation with list structure.
    if (quoteBoundaryPending(self)) {
      output = settleQuoteBoundaryAtEnter(self, tagId, node.isInline, enterIsLiteral, output);
    }
    // With no caption or quote state pending, and no blockquote entering, none of
    // the caption handling below can write or change state, so it is skipped.
    boolean captionFast =
        self.captionBoundary == ConvertState.CAPTION_BOUNDARY_NONE
            && self.captionFrames.isEmpty()
            && tagId != Consts.TAG_BLOCKQUOTE;
    boolean structuralQuote = tagId == Consts.TAG_BLOCKQUOTE && !enterIsLiteral;
    boolean quoteSiblingSeparator = false;
    boolean captionHeadingEnter = false;
    boolean consumesCaptionBoundary = false;
    boolean opensCaption = false;
    if (!captionFast) {
      boolean captionActive =
          self.captionBoundary != ConvertState.CAPTION_BOUNDARY_NONE
              || !self.captionFrames.isEmpty();
      boolean captionVisibleOutput =
          captionActive && output != null && Chars.hasVisible(output);
      boolean defaultInlineMarker =
          !enterIsLiteral && captionVisibleOutput && inlineMarkerType(tagId) != 0;
      boolean tentativeCaptionOutput =
          defaultInlineMarker || (!enterIsLiteral && tagId == Consts.TAG_A);
      boolean quoteParent =
          self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT;
      // A sibling quote keeps the separator after a closed quote's content. An
      // empty quote left the item's opening line, where a blank would end it.
      quoteSiblingSeparator =
          quoteParent
              && structuralQuote
              && lastOutputByte(self) >= 0
              && lastOutputByte(self) != '\n';
      captionHeadingEnter = captionVisibleOutput && captionHeadingEnter(self, tagId);
      if (captionVisibleOutput || structuralQuote) {
        expandDeferredCaptionBreaks(self, false);
      }
      if (captionVisibleOutput || structuralQuote) {
        // An anchor without an href writes nothing, so it cannot carry the boundary.
        if (captionVisibleOutput && node.isInline && quoteBoundaryPending(self)) {
          consumesCaptionBoundary = prepareInlineQuoteBoundary(self, enterIsLiteral);
        } else {
          consumesCaptionBoundary =
              prepareCaptionBoundary(self, tagId != Consts.TAG_LI && !structuralQuote);
        }
      }
      if (captionVisibleOutput && (tagId != Consts.TAG_BLOCKQUOTE || enterIsLiteral)) {
        opensCaption =
            !enterIsLiteral
                && !tentativeCaptionOutput
                && !self.captionFrames.isEmpty()
                && self.captionFrames.get(self.captionFrames.size() - 1).state
                    != ConvertState.CAPTION_COMMITTED;
        int mode =
            captionEnter && enterIsLiteral
                ? CAPTION_MATERIALIZE_EXPLICIT_COMMIT
                : (tentativeCaptionOutput
                    ? CAPTION_MATERIALIZE_TENTATIVE
                    : CAPTION_MATERIALIZE_COMMIT);
        materializeCaptionFrames(self, mode);
      }
    }
    int configuredNewLines =
        quoteAtStart(self) || consumesCaptionBoundary || captionEnter ? 0 : config[0];

    // emptyLinks: skip hrefs that cannot represent meaningful navigation.
    if (tagId == Consts.TAG_A
        && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINKS) != 0) {
      String href = node.attributes.getBit(Consts.ATTR_HREF);
      if (href != null && Url.isEmptyLinkHref(href)) {
        beginLink(self, self.buffer.length(), true, false);
        resetEmptyTentativeCaptionFrames(self);
        if (self.streaming) {
          self.link.holdReleased = true;
        }
        self.lastNodeIsInline = node.isInline;
        return;
      }
    }

    if (!self.plainText && ConvertState.isRawHtmlBlockTag(tagId)) {
      enterRawHtmlBoundary(self, enterIsLiteral, output != null, configuredNewLines);
    }

    int headingBreakPrefixLen = 0;
    if (self.headingBreakPending) {
      SettledHeadingBreak settled = settleHeadingBreak(self, tagId, output);
      output = settled.output;
      headingBreakPrefixLen = settled.prefixLen;
    }
    if (tagId >= Consts.TAG_H1
        && tagId <= Consts.TAG_H6
        && self.depthMap[Consts.TAG_LI] > 0) {
      output = enterItemHeading(self, enterIsLiteral, output);
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
        && !output.equals(" ")
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
    if (!self.plainText
        && !enterIsLiteral
        && tagId == Consts.TAG_BR
        && configuredNewLines == 0
        && output != null
        && !output.isEmpty()
        && !output.equals(" ")
        && !Chars.hasVisible(output)
        && deferStreamingBreak(self, output)) {
      self.pendingInlineWhitespace = false;
      self.lastTextNodeContainsWhitespace = false;
      self.hasLastTextNode = false;
      self.lastNodeIsInline = node.isInline;
      return;
    }

    if (tagId == Consts.TAG_LI && !self.plainText && !self.inTableCell()) {
      self.listRulePending = false;
      int outputStart = self.buffer.length();
      writeOutput(self, true, node.isInline, configuredNewLines, output, enterIsLiteral);
      recordItemMarker(self, node.index, outputStart);
      return;
    }

    boolean rawHtmlQuoteBoundary =
        self.pendingRawHtmlBoundary && !enterIsLiteral && tagId == Consts.TAG_BLOCKQUOTE;
    boolean quoteOnItemMarker =
        !self.plainText
            && !enterIsLiteral
            && tagId == Consts.TAG_BLOCKQUOTE
            && onItemMarkerLine(self);
    int outputStart = self.buffer.length();
    int pending = self.pendingEmphasis;
    writeOutput(
        self, true, node.isInline, configuredNewLines, output, enterIsLiteral || captionHeadingEnter);
    if (openVoidEnter) {
      self.lastNodeIsInline = true;
    }
    if (enterIsLiteral && node.isInline && self.buffer.length() > outputStart) {
      self.literalOpenerEnds.add(new int[] {node.depth, self.drained + self.buffer.length()});
    }

    if (tagId == Consts.TAG_IMG
        && !enterIsLiteral
        && self.buffer.length() > outputStart
        && output != null) {
      // Alt text from a void image ends a word. A held-open image can
      // continue its output with child text.
      self.imageTextPending |= !openVoidEnter && self.plainText && !output.isEmpty();
      if (self.lastContentCacheLen == output.length()
          && (!self.plainText || Chars.hasVisible(output))) {
        markRenderedChildContent(self);
      }
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
      if (!self.blockquotes.isEmpty()
          && !quoteSiblingSeparator
          && endsWith(self.buffer, "\n\n")
          && (!rawHtmlQuoteBoundary || endsWith(self.buffer, "\n\n\n"))) {
        truncateBuffer(self, self.buffer.length() - 1);
        for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
          frame.contentStart = Math.min(frame.contentStart, self.buffer.length());
        }
      }
      if (self.pendingRawHtmlBoundary) {
        finishRawHtmlBlock(self, ">", false, 0);
      }
      self.blockquotes.add(
          new ConvertState.BlockquoteFrame(
              self.buffer.length(), self.inTableCell() ? "" : self.listIndent.toString()));
      self.blockquotes.get(self.blockquotes.size() - 1).itemSeparatorLen =
          quoteOnItemMarker ? self.buffer.length() - outputStart : 0;
    }

    if (!self.plainText && !enterIsLiteral && isInlineCodeTag(tagId)) {
      if (self.rawCodeOwnerDepth < 0
          && self.buffer.length() > outputStart
          && (output != null && output.equals("<code>")
              || (self.inRawHtmlBlock()
                  && output != null
                  && output.equals(Consts.MARKDOWN_INLINE_CODE)))) {
        self.rawCodeOwnerDepth = node.depth;
      }
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
      } else if (tagId == Consts.TAG_CODE
          && self.preFenceOwnerDepth == 0
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
        self.link.rewritesBracket = emittedBracket && !exitIsOverridden;
        if (emittedBracket
            && (self.cleanFlags
                    & (ConvertState.CLEAN_FRAGMENTS
                        | ConvertState.CLEAN_SELF_LINK_HEADINGS
                        | ConvertState.CLEAN_REDUNDANT_LINKS))
                != 0) {
          self.link.closerBefore = pending;
        }
        if (emittedBracket && headingBreakPrefixLen != 0) {
          self.link.headingBreakPrefixLen = headingBreakPrefixLen;
          self.link.startsAtHardBreak =
              self.hardBreakEnd == bracketPos - self.link.headingBreakPrefixLen;
        }
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

    settleEmptyTextPending(
        self,
        outputStart,
        tagId == Consts.TAG_A
            || tagId == Consts.TAG_CODE
            || inlineMarkerType(tagId) != 0);
    boolean codeSpanMarker = self.depthMap[Consts.TAG_PRE] == 0 && !self.inRawHtmlBlock();
    int markerTag =
        !enterIsLiteral && (tagId != Consts.TAG_CODE || codeSpanMarker) ? tagId : -1;
    boolean keepsMarkers =
        tagId == Consts.TAG_A && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) != 0;
    boolean exitIsOverridden = override != null && override.exit() != null;
    recordEnterMarker(
        self, markerTag, output, outputStart, keepsMarkers, !exitIsOverridden, pending);
    // An element that writes nothing, like a dropped `<svg>`, is no boundary.
    boolean hasOwnOutput = output != null && !output.isEmpty();
    if (!node.isInline
        && (configuredNewLines > 0 || hasOwnOutput)
        && !self.openMarkers.isEmpty()) {
      releaseMarkersAtBlock(self);
    }
    if (consumesCaptionBoundary
        && (!node.isInline || tagId == Consts.TAG_IMG || tagId == Consts.TAG_BR)) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    } else if (opensCaption
        && node.isInline
        && tagId != Consts.TAG_IMG
        && tagId != Consts.TAG_BR
        && self.captionBoundary == ConvertState.CAPTION_BOUNDARY_NONE) {
      // Inline output that opens a caption leaves the next source space to it.
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE;
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
    if (!override.hasSpacing()) {
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
      // A heading's break is owed only inside its item, even one whose markers a
      // plugin skipped. An excluded item is no part of the output at all.
      if (tagId == Consts.TAG_LI && !node.excludedFromMarkdown) {
        self.headingBreakPending = false;
      }
      self.lastNodeIsInline = isInline;
      return;
    }
    if (self.htmlFormat) {
      if (tagId == Consts.TAG_LI) {
        self.headingBreakPending = false;
      }
      HtmlOutputOps.emitHtmlExit(self, node);
      self.lastNodeIsInline = isInline;
      return;
    }
    if (tagId == Consts.TAG_FIGCAPTION && transparentCodeCaption(self, node)) {
      return;
    }
    if (isInlineCodeTag(tagId) && self.rawCodeOwnerDepth == node.depth) {
      self.rawCodeOwnerDepth = -1;
    }
    boolean emptyQuoteItemExit = false;
    if (tagId == Consts.TAG_LI) {
      if (self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT
          || self.captionBoundary == ConvertState.CAPTION_BOUNDARY_QUOTE_TENTATIVE) {
        restoreQuoteParentBoundary(self);
        // A discarded quote reopened this marker line. Close the empty item
        // before any parent continuation can arrive.
        if (onItemMarkerLine(self)) {
          self.buffer.append('\n');
          self.lastContentCacheLen = 1;
          emptyQuoteItemExit = true;
        }
        self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
      }
      self.headingBreakPending = false;
    }
    boolean ownsPreFence = tagId == Consts.TAG_PRE;
    boolean closesOwnPreFence =
        tagId == Consts.TAG_PRE && self.preFenceOwnerDepth == self.depthMap[Consts.TAG_PRE];
    if (self.hasTagOverrides) {
      // The caller moved the node out of its slot. The open-void spacing check
      // reads the flag back there, and only overrides reach that check.
      self.stack.retired().isInline = node.isInline;
      // A literal opener this element wrote no longer ends the output once it closes.
      while (!self.literalOpenerEnds.isEmpty()
          && self.literalOpenerEnds.get(self.literalOpenerEnds.size() - 1)[0] >= node.depth) {
        self.literalOpenerEnds.remove(self.literalOpenerEnds.size() - 1);
      }
    }
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
    boolean openVoid = isOpenVoid(tagId, override);
    boolean voidSpacingAtEnter = openVoid && override != null && voidSpacingFollowsEnter(override);

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
    if (isInlineCodeTag(tagId) && self.depthMap[Consts.TAG_PRE] == 0
        && !self.inRawHtmlBlock()
        && !self.codeSpans.isEmpty()) {
      ConvertState.CodeSpanState top = self.codeSpans.get(self.codeSpans.size() - 1);
      if (top.depth == node.depth) {
        closingCodeSpan = self.codeSpans.remove(self.codeSpans.size() - 1);
      }
    }

    boolean closesQuote =
        !self.plainText && tagId == Consts.TAG_BLOCKQUOTE && !self.blockquotes.isEmpty();
    if (closesQuote) {
      finalizeBlockquote(self);
    }
    if (!self.plainText && tagId == Consts.TAG_BLOCKQUOTE) {
      self.pendingRawHtmlBoundary = false;
    }

    if (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6) {
      self.headingHashRun = 0;
    }

    if (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6 && self.depthMap[Consts.TAG_A] == 0) {
      if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && self.inHeading) {
        // The recorded start can sit past the buffer end when a following
        // element that emits nothing trimmed the heading's trailing space.
        if (self.headingBufferStart <= self.buffer.length()) {
          String slug = Url.slugifyMarkdownHeading(self.buffer.substring(self.headingBufferStart));
          if (!slug.isEmpty()) {
            self.headingSlugs.add(slug);
          }
        }
        self.inHeading = false;
      }
      if (!self.plainText && !self.inTableCell()) {
        // An overridden exit inside a code span has no ATX closing sequence.
        // Keep its literal hashes, as the override's own handler does in JS.
        if (!hasOverride || self.codeSpans.isEmpty()) {
          escapeTrailingHeadingHashes(self);
        }
        if (self.depthMap[Consts.TAG_LI] > 0) {
          oweItemHeadingBreak(self, node);
        }
      }
    }

    if (tagId == Consts.TAG_A && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) == 0) {
      expandDeferredCaptionBreaks(self, false);
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
        && self.depthMap[Consts.TAG_LI] > 0
        && !openVoid) {
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
        expandDeferredCaptionBreaks(self, false);
        consumesCaptionBoundary =
            (isInline && quoteBoundaryPending(self))
                ? prepareInlineQuoteBoundary(self, hasOverride)
                : prepareCaptionBoundary(
                    self, tagId != Consts.TAG_BLOCKQUOTE && tagId != Consts.TAG_LI);
        materializeCaptionFrames(
            self,
            defaultInlineMarker ? CAPTION_MATERIALIZE_TENTATIVE : CAPTION_MATERIALIZE_COMMIT);
      }
    } else if (hasOverride && captionVisibleOutput) {
      expandDeferredCaptionBreaks(self, false);
      consumesCaptionBoundary = prepareCaptionBoundary(self, true);
      materializeCaptionFrames(self, CAPTION_MATERIALIZE_COMMIT);
    }

    int captionExitSpacing = 0;
    boolean captionOpened = false;
    if (captionExit) {
      expandDeferredCaptionBreaksUntil(
          self, Math.max(0, self.captionFrames.size() - 1), false);
    }
    if (captionExit && !self.captionFrames.isEmpty()) {
      ConvertState.CaptionFrame frame = self.captionFrames.remove(self.captionFrames.size() - 1);
      ConvertState.DeferredBreakRun deferredBreaks = frame.deferredBreaks;
      boolean restoresHeadingBreak = false;
      if (frame.state == ConvertState.CAPTION_PENDING) {
        if (!hasOverride) {
          output = null;
          restoresHeadingBreak = frame.restoresHeadingBreak;
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
          captionOpened = true;
        } else {
          truncateBuffer(self, frame.tentativeOutputStart);
          self.pendingInlineWhitespace |= frame.tentativeRestorePendingWhitespace;
          if (frame.tentativeRestoreSpace) {
            self.buffer.append(' ');
            self.lastContentCacheLen = 1;
          } else {
            self.lastContentCacheLen = 0;
          }
          if (!hasOverride) {
            output = null;
            restoresHeadingBreak = frame.restoresHeadingBreak;
          }
        }
      } else {
        captionExitSpacing = frame.spacing[1];
        captionOpened = true;
      }
      writeDeferredBreakRun(self, deferredBreaks, false);
      // A break the dropped caption left behind already ends the heading's line.
      if (restoresHeadingBreak && !captionExitOpensBlockLine(self)) {
        self.headingBreakPending = true;
      }
    }

    int newLines;
    if (captionExit || consumesCaptionBoundary || voidSpacingAtEnter) {
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
    if (!self.openMarkers.isEmpty()) {
      int releaseNewLines =
          captionExit || consumesCaptionBoundary || voidSpacingAtEnter
              ? 0
              : (tagId == Consts.TAG_HR && !self.blockquotes.isEmpty()
                  ? Math.min(1, config[1])
                  : config[1]);
      boolean writes = releaseNewLines > 0 || (output != null && !output.isEmpty());
      if (hasOverride || isInline || writes) {
        releaseMarkersOnExit(self, hasOverride || !isInline, output);
      } else {
        noteSilentBlock(self);
      }
    }
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

    if (tagId == Consts.TAG_LI && !self.plainText) {
      // A hard break already ended the item. Its continuation indentation must
      // not become a whitespace-only line before the following block.
      if (self.hardBreakEnd == self.buffer.length()
          && !self.emptyItemHazard
          && !hasOverride
          && self.depthMap[Consts.TAG_PRE] == 0
          && !self.inTableCell()) {
        trimTrailingSpaces(self);
        self.hardBreakEnd = self.buffer.length();
      }
      self.pendingRawHtmlBoundary = false;
    }

    int outputStart = self.buffer.length();
    String effective = tableSeparator != null ? tableSeparator : output;
    if (!hasOverride
        && effective != null
        && emphasisExitFollowsWhitespace(self, tagId, effective)) {
      writeEmphasisExit(self, isInline, newLines, effective);
    } else {
      writeOutput(self, false, isInline, newLines, effective, false);
    }
    // The default closer of an overridden enter matches no opener.
    boolean enterIsOverridden = override != null && override.enter() != null;
    noteClosedMarker(self, enterIsOverridden ? -1 : markerTag, effective);
    if (openVoid) {
      self.lastNodeIsInline = true;
    }
    if (hasOverride
        && (tagId == Consts.TAG_UL || tagId == Consts.TAG_OL)
        && effective != null
        && !self.listMarkers.isEmpty()) {
      ConvertState.ListMarker marker = self.listMarkers.get(self.listMarkers.size() - 1);
      Integer written = exitListMarker(effective, marker.written != 0);
      if (written != null) {
        marker.written = written;
        self.siblingListMarker = 0;
      }
    }
    if (consumesCaptionBoundary
        && (!isInline || tagId == Consts.TAG_IMG || tagId == Consts.TAG_BR)) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    } else if (consumesCaptionBoundary && !captionExit && quoteBoundaryPending(self)) {
      closeQuoteWrapper(self);
    }
    if (captionExitSpacing > 0) {
      setCaptionBoundaryPending(self, captionExitSpacing);
    } else if (captionOpened
        && self.captionBoundary == ConvertState.CAPTION_BOUNDARY_CONSUME_SPACE) {
      // The space an opened caption owned does not outlive it.
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_NONE;
    }
    // Reuse the deferred block boundary: it already ignores invisible output
    // and supplies list continuation indentation, without taxing plain text.
    if (closesQuote
        && !hasOverride
        && self.depthMap[Consts.TAG_LI] > 0
        && self.depthMap[Consts.TAG_PRE] == 0
        && !self.inRawHtmlBlock()
        && !self.inTableCell()) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT;
      // An HR inside this quote cannot own the parent's deferred separator.
      self.listRulePending = false;
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
    if (tagId == Consts.TAG_A) {
      endLink(self);
    }
    if (emptyQuoteItemExit) {
      self.captionBoundary = ConvertState.CAPTION_BOUNDARY_QUOTE_PARENT;
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
    self.emptyItemAnswered = false;
    self.emptyItemAfterCr = lineEnding == '\r';
    self.emptyItemLineStart = lineStart;
    self.emptyItemLen = self.buffer.length();
  }

  /** The marker's line may still get a newline inserted, so it must stay put. */
  static boolean itemMarkerLineHeld(ConvertState self) {
    return self.emptyItemHazard && !self.emptyItemAnswered;
  }

  /** A capped stream charges a held marker line as written, as one-shot does. */
  static boolean chargesMarkerLine(ConvertState self) {
    return self.maxNodeBytesInt != 0 && itemMarkerLineHeld(self);
  }

  static void settleItemMarker(ConvertState self) {
    if (self.buffer.length() <= self.emptyItemLen) {
      return;
    }
    int contentEnd = trailingAsciiWhitespaceStart(self.buffer, self.buffer.length());
    if (contentEnd > self.emptyItemLen
        && self.openMarkers.isEmpty()
        && self.codeSpans.isEmpty()
        && self.codeFence == null
        && openLinkDropFloor(self, contentEnd) < 0
        && firstTentativeCaptionStart(self) < 0
        // A quote opened after the marker can use its own cap once the marker
        // settles. With fragment cleaning, keep the existing rewrite order.
        && (self.blockquotes.isEmpty()
            || self.blockquotes.get(self.blockquotes.size() - 1).contentStart
                <= self.emptyItemLineStart
            || !self.retainsWholeDocument())) {
      // Trailing whitespace can precede a hard break whose continuation indent
      // still needs the exit guard. Answer the line hold without clearing it.
      boolean atExit =
          self.depthMap[Consts.TAG_A] == 0
              && self.buffer.length() > 0
              && !Scan.isWhitespace(self.buffer.charAt(self.buffer.length() - 1));
      resolveItemMarker(self, atExit);
      // Content starting on a later line owes the blank line whatever follows, so
      // insert it now instead of holding the item until it exits.
      if (itemMarkerLineHeld(self)) {
        resolveItemMarker(self, true);
        self.emptyItemHazard = true;
        self.emptyItemAnswered = true;
      }
    }
  }

  /**
   * The earliest bracket an open link could still drop, or {@code -1}. A streamed-out bracket can
   * no longer be dropped.
   */
  private static int openLinkDropFloor(ConvertState self, int contentEnd) {
    int floor = Integer.MAX_VALUE;
    for (int i = -1; i < self.parentLinks.size(); i++) {
      ConvertState.LinkOutputState link =
          i < 0 ? self.link : self.parentLinks.get(i);
      if (!link.open || !link.rewritesBracket || link.holdReleased) {
        continue;
      }
      if (link.emptyTextPending
          || !self.openMarkers.isEmpty()
          || !self.codeSpans.isEmpty()
          || contentEnd <= link.bracketPos + 1) {
        floor = Math.min(floor, link.bracketPos - link.headingBreakPrefixLen);
      }
    }
    return floor == Integer.MAX_VALUE ? -1 : floor;
  }

  static void resolveItemMarker(ConvertState self, boolean atExit) {
    if (!self.emptyItemHazard) {
      return;
    }
    // The drain may have freed the line since; the answer it saw is final.
    if (self.emptyItemAnswered) {
      self.emptyItemHazard = !atExit;
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
      boolean opensTheItem = false;
      if (!self.codeSpans.isEmpty()) {
        opensTheItem = true;
      }
      if (!self.openMarkers.isEmpty()) {
        int at = self.openMarkers.get(0).outputStart;
        if (at >= end && at <= itemContentStart) {
          opensTheItem = true;
        }
      }
      int floor =
          openLinkDropFloor(
              self, trailingAsciiWhitespaceStart(self.buffer, self.buffer.length()));
      if (floor >= end && floor <= itemContentStart) {
        opensTheItem = true;
      }
      int tentative = firstTentativeCaptionStart(self);
      if (tentative >= end && tentative <= itemContentStart) {
        opensTheItem = true;
      }
      if (opensTheItem) {
        return;
      }
    }
    // Content on the marker's own line settles it: no blank line is owed. The
    // drain only records that, since the `<li>` exit's hard-break trim reads it.
    if (!tail.isEmpty() && tail.charAt(0) != '\n' && tail.charAt(0) != '\r') {
      self.emptyItemAnswered = !atExit;
      self.emptyItemHazard = !atExit;
      return;
    }
    // Otherwise only the item's own exit may act.
    if (!atExit) {
      return;
    }
    self.emptyItemHazard = false;
    // A link's `](url)` will share the marker's line, so a blank line would split
    // the link across two blocks.
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
    if (!replaceCodeSpanContent(self, at, removeEnd, separator)) {
      return;
    }
    int inserted = separator.length() - replaced;
    shiftOffsetsAtOrAfter(self, at, inserted);
    // Settled before its exit, the item can still hold an open link, whose close
    // reads its bracket.
    for (int i = -1; i < self.parentLinks.size(); i++) {
      ConvertState.LinkOutputState link =
          i < 0 ? self.link : self.parentLinks.get(i);
      if (link.open && link.bracketPos >= at) {
        link.bracketPos += inserted;
      }
    }
    moveStreamPositions(self, self.drained + at, self.drained + at + inserted);
    // Upstream deliberately does not shift blockquote content starts wholesale:
    // a frame the item opened starts past the line and moves with its content,
    // or it quotes from the wrong byte. A frame starting at the line stays.
    for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
      if (frame.contentStart > at) {
        frame.contentStart += inserted;
      }
    }
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
      if (marker.verticalTabStart >= at) {
        marker.verticalTabStart += amount;
      }
    }
    for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
      if (run.outputStart >= at) {
        run.outputStart += amount;
      }
      if (run.outputEnd >= at) {
        run.outputEnd += amount;
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
    // Upstream deliberately does not shift blockquote content starts: blockquote frames keep
    // their absolute content offset.
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

  /** Start of the whitespace-or-vertical-tab run ending at {@code end}. */
  private static int trailingWhitespaceOrVerticalTabStart(CharSequence buffer, int end) {
    int start = Math.min(end, buffer.length());
    while (start > 0) {
      char c = buffer.charAt(start - 1);
      if (!Chars.isAsciiWhitespace(c) && c != 0x0B) {
        break;
      }
      start--;
    }
    return start;
  }

  /**
   * Whether a closer may still move trailing vertical tabs out, as {@code writeEmphasisExit} does.
   */
  static boolean emphasisMovesVerticalTabs(ConvertState self) {
    return !self.plainText
        && (self.depthMap[Consts.TAG_PRE] == 0 || self.preFenceOwnerDepth == 0)
        && (self.depthMap[Consts.TAG_EM] != 0
            || self.depthMap[Consts.TAG_I] != 0
            || self.depthMap[Consts.TAG_STRONG] != 0
            || self.depthMap[Consts.TAG_B] != 0
            || self.depthMap[Consts.TAG_CITE] != 0
            || self.depthMap[Consts.TAG_DFN] != 0
            || self.depthMap[Consts.TAG_DEL] != 0
            || self.depthMap[Consts.TAG_S] != 0
            || self.depthMap[Consts.TAG_STRIKE] != 0);
  }

  /**
   * Start of held output a closer may move, or {@code -1}: the trailing run's first tab or a
   * marker's tab text.
   */
  static int verticalTabHoldStart(ConvertState self) {
    if (!emphasisMovesVerticalTabs(self)) {
      return -1;
    }
    int marker = -1;
    if (!self.openMarkers.isEmpty()) {
      int start = self.openMarkers.get(0).verticalTabStart;
      if (start >= 0) {
        marker = start;
      }
    }
    int run = -1;
    int len = self.buffer.length();
    if (len > 0) {
      char last = self.buffer.charAt(len - 1);
      if (Chars.isAsciiWhitespace(last) || last == 0x0B) {
        int index = len;
        while (index > 0) {
          char c = self.buffer.charAt(index - 1);
          if (!Chars.isAsciiWhitespace(c) && c != 0x0B) {
            break;
          }
          index--;
          if (c == 0x0B) {
            run = index;
          }
        }
      }
    }
    if (marker >= 0 && run >= 0) {
      return Math.min(marker, run);
    }
    return marker >= 0 ? marker : run;
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
      floor = self.link.bracketPos - self.link.headingBreakPrefixLen;
    }
    for (ConvertState.LinkOutputState parent : self.parentLinks) {
      if (linkHoldRequired(self, parent)) {
        floor = Math.min(floor, parent.bracketPos - parent.headingBreakPrefixLen);
      }
    }
    return floor == Integer.MAX_VALUE ? -1 : floor;
  }

  /** Records the link bookkeeping that streaming and the node cap need. */
  static void prepareStreamingLink(
      ConvertState self, ElementNode node, boolean rewriteAnchor) {
    // One-shot keeps no link hold, but the guard of the open item has to know that `emptyLinkText`
    // may still drop this link and leave it empty.
    self.link.emptyTextPending =
        rewriteAnchor && (self.cleanFlags & ConvertState.CLEAN_EMPTY_LINK_TEXT) != 0;
    // Avoid the href lookup and hold bookkeeping for one-shot conversion, unless
    // `maxNodeBytes` has to charge what the link holds back.
    if (!self.streaming && self.maxNodeBytes == 0) {
      return;
    }
    String href = node.attributes.getBit(Consts.ATTR_HREF);
    String trimmedHref = href == null ? null : Url.trimUrl(href);
    boolean pinsOutput =
        rewriteAnchor
            && trimmedHref != null
            && trimmedHref.startsWith("#")
            && (((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && trimmedHref.length() > 1)
                || ((self.cleanFlags & ConvertState.CLEAN_SELF_LINK_HEADINGS) != 0
                    && self.inHeading()));
    self.link.pinsOutput = pinsOutput;
    if (!self.streaming) {
      return;
    }
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
    // An opener that follows may still take back the closers before it, as
    // long as only blanks and openers that may close empty came after them.
    if (!self.closedMarkers.isEmpty()) {
      int[] closer = self.closedMarkers.get(self.closedMarkers.size() - 1);
      int afterLen = bufLen - closer[1];
      if (afterLen <= CLOSED_MARKER_REACH && allTransient(self.buffer, closer[1], bufLen)) {
        stableEnd = Math.min(stableEnd, self.closedMarkers.get(0)[0]);
      } else {
        self.closedMarkers.clear();
      }
    }
    if (itemMarkerLineHeld(self)) {
      stableEnd = Math.min(stableEnd, keepTwoBefore(self.buffer, self.emptyItemLineStart));
    }
    // After the holds above: a droppable opener or link after the run can leave it trailing.
    if (emphasisMovesVerticalTabs(self)) {
      int blank = trailingAsciiWhitespaceStart(self.buffer, stableEnd);
      if (blank > 0 && self.buffer.charAt(blank - 1) == 0x0B) {
        stableEnd = trailingWhitespaceOrVerticalTabStart(self.buffer, blank);
      }
    }
    if (self.inHeading()) {
      int run = Math.min(stableEnd, bufLen);
      while (run > 0 && isHeadingRunChar(self.buffer.charAt(run - 1))) {
        run--;
      }
      stableEnd = run;
    }

    if (!self.streamingBreakRuns.isEmpty()) {
      // A run is final once content follows it. Find the last content byte once: rescanning the
      // tail after every run cost the square of a long run of breaks separated by whitespace.
      int contentEnd = -1;
      for (int i = stableEnd - 1; i >= 0; i--) {
        if (!Chars.isAsciiWhitespace(self.buffer.charAt(i))) {
          contentEnd = i;
          break;
        }
      }
      for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
        if (run.outputEnd > stableEnd || contentEnd < run.outputEnd) {
          stableEnd = Math.min(stableEnd, run.outputStart);
          break;
        }
      }
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
    String out;
    int extraLen = 0;
    for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
      if (run.outputStart >= start && run.outputEnd <= stableEnd) {
        extraLen += run.unit.length() * (run.count - 1);
      }
    }
    if (extraLen == 0) {
      out = self.buffer.substring(start, stableEnd);
    } else {
      StringBuilder expanded = new StringBuilder(stableEnd - start + extraLen);
      int cursor = start;
      for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
        if (run.outputStart < start || run.outputEnd > stableEnd) {
          continue;
        }
        expanded.append(self.buffer, cursor, run.outputStart);
        for (int copy = 1; copy < run.count; copy++) {
          expanded.append(run.unit);
        }
        cursor = run.outputStart;
      }
      expanded.append(self.buffer, cursor, stableEnd);
      out = expanded.toString();
    }
    for (int i = self.streamingBreakRuns.size() - 1; i >= 0; i--) {
      if (self.streamingBreakRuns.get(i).outputEnd <= stableEnd) {
        self.streamingBreakRuns.remove(i);
      }
    }
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
    if (itemMarkerLineHeld(self)) {
      drainEnd =
          Math.min(drainEnd, keepTwoBefore(self.buffer, self.emptyItemLineStart));
    }
    // Keep a lead-only line whole: `opensBlockLine` reads back to its start.
    int leadStart = uncutBlockLeadStart(self, drainEnd, markerTail);
    if (leadStart >= 0) {
      drainEnd = Math.min(drainEnd, keepTwoBefore(self.buffer, leadStart));
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
    noteListGapDrain(self, drainEnd);
    self.drainedContent = self.drainedContent || hasVisibleUnicode(self.buffer, drainEnd);
    self.drained += drainEnd;
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
      if (marker.verticalTabStart >= 0) {
        marker.verticalTabStart = Math.max(0, marker.verticalTabStart - drainEnd);
      }
    }
    for (ConvertState.CaptionFrame frame : self.captionFrames) {
      if (frame.state == ConvertState.CAPTION_TENTATIVE) {
        frame.tentativeOutputStart -= drainEnd;
        frame.tentativeContentStart -= drainEnd;
      }
    }
    for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
      run.outputStart -= drainEnd;
      run.outputEnd -= drainEnd;
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
    self.listGapFrom = Math.max(0, self.listGapFrom - removed);
    if (self.blockquoteLineScannedTo != 0) {
      self.blockquoteLineScannedTo = Math.max(0, self.blockquoteLineScannedTo - removed);
      self.blockquoteLineEnd = Math.max(0, self.blockquoteLineEnd - removed);
    }
    if (self.hardBreakEnd >= 0) {
      self.hardBreakEnd = self.hardBreakEnd >= removed ? self.hardBreakEnd - removed : -1;
    }
    self.captionLineSpacesStart = 0;
    self.captionLineSpacesEnd = -1;
    self.captionLineOpensStart = -1;
    if (!self.closedMarkers.isEmpty() && self.closedMarkers.get(0)[0] < removed) {
      self.closedMarkers.clear();
    }
    for (int[] closer : self.closedMarkers) {
      closer[0] -= removed;
      closer[1] -= removed;
    }
  }

  /**
   * Start of the line {@code drainEnd} cuts, if no later line was yielded and only a block lead of
   * at most {@code widest} bytes precedes the cut.
   */
  private static int uncutBlockLeadStart(ConvertState self, int drainEnd, int widest) {
    StringBuilder bytes = self.buffer;
    int len = bytes.length();
    if (drainEnd == 0) {
      return -1;
    }
    char endChar = bytes.charAt(drainEnd - 1);
    if (!(endChar == ' '
        || endChar == '-'
        || endChar == '*'
        || endChar == '+'
        || endChar == '.'
        || endChar == ')'
        || (endChar >= '0' && endChar <= '9'))) {
      return -1;
    }
    // Yielded bytes are final, so a later yielded line keeps this one from ever being read again.
    int yieldedEnd = Math.max(drainEnd, Math.min(self.lastYieldedLength, len));
    while (yieldedEnd > drainEnd && Chars.isAsciiWhitespace(bytes.charAt(yieldedEnd - 1))) {
      yieldedEnd--;
    }
    for (int k = drainEnd; k < yieldedEnd; k++) {
      if (bytes.charAt(k) == '\n') {
        return -1;
      }
    }
    int floor = Math.max(0, drainEnd - widest);
    int i = drainEnd;
    while (i > floor && bytes.charAt(i - 1) == ' ') {
      i--;
    }
    if (i > floor && bytes.charAt(i - 1) != '\n') {
      char lead = bytes.charAt(i - 1);
      int marker;
      if (lead == '-' || lead == '*' || lead == '+') {
        marker = i - 1;
      } else if (lead == '.' || lead == ')' || (lead >= '0' && lead <= '9')) {
        // The cut may land between an ordered marker's digits and its delimiter.
        int digitsEnd = i - (isAsciiDigit(lead) ? 0 : 1);
        int at = digitsEnd;
        while (at > floor && digitsEnd - at < 9 && isAsciiDigit(bytes.charAt(at - 1))) {
          at--;
        }
        if (at == digitsEnd) {
          return -1;
        }
        marker = at;
      } else {
        return -1;
      }
      i = marker;
      while (i > floor && bytes.charAt(i - 1) == ' ') {
        i--;
      }
    }
    if (i < floor) {
      return -1;
    }
    if (i > 0 && bytes.charAt(i - 1) == '\n') {
      return i;
    }
    // `buffer[0]` opens a line before any cut or after one that ended a line.
    if (i == 0
        && (self.cutLineLeadKind == ConvertState.CUT_LEAD_UNCUT
            || (self.cutLineLeadKind == ConvertState.CUT_LEAD_BLANK
                && self.cutLineLeadIndent == 0))) {
      return 0;
    }
    return -1;
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
    // No opener follows the end of the document.
    self.closedMarkers.clear();
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
    boolean hasCloser = false;
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
      removals.add(new int[] {bracketStart, bracketStart + 1, link.bracketCloser});
      removals.add(new int[] {textEnd, linkEnd, link.textCloser});
      hasCloser |= link.bracketCloser != 0 || link.textCloser != 0;
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

    if (!hasCloser) {
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
    } else {
      StringBuilder rebuilt = removeFragmentWrappers(buffer, removals);
      buffer.setLength(0);
      buffer.append(rebuilt);
    }
  }

  /**
   * {@code clean.fragments} removal for links that cancelled a pending closer. Each sorted removal
   * carries the pending closer its wrapper cancelled. The first char that survives a removal,
   * however many adjacent removals it follows, is written as a numeric reference when it is ASCII
   * alphanumeric and the closer now precedes it.
   */
  private static StringBuilder removeFragmentWrappers(
      StringBuilder buffer, List<int[]> removals) {
    StringBuilder out = new StringBuilder(buffer.length());
    int read = 0;
    int closer = 0;
    for (int[] removal : removals) {
      int removeStart = removal[0];
      int removeEnd = removal[1];
      int removedCloser = removal[2];
      if (removeEnd <= read) {
        continue;
      }
      if (removeStart > read) {
        pushFragmentRun(out, buffer, read, removeStart, closer);
        closer = 0;
      }
      read = removeEnd;
      if (removedCloser != 0) {
        closer = removedCloser;
      }
    }
    if (read < buffer.length()) {
      pushFragmentRun(out, buffer, read, buffer.length(), closer);
    }
    return out;
  }

  /** Push one surviving run, referencing its first char when {@code closer} precedes it. */
  private static void pushFragmentRun(
      StringBuilder out, StringBuilder buffer, int start, int end, int closer) {
    char first = buffer.charAt(start);
    if (closer != 0
        && Chars.isAsciiAlphanumeric(first)
        && out.length() > 0
        && out.charAt(out.length() - 1) == closer) {
      out.append("&#").append((int) first).append(';');
      out.append(buffer, start + 1, end);
    } else {
      out.append(buffer, start, end);
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
   * Write every copy a collapsed streaming {@code <br>} run stands for, then forget the runs.
   * Mirrors the expansion in {@code get_markdown}.
   */
  private static void expandStreamingBreakRuns(ConvertState self) {
    if (self.streamingBreakRuns.isEmpty()) {
      return;
    }
    int extraLen = 0;
    for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
      extraLen += run.unit.length() * (run.count - 1);
    }
    StringBuilder expanded = new StringBuilder(self.buffer.length() + extraLen);
    int cursor = 0;
    for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
      expanded.append(self.buffer, cursor, run.outputStart);
      for (int copy = 1; copy < run.count; copy++) {
        expanded.append(run.unit);
      }
      cursor = run.outputStart;
    }
    expanded.append(self.buffer, cursor, self.buffer.length());
    self.streamingBreakRuns.clear();
    self.buffer.setLength(0);
    self.buffer.append(expanded);
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
        for (int i = self.streamingBreakRuns.size() - 1; i >= 0; i--) {
          if (self.streamingBreakRuns.get(i).outputEnd <= start) {
            self.streamingBreakRuns.remove(i);
          }
        }
        for (ConvertState.StreamingBreakRun run : self.streamingBreakRuns) {
          run.outputStart = Math.max(0, run.outputStart - start);
          run.outputEnd -= start;
        }
      }
    }
    if ((self.cleanFlags & ConvertState.CLEAN_FRAGMENTS) != 0 && !self.fragmentLinks.isEmpty()) {
      applyFragmentRemovals(self, start);
    }
    expandStreamingBreakRuns(self);
    String out = self.buffer.toString();
    self.buffer.setLength(0);
    return out;
  }
}
