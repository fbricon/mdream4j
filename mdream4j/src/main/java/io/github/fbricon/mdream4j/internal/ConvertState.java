/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.Clean;
import io.github.fbricon.mdream4j.Filter;
import io.github.fbricon.mdream4j.Frontmatter;
import io.github.fbricon.mdream4j.Options;
import io.github.fbricon.mdream4j.OutputFormat;
import io.github.fbricon.mdream4j.Plugins;
import io.github.fbricon.mdream4j.TagOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable conversion state. Mirrors Rust {@code convert::ConvertState}.
 *
 * <p>Rust splits the methods over {@code convert/mod.rs}, {@code parse.rs} and {@code output.rs};
 * this port keeps the fields in one class and the method bodies in {@link ParseOps} and {@link
 * OutputOps}.
 */
final class ConvertState {

  // === Options and derived flags ===

  final Options options;
  /** Mutable copy of {@link Options#origin()}: the first usable {@code <base href>} replaces it. */
  String origin;
  final OutputFormat format;
  final boolean plainText;
  final boolean htmlFormat;
  final int wrapWidth;
  final long maxNodeBytes;
  /** {@link #maxNodeBytes} clamped to the port's {@code int} buffer budgets. */
  final int maxNodeBytesInt;

  final boolean hasPlugins;
  final boolean hasTailwind;
  final boolean hasIsolateMain;
  final boolean hasFrontmatter;
  final boolean hasFilter;
  final boolean hasExtraction;
  final boolean hasTagOverrides;
  final boolean attrsForceAll;

  // === Parser state ===

  final int[] depthMap = new int[Consts.MAX_TAG_ID];
  boolean rawHtmlBlockOpen;
  boolean rawHtmlNeedsBoundary;
  boolean pendingRawHtmlBoundary;
  int depth;
  boolean hasEncodedHtmlEntity;
  boolean lastCharWasWhitespace = true;
  boolean textBufferContainsWhitespace;
  boolean textBufferContainsNonWhitespace;
  boolean textNodeExhausted;
  int textBufferBatchableLen;
  int textRunNextFlush = TEXT_RUN_FLUSH_THRESHOLD;
  boolean textBufferHasInlineGfmHazard;
  boolean justClosedTag;
  boolean isFirstTextInElement;
  boolean inNonNesting;
  boolean rawtextEndTagPending;
  int scriptDataState = SCRIPT_DATA;
  boolean inPre;

  /** Overflow root tag id, or {@code -1}. */
  int overflowTagId = -1;
  String overflowCustomName;
  int overflowSameNameDepth;
  boolean overflowIncluded;
  String overflowOpaqueName;
  int overflowOpaqueDepth;
  String overflowRawName;
  boolean overflowRawExcludesText;

  int hiddenSinceDepth = -1;
  int filterIncludedSinceDepth = -1;
  int collapseNonSpanDepth;
  int collapseSpanDepth;

  int firstBlockParentIndex = -1;
  final List<Integer> blockParentIndices = new ArrayList<>(16);

  final StringBuilder parseTextBuffer = new StringBuilder();
  final StringBuilder entityBuffer = new StringBuilder();

  PendingStartTag pendingStart;
  Scan.PendingTagScan pendingTag;
  boolean baseHrefSeen;

  Discard discard = Discard.none();
  final StringBuilder scriptTextBuffer = new StringBuilder();

  final NodeStack stack = new NodeStack(TYPICAL_NESTING_DEPTH);
  Attributes attrScratch = new Attributes();

  // === Plugin tracking ===

  boolean isolateMainFound;
  boolean isolateMainClosed;
  int isolateFirstHeaderDepth = -1;
  boolean isolateAfterFooter;

  boolean frontmatterInHead;
  String frontmatterTitle;
  final List<String[]> frontmatterMeta = new ArrayList<>();

  final List<ParsedSelectorEntry> extractionParsedSelectors = new ArrayList<>();
  final List<TrackedExtraction> extractionTracked = new ArrayList<>();
  final List<io.github.fbricon.mdream4j.ExtractedElement> extractionResults = new ArrayList<>();

  final List<ParsedSelectorEntry> filterIncludeParsed = new ArrayList<>();
  final List<ParsedSelectorEntry> filterExcludeParsed = new ArrayList<>();
  long filterExcludeTags = 0L;
  boolean filterExcludeTagsPresent;
  boolean filterProcessChildren = true;

  final List<ParsedSelectorEntry> tagOverrides = new ArrayList<>();
  final int[] overrideIdx = new int[Consts.MAX_TAG_ID];

  // === Markdown output state ===

  final StringBuilder buffer = new StringBuilder(1024);
  int lastContentCacheLen;
  boolean tableRenderedTable;
  boolean tableRowOpenerPending;
  int tableCurrentRowCells;
  boolean rawHtmlMarkdown;
  int rawHtmlScannedTo;
  int lineStart;
  int lineStartScannedTo;
  int headingHashRun;
  int heldBreakBytes;
  boolean heldOutputExceeded;
  int tableHeaderCells;
  int tableColumnCap;
  boolean truncated;
  final List<Integer> tableColumnAlignments = new ArrayList<>();
  boolean lastTextNodeContainsWhitespace;
  int lastTextNodeDepth;
  int lastTextNodeIndex;
  boolean hasLastTextNode;
  boolean lastNodeIsInline;
  boolean pendingInlineWhitespace;
  boolean streaming;
  final List<LinkOutputState> parentLinks = new ArrayList<>();
  LinkOutputState link = new LinkOutputState();
  final List<OpenMarker> openMarkers = new ArrayList<>();
  final List<CodeSpanState> codeSpans = new ArrayList<>();
  CodeFenceState codeFence;
  final List<BlockquoteFrame> blockquotes = new ArrayList<>(4);
  final StringBuilder blockquoteScratch = new StringBuilder();
  final StringBuilder escapeScratch = new StringBuilder();
  int blockquoteLineScannedTo;
  int blockquoteLineEnd;
  final List<String> headingSlugs = new ArrayList<>();
  final List<FragmentLink> fragmentLinks = new ArrayList<>();
  boolean inHeading;
  int headingBufferStart;

  final StringBuilder listIndent = new StringBuilder();
  final List<Integer> listIndentWidths = new ArrayList<>(8);
  final List<Integer> orderedStarts = new ArrayList<>();
  final List<Integer> listItemCounts = new ArrayList<>();

  boolean preFencePending;
  int preFencePendingDepth;
  String preFenceLang = "";
  int preFenceOwnerDepth;
  boolean emptyItemHazard;
  int emptyItemLineStart;
  int emptyItemLen;
  boolean emptyItemAfterCr;
  boolean listRulePending;
  int hardBreakEnd = -1;
  int captionBoundary;
  int captionBoundaryNewLines;
  final List<CaptionFrame> captionFrames = new ArrayList<>(2);

  int lastYieldedLength;
  boolean hasStreamedOutput;
  final char[] flushedTail = {'\n', '\n'};
  int bufferStartColumn;
  boolean preserveLeadingWhitespace;

  int cleanFlags;
  final boolean cleanUrls;

  // === Constants ===

  static final int TYPICAL_NESTING_DEPTH = 32;
  static final int TEXT_RUN_FLUSH_THRESHOLD = 64 * 1024;
  static final int TEXT_RUN_SPLIT_SCAN = 64;

  static final int NO_OVERRIDE = 255;

  static final int CLEAN_EMPTY_LINKS = 1;
  static final int CLEAN_FRAGMENTS = 2;
  static final int CLEAN_REDUNDANT_LINKS = 4;
  static final int CLEAN_SELF_LINK_HEADINGS = 8;
  static final int CLEAN_EMPTY_IMAGES = 16;
  static final int CLEAN_EMPTY_LINK_TEXT = 32;

  static final int SCRIPT_DATA = 0;
  static final int GFM_HAZARD_BIT = 1;
  static final int GFM_NEWLINE_BIT = 2;
  static final int GFM_TEXT_ACTIVE_BIT = 4;

  static final int MAX_ORDERED_START = 999_999_999;

  /** Per-byte GFM flags shared by the parser and escaping pass. */
  static final int[] GFM_BYTE_FLAGS = buildGfmByteFlags();

  /** Bytes the parser may batch without inspecting their escaping. */
  static final boolean[] BATCHABLE_TEXT = buildBatchableText();

  ConvertState(Options options, int capacity) {
    this.options = options;
    this.origin = options.origin();
    this.format = options.format();
    this.plainText = format == OutputFormat.TEXT;
    this.htmlFormat = format == OutputFormat.HTML;
    this.wrapWidth = options.wrapWidth();
    this.maxNodeBytes = options.maxNodeBytes();
    this.maxNodeBytesInt =
        maxNodeBytes == 0 ? 0 : (int) Math.min(maxNodeBytes, Integer.MAX_VALUE);
    this.tableColumnCap =
        maxNodeBytes == 0
            ? Integer.MAX_VALUE
            : (int) Math.max(1, Math.min(maxNodeBytes / 7, Integer.MAX_VALUE));

    for (int i = 0; i < overrideIdx.length; i++) {
      overrideIdx[i] = NO_OVERRIDE;
    }

    Clean clean = options.clean();
    boolean urls = false;
    if (clean != null) {
      urls = clean.urls();
      if (clean.emptyLinks()) {
        cleanFlags |= CLEAN_EMPTY_LINKS;
      }
      if (clean.fragments()) {
        cleanFlags |= CLEAN_FRAGMENTS;
      }
      if (clean.redundantLinks()) {
        cleanFlags |= CLEAN_REDUNDANT_LINKS;
      }
      if (clean.selfLinkHeadings()) {
        cleanFlags |= CLEAN_SELF_LINK_HEADINGS;
      }
      if (clean.emptyImages()) {
        cleanFlags |= CLEAN_EMPTY_IMAGES;
      }
      if (clean.emptyLinkText()) {
        cleanFlags |= CLEAN_EMPTY_LINK_TEXT;
      }
    }
    this.cleanUrls = urls;

    Plugins plugins = options.plugins();
    boolean hasTailwind = false;
    boolean hasIsolate = false;
    boolean hasFrontmatter = false;
    boolean hasFilter = false;
    boolean hasExtraction = false;
    boolean hasOverrides = false;
    boolean pluginsPresent = plugins != null;

    if (plugins != null) {
      hasTailwind = plugins.tailwindEnabled();
      hasIsolate = plugins.isolateMainEnabled();
      hasFrontmatter = plugins.frontmatter() != null;
      Filter filter = plugins.filter();
      if (filter != null) {
        hasFilter = true;
        for (String selector : filter.include()) {
          filterIncludeParsed.add(
              new ParsedSelectorEntry(selector, Selector.parseSelectorList(selector)));
        }
        for (String selector : filter.exclude()) {
          filterExcludeParsed.add(
              new ParsedSelectorEntry(selector, Selector.parseSelectorList(selector)));
        }
        this.filterExcludeTagsPresent = !filterExcludeParsed.isEmpty();
        this.filterExcludeTags = excludeTagTable(filterExcludeParsed);
        this.filterProcessChildren = filter.processChildren();
      }
      if (plugins.extraction() != null) {
        hasExtraction = true;
        for (String selector : plugins.extraction().selectors()) {
          extractionParsedSelectors.add(
              new ParsedSelectorEntry(selector, Selector.parseSelectorList(selector)));
        }
      }
      Map<String, TagOverride> overrides = plugins.tagOverrides();
      if (!overrides.isEmpty()) {
        hasOverrides = true;
        int index = 0;
        for (Map.Entry<String, TagOverride> entry : overrides.entrySet()) {
          TagOverride config = entry.getValue();
          int aliasTagId = -1;
          if (config.alias() != null) {
            Integer id = Consts.get_tag_id(config.alias());
            aliasTagId = id == null ? -1 : id;
          }
          tagOverrides.add(new ParsedSelectorEntry(entry.getKey(), config, aliasTagId));
          Integer id = Consts.get_tag_id(entry.getKey());
          if (id != null && overrideIdx[id] == NO_OVERRIDE && index < NO_OVERRIDE) {
            overrideIdx[id] = index;
          }
          index++;
        }
      }
    }

    this.hasPlugins = pluginsPresent;
    this.hasTailwind = hasTailwind;
    this.hasIsolateMain = hasIsolate;
    this.hasFrontmatter = hasFrontmatter;
    this.hasFilter = hasFilter;
    this.hasExtraction = hasExtraction;
    this.hasTagOverrides = hasOverrides;
    this.attrsForceAll = hasFilter || hasExtraction || (hasTailwind && maxNodeBytes != 0);
  }

  // === Small helpers shared with the ported modules ===

  boolean isMarkdown() {
    return format == OutputFormat.MARKDOWN;
  }

  boolean isHtml() {
    return format == OutputFormat.HTML;
  }

  boolean inTableCell() {
    return depthMap[Consts.TAG_TD] > 0 || depthMap[Consts.TAG_TH] > 0;
  }

  boolean inHeading() {
    return depthMap[Consts.TAG_H1] > 0
        || depthMap[Consts.TAG_H2] > 0
        || depthMap[Consts.TAG_H3] > 0
        || depthMap[Consts.TAG_H4] > 0
        || depthMap[Consts.TAG_H5] > 0
        || depthMap[Consts.TAG_H6] > 0;
  }

  boolean inRawHtmlBlock() {
    return rawHtmlBlockOpen;
  }

  int rawHtmlBlockDepth() {
    return depthMap[Consts.TAG_DETAILS]
        + depthMap[Consts.TAG_SUMMARY]
        + depthMap[Consts.TAG_ADDRESS]
        + depthMap[Consts.TAG_DL]
        + depthMap[Consts.TAG_DT]
        + depthMap[Consts.TAG_DD];
  }

  static boolean isRawHtmlBlockTag(int id) {
    return id == Consts.TAG_DETAILS
        || id == Consts.TAG_SUMMARY
        || id == Consts.TAG_ADDRESS
        || id == Consts.TAG_DL
        || id == Consts.TAG_DT
        || id == Consts.TAG_DD;
  }

  void refreshRawHtmlBlock(int id) {
    if (isRawHtmlBlockTag(id)) {
      rawHtmlBlockOpen = rawHtmlBlockDepth() > 0;
    }
  }

  int attrMask(TagHandler handler) {
    int bits = attrsForceAll ? Consts.ATTR_ALL : handler == null ? Consts.ATTR_NONE : handler.wantedAttrs;
    return bits | (hasTailwind ? Consts.ATTR_CLASS : Consts.ATTR_NONE);
  }

  int parserTagDepth(int tagId) {
    return depthMap[tagId] + (overflowTagId == tagId ? 1 : 0);
  }

  int parserLastTagId() {
    if (overflowTagId >= 0) {
      return overflowTagId;
    }
    ElementNode last = stack.size() == 0 ? null : stack.get(stack.size() - 1);
    return last == null ? -1 : last.tagId;
  }

  void popParserTop() {
    if (overflowSameNameDepth > 0) {
      clearOverflow();
    } else {
      ParseOps.closeNode(this);
    }
  }

  void clearOverflow() {
    overflowTagId = -1;
    overflowCustomName = null;
    overflowSameNameDepth = 0;
    overflowIncluded = false;
    overflowRawName = null;
    overflowRawExcludesText = false;
  }

  void clearOpaqueOverflow() {
    overflowOpaqueName = null;
    overflowOpaqueDepth = 0;
  }

  boolean matchesOverflowTag(String tagName, int tagId, boolean isBuiltin) {
    if (isBuiltin) {
      return overflowTagId == tagId;
    }
    return overflowCustomName != null && overflowCustomName.equals(tagName);
  }

  boolean matchesOpaqueTag(String tagName) {
    return overflowOpaqueName != null && Chars.asciiEqualsIgnoreCase(overflowOpaqueName, tagName);
  }

  void updateInPreOnClose(int id) {
    if (id == Consts.TAG_PRE && depthMap[Consts.TAG_PRE] == 0) {
      inPre = false;
    }
  }

  TagOverride overrideForId(int id) {
    int index = overrideIdx[id];
    if (index == NO_OVERRIDE || index >= tagOverrides.size()) {
      return null;
    }
    return tagOverrides.get(index).override;
  }

  TagOverride overrideForNode(ElementNode node) {
    if (!hasTagOverrides) {
      return null;
    }
    if (node.tagId >= 0 && node.customName() == null) {
      return overrideForId(node.tagId);
    }
    for (ParsedSelectorEntry entry : tagOverrides) {
      if (entry.selector.equals(node.name())) {
        return entry.override;
      }
    }
    return null;
  }

  TagOverride overrideForName(String name, int builtinTagId) {
    if (builtinTagId >= 0) {
      return overrideForId(builtinTagId);
    }
    for (ParsedSelectorEntry entry : tagOverrides) {
      if (entry.selector.equals(name)) {
        return entry.override;
      }
    }
    return null;
  }

  int aliasTagId(String tagName, int builtinTagId) {
    TagOverride config = overrideForName(tagName, builtinTagId);
    return config == null ? -1 : aliasId(config);
  }

  int aliasId(TagOverride config) {
    if (config == null || config.alias() == null) {
      return -1;
    }
    Integer id = Consts.get_tag_id(config.alias());
    return id == null ? -1 : id;
  }

  int orderedItemNumber(int index) {
    int start = 1;
    if (!orderedStarts.isEmpty()) {
      Integer value = orderedStarts.get(orderedStarts.size() - 1);
      if (value != null) {
        start = value;
      }
    }
    long number = (long) start + index;
    return (int) Math.min(number, MAX_ORDERED_START);
  }

  private static long excludeTagTable(List<ParsedSelectorEntry> selectors) {
    long table = 0L;
    for (ParsedSelectorEntry entry : selectors) {
      ParsedSelector parsed = entry.parsed;
      if (parsed.kind != ParsedSelector.Kind.TAG) {
        return 0L;
      }
      Integer id = Consts.get_tag_id(parsed.text);
      if (id == null) {
        return 0L;
      }
      if (id < 64) {
        table |= 1L << id;
      } else {
        return 0L;
      }
    }
    return table;
  }

  private static int[] buildGfmByteFlags() {
    int[] table = new int[256];
    long low = (1L << '*') | (1L << '<');
    long high =
        (1L << ('[' - 64))
            | (1L << ('\\' - 64))
            | (1L << ('_' - 64))
            | (1L << ('`' - 64))
            | (1L << ('~' - 64));
    for (int c = 33; c < 0x80; c++) {
      long mask = c < 64 ? low : high;
      if (((mask >>> (c & 63)) & 1L) != 0) {
        table[c] |= GFM_HAZARD_BIT;
      }
    }
    table['\n'] |= GFM_NEWLINE_BIT;
    table['\r'] |= GFM_NEWLINE_BIT;
    String active = "\\*_~`[]|><\n\r#-+=.) 0123456789";
    for (int i = 0; i < active.length(); i++) {
      table[active.charAt(i)] |= GFM_TEXT_ACTIVE_BIT;
    }
    return table;
  }

  private static boolean[] buildBatchableText() {
    boolean[] table = new boolean[256];
    long low = (1L << '*') | (1L << '<');
    long high =
        (1L << ('[' - 64))
            | (1L << ('\\' - 64))
            | (1L << ('_' - 64))
            | (1L << ('`' - 64))
            | (1L << ('~' - 64));
    for (int c = 33; c < 0x80; c++) {
      long mask = c < 64 ? low : high;
      table[c] = ((mask >>> (c & 63)) & 1L) == 0;
    }
    table['&'] = false;
    return table;
  }

  // === Nested state types ===

  static final class ParsedSelectorEntry {
    final String selector;
    final ParsedSelector parsed;
    final TagOverride override;
    final int aliasTagId;

    ParsedSelectorEntry(String selector, ParsedSelector parsed) {
      this.selector = selector;
      this.parsed = parsed;
      this.override = null;
      this.aliasTagId = -1;
    }

    ParsedSelectorEntry(String selector, TagOverride override, int aliasTagId) {
      this.selector = selector;
      this.parsed = null;
      this.override = override;
      this.aliasTagId = aliasTagId;
    }
  }

  static final class TrackedExtraction {
    final String selector;
    final int stackDepth;
    final StringBuilder textContent = new StringBuilder();
    final String tagName;
    final List<String[]> attributes;

    TrackedExtraction(
        String selector, int stackDepth, String tagName, List<String[]> attributes) {
      this.selector = selector;
      this.stackDepth = stackDepth;
      this.tagName = tagName;
      this.attributes = attributes;
    }
  }

  static final class LinkOutputState {
    int bracketPos;
    boolean skipped;
    boolean rawHtmlOpen;
    int urlMaxLen;
    boolean holdForever;
    boolean pinsOutput;
    boolean holdReleased;
    boolean emptyTextPending;
    boolean bracketEmitted;
    boolean open;
    int beginDepth;
    boolean startsAtHardBreak;

    void reset() {
      bracketPos = 0;
      skipped = false;
      rawHtmlOpen = false;
      urlMaxLen = 0;
      holdForever = false;
      pinsOutput = false;
      holdReleased = false;
      emptyTextPending = false;
      bracketEmitted = false;
      open = false;
      beginDepth = 0;
      startsAtHardBreak = false;
    }

    void copyFrom(LinkOutputState other) {
      bracketPos = other.bracketPos;
      skipped = other.skipped;
      rawHtmlOpen = other.rawHtmlOpen;
      urlMaxLen = other.urlMaxLen;
      holdForever = other.holdForever;
      pinsOutput = other.pinsOutput;
      holdReleased = other.holdReleased;
      emptyTextPending = other.emptyTextPending;
      bracketEmitted = other.bracketEmitted;
      open = other.open;
      beginDepth = other.beginDepth;
      startsAtHardBreak = other.startsAtHardBreak;
    }
  }

  static final class OpenMarker {
    int outputStart;
    int contentStart;
    int kind;
    boolean startsAtHardBreak;
  }

  static final class CodeSpanState {
    int depth;
    int outputStart;
    int contentStart;
    boolean openerEmitted;
    boolean exhausted;
  }

  static final class CodeFenceState {
    int outputStart;
    int markerOffset;
    int contentStart;
    String indent;
    String language;
  }

  static final class BlockquoteFrame {
    int contentStart;
    final String listIndent;

    BlockquoteFrame(int contentStart, String listIndent) {
      this.contentStart = contentStart;
      this.listIndent = listIndent;
    }
  }

  static final int CAPTION_PENDING = 0;
  static final int CAPTION_TENTATIVE = 1;
  static final int CAPTION_COMMITTED = 2;

  static final int CAPTION_BOUNDARY_NONE = 0;
  static final int CAPTION_BOUNDARY_PENDING = 1;
  static final int CAPTION_BOUNDARY_CONSUME_SPACE = 2;

  static final class CaptionFrame {
    int state = CAPTION_PENDING;
    int[] spacing = {0, 0};
    DeferredBreakRun deferredBreaks;
    boolean hasInternalBreak;

    int tentativeOutputStart;
    int tentativeContentStart;
    boolean tentativeRestoreSpace;
  }

  static final class DeferredBreakRun {
    String fragment;
    int count;
  }

  static final class FragmentLink {
    int bracketStart;
    int textEnd;
    int linkEnd;
    final String fragment;
    final boolean hasTitle;

    FragmentLink(int bracketStart, int textEnd, int linkEnd, String fragment, boolean hasTitle) {
      this.bracketStart = bracketStart;
      this.textEnd = textEnd;
      this.linkEnd = linkEnd;
      this.fragment = fragment;
      this.hasTitle = hasTitle;
    }
  }

  static final class PendingStartTag {
    final String customName;
    final int tagId;
    final boolean isBuiltin;
    final Scan.TagCarry carry;
    Attributes attrs;

    PendingStartTag(
        String customName, int tagId, boolean isBuiltin, Scan.TagCarry carry, Attributes attrs) {
      this.customName = customName;
      this.tagId = tagId;
      this.isBuiltin = isBuiltin;
      this.carry = carry;
      this.attrs = attrs;
    }

    String name() {
      if (customName != null) {
        return customName;
      }
      return tagId >= 0 ? Consts.TAG_NAMES[tagId] : "";
    }
  }

  /** Scanner bookkeeping for a token being dropped because it outgrew the cap. */
  static final class Discard {
    static final int KIND_NONE = 0;
    static final int KIND_TAG = 1;
    static final int KIND_COMMENT = 2;
    static final int KIND_CLOSETAG = 3;
    static final int KIND_DOCTYPE = 4;
    static final int KIND_CDATA = 5;

    final int kind;
    final Scan.PendingTagScan tag;
    final Scan.DiscardedComment comment;
    final Scan.DiscardedCloseTag closeTag;
    final int[] brackets;

    private Discard(
        int kind,
        Scan.PendingTagScan tag,
        Scan.DiscardedComment comment,
        Scan.DiscardedCloseTag closeTag,
        int[] brackets) {
      this.kind = kind;
      this.tag = tag;
      this.comment = comment;
      this.closeTag = closeTag;
      this.brackets = brackets;
    }

    static Discard none() {
      return new Discard(KIND_NONE, null, null, null, null);
    }

    static Discard tag(Scan.PendingTagScan pending) {
      return new Discard(KIND_TAG, pending, null, null, null);
    }

    static Discard comment(Scan.DiscardedComment state) {
      return new Discard(KIND_COMMENT, null, state, null, null);
    }

    static Discard closeTag(Scan.DiscardedCloseTag state) {
      return new Discard(KIND_CLOSETAG, null, null, state, null);
    }

    static Discard doctype() {
      return new Discard(KIND_DOCTYPE, null, null, null, null);
    }

    static Discard cdata(int brackets) {
      return new Discard(KIND_CDATA, null, null, null, new int[] {brackets});
    }
  }
}
