/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j;

import io.github.fbricon.mdream4j.internal.Engine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits converted output into chunks based on headings, horizontal rules and a
 * size limit.
 *
 * <p>The algorithm is a byte-for-byte port of the reference splitter: offsets,
 * {@linkplain SplitterOptions#chunkSize() chunk size} and overlap are measured
 * in UTF-8 bytes, not characters.
 */
public final class Splitter {

  private static final byte[] FENCE = {'`', '`', '`'};

  private static final byte[][] SEPARATORS = {
    {'\n', '\n'}, {'`', '`', '`', '\n'}, {'\n'}, {' '}
  };

  private Splitter() {}

  /** Splits Markdown with the default {@link SplitterOptions}. */
  public static List<MarkdownChunk> splitMarkdown(String markdown) {
    return splitMarkdown(markdown, SplitterOptions.defaults());
  }

  /**
   * Splits text into chunks based on headings, horizontal rules and size.
   *
   * @param markdown the text to split; must not be {@code null}
   * @param options the split options; must not be {@code null}
   * @return the chunks in document order, empty when the input is blank
   * @throws IllegalArgumentException when the overlap is not smaller than the
   *     chunk size
   */
  public static List<MarkdownChunk> splitMarkdown(String markdown, SplitterOptions options) {
    Objects.requireNonNull(markdown, "markdown");
    Objects.requireNonNull(options, "options");
    if (options.chunkOverlap() >= options.chunkSize()) {
      throw new IllegalArgumentException("chunk_overlap must be less than chunk_size");
    }

    byte[] all = markdown.getBytes(StandardCharsets.UTF_8);
    int firstNonWhitespace = trimStart(all, 0, all.length);
    if (trimEnd(all, firstNonWhitespace, all.length) == firstNonWhitespace) {
      return Collections.emptyList();
    }
    byte[] md =
        firstNonWhitespace == 0 ? all : Arrays.copyOfRange(all, firstNonWhitespace, all.length);

    int lineCount = countNewlines(md, 0, md.length) + 1;
    int[] lineStarts = new int[lineCount];
    int[] lineLengths = new int[lineCount];
    int lineIndex = 0;
    int position = 0;
    for (int i = 0; i < md.length; i++) {
      if (md[i] == '\n') {
        lineStarts[lineIndex] = position;
        lineLengths[lineIndex] = i - position;
        lineIndex++;
        position = i + 1;
      }
    }
    lineStarts[lineIndex] = position;
    lineLengths[lineIndex] = md.length - position;

    int frontmatterEnd = -1;
    if (trimmedEquals(md, lineStarts[0], lineLengths[0], "---")) {
      for (int i = 1; i < lineCount; i++) {
        if (trimmedEquals(md, lineStarts[i], lineLengths[i], "---")) {
          frontmatterEnd = i;
          break;
        }
      }
    }

    int splitLevels = 0;
    for (int level : options.headersToSplitOn()) {
      if (level >= 1 && level <= 6) {
        splitLevels |= 1 << (level - 1);
      }
    }

    Run run = new Run(md, options);
    int[] fenceEnds = null;

    for (int i = 0; i < lineCount; i++) {
      int linePos = lineStarts[i];
      int lineLen = lineLengths[i];
      boolean isFrontmatter = frontmatterEnd >= 0 && (i == 0 || i == frontmatterEnd);

      if (startsWith(md, linePos, lineLen, FENCE)) {
        if (run.inCodeBlock) {
          run.inCodeBlock = false;
        } else {
          run.inCodeBlock = true;
          String rest = new String(md, linePos + 3, lineLen - 3, StandardCharsets.UTF_8);
          String language = strip(rest);
          if (!language.isEmpty() && run.codeLanguage.isEmpty()) {
            run.codeLanguage = language;
          }
        }
      }

      if (!run.inCodeBlock && !startsWith(md, linePos, lineLen, FENCE)) {
        int level = isFrontmatter ? 0 : headerLevel(md, linePos, lineLen);
        if (level > 0) {
          String text =
              stripMarkdownFormatting(md, linePos + level + 1, linePos + lineLen);
          int bit = 1 << (level - 1);

          if ((splitLevels & bit) != 0) {
            if ((run.seenSplitHeaders & bit) != 0) {
              run.flush(linePos, false);
              // Clear the hierarchy at this level and below.
              run.hierarchy.removeIf(entry -> entry.level >= level);
            }
            run.seenSplitHeaders |= bit;
          }

          Header entry = run.findHeader(level);
          if (entry != null) {
            entry.text = text;
          } else {
            run.hierarchy.add(new Header(level, text));
          }
        }

        if (!isFrontmatter && level == 0 && isHr(md, linePos, lineLen)) {
          run.flush(linePos, false);
        }
      }

      if (!options.returnEachLine()) {
        int lineEnd = Math.min(linePos + lineLen + 1, md.length);
        int currentChunkSize = Math.max(0, lineEnd - run.lastChunkEnd);

        if (currentChunkSize > options.chunkSize()) {
          long ideal = (long) run.lastChunkEnd + options.chunkSize();
          int idealSplitPos = (int) Math.min(ideal, md.length);
          if (fenceEnds == null) {
            fenceEnds = backtickFenceEnds(md);
          }
          int splitPosition = -1;

          for (byte[] separator : SEPARATORS) {
            int searchEnd = Math.min(idealSplitPos, lineEnd);
            while (searchEnd < lineEnd && !isCharBoundary(md, searchEnd)) {
              searchEnd++;
            }
            int searchStart = Math.min(Math.max(0, run.lastSplit + 1 - separator.length), searchEnd);
            while (!isCharBoundary(md, searchStart)) {
              searchStart--;
            }
            int match = lastIndexOf(md, searchStart, searchEnd, separator);
            if (match >= 0) {
              int candidate = match + separator.length;
              if ((partitionPoint(fenceEnds, candidate) & 1) == 1) {
                continue;
              }
              if (candidate > run.lastSplit) {
                splitPosition = candidate;
                break;
              }
            }
          }

          int finalSplit =
              splitPosition == -1 || splitPosition <= run.lastChunkEnd ? lineEnd : splitPosition;
          run.flush(finalSplit, true);
        }
      }
    }

    run.flush(md.length, false);

    if (options.returnEachLine() && !run.chunks.isEmpty()) {
      List<MarkdownChunk> lineChunks = new ArrayList<>();
      for (MarkdownChunk chunk : run.chunks) {
        int chunkStartLine = chunk.metadata().loc() == null ? 1 : chunk.metadata().loc().from();
        String[] lines = chunk.content().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if (!strip(lines[i]).isEmpty()) {
            lineChunks.add(
                new MarkdownChunk(
                    lines[i],
                    new ChunkMetadata(
                        chunk.metadata().headers(),
                        chunk.metadata().code(),
                        new ChunkLoc(chunkStartLine + i, chunkStartLine + i))));
          }
        }
      }
      return lineChunks;
    }

    return run.chunks;
  }

  /**
   * Converts HTML to Markdown and splits it in one call.
   *
   * @param html the HTML input; must not be {@code null}
   * @param mdOptions the conversion options; must not be {@code null}
   * @param splitOptions the split options; must not be {@code null}
   * @return the chunks in document order
   */
  public static List<MarkdownChunk> htmlToMarkdownChunks(
      CharSequence html, Options mdOptions, SplitterOptions splitOptions) {
    return htmlToFormatChunks(html, mdOptions, splitOptions, OutputFormat.MARKDOWN);
  }

  /**
   * Converts HTML to the requested format and splits it in one call.
   *
   * @param html the HTML input; must not be {@code null}
   * @param mdOptions the conversion options; must not be {@code null}
   * @param splitOptions the split options; must not be {@code null}
   * @param format the output format; must not be {@code null}
   * @return the chunks in document order
   */
  public static List<MarkdownChunk> htmlToFormatChunks(
      CharSequence html,
      Options mdOptions,
      SplitterOptions splitOptions,
      OutputFormat format) {
    Objects.requireNonNull(html, "html");
    Objects.requireNonNull(mdOptions, "mdOptions");
    Objects.requireNonNull(splitOptions, "splitOptions");
    Objects.requireNonNull(format, "format");
    String output = Engine.convert(html, mdOptions.withFormat(format));
    return splitMarkdown(output, splitOptions);
  }

  /** Mutable state shared by the loop and the flush step. */
  private static final class Run {

    final byte[] md;
    final SplitterOptions options;
    final List<MarkdownChunk> chunks = new ArrayList<>();
    final List<Header> hierarchy = new ArrayList<>();

    int seenSplitHeaders;
    String codeLanguage = "";
    int lineNumber = 1;
    int lastChunkEnd;
    int lastSplit;
    boolean inCodeBlock;

    Run(byte[] md, SplitterOptions options) {
      this.md = md;
      this.options = options;
    }

    Header findHeader(int level) {
      for (Header entry : hierarchy) {
        if (entry.level == level) {
          return entry;
        }
      }
      return null;
    }

    void flush(int endPosition, boolean applyOverlap) {
      int end = Math.min(endPosition, md.length);
      int start = Math.min(lastChunkEnd, end);
      String original = new String(md, start, end - start, StandardCharsets.UTF_8);

      if (strip(original).isEmpty()) {
        lastChunkEnd = end;
        return;
      }

      String content;
      if (options.stripHeaders()) {
        StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (String line : original.split("\n", -1)) {
          if (isHeaderLine(line)) {
            continue;
          }
          if (!first) {
            joined.append('\n');
          }
          joined.append(line);
          first = false;
        }
        content = strip(joined.toString());
        if (content.isEmpty()) {
          lastChunkEnd = end;
          return;
        }
      } else {
        content = stripEnd(original);
      }

      int newlineCount = countNewlines(md, start, end);
      Map<String, String> headers = null;
      if (!hierarchy.isEmpty()) {
        headers = new LinkedHashMap<>();
        for (Header entry : hierarchy) {
          headers.put("h" + entry.level, entry.text);
        }
      }
      ChunkMetadata metadata =
          new ChunkMetadata(
              headers,
              codeLanguage.isEmpty() ? null : codeLanguage,
              new ChunkLoc(lineNumber, lineNumber + newlineCount));
      chunks.add(new MarkdownChunk(content, metadata));

      codeLanguage = "";
      lastSplit = end;

      if (applyOverlap && options.chunkOverlap() > 0) {
        int contentLength = end - start;
        int maxOverlap = Math.max(0, contentLength - 1);
        int actualOverlap = Math.min(options.chunkOverlap(), maxOverlap);
        int overlapPos = end - actualOverlap;
        while (overlapPos < md.length && !isCharBoundary(md, overlapPos)) {
          overlapPos++;
        }
        lastChunkEnd = overlapPos;
      } else {
        lastChunkEnd = end;
      }

      lineNumber += newlineCount;
    }
  }

  private static final class Header {

    final int level;
    String text;

    Header(int level, String text) {
      this.level = level;
      this.text = text;
    }
  }

  private static boolean isHeaderLine(String line) {
    if (line.isEmpty() || line.charAt(0) != '#') {
      return false;
    }
    int level = 0;
    while (level < line.length() && line.charAt(level) == '#') {
      level++;
      if (level > 6) {
        return false;
      }
    }
    if (level >= line.length() || line.charAt(level) != ' ') {
      return false;
    }
    return level + 1 < line.length();
  }

  private static int headerLevel(byte[] md, int start, int length) {
    if (length == 0 || md[start] != '#') {
      return 0;
    }
    int level = 0;
    while (level < length && md[start + level] == '#') {
      level++;
      if (level > 6) {
        return 0;
      }
    }
    if (level >= length || md[start + level] != ' ') {
      return 0;
    }
    return level + 1 < length ? level : 0;
  }

  private static boolean isHr(byte[] md, int start, int length) {
    if (length != 3) {
      return false;
    }
    byte first = md[start];
    return (first == '-' || first == '*' || first == '_')
        && md[start + 1] == first
        && md[start + 2] == first;
  }

  private static boolean startsWith(byte[] md, int start, int length, byte[] prefix) {
    if (length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (md[start + i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean trimmedEquals(byte[] md, int start, int length, String ascii) {
    int from = trimStart(md, start, start + length);
    int to = trimEnd(md, from, start + length);
    if (to - from != ascii.length()) {
      return false;
    }
    for (int i = 0; i < ascii.length(); i++) {
      if (md[from + i] != (byte) ascii.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Removes Markdown emphasis markers and backticks from a header's text.
   *
   * <p>Only ASCII bytes are dropped, so the remaining bytes stay valid UTF-8.
   */
  private static String stripMarkdownFormatting(byte[] md, int start, int end) {
    byte[] result = new byte[end - start];
    int length = 0;
    int i = start;
    while (i < end) {
      byte b = md[i];
      if (b == '*' || b == '_') {
        if (i + 2 < end && md[i + 1] == b && md[i + 2] == b) {
          i += 3;
          continue;
        }
        if (i + 1 < end && md[i + 1] == b) {
          i += 2;
          continue;
        }
        i += 1;
        continue;
      }
      if (b == '`') {
        i += 1;
        continue;
      }
      result[length++] = b;
      i += 1;
    }
    return strip(new String(result, 0, length, StandardCharsets.UTF_8));
  }

  /** End offsets of the backtick runs in {@code md}, matched left to right. */
  private static int[] backtickFenceEnds(byte[] md) {
    int[] ends = new int[md.length / 3 + 1];
    int count = 0;
    int i = 0;
    while (i + 2 < md.length) {
      if (md[i + 2] != '`') {
        i += 3;
      } else if (md[i] == '`' && md[i + 1] == '`') {
        i += 3;
        ends[count++] = i;
      } else {
        i += 1;
      }
    }
    return Arrays.copyOf(ends, count);
  }

  private static int countNewlines(byte[] md, int start, int end) {
    int count = 0;
    for (int i = start; i < end; i++) {
      if (md[i] == '\n') {
        count++;
      }
    }
    return count;
  }

  /** Whether {@code pos} is a UTF-8 code-point boundary. */
  private static boolean isCharBoundary(byte[] md, int pos) {
    return pos <= 0 || pos >= md.length || (md[pos] & 0xC0) != 0x80;
  }

  /** Number of fence ends at or before {@code position}. */
  private static int partitionPoint(int[] sorted, int position) {
    int low = 0;
    int high = sorted.length;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (sorted[mid] <= position) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /** Last occurrence of {@code needle} in {@code hay[from..to)}. */
  private static int lastIndexOf(byte[] hay, int from, int to, byte[] needle) {
    outer:
    for (int i = to - needle.length; i >= from; i--) {
      for (int j = 0; j < needle.length; j++) {
        if (hay[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static int trimStart(byte[] md, int start, int end) {
    int i = start;
    while (i < end) {
      int codePoint = codePointAt(md, i, end);
      if (!isWhitespace(codePoint)) {
        break;
      }
      i += utf8Length(codePoint);
    }
    return i;
  }

  private static int trimEnd(byte[] md, int start, int end) {
    int i = end;
    while (i > start) {
      int previous = i - 1;
      while (previous > start && (md[previous] & 0xC0) == 0x80) {
        previous--;
      }
      int codePoint = codePointAt(md, previous, end);
      if (!isWhitespace(codePoint)) {
        break;
      }
      i = previous;
    }
    return i;
  }

  private static int codePointAt(byte[] md, int index, int end) {
    int lead = md[index] & 0xFF;
    if (lead < 0x80) {
      return lead;
    }
    if ((lead & 0xE0) == 0xC0 && index + 1 < end) {
      return ((lead & 0x1F) << 6) | (md[index + 1] & 0x3F);
    }
    if ((lead & 0xF0) == 0xE0 && index + 2 < end) {
      return ((lead & 0x0F) << 12)
          | ((md[index + 1] & 0x3F) << 6)
          | (md[index + 2] & 0x3F);
    }
    if ((lead & 0xF8) == 0xF0 && index + 3 < end) {
      return ((lead & 0x07) << 18)
          | ((md[index + 1] & 0x3F) << 12)
          | ((md[index + 2] & 0x3F) << 6)
          | (md[index + 3] & 0x3F);
    }
    return lead;
  }

  private static int utf8Length(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }

  /** Unicode {@code White_Space}, matching Rust's {@code char::is_whitespace}. */
  private static boolean isWhitespace(int codePoint) {
    return Character.isSpaceChar(codePoint)
        || (codePoint >= 0x09 && codePoint <= 0x0D)
        || codePoint == 0x85;
  }

  private static String strip(String text) {
    int start = 0;
    int end = text.length();
    while (start < end) {
      int codePoint = text.codePointAt(start);
      if (!isWhitespace(codePoint)) {
        break;
      }
      start += Character.charCount(codePoint);
    }
    while (end > start) {
      int codePoint = text.codePointBefore(end);
      if (!isWhitespace(codePoint)) {
        break;
      }
      end -= Character.charCount(codePoint);
    }
    return text.substring(start, end);
  }

  private static String stripEnd(String text) {
    int end = text.length();
    while (end > 0) {
      int codePoint = text.codePointBefore(end);
      if (!isWhitespace(codePoint)) {
        break;
      }
      end -= Character.charCount(codePoint);
    }
    return text.substring(0, end);
  }
}
