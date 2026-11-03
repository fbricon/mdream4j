/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * Low-level HTML scanning primitives: whitespace, comments, tag attributes.
 *
 * <p>The Rust source scans UTF-8 bytes. This port scans UTF-16 {@code char}s; every structural byte
 * it looks for is ASCII, so slices and positions agree. Byte budgets are converted back to UTF-8
 * length where the upstream counts bytes.
 */
final class Scan {

  // Tokenizer states. Mirrors Rust `scan::State`; `QUOTED` also stores the open quote.
  static final int ST_GAP = 0;
  static final int ST_NAME = 1;
  static final int ST_AFTER_NAME = 2;
  static final int ST_BEFORE_VALUE = 3;
  static final int ST_UNQUOTED_VALUE = 4;
  static final int ST_QUOTED = 5;

  private static final int ATTR_NAME_STOP = 1;
  private static final int UNQUOTED_VALUE_STOP = 2;
  private static final int NON_WHITESPACE_STOP = 4;
  private static final int TAG_NAME_STOP = 8;

  /** Packed stop-set bits for every byte value. */
  private static final int[] SCAN_CLASS = buildScanClass();

  static final int MAX_WANTED_ATTR_NAME = Consts.MAX_WANTED_ATTR_NAME;

  private Scan() {}

  /** Whitespace check optimized for the hot character loop. */
  static boolean isWhitespace(char c) {
    return Chars.isAsciiWhitespace(c);
  }

  private static int[] buildScanClass() {
    int[] table = new int[256];
    for (int b = 0; b < 256; b++) {
      char c = (char) b;
      boolean ws = isWhitespace(c);
      boolean name = ws || c == '>' || c == '=' || c == '/';
      boolean unquoted = ws || c == '>';
      boolean tag = ws || c == '>' || c == '/';
      table[b] =
          (name ? ATTR_NAME_STOP : 0)
              | (unquoted ? UNQUOTED_VALUE_STOP : 0)
              | (!ws ? NON_WHITESPACE_STOP : 0)
              | (tag ? TAG_NAME_STOP : 0);
    }
    return table;
  }

  /** First tag-name stop byte at or after {@code from}. */
  static int tagNameEnd(String bytes, int from) {
    int i = from;
    while (i < bytes.length() && (SCAN_CLASS[scanClassIndex(bytes.charAt(i))] & TAG_NAME_STOP) == 0) {
      i++;
    }
    return i;
  }

  private static int scanClassIndex(char c) {
    // Rust indexes a 256-entry table with a byte; non-ASCII bytes land above 127 and match no
    // stop bit, so folding every non-ASCII char to 128 is equivalent.
    return c < 256 ? c : 128;
  }

  /** Finds {@code needle} at or after {@code from}; returns its index or {@code -1}. */
  static int findByte(String hay, char needle, int from) {
    return hay.indexOf(needle, from);
  }

  /** UTF-8 byte length of a char range, matching Rust's {@code str::len()}. */
  static int utf8Length(CharSequence text) {
    return utf8Length(text, 0, text.length());
  }

  static int utf8Length(CharSequence text, int start, int end) {
    int length = 0;
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        length += 1;
      } else if (c < 0x800) {
        length += 2;
      } else if (Character.isSurrogate(c)) {
        if (Character.isHighSurrogate(c)
            && i + 1 < end
            && Character.isLowSurrogate(text.charAt(i + 1))) {
          length += 4;
          i++;
        } else {
          length += 3;
        }
      } else {
        length += 3;
      }
    }
    return length;
  }

  static final class CommentResult {
    final boolean complete;
    final int newPosition;

    CommentResult(boolean complete, int newPosition) {
      this.complete = complete;
      this.newPosition = newPosition;
    }
  }

  static CommentResult processCommentOrDoctype(String htmlChunk, int position) {
    int i = position;
    int chunkLength = htmlChunk.length();

    if (i + 3 < chunkLength && htmlChunk.charAt(i + 2) == '-' && htmlChunk.charAt(i + 3) == '-') {
      i += 4;
      // `-->` is not the only terminator. `<!-->` and `<!--->` close straight out
      // of the comment start states, and a run of two or more dashes also closes
      // on `!>`. Matching `-->` alone leaves those comments open, so the scan
      // runs to end of chunk and every byte after them is discarded.
      if (i < chunkLength && htmlChunk.charAt(i) == '>') {
        return new CommentResult(true, i + 1);
      }
      if (i + 1 < chunkLength && htmlChunk.charAt(i) == '-' && htmlChunk.charAt(i + 1) == '>') {
        return new CommentResult(true, i + 2);
      }
      while (i + 2 < chunkLength) {
        // Scanning for the dash on its own keeps this loop vectorizable; the
        // three-byte window is only formed once a dash is found.
        while (i + 2 < chunkLength && htmlChunk.charAt(i) != '-') {
          i++;
        }
        if (i + 2 >= chunkLength) {
          break;
        }
        if (htmlChunk.charAt(i + 1) == '-') {
          char afterDashes = htmlChunk.charAt(i + 2);
          if (afterDashes == '>') {
            return new CommentResult(true, i + 3);
          }
          if (afterDashes == '!' && i + 3 < chunkLength && htmlChunk.charAt(i + 3) == '>') {
            return new CommentResult(true, i + 4);
          }
        }
        i++;
      }
      return new CommentResult(false, position);
    } else {
      i += 2;
      while (i < chunkLength) {
        if (htmlChunk.charAt(i) == '>') {
          i++;
          return new CommentResult(true, i);
        }
        i++;
      }
      return new CommentResult(false, i);
    }
  }

  /**
   * How far a start tag spanning several chunks has been searched for its {@code >}, so the next
   * chunk resumes instead of restarting.
   */
  static final class PendingTagScan {
    int scanned;
    int state = ST_GAP;
    char quote;

    void restart() {
      scanned = 0;
    }
  }

  enum DiscardedCommentState {
    START,
    START_DASH,
    COMMENT,
    END_DASH,
    END,
    END_BANG
  }

  /** Mutable holder for a {@link DiscardedCommentState}, mirroring Rust's {@code &mut}. */
  static final class DiscardedComment {
    DiscardedCommentState state = DiscardedCommentState.START;
  }

  /**
   * Find the end of a comment whose earlier bytes have been dropped, resuming from its tokenizer
   * state. Returns the index after the {@code >} without retaining bytes.
   */
  static int discardedCommentEnd(String chunk, DiscardedComment state) {
    int index = 0;
    int len = chunk.length();
    while (index < len) {
      // Only a dash moves a comment body towards its end.
      if (state.state == DiscardedCommentState.COMMENT) {
        while (index < len && chunk.charAt(index) != '-') {
          index++;
        }
        if (index == len) {
          return -1;
        }
      }
      char c = chunk.charAt(index);
      if (c == '>'
          && (state.state == DiscardedCommentState.START
              || state.state == DiscardedCommentState.START_DASH
              || state.state == DiscardedCommentState.END
              || state.state == DiscardedCommentState.END_BANG)) {
        return index + 1;
      }
      switch (c) {
        case '-':
          switch (state.state) {
            case START:
              state.state = DiscardedCommentState.START_DASH;
              break;
            case START_DASH:
            case END_DASH:
            case END:
              state.state = DiscardedCommentState.END;
              break;
            default:
              state.state = DiscardedCommentState.END_DASH;
              break;
          }
          break;
        case '!':
          if (state.state == DiscardedCommentState.END) {
            state.state = DiscardedCommentState.END_BANG;
          } else {
            state.state = DiscardedCommentState.COMMENT;
          }
          break;
        default:
          state.state = DiscardedCommentState.COMMENT;
          break;
      }
      index++;
    }
    return -1;
  }

  /**
   * Quote state carried while an end tag's bytes are dropped. The end-tag tokenizer only treats a
   * quote as opening a value once the tag name has ended, so both bits have to survive a chunk
   * boundary.
   */
  static final class DiscardedCloseTag {
    /** The tag name has ended within the bytes scanned so far. */
    boolean nameEnded;

    char quote;
  }

  /**
   * Find the {@code >} ending an end tag whose earlier bytes have been dropped. Mirrors the
   * end-tag tokenizer, where a {@code >} inside a quoted parse-error attribute does not complete
   * the token. Returns the index after the {@code >}, or {@code -1}.
   */
  static int discardedCloseTagEnd(String chunk, DiscardedCloseTag state) {
    int len = chunk.length();
    for (int index = 0; index < len; index++) {
      char c = chunk.charAt(index);
      if (!state.nameEnded && (isWhitespace(c) || c == '/' || c == '>')) {
        state.nameEnded = true;
      }
      if (state.quote != 0) {
        if (c == state.quote) {
          state.quote = 0;
        }
      } else if (state.nameEnded && (c == '"' || c == '\'')) {
        state.quote = c;
      } else if (c == '>') {
        return index + 1;
      }
    }
    return -1;
  }

  /** Find the {@code >} ending a dropped doctype or bogus comment. */
  static int discardedGt(String chunk) {
    int index = chunk.indexOf('>');
    return index < 0 ? -1 : index + 1;
  }

  /** Find the {@code ]]>} ending a dropped CDATA section, resuming from the trailing bracket run. */
  static int discardedCdataEnd(String chunk, int[] brackets) {
    int len = chunk.length();
    for (int index = 0; index < len; index++) {
      char c = chunk.charAt(index);
      if (c == ']') {
        brackets[0] = Math.min(brackets[0] + 1, 2);
      } else if (c == '>' && brackets[0] >= 2) {
        return index + 1;
      } else {
        brackets[0] = 0;
      }
    }
    return -1;
  }

  /**
   * Resume the search for a start tag's {@code >}, returning its index once the tag is complete.
   * Runs the same tokenizer as {@code scanTag} so the two always agree on where a tag ends.
   */
  static int tagIsComplete(String htmlChunk, int attrsStart, PendingTagScan pending) {
    int i = attrsStart + pending.scanned;
    int len = htmlChunk.length();

    while (i < len) {
      char c = htmlChunk.charAt(i);
      // `/>` is reached through its own `>`, so one test covers both endings. A
      // `>` inside a quoted value does not end the tag.
      if (c == '>' && pending.state != ST_QUOTED) {
        return i;
      }
      pending.state = stepWithoutExtraction(pending.state, pending.quote, c);
      if (pending.state == ST_QUOTED && pending.quote == 0) {
        pending.quote = c;
      } else if (pending.state != ST_QUOTED) {
        pending.quote = 0;
      }
      i++;
    }

    pending.scanned = i - attrsStart;
    return -1;
  }

  /**
   * Bytes a start tag may retain, counted against {@code max_node_bytes}. Attributes the mask
   * rejects are not charged.
   */
  static final class AttrBudget {
    final int cap;
    int used;
    boolean overflow;

    AttrBudget(int cap) {
      this.cap = cap;
    }

    boolean fits(int extra) {
      if (cap == 0 || used + extra <= cap) {
        return true;
      }
      overflow = true;
      return false;
    }

    boolean take(int bytes) {
      if (!fits(bytes)) {
        return false;
      }
      used += bytes;
      return true;
    }

    /** Charge bytes retained outside the attribute scan — the tag's own name. */
    boolean charge(int bytes) {
      return take(bytes);
    }
  }

  /** Outcome of scanning a start tag's attribute region. */
  static final class TagScan {
    final boolean complete;
    final int newPosition;
    final boolean selfClosing;
    final boolean overflow;
    /** Scanner state for the next chunk, set only when the tag is incomplete. */
    final TagCarry carry;

    private TagScan(
        boolean complete,
        int newPosition,
        boolean selfClosing,
        boolean overflow,
        TagCarry carry) {
      this.complete = complete;
      this.newPosition = newPosition;
      this.selfClosing = selfClosing;
      this.overflow = overflow;
      this.carry = carry;
    }

    static TagScan done(int newPosition, boolean selfClosing, boolean overflow) {
      return new TagScan(true, newPosition, selfClosing, overflow, null);
    }

    static TagScan suspended(int newPosition, TagCarry carry) {
      return new TagScan(false, newPosition, false, carry.budget.overflow, carry);
    }
  }

  /**
   * Scan a start tag's attribute region to its {@code >}, storing only the attributes {@code
   * attrMask} selects into {@code out}. {@code out} is cleared on entry and owned by the caller so
   * its buffer is recycled across elements.
   */
  static TagScan processTagAttributes(
      String htmlChunk,
      int position,
      TagHandler tagHandler,
      int attrMask,
      AttrBudget budget,
      Attributes out) {
    boolean selfClosing = tagHandler != null && tagHandler.isSelfClosing;
    return scanTag(
        htmlChunk, position, selfClosing, attrMask != Consts.ATTR_NONE, attrMask, budget, out);
  }

  /** Walk a start tag to its {@code >}, extracting attributes on the way when {@code extract}. */
  private static TagScan scanTag(
      String htmlChunk,
      int position,
      boolean selfClosing,
      boolean extract,
      int attrMask,
      AttrBudget budget,
      Attributes out) {
    int chunkLength = htmlChunk.length();
    AttrScan scan = new AttrScan(attrMask, budget, out);
    // Extracting and non-extracting scans still need the same tokenizer state to
    // distinguish a quoted value from a quote inside an unquoted one.
    int state = ST_GAP;
    char quote = 0;
    int i = position;

    while (i < chunkLength) {
      char c = htmlChunk.charAt(i);

      // A quoted value hides `>`. Jump it whole.
      if (state == ST_QUOTED) {
        int offset = findByte(htmlChunk, quote, i);
        if (offset >= 0) {
          state = ST_GAP;
          quote = 0;
          i = offset + 1;
        } else {
          // Unterminated: the tag cannot close in this chunk.
          AttrScan paused = scan;
          paused.state = ST_QUOTED;
          paused.quote = quote;
          return TagScan.suspended(chunkLength, TagCarry.opaque(paused, budget));
        }
        continue;
      }

      int attributeState = extract ? scan.state : state;
      // Run to the next delimiter instead of dispatching per byte.
      int runStop = runStop(attributeState);
      if (runStop != 0 && (SCAN_CLASS[scanClassIndex(c)] & runStop) == 0) {
        i++;
        while (i < chunkLength && (SCAN_CLASS[scanClassIndex(htmlChunk.charAt(i))] & runStop) == 0) {
          i++;
        }
        continue;
      }
      if (attributeState != ST_UNQUOTED_VALUE && c == '/') {
        // The `>` that would make this self-closing is in the next chunk. Feeding
        // the `/` to the tokenizer now would bury it in an attribute name.
        if (i + 1 == chunkLength) {
          TagCarry carry =
              extract
                  ? scan.suspend(htmlChunk, i)
                  : TagCarry.opaque(state, quote, budget);
          carry.slash = true;
          return TagScan.suspended(chunkLength, carry);
        }
        if (htmlChunk.charAt(i + 1) == '>') {
          scan.finish(htmlChunk, i);
          return TagScan.done(i + 2, true, scan.budget.overflow);
        }
      }
      if (c == '>') {
        scan.finish(htmlChunk, i);
        return TagScan.done(i + 1, selfClosing, scan.budget.overflow);
      }

      // Run to the closing quote without re-entering the state dispatch.
      if (extract && scan.opensQuotedValue(c)) {
        int valueStart = i + 1;
        int end = findByte(htmlChunk, c, valueStart);
        if (end < 0) {
          end = chunkLength;
        }
        if (end == chunkLength) {
          // Unterminated: the tag cannot close in this chunk. `state` carries the
          // open quote, so the resume ends the value on the matching delimiter.
          scan.state = ST_QUOTED;
          scan.quote = c;
          scan.valueStart = valueStart;
          return TagScan.suspended(chunkLength, scan.suspend(htmlChunk, end));
        }
        scan.takeValue(htmlChunk, valueStart, end);
        i = end + 1;
        continue;
      }

      if (extract) {
        scan.step(htmlChunk, c, i);
      } else {
        int next = stepWithoutExtraction(state, quote, c);
        if (next == ST_QUOTED) {
          quote = c;
        } else {
          quote = 0;
        }
        state = next;
      }
      i++;
    }

    TagCarry carry =
        extract
            ? scan.suspend(htmlChunk, chunkLength)
            : TagCarry.opaque(state, quote, budget);
    return TagScan.suspended(chunkLength, carry);
  }

  private static int runStop(int state) {
    switch (state) {
      case ST_NAME:
        return ATTR_NAME_STOP;
      case ST_UNQUOTED_VALUE:
        return UNQUOTED_VALUE_STOP;
      case ST_GAP:
      case ST_AFTER_NAME:
      case ST_BEFORE_VALUE:
        return NON_WHITESPACE_STOP;
      default:
        return 0;
    }
  }

  /**
   * Mask rejection happens before any lowercasing, entity decoding or budget charge, so unwanted
   * attributes cost nothing at all.
   */
  private static void pushAttr(
      Attributes result,
      int mask,
      AttrBudget budget,
      String raw,
      String value) {
    int bit = Consts.attr_bit(raw);
    if (mask != Consts.ATTR_ALL && (mask & bit) == 0) {
      return;
    }
    // First occurrence wins, so a later duplicate is ignored and must not charge.
    if (bit != Consts.ATTR_NONE && result.containsBit(bit)) {
      return;
    }
    int valueLength = value == null ? 0 : utf8Length(value);
    if (!budget.take(utf8Length(raw) + valueLength)) {
      return;
    }
    // A known name is fully described by `bit`; only the `ATTR_ALL` long tail
    // needs an owned, lowercased copy. Values decode into a recycled buffer.
    if (bit == Consts.ATTR_NONE) {
      result.insertCustom(Chars.asciiLower(raw), value);
    } else {
      result.insertKnown(bit, value);
    }
  }

  /** Attribute extraction fed one char at a time by the tag scan. */
  private static final class AttrScan {
    final int mask;
    final AttrBudget budget;
    final Attributes result;
    int state = ST_GAP;
    char quote;
    int nameStart;
    int nameEnd;
    int valueStart;

    AttrScan(int mask, AttrBudget budget, Attributes result) {
      this.mask = mask;
      this.budget = budget;
      this.result = result;
      result.clear();
      // A filtered mask keeps at most three names, so skip the eager reservation.
      if (mask == Consts.ATTR_ALL) {
        result.reserve(4);
      }
    }

    boolean opensQuotedValue(char c) {
      return state == ST_BEFORE_VALUE && (c == '"' || c == '\'');
    }

    void takeValue(String chunk, int valueStart, int valueEnd) {
      pushAttr(
          result,
          mask,
          budget,
          chunk.substring(nameStart, nameEnd),
          chunk.substring(valueStart, valueEnd));
      state = ST_GAP;
      quote = 0;
    }

    void takeBareName(String chunk, int nameEnd) {
      pushAttr(result, mask, budget, chunk.substring(nameStart, nameEnd), null);
    }

    /** {@code isWhitespace} is computed per arm, not per char. */
    void step(String chunk, char c, int index) {
      switch (state) {
        case ST_GAP:
          if (!isWhitespace(c)) {
            state = ST_NAME;
            nameStart = index;
          }
          break;
        case ST_NAME:
          if (c == '=' || isWhitespace(c)) {
            nameEnd = index;
            state = c == '=' ? ST_BEFORE_VALUE : ST_AFTER_NAME;
          }
          break;
        case ST_AFTER_NAME:
          if (c == '=') {
            state = ST_BEFORE_VALUE;
          } else if (!isWhitespace(c)) {
            takeBareName(chunk, nameEnd);
            state = ST_NAME;
            nameStart = index;
          }
          break;
        case ST_BEFORE_VALUE:
          if (!isWhitespace(c)) {
            state = ST_UNQUOTED_VALUE;
            valueStart = index;
          }
          break;
        case ST_UNQUOTED_VALUE:
          if (isWhitespace(c)) {
            takeValue(chunk, valueStart, index);
          }
          break;
        case ST_QUOTED:
          if (c == quote) {
            takeValue(chunk, valueStart, index);
          }
          break;
        default:
          break;
      }
    }

    /** Take the attribute still open when the tag ended at {@code end}. */
    void finish(String chunk, int end) {
      switch (state) {
        case ST_NAME:
          takeBareName(chunk, end);
          break;
        case ST_AFTER_NAME:
        case ST_BEFORE_VALUE:
          takeBareName(chunk, nameEnd);
          break;
        case ST_UNQUOTED_VALUE:
        case ST_QUOTED:
          takeValue(chunk, valueStart, end);
          break;
        default:
          break;
      }
      state = ST_GAP;
      quote = 0;
    }

    /** Freeze the in-flight attribute so the next chunk continues it. */
    TagCarry suspend(String chunk, int end) {
      TagCarry carry = TagCarry.opaque(this, budget);
      switch (state) {
        case ST_NAME:
          carry.pushName(mask, chunk.substring(nameStart, end));
          break;
        case ST_AFTER_NAME:
        case ST_BEFORE_VALUE:
          carry.pushName(mask, chunk.substring(nameStart, nameEnd));
          carry.closeName(result);
          break;
        case ST_UNQUOTED_VALUE:
        case ST_QUOTED:
          carry.pushName(mask, chunk.substring(nameStart, nameEnd));
          carry.closeName(result);
          carry.pushValue(mask, chunk.substring(valueStart, end));
          break;
        default:
          break;
      }
      return carry;
    }
  }

  private static int stepWithoutExtraction(int state, char quote, char c) {
    switch (state) {
      case ST_GAP:
        return isWhitespace(c) ? ST_GAP : ST_NAME;
      case ST_NAME:
        if (c == '=') {
          return ST_BEFORE_VALUE;
        }
        return isWhitespace(c) ? ST_AFTER_NAME : ST_NAME;
      case ST_AFTER_NAME:
        if (c == '=') {
          return ST_BEFORE_VALUE;
        }
        return isWhitespace(c) ? ST_AFTER_NAME : ST_NAME;
      case ST_BEFORE_VALUE:
        if (c == '"' || c == '\'') {
          return ST_QUOTED;
        }
        return isWhitespace(c) ? ST_BEFORE_VALUE : ST_UNQUOTED_VALUE;
      case ST_UNQUOTED_VALUE:
        return isWhitespace(c) ? ST_GAP : ST_UNQUOTED_VALUE;
      case ST_QUOTED:
        return c == quote ? ST_GAP : ST_QUOTED;
      default:
        return ST_GAP;
    }
  }

  /**
   * A start tag whose bytes ran out mid-tag. Holds the tokenizer position and the in-flight
   * attribute, never the raw tag.
   */
  static final class TagCarry {
    int state;
    char quote;
    boolean slash;
    StringBuilder name = new StringBuilder();
    StringBuilder value = new StringBuilder();
    boolean dropped;
    final AttrBudget budget;

    private TagCarry(int state, char quote, AttrBudget budget) {
      this.state = state;
      this.quote = quote;
      this.budget = budget;
    }

    static TagCarry opaque(AttrScan scan, AttrBudget budget) {
      return new TagCarry(scan.state, scan.quote, budget);
    }

    static TagCarry opaque(int state, AttrBudget budget) {
      return new TagCarry(state, (char) 0, budget);
    }

    static TagCarry opaque(int state, char quote, AttrBudget budget) {
      return new TagCarry(state, quote, budget);
    }

    /** Whether the in-flight attribute, whose name is complete, is retained. */
    boolean wanted(int mask) {
      return !dropped
          && (mask == Consts.ATTR_ALL
              || (mask & Consts.attr_bit(name))
                  != 0);
    }

    void start() {
      name.setLength(0);
      value.setLength(0);
      dropped = false;
    }

    void pushName(int mask, String text) {
      if (mask == Consts.ATTR_NONE || dropped || text.isEmpty()) {
        return;
      }
      int len = utf8Length(name) + utf8Length(text);
      boolean rejected =
          mask == Consts.ATTR_ALL
              ? !budget.fits(len)
              : len > MAX_WANTED_ATTR_NAME;
      if (rejected) {
        dropped = true;
        name.setLength(0);
        return;
      }
      name.append(text);
    }

    /** Called once the in-flight name is complete. */
    void closeName(Attributes out) {
      if (dropped || name.length() == 0) {
        return;
      }
      int bit = Consts.attr_bit(name);
      if (bit != Consts.ATTR_NONE && out.containsBit(bit)) {
        dropped = true;
        name.setLength(0);
      }
    }

    void pushValue(int mask, String text) {
      if (text.isEmpty() || !wanted(mask)) {
        return;
      }
      if (!budget.fits(utf8Length(name) + utf8Length(value) + utf8Length(text))) {
        dropped = true;
        name.setLength(0);
        value.setLength(0);
        return;
      }
      value.append(text);
    }

    void emit(int mask, Attributes out, boolean withValue) {
      if (!dropped && name.length() > 0) {
        pushAttr(out, mask, budget, name.toString(), withValue ? value.toString() : null);
      }
      start();
    }

    /** Take the attribute still open when the tag ended. */
    void finish(int mask, Attributes out) {
      switch (state) {
        case ST_NAME:
        case ST_AFTER_NAME:
        case ST_BEFORE_VALUE:
          emit(mask, out, false);
          break;
        case ST_UNQUOTED_VALUE:
        case ST_QUOTED:
          emit(mask, out, true);
          break;
        default:
          break;
      }
      state = ST_GAP;
      quote = 0;
    }

    /** The {@code /} held over from the previous chunk was not part of {@code />}. */
    void replaySlash(int mask, Attributes out) {
      switch (state) {
        case ST_GAP:
          start();
          state = ST_NAME;
          pushName(mask, "/");
          break;
        case ST_NAME:
          pushName(mask, "/");
          break;
        case ST_AFTER_NAME:
          emit(mask, out, false);
          state = ST_NAME;
          pushName(mask, "/");
          break;
        case ST_BEFORE_VALUE:
          state = ST_UNQUOTED_VALUE;
          pushValue(mask, "/");
          break;
        case ST_UNQUOTED_VALUE:
        case ST_QUOTED:
          pushValue(mask, "/");
          break;
        default:
          break;
      }
    }

    /** Consume the name run starting at {@code from}, whose first char does not end it. */
    int runName(int mask, String chunk, int from) {
      int end = from + 1;
      int len = chunk.length();
      while (end < len && !endsName(chunk.charAt(end))) {
        end++;
      }
      pushName(mask, chunk.substring(from, end));
      return end;
    }

    /** Consume the unquoted-value run starting at {@code from}. */
    int runValue(int mask, String chunk, int from) {
      int end = from + 1;
      int len = chunk.length();
      while (end < len && !endsUnquotedValue(chunk.charAt(end))) {
        end++;
      }
      pushValue(mask, chunk.substring(from, end));
      return end;
    }
  }

  private static boolean endsName(char c) {
    return isWhitespace(c) || c == '=' || c == '>' || c == '/';
  }

  /** {@code /} and quotes are ordinary chars inside an unquoted value. */
  private static boolean endsUnquotedValue(char c) {
    return isWhitespace(c) || c == '>';
  }

  static final class TagResume {
    final boolean complete;
    final int newPosition;
    final boolean selfClosing;

    private TagResume(boolean complete, int newPosition, boolean selfClosing) {
      this.complete = complete;
      this.newPosition = newPosition;
      this.selfClosing = selfClosing;
    }

    static TagResume incomplete(int newPosition) {
      return new TagResume(false, newPosition, false);
    }

    static TagResume done(int newPosition, boolean selfClosing) {
      return new TagResume(true, newPosition, selfClosing);
    }
  }

  /**
   * Continue a start tag from a previous chunk's {@link TagCarry}, appending to the attributes it
   * already collected.
   */
  static TagResume resumeTagAttributes(
      String htmlChunk, boolean selfClosing, int mask, TagCarry carry, Attributes out) {
    int chunkLength = htmlChunk.length();
    int i = 0;

    if (carry.slash) {
      if (i == chunkLength) {
        return TagResume.incomplete(chunkLength);
      }
      carry.slash = false;
      if (htmlChunk.charAt(i) == '>') {
        carry.finish(mask, out);
        return TagResume.done(i + 1, true);
      }
      carry.replaySlash(mask, out);
    }

    while (i < chunkLength) {
      char c = htmlChunk.charAt(i);

      // Inside a quoted value nothing is markup.
      if (carry.state != ST_QUOTED) {
        if (carry.state != ST_UNQUOTED_VALUE && c == '/') {
          if (i + 1 == chunkLength) {
            carry.slash = true;
            return TagResume.incomplete(chunkLength);
          }
          if (htmlChunk.charAt(i + 1) == '>') {
            carry.finish(mask, out);
            return TagResume.done(i + 2, true);
          }
        }
        if (c == '>') {
          carry.finish(mask, out);
          return TagResume.done(i + 1, selfClosing);
        }
      }

      switch (carry.state) {
        case ST_GAP:
          if (isWhitespace(c)) {
            i++;
          } else {
            carry.start();
            carry.state = ST_NAME;
            i = carry.runName(mask, htmlChunk, i);
          }
          break;
        case ST_NAME:
          if (c == '=') {
            carry.closeName(out);
            carry.state = ST_BEFORE_VALUE;
            i++;
          } else if (isWhitespace(c)) {
            carry.closeName(out);
            carry.state = ST_AFTER_NAME;
            i++;
          } else {
            i = carry.runName(mask, htmlChunk, i);
          }
          break;
        case ST_AFTER_NAME:
          if (c == '=') {
            carry.state = ST_BEFORE_VALUE;
            i++;
          } else if (isWhitespace(c)) {
            i++;
          } else {
            carry.emit(mask, out, false);
            carry.state = ST_NAME;
            i = carry.runName(mask, htmlChunk, i);
          }
          break;
        case ST_BEFORE_VALUE:
          if (isWhitespace(c)) {
            i++;
          } else if (c == '"' || c == '\'') {
            carry.state = ST_QUOTED;
            carry.quote = c;
            i++;
          } else {
            carry.state = ST_UNQUOTED_VALUE;
            i = carry.runValue(mask, htmlChunk, i);
          }
          break;
        case ST_UNQUOTED_VALUE:
          if (isWhitespace(c)) {
            carry.emit(mask, out, true);
            carry.state = ST_GAP;
            i++;
          } else {
            i = carry.runValue(mask, htmlChunk, i);
          }
          break;
        case ST_QUOTED:
          // Open from an earlier chunk, or opened in this one.
          int end = i;
          while (end < chunkLength && htmlChunk.charAt(end) != carry.quote) {
            end++;
          }
          carry.pushValue(mask, htmlChunk.substring(i, end));
          if (end == chunkLength) {
            return TagResume.incomplete(chunkLength);
          }
          carry.emit(mask, out, true);
          carry.state = ST_GAP;
          carry.quote = 0;
          i = end + 1;
          break;
        default:
          return TagResume.incomplete(chunkLength);
      }
    }

    return TagResume.incomplete(chunkLength);
  }
}
