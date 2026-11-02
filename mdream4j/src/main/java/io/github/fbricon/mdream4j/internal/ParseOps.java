/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.Frontmatter;
import io.github.fbricon.mdream4j.Plugins;
import io.github.fbricon.mdream4j.TagOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** HTML parsing: tag scanning, node lifecycle, text-buffer processing. Mirrors {@code convert/parse.rs}. */
final class ParseOps {

  private ParseOps() {}

  static final int MAX_ELEMENT_DEPTH = 512;

  private static final boolean[] CLOSES_P = new boolean[Consts.MAX_TAG_ID];
  private static final boolean[] NEEDS_IMPLIED_END_RECOVERY = new boolean[Consts.MAX_TAG_ID];
  private static final boolean[] P_SCOPE_BOUNDARY = new boolean[256];
  private static final boolean[] LI_SCOPE_BOUNDARY = new boolean[256];
  private static final boolean[] DL_SCOPE_BOUNDARY = new boolean[256];
  private static final boolean[] CELL_SCOPE_BOUNDARY = new boolean[256];
  private static final boolean[] A_SCOPE_BOUNDARY = new boolean[256];
  private static final boolean[] TARGET_A = new boolean[256];
  private static final boolean[] TARGET_P = new boolean[256];
  private static final boolean[] TARGET_LI = new boolean[256];
  private static final boolean[] TARGET_CELL = new boolean[256];
  private static final boolean[] TARGET_DT_DD = new boolean[256];
  private static final boolean[] ROW_CLOSEABLE = new boolean[256];
  private static final boolean[] SECTION_CLOSEABLE = new boolean[256];

  static {
    CLOSES_P[Consts.TAG_DIV] = true;
    CLOSES_P[Consts.TAG_P] = true;
    CLOSES_P[Consts.TAG_UL] = true;
    CLOSES_P[Consts.TAG_OL] = true;
    CLOSES_P[Consts.TAG_DL] = true;
    CLOSES_P[Consts.TAG_LI] = true;
    CLOSES_P[Consts.TAG_DD] = true;
    CLOSES_P[Consts.TAG_DT] = true;
    CLOSES_P[Consts.TAG_TABLE] = true;
    CLOSES_P[Consts.TAG_H1] = true;
    CLOSES_P[Consts.TAG_H2] = true;
    CLOSES_P[Consts.TAG_H3] = true;
    CLOSES_P[Consts.TAG_H4] = true;
    CLOSES_P[Consts.TAG_H5] = true;
    CLOSES_P[Consts.TAG_H6] = true;
    CLOSES_P[Consts.TAG_BLOCKQUOTE] = true;
    CLOSES_P[Consts.TAG_SECTION] = true;
    CLOSES_P[Consts.TAG_ARTICLE] = true;
    CLOSES_P[Consts.TAG_HEADER] = true;
    CLOSES_P[Consts.TAG_FOOTER] = true;
    CLOSES_P[Consts.TAG_NAV] = true;
    CLOSES_P[Consts.TAG_ASIDE] = true;
    CLOSES_P[Consts.TAG_PRE] = true;
    CLOSES_P[Consts.TAG_HR] = true;
    CLOSES_P[Consts.TAG_FORM] = true;
    CLOSES_P[Consts.TAG_FIELDSET] = true;
    CLOSES_P[Consts.TAG_FIGURE] = true;
    CLOSES_P[Consts.TAG_FIGCAPTION] = true;
    CLOSES_P[Consts.TAG_ADDRESS] = true;
    CLOSES_P[Consts.TAG_MAIN] = true;
    CLOSES_P[Consts.TAG_CENTER] = true;
    CLOSES_P[Consts.TAG_DETAILS] = true;
    CLOSES_P[Consts.TAG_SUMMARY] = true;
    CLOSES_P[Consts.TAG_DIALOG] = true;

    System.arraycopy(CLOSES_P, 0, NEEDS_IMPLIED_END_RECOVERY, 0, CLOSES_P.length);
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_A] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_TD] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_TH] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_TR] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_THEAD] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_TBODY] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_TFOOT] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_OPTION] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_OPTGROUP] = true;
    NEEDS_IMPLIED_END_RECOVERY[Consts.TAG_SELECT] = true;

    tagSet(
        P_SCOPE_BOUNDARY,
        Consts.TAG_BUTTON,
        Consts.TAG_TD,
        Consts.TAG_TH,
        Consts.TAG_CAPTION,
        Consts.TAG_TABLE,
        Consts.TAG_TEMPLATE,
        Consts.TAG_HTML,
        Consts.TAG_UL,
        Consts.TAG_OL,
        Consts.TAG_DL,
        Consts.TAG_LI);
    tagSet(
        LI_SCOPE_BOUNDARY,
        Consts.TAG_UL,
        Consts.TAG_OL,
        Consts.TAG_TABLE,
        Consts.TAG_TD,
        Consts.TAG_TH,
        Consts.TAG_CAPTION,
        Consts.TAG_TEMPLATE,
        Consts.TAG_HTML);
    tagSet(
        DL_SCOPE_BOUNDARY,
        Consts.TAG_DL,
        Consts.TAG_UL,
        Consts.TAG_OL,
        Consts.TAG_LI,
        Consts.TAG_TABLE,
        Consts.TAG_TD,
        Consts.TAG_TH,
        Consts.TAG_CAPTION,
        Consts.TAG_TEMPLATE,
        Consts.TAG_HTML);
    tagSet(
        CELL_SCOPE_BOUNDARY,
        Consts.TAG_TR,
        Consts.TAG_THEAD,
        Consts.TAG_TBODY,
        Consts.TAG_TFOOT,
        Consts.TAG_TABLE,
        Consts.TAG_CAPTION,
        Consts.TAG_TEMPLATE,
        Consts.TAG_HTML);
    tagSet(
        A_SCOPE_BOUNDARY,
        Consts.TAG_P,
        Consts.TAG_DIV,
        Consts.TAG_LI,
        Consts.TAG_UL,
        Consts.TAG_OL,
        Consts.TAG_DL,
        Consts.TAG_DD,
        Consts.TAG_DT,
        Consts.TAG_TABLE,
        Consts.TAG_TD,
        Consts.TAG_TH,
        Consts.TAG_TR,
        Consts.TAG_CAPTION,
        Consts.TAG_BLOCKQUOTE,
        Consts.TAG_SECTION,
        Consts.TAG_ARTICLE,
        Consts.TAG_HEADER,
        Consts.TAG_FOOTER,
        Consts.TAG_NAV,
        Consts.TAG_ASIDE,
        Consts.TAG_MAIN,
        Consts.TAG_FORM,
        Consts.TAG_FIELDSET,
        Consts.TAG_FIGURE,
        Consts.TAG_BUTTON,
        Consts.TAG_H1,
        Consts.TAG_H2,
        Consts.TAG_H3,
        Consts.TAG_H4,
        Consts.TAG_H5,
        Consts.TAG_H6,
        Consts.TAG_TEMPLATE,
        Consts.TAG_HTML);
    tagSet(TARGET_A, Consts.TAG_A);
    tagSet(TARGET_P, Consts.TAG_P);
    tagSet(TARGET_LI, Consts.TAG_LI);
    tagSet(TARGET_CELL, Consts.TAG_TD, Consts.TAG_TH);
    tagSet(TARGET_DT_DD, Consts.TAG_DT, Consts.TAG_DD);
    tagSet(ROW_CLOSEABLE, Consts.TAG_TD, Consts.TAG_TH, Consts.TAG_TR);
    tagSet(
        SECTION_CLOSEABLE,
        Consts.TAG_TD,
        Consts.TAG_TH,
        Consts.TAG_TR,
        Consts.TAG_THEAD,
        Consts.TAG_TBODY,
        Consts.TAG_TFOOT,
        Consts.TAG_CAPTION);
  }

  private static void tagSet(boolean[] table, int... ids) {
    for (int id : ids) {
      table[id] = true;
    }
  }

  static boolean needsImpliedEndRecovery(int id) {
    return id >= 0 && id < NEEDS_IMPLIED_END_RECOVERY.length && NEEDS_IMPLIED_END_RECOVERY[id];
  }

  static boolean isHeadContentTag(int tagId) {
    return tagId == Consts.TAG_TITLE
        || tagId == Consts.TAG_META
        || tagId == Consts.TAG_LINK
        || tagId == Consts.TAG_BASE
        || tagId == Consts.TAG_STYLE
        || tagId == Consts.TAG_SCRIPT
        || tagId == Consts.TAG_NOSCRIPT
        || tagId == Consts.TAG_TEMPLATE;
  }

  // === Result types ===

  static final class OpeningTagResult {
    final boolean complete;
    final int newPosition;
    final boolean selfClosing;
    final boolean skip;

    OpeningTagResult(boolean complete, int newPosition, boolean selfClosing, boolean skip) {
      this.complete = complete;
      this.newPosition = newPosition;
      this.selfClosing = selfClosing;
      this.skip = skip;
    }
  }

  static final class CloseTagResult {
    final boolean complete;
    final int newPosition;
    final boolean boundedPrefix;
    final boolean nameEnded;

    CloseTagResult(boolean complete, int newPosition, boolean boundedPrefix, boolean nameEnded) {
      this.complete = complete;
      this.newPosition = newPosition;
      this.boundedPrefix = boundedPrefix;
      this.nameEnded = nameEnded;
    }
  }

  // === Hidden element detection ===

  static boolean isHidden(ElementNode node) {
    String style = node.attributes.get("style");
    if (style != null && styleHides(style)) {
      return true;
    }
    String hidden = node.attributes.get("hidden");
    return hidden != null && !Chars.asciiEqualsIgnoreCase(hidden, "until-found");
  }

  private static boolean styleHides(String style) {
    int index = 0;
    while (index < style.length()) {
      char c = style.charAt(index);
      if (c == 'd') {
        int value = styleValueAt(style, index, "display:");
        if (value >= 0 && style.startsWith("none", value)) {
          return true;
        }
      } else if (c == 'v') {
        int value = styleValueAt(style, index, "visibility:");
        if (value >= 0 && style.startsWith("hidden", value)) {
          return true;
        }
      } else if (c == 'p') {
        int value = styleValueAt(style, index, "position:");
        if (value >= 0
            && (style.startsWith("absolute", value) || style.startsWith("fixed", value))) {
          return true;
        }
      }
      index++;
    }
    return false;
  }

  private static int styleValueAt(String style, int index, String property) {
    int end = index + property.length();
    if (end > style.length() || !style.regionMatches(index, property, 0, property.length())) {
      return -1;
    }
    return end + (end < style.length() && style.charAt(end) == ' ' ? 1 : 0);
  }

  // === Text buffering ===

  static void processTextBuffer(ConvertState self, StringBuilder textBuffer) {
    processTextBufferPiece(self, textBuffer, true);
  }

  static void processTextBufferPiece(
      ConvertState self, StringBuilder textBuffer, boolean completesTextNode) {
    boolean containsNonWhitespace = self.textBufferContainsNonWhitespace;
    boolean containsWhitespace = self.textBufferContainsWhitespace;
    boolean hasInlineGfmHazard = self.textBufferHasInlineGfmHazard || self.hasEncodedHtmlEntity;
    self.textBufferContainsNonWhitespace = false;
    self.textBufferContainsWhitespace = false;
    self.textBufferBatchableLen = 0;
    self.textBufferHasInlineGfmHazard = false;

    TagHandler overflowHandler = self.overflowTagId >= 0 ? Tags.getTagHandler(self.overflowTagId) : null;
    boolean overflowExcludesText =
        overflowHandler != null && overflowHandler.excludesTextNodes;
    ElementNode parent = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
    boolean excludesTextNodes =
        self.overflowOpaqueDepth > 0
            || self.overflowRawExcludesText
            || overflowExcludesText
            || (parent != null
                && (parent.excludesTextNodes
                    || (!self.overflowIncluded && parent.excludedFromMarkdown)));

    if (self.hasIsolateMain && isolateMainExcludes(self)) {
      excludesTextNodes = true;
    }

    boolean inTitle = parent != null && parent.tagId == Consts.TAG_TITLE;
    boolean captureTitle =
        inTitle && self.hasFrontmatter && self.frontmatterInHead && !excludesTextNodes;
    if (inTitle) {
      excludesTextNodes = true;
    }

    boolean inPreTag = self.inPre;
    int childTextNodeIndex = parent != null ? parent.childTextNodeIndex : 0;

    if (!inPreTag && !containsNonWhitespace && childTextNodeIndex == 0 && self.stack.size() != 0) {
      textBuffer.setLength(0);
      return;
    }
    if (textBuffer.length() == 0) {
      return;
    }

    int firstBlockParentIndex = self.firstBlockParentIndex;
    int firstBlockChildTextCount =
        firstBlockParentIndex >= 0 ? self.stack.get(firstBlockParentIndex).childTextNodeIndex : 0;

    boolean isFirstTextInBlock;
    if (firstBlockChildTextCount != 0) {
      isFirstTextInBlock = false;
    } else if (firstBlockParentIndex >= 0) {
      isFirstTextInBlock = true;
    } else if (self.buffer.length() > 0) {
      isFirstTextInBlock = self.buffer.charAt(self.buffer.length() - 1) == '\n';
    } else {
      // An `html` stream hands out its whole buffer each chunk, so an empty buffer there only
      // means a document start when nothing was yielded before it.
      isFirstTextInBlock = !self.htmlFormat || self.flushedTail[1] == '\n';
    }

    if (containsWhitespace && isFirstTextInBlock) {
      int start = 0;
      while (start < textBuffer.length()) {
        char c = textBuffer.charAt(start);
        boolean ws = inPreTag ? (c == '\n' || c == '\r') : Scan.isWhitespace(c);
        if (!ws) {
          break;
        }
        start++;
      }
      if (start > 0) {
        if (firstBlockParentIndex < 0) {
          int currentDepth = self.stack.size();
          for (ConvertState.TrackedExtraction tracked : self.extractionTracked) {
            if (tracked.stackDepth <= currentDepth) {
              tracked.textContent.append(textBuffer, 0, start);
            }
          }
        }
        textBuffer.delete(0, start);
      }
    }

    if (self.hasEncodedHtmlEntity) {
      boolean protect =
          !inTitle
              && self.isMarkdown()
              && self.depthMap[Consts.TAG_PRE] == 0
              && self.depthMap[Consts.TAG_CODE] == 0
              && !self.inRawHtmlBlock();
      if (Entities.decodeInto(textBuffer, false, protect, self.entityBuffer)) {
        swap(textBuffer, self.entityBuffer);
      }
      self.hasEncodedHtmlEntity = false;
    }

    String text = textBuffer.toString();

    if (captureTitle) {
      String value = Chars.trimUnicode(text);
      if (!value.isEmpty()) {
        self.frontmatterTitle = value;
      }
    }

    String tailwindPrefix = null;
    String tailwindSuffix = null;
    if (self.hasTailwind && parent != null && parent.tailwind() != null) {
      TailwindData tw = parent.tailwind();
      if (tw.hidden) {
        excludesTextNodes = true;
      } else if (!excludesTextNodes) {
        tailwindPrefix = tw.prefix;
        tailwindSuffix = tw.suffix;
      }
    }

    if (!self.extractionTracked.isEmpty()) {
      int currentDepth = self.stack.size();
      for (ConvertState.TrackedExtraction tracked : self.extractionTracked) {
        if (tracked.stackDepth <= currentDepth) {
          tracked.textContent.append(text);
        }
      }
    }

    if (!excludesTextNodes) {
      int depth = self.depth;
      int index = parent != null ? parent.currentWalkIndex : 0;
      self.textBufferHasInlineGfmHazard = hasInlineGfmHazard;
      if (self.link.emptyTextPending && !Chars.trimUnicode(text).isEmpty()) {
        self.link.emptyTextPending = false;
      }
      if (tailwindPrefix == null && tailwindSuffix == null) {
        OutputOps.emitTextWithGeneratedMarkdown(
            self, text, containsWhitespace, depth, index, null, null);
      } else {
        OutputOps.emitTextWithGeneratedMarkdown(
            self, text, containsWhitespace, depth, index, tailwindPrefix, tailwindSuffix);
      }
    }

    textBuffer.setLength(0);

    if (completesTextNode) {
      advanceTextNodeCounters(self, firstBlockParentIndex);
    }
  }

  private static void advanceTextNodeCounters(ConvertState self, int firstBlockParentIndex) {
    if (self.stack.size() > 0) {
      self.stack.get(self.stack.size() - 1).currentWalkIndex++;
    }
    int upTo = firstBlockParentIndex >= 0 ? firstBlockParentIndex : 0;
    for (int i = upTo; i < self.stack.size(); i++) {
      self.stack.get(i).childTextNodeIndex++;
    }
  }

  private static boolean isolateMainExcludes(ConvertState self) {
    if (self.isolateMainFound) {
      return self.isolateMainClosed;
    }
    if (self.isolateFirstHeaderDepth < 0) {
      return self.depthMap[Consts.TAG_HEAD] == 0;
    }
    return self.isolateAfterFooter;
  }

  private static void swap(StringBuilder a, StringBuilder b) {
    String tmp = a.toString();
    a.setLength(0);
    a.append(b);
    b.setLength(0);
    b.append(tmp);
  }

  // === Tree recovery ===

  static void closeImpliedTo(ConvertState self, boolean[] target, boolean[] boundary) {
    if (self.overflowSameNameDepth > 0) {
      if (self.overflowTagId >= 0) {
        if (target[self.overflowTagId]) {
          self.clearOverflow();
          return;
        }
        if (boundary[self.overflowTagId]) {
          return;
        }
      }
      self.clearOverflow();
    }
    int closeCount = 0;
    boolean found = false;
    for (int i = self.stack.size() - 1; i >= 0; i--) {
      int id = self.stack.get(i).tagId;
      if (id >= 0) {
        if (target[id]) {
          closeCount++;
          found = true;
          break;
        }
        if (boundary[id]) {
          break;
        }
      }
      closeCount++;
    }
    if (found) {
      for (int i = 0; i < closeCount; i++) {
        closeNode(self);
      }
    }
  }

  static void closeTableContext(ConvertState self, boolean[] closeable) {
    if (self.overflowSameNameDepth > 0) {
      boolean closeRoot = self.overflowTagId >= 0 && closeable[self.overflowTagId];
      if (!closeRoot) {
        return;
      }
      self.clearOverflow();
    }
    int closeCount = 0;
    int walked = 0;
    for (int i = self.stack.size() - 1; i >= 0; i--) {
      int id = self.stack.get(i).tagId;
      if (id >= 0 && closeable[id]) {
        walked++;
        closeCount = walked;
      } else if (id == Consts.TAG_TABLE
          || id == Consts.TAG_TEMPLATE
          || id == Consts.TAG_CAPTION) {
        break;
      } else {
        walked++;
      }
    }
    for (int i = 0; i < closeCount; i++) {
      closeNode(self);
    }
  }

  static boolean closeSelectTo(ConvertState self, int targetId) {
    if (self.overflowSameNameDepth > 0) {
      if (self.overflowTagId >= 0) {
        if (self.overflowTagId == targetId) {
          self.clearOverflow();
          return true;
        }
        if ((self.overflowTagId == Consts.TAG_SELECT && targetId != Consts.TAG_SELECT)
            || self.overflowTagId == Consts.TAG_TEMPLATE) {
          return false;
        }
      }
      self.clearOverflow();
    }
    int targetIndex = -1;
    for (int i = self.stack.size() - 1; i >= 0; i--) {
      int id = self.stack.get(i).tagId;
      if (id == targetId) {
        targetIndex = i;
        break;
      }
      if (id == Consts.TAG_SELECT && targetId != Consts.TAG_SELECT) {
        break;
      }
      if (id == Consts.TAG_TEMPLATE) {
        break;
      }
    }
    if (targetIndex >= 0) {
      while (self.stack.size() > targetIndex) {
        closeNode(self);
      }
      return true;
    }
    return false;
  }

  // === Opening tags ===

  static OpeningTagResult processOpeningTag(
      ConvertState self, String tagName, int tagId, boolean isBuiltin, String chunk, int position) {
    TagHandler handler = tagId >= 0 ? Tags.getTagHandler(tagId) : null;
    Scan.AttrBudget budget = new Scan.AttrBudget(self.maxNodeBytesInt);
    if (!isBuiltin && !tagName.startsWith("#")) {
      budget.charge(Scan.utf8Length(tagName));
    }
    Scan.TagScan scan =
        Scan.processTagAttributes(chunk, position, handler, self.attrMask(handler), budget, self.attrScratch);
    if (!scan.complete) {
      self.pendingStart =
          new ConvertState.PendingStartTag(
              isBuiltin ? null : tagName,
              tagId,
              isBuiltin,
              scan.carry,
              self.attrScratch);
      self.attrScratch = new Attributes();
      return new OpeningTagResult(false, position, false, false);
    }
    return applyOpeningTag(
        self, tagName, tagId, isBuiltin, scan.newPosition, scan.selfClosing, scan.overflow);
  }

  static OpeningTagResult applyOpeningTag(
      ConvertState self,
      String tagName,
      int tagId,
      boolean isBuiltin,
      int newPosition,
      boolean selfClosing,
      boolean overflow) {
    TagHandler handler = tagId >= 0 ? Tags.getTagHandler(tagId) : null;

    if (overflow) {
      self.truncated = true;
      self.attrScratch.clear();
      return new OpeningTagResult(true, newPosition, false, true);
    }

    if (self.overflowOpaqueDepth > 0) {
      if (!selfClosing && self.matchesOpaqueTag(tagName)) {
        self.overflowOpaqueDepth = Math.min(self.overflowOpaqueDepth + 1, Integer.MAX_VALUE);
      } else if (!selfClosing && handler != null && handler.isNonNesting) {
        self.overflowRawName = tagName;
        self.overflowRawExcludesText = handler.excludesTextNodes;
      }
      return new OpeningTagResult(true, newPosition, selfClosing, true);
    }

    if (self.overflowSameNameDepth == 0
        && self.depthMap[Consts.TAG_HEAD] > 0
        && self.depthMap[Consts.TAG_TEMPLATE] == 0
        && !isHeadContentTag(tagId)) {
      while (self.stack.size() > 0
          && self.stack.get(self.stack.size() - 1).tagId != Consts.TAG_HEAD) {
        closeNode(self);
      }
      if (self.stack.size() > 0
          && self.stack.get(self.stack.size() - 1).tagId == Consts.TAG_HEAD) {
        closeNode(self);
      }
    }

    if (tagId >= 0 && needsImpliedEndRecovery(tagId)) {
      switch (tagId) {
        case Consts.TAG_SELECT:
          if (self.parserTagDepth(Consts.TAG_SELECT) > 0 && closeSelectTo(self, Consts.TAG_SELECT)) {
            return new OpeningTagResult(true, newPosition, false, true);
          }
          break;
        case Consts.TAG_OPTION:
          if (self.parserTagDepth(Consts.TAG_SELECT) > 0) {
            closeSelectTo(self, Consts.TAG_OPTION);
          } else if (self.parserLastTagId() == Consts.TAG_OPTION) {
            self.popParserTop();
          }
          break;
        case Consts.TAG_OPTGROUP:
          if (self.parserTagDepth(Consts.TAG_SELECT) > 0) {
            closeSelectTo(self, Consts.TAG_OPTION);
            closeSelectTo(self, Consts.TAG_OPTGROUP);
          }
          break;
        case Consts.TAG_A:
          if (self.parserTagDepth(Consts.TAG_A) > 0) {
            closeImpliedTo(self, TARGET_A, A_SCOPE_BOUNDARY);
          }
          break;
        case Consts.TAG_TD:
        case Consts.TAG_TH:
        case Consts.TAG_TR:
        case Consts.TAG_THEAD:
        case Consts.TAG_TBODY:
        case Consts.TAG_TFOOT:
          if (self.parserTagDepth(Consts.TAG_TABLE) > 0) {
            if ((tagId == Consts.TAG_TD || tagId == Consts.TAG_TH)
                && (self.parserTagDepth(Consts.TAG_TD) > 0
                    || self.parserTagDepth(Consts.TAG_TH) > 0)) {
              closeImpliedTo(self, TARGET_CELL, CELL_SCOPE_BOUNDARY);
            } else if (tagId == Consts.TAG_TR && self.parserTagDepth(Consts.TAG_TR) > 0) {
              closeTableContext(self, ROW_CLOSEABLE);
            } else if (tagId == Consts.TAG_THEAD
                || tagId == Consts.TAG_TBODY
                || tagId == Consts.TAG_TFOOT) {
              closeTableContext(self, SECTION_CLOSEABLE);
            }
          }
          break;
        default:
          if (self.parserTagDepth(Consts.TAG_P) > 0) {
            closeImpliedTo(self, TARGET_P, P_SCOPE_BOUNDARY);
          }
          if (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6) {
            int last = self.parserLastTagId();
            if (last >= Consts.TAG_H1 && last <= Consts.TAG_H6) {
              self.popParserTop();
            }
          } else if (tagId == Consts.TAG_LI && self.parserTagDepth(Consts.TAG_LI) > 0) {
            closeImpliedTo(self, TARGET_LI, LI_SCOPE_BOUNDARY);
          } else if ((tagId == Consts.TAG_DT || tagId == Consts.TAG_DD)
              && (self.parserTagDepth(Consts.TAG_DT) > 0
                  || self.parserTagDepth(Consts.TAG_DD) > 0)) {
            closeImpliedTo(self, TARGET_DT_DD, DL_SCOPE_BOUNDARY);
          }
          break;
      }
    }

    boolean inOverflow =
        !selfClosing
            && (self.overflowSameNameDepth > 0 || self.stack.size() >= MAX_ELEMENT_DEPTH);
    int overflowPluginMode =
        inOverflow
            ? overflowPluginMode(self, tagName, tagId, isBuiltin, self.attrScratch, handler)
            : 0;
    if (inOverflow && (tagId == Consts.TAG_TEMPLATE || overflowPluginMode == 1)) {
      enterOpaqueOverflow(self, tagName, handler);
      return new OpeningTagResult(true, newPosition, false, true);
    }

    if (self.overflowSameNameDepth > 0 && !selfClosing) {
      if (self.matchesOverflowTag(tagName, tagId, isBuiltin)) {
        self.overflowSameNameDepth = Math.min(self.overflowSameNameDepth + 1, Integer.MAX_VALUE);
      } else if (handler != null && handler.isNonNesting) {
        self.overflowRawName = tagName;
        self.overflowRawExcludesText = handler.excludesTextNodes;
      }
      return new OpeningTagResult(true, newPosition, false, true);
    }

    if (!selfClosing && self.stack.size() >= MAX_ELEMENT_DEPTH) {
      enterOverflow(self, tagName, tagId, isBuiltin, handler, overflowPluginMode == 2);
      return new OpeningTagResult(true, newPosition, false, true);
    }

    if (tagId >= 0) {
      if (tagId < Consts.MAX_TAG_ID) {
        self.depthMap[tagId] = Math.min(self.depthMap[tagId] + 1, Integer.MAX_VALUE);
      }
      self.refreshRawHtmlBlock(tagId);
      if (tagId == Consts.TAG_PRE) {
        self.inPre = true;
      }
      if (tagId == Consts.TAG_BASE) {
        captureBaseHref(self);
      }
    }
    self.depth++;

    int currentWalkIndex =
        self.stack.size() == 0 ? 0 : self.stack.get(self.stack.size() - 1).currentWalkIndex;

    boolean hInline;
    boolean hExcludes;
    boolean hNonNesting;
    boolean hCollapses;
    int[] hSpacing;
    if (handler != null) {
      hInline = handler.isInline;
      hExcludes = handler.excludesTextNodes;
      hNonNesting = handler.isNonNesting;
      hCollapses = handler.collapsesInnerWhiteSpace;
      hSpacing = handler.spacing;
    } else if (tagId < 0) {
      hInline = true;
      hExcludes = false;
      hNonNesting = false;
      hCollapses = false;
      hSpacing = Consts.NO_SPACING;
    } else {
      hInline = false;
      hExcludes = false;
      hNonNesting = false;
      hCollapses = false;
      hSpacing = null;
    }

    if (self.hasTagOverrides && tagId >= 0) {
      TagOverride override = self.overrideForId(tagId);
      if (override != null && override.inline() != null) {
        hInline = override.inline();
      }
      if (override != null
          && (override.selfClosing() != null
              || override.collapseInnerWhiteSpace() != null
              || override.spacingBefore() != 0
              || override.spacingAfter() != 0)) {
        // The immutable handler flags stay shared; the node picks up the
        // override values below.
      }
    } else if (self.hasTagOverrides && tagId < 0) {
      TagOverride override = self.overrideForName(tagName, -1);
      if (override != null && override.inline() != null) {
        hInline = override.inline();
      }
    }

    int parentIndex = self.stack.size() - 1;
    int idx = self.stack.size();
    boolean parentHidden =
        self.hasPlugins
            && self.hasTailwind
            && parentIndex >= 0
            && self.stack.get(parentIndex).tailwind() != null
            && self.stack.get(parentIndex).tailwind().hidden;

    ElementNode tag = self.stack.pushSlot();
    Attributes swap = tag.attributes;
    tag.attributes = self.attrScratch;
    self.attrScratch = swap;
    tag.extras = isBuiltin ? null : new NodeExtras(tagName);
    tag.tagId = tagId;
    tag.depth = self.depth;
    tag.index = currentWalkIndex;
    tag.currentWalkIndex = 0;
    tag.childTextNodeIndex = 0;
    tag.containsWhitespace = false;
    tag.excludedFromMarkdown = false;
    tag.enterSkipped = false;
    tag.isInline = hInline;
    tag.excludesTextNodes = hExcludes;
    tag.isNonNesting = hNonNesting;
    tag.collapsesInnerWhiteSpace = hCollapses;
    tag.spacing = hSpacing;

    if (self.hasTagOverrides) {
      TagOverride override =
          tagId >= 0 ? self.overrideForId(tagId) : self.overrideForName(tagName, -1);
      if (override != null) {
        if (override.inline() != null) {
          tag.isInline = override.inline();
        }
        if (override.selfClosing() != null) {
          tag.isNonNesting = tag.isNonNesting;
        }
        if (override.collapseInnerWhiteSpace() != null) {
          tag.collapsesInnerWhiteSpace = override.collapseInnerWhiteSpace();
        }
        if (override.spacingBefore() != 0 || override.spacingAfter() != 0) {
          tag.spacing =
              new int[] {override.spacingBefore() & 0xFF, override.spacingAfter() & 0xFF};
        }
      }
    }

    boolean skipNode = false;
    boolean filterExcluded = false;
    boolean inTemplate = self.depthMap[Consts.TAG_TEMPLATE] > 0;

    if (self.hasPlugins) {
      if (self.hasTailwind) {
        String classAttr = tag.attributes.getBit(Consts.ATTR_CLASS);
        if (classAttr != null) {
          Tailwind.Result result = Tailwind.processTailwindClasses(classAttr);
          String prefix = self.plainText ? null : result.prefix();
          String suffix = self.plainText ? null : result.suffix();
          boolean hidden = result.hidden() || parentHidden;
          if (prefix != null || suffix != null || hidden) {
            tag.setTailwind(new TailwindData(prefix, suffix, hidden));
            if (hidden) {
              skipNode = true;
            }
          }
        } else if (parentHidden) {
          tag.setTailwind(new TailwindData(null, null, true));
          skipNode = true;
        }
      }

      if (self.hasFilter) {
        if (self.hiddenSinceDepth >= 0) {
          skipNode = true;
          filterExcluded = true;
        } else if (isHidden(tag) || excludedByFilter(self, tag)) {
          skipNode = true;
          filterExcluded = true;
          self.hiddenSinceDepth = self.depth;
        }
        if (!skipNode && !self.filterIncludeParsed.isEmpty()) {
          boolean matchFound =
              self.filterProcessChildren && self.filterIncludedSinceDepth >= 0;
          if (!matchFound) {
            for (ConvertState.ParsedSelectorEntry entry : self.filterIncludeParsed) {
              if (Selector.matchesSelector(tag, entry.parsed)) {
                matchFound = true;
                if (self.filterIncludedSinceDepth < 0) {
                  self.filterIncludedSinceDepth = self.depth;
                }
                break;
              }
            }
          }
          if (!matchFound) {
            skipNode = true;
            filterExcluded = true;
          }
        }
      }

      if (self.hasIsolateMain && !inTemplate) {
        boolean isMain = tagId == Consts.TAG_MAIN;
        if (!self.isolateMainFound && isMain && self.depth <= 50) {
          self.isolateMainFound = true;
        }
        if (self.isolateMainFound) {
          if (self.isolateMainClosed) {
            skipNode = true;
          }
        } else {
          boolean isHeader = tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6;
          if (self.isolateFirstHeaderDepth < 0
              && isHeader
              && self.depthMap[Consts.TAG_HEADER] == 0) {
            self.isolateFirstHeaderDepth = self.depth;
          }
          int headerDepth = self.isolateFirstHeaderDepth;
          if (headerDepth >= 0
              && !self.isolateAfterFooter
              && tagId == Consts.TAG_FOOTER
              && self.depth - headerDepth <= 5) {
            self.isolateAfterFooter = true;
            skipNode = true;
          }
          if (self.isolateFirstHeaderDepth < 0) {
            if (tagId != Consts.TAG_HEAD && self.depthMap[Consts.TAG_HEAD] == 0) {
              skipNode = true;
            }
          } else if (self.isolateAfterFooter) {
            skipNode = true;
          }
        }
      }

      if (self.hasFrontmatter && !inTemplate) {
        if (tagId == Consts.TAG_HEAD) {
          self.frontmatterInHead = true;
        } else if (self.frontmatterInHead && tagId == Consts.TAG_META) {
          String name =
              tag.attributes.getBit(Consts.ATTR_NAME) != null
                  ? tag.attributes.getBit(Consts.ATTR_NAME)
                  : tag.attributes.getBit(Consts.ATTR_PROPERTY);
          String content = tag.attributes.getBit(Consts.ATTR_CONTENT);
          if (name != null && content != null && !content.isEmpty() && allowedMeta(self, name)) {
            boolean replaced = false;
            for (String[] entry : self.frontmatterMeta) {
              if (entry[0].equals(name)) {
                entry[1] = content;
                replaced = true;
                break;
              }
            }
            if (!replaced) {
              self.frontmatterMeta.add(new String[] {name, content});
            }
          }
        }
      }
    }

    tag.excludedFromMarkdown =
        inTemplate
            || filterExcluded
            || (skipNode && (!self.hasIsolateMain || self.isolateMainFound));
    tag.enterSkipped = skipNode;

    if (tag.collapsesInnerWhiteSpace && !tag.excludedFromMarkdown) {
      if (tag.tagId == Consts.TAG_SPAN) {
        self.collapseSpanDepth = Math.min(self.collapseSpanDepth + 1, Integer.MAX_VALUE);
      } else {
        self.collapseNonSpanDepth = Math.min(self.collapseNonSpanDepth + 1, Integer.MAX_VALUE);
      }
    }

    if (!tag.isInline) {
      self.firstBlockParentIndex = idx;
      self.blockParentIndices.add(idx);
    }

    if (tagId == Consts.TAG_OL) {
      String startValue = tag.attributes.getBit(Consts.ATTR_START);
      Long parsedStart =
          startValue == null
              ? null
              : parseBoundedU32(startValue, ConvertState.MAX_ORDERED_START);
      self.orderedStarts.add(parsedStart == null ? null : parsedStart.intValue());
      self.listItemCounts.add(0);
    } else if (tagId == Consts.TAG_UL) {
      self.listItemCounts.add(0);
    } else if (tagId == Consts.TAG_LI) {
      if (parentIndex >= 0) {
        int parentTag = self.stack.get(parentIndex).tagId;
        if ((parentTag == Consts.TAG_OL || parentTag == Consts.TAG_UL)
            && !self.listItemCounts.isEmpty()) {
          int countIndex = self.listItemCounts.size() - 1;
          int count = self.listItemCounts.get(countIndex);
          self.stack.get(idx).index = count;
          self.listItemCounts.set(countIndex, count + 1);
        }
      }
    }

    if (parentIndex >= 0) {
      self.stack.get(parentIndex).currentWalkIndex++;
    }

    if (!self.extractionParsedSelectors.isEmpty()) {
      ElementNode element = self.stack.get(self.stack.size() - 1);
      int stackDepth = self.stack.size();
      for (ConvertState.ParsedSelectorEntry entry : self.extractionParsedSelectors) {
        if (Selector.matchesSelector(element, entry.parsed)) {
          List<String[]> attrs = new java.util.ArrayList<>();
          for (Attr attr : element.attributes) {
            attrs.add(new String[] {attrName(attr), attr.value});
          }
          self.extractionTracked.add(
              new ConvertState.TrackedExtraction(
                  entry.selector, stackDepth, element.name(), attrs));
        }
      }
    }

    if (!skipNode) {
      if (OutputOps.elementFastPathOk(self, tagId)) {
        OutputOps.emitEnterSimple(self, false);
      } else if (OutputOps.linkFastPathOk(self, tagId)) {
        OutputOps.emitEnterSimple(self, true);
      } else {
        OutputOps.emitEnterElement(self);
      }
    }

    if (tagId == Consts.TAG_LI && self.stack.size() > 0) {
      ElementNode li = self.stack.get(self.stack.size() - 1);
      int width = 0;
      if (!skipNode && !self.inTableCell() && !self.plainText) {
        int stackLen = self.stack.size();
        boolean parentIsOrdered =
            stackLen >= 2 && self.stack.get(stackLen - 2).tagId == Consts.TAG_OL;
        if (parentIsOrdered) {
          int n = Math.max(1, self.orderedItemNumber(li.index));
          width = (int) (Math.log10(n) + 1) + 2;
        } else {
          width = 2;
        }
      }
      self.listIndentWidths.add(Math.min(width, 255));
      for (int i = 0; i < width; i++) {
        self.listIndent.append(' ');
      }
    }

    self.hasEncodedHtmlEntity = false;

    if (self.stack.size() > 0
        && self.stack.get(self.stack.size() - 1).isNonNesting
        && !selfClosing) {
      self.inNonNesting = true;
    }

    if (!selfClosing) {
      self.justClosedTag = false;
    }

    return new OpeningTagResult(true, newPosition, selfClosing, false);
  }

  private static boolean excludedByFilter(ConvertState self, ElementNode tag) {
    if (self.filterExcludeTagsPresent && tag.tagId >= 0 && tag.tagId < 64 && tag.customName() == null) {
      return ((self.filterExcludeTags >>> tag.tagId) & 1L) != 0;
    }
    for (ConvertState.ParsedSelectorEntry entry : self.filterExcludeParsed) {
      if (Selector.matchesSelector(tag, entry.parsed)) {
        return true;
      }
    }
    return false;
  }

  private static boolean allowedMeta(ConvertState self, String name) {
    if (name.equals("description")
        || name.equals("keywords")
        || name.equals("author")
        || name.equals("date")
        || name.equals("og:title")
        || name.equals("og:description")
        || name.equals("twitter:title")
        || name.equals("twitter:description")) {
      return true;
    }
    Plugins plugins = self.options.plugins();
    if (plugins == null || plugins.frontmatter() == null) {
      return false;
    }
    for (String allowed : plugins.frontmatter().metaFields()) {
      if (allowed.equals(name)) {
        return true;
      }
    }
    return false;
  }

  static String attrName(Attr attr) {
    if (attr.name != null) {
      return attr.name;
    }
    String name = Consts.attr_name(attr.bit);
    return name == null ? "" : name;
  }

  static Long parseBoundedU32(String value, long max) {
    int start = 0;
    int end = value.length();
    while (start < end && Chars.isAsciiWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && Chars.isAsciiWhitespace(value.charAt(end - 1))) {
      end--;
    }
    int index = start;
    if (index < end && value.charAt(index) == '+') {
      index++;
    }
    if (index == end) {
      return null;
    }
    long parsed = 0;
    while (index < end) {
      char c = value.charAt(index);
      if (c < '0' || c > '9') {
        return null;
      }
      parsed = parsed * 10 + (c - '0');
      if (parsed > max) {
        return null;
      }
      index++;
    }
    return parsed;
  }

  private static void captureBaseHref(ConvertState self) {
    if (self.baseHrefSeen
        || self.depthMap[Consts.TAG_A] != 0
        || self.depthMap[Consts.TAG_TEMPLATE] != 0
        || self.stack.size() >= MAX_ELEMENT_DEPTH) {
      return;
    }
    String href = self.attrScratch.getBit(Consts.ATTR_HREF);
    if (href == null) {
      return;
    }
    self.baseHrefSeen = true;
    String base = Url.resolveBaseHref(href, self.origin);
    if (base != null) {
      self.origin = base;
    }
  }

  private static int overflowPluginMode(
      ConvertState self,
      String tagName,
      int tagId,
      boolean isBuiltin,
      Attributes attributes,
      TagHandler handler) {
    if (!self.hasFilter && !self.hasTailwind) {
      return 0;
    }
    ElementNode parent = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
    if (self.hiddenSinceDepth >= 0
        || self.depthMap[Consts.TAG_TEMPLATE] > 0
        || (parent != null && parent.tailwind() != null && parent.tailwind().hidden)) {
      return 1;
    }
    TagHandler effective =
        handler != null
            ? handler
            : new TagHandler(
                false, false, false, true, Consts.NO_SPACING, false, Consts.ATTR_NONE);
    ElementNode node = new ElementNode();
    node.attributes = attributes;
    node.extras = isBuiltin ? null : new NodeExtras(tagName);
    node.tagId = tagId;
    node.isInline = effective.isInline;
    node.excludesTextNodes = effective.excludesTextNodes;
    node.isNonNesting = effective.isNonNesting;
    node.collapsesInnerWhiteSpace = effective.collapsesInnerWhiteSpace;
    node.spacing = effective.spacing;
    if (self.hasTailwind) {
      String classAttr = node.attributes.getBit(Consts.ATTR_CLASS);
      if (classAttr != null && Tailwind.processTailwindClasses(classAttr).hidden()) {
        return 1;
      }
    }
    if (!self.hasFilter) {
      return 0;
    }
    if (isHidden(node) || excludedByFilter(self, node)) {
      return 1;
    }
    for (ConvertState.ParsedSelectorEntry entry : self.filterIncludeParsed) {
      if (Selector.matchesSelector(node, entry.parsed)) {
        return 2;
      }
    }
    return 0;
  }

  private static void enterOverflow(
      ConvertState self,
      String tagName,
      int tagId,
      boolean isBuiltin,
      TagHandler handler,
      boolean included) {
    self.overflowTagId = isBuiltin ? tagId : -1;
    self.overflowCustomName = isBuiltin ? null : tagName;
    self.overflowSameNameDepth = 1;
    self.overflowIncluded = included;
    if (handler != null && handler.isNonNesting) {
      self.overflowRawName = tagName;
      self.overflowRawExcludesText = handler.excludesTextNodes;
    }
  }

  private static void enterOpaqueOverflow(ConvertState self, String tagName, TagHandler handler) {
    self.overflowOpaqueName = tagName;
    self.overflowOpaqueDepth = 1;
    if (handler != null && handler.isNonNesting) {
      self.overflowRawName = tagName;
      self.overflowRawExcludesText = handler.excludesTextNodes;
    }
  }

  // === Closing tags ===

  static void closeNode(ConvertState self) {
    if (self.stack.size() == 0) {
      return;
    }

    if (!self.extractionTracked.isEmpty()) {
      int currentDepth = self.stack.size();
      int first = -1;
      for (int i = 0; i < self.extractionTracked.size(); i++) {
        if (self.extractionTracked.get(i).stackDepth == currentDepth) {
          first = i;
          break;
        }
      }
      if (first >= 0) {
        List<ConvertState.TrackedExtraction> tail =
            self.extractionTracked.subList(first, self.extractionTracked.size());
        for (ConvertState.TrackedExtraction tracked : tail) {
          java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
          for (String[] pair : tracked.attributes) {
            attrs.put(pair[0], pair[1]);
          }
          self.extractionResults.add(
              new io.github.fbricon.mdream4j.ExtractedElement(
                  tracked.selector,
                  tracked.tagName,
                  tracked.textContent.toString().trim(),
                  attrs));
        }
        tail.clear();
      }
    }

    int poppingIndex = self.stack.size() - 1;
    if (!self.stack.retire()) {
      return;
    }
    ElementNode node = self.stack.retired();
    int nodeDepth = node.depth;
    int nodeTagId = node.tagId;
    boolean nodeCollapses = node.collapsesInnerWhiteSpace;
    boolean nodeExcluded = node.excludedFromMarkdown;
    boolean nodeEnterSkipped = node.enterSkipped;
    int nodeChildTextIndex = node.childTextNodeIndex;

    if (self.hiddenSinceDepth == nodeDepth) {
      self.hiddenSinceDepth = -1;
    }
    if (self.filterIncludedSinceDepth == nodeDepth) {
      self.filterIncludedSinceDepth = -1;
    }

    if (self.firstBlockParentIndex == poppingIndex) {
      if (!self.blockParentIndices.isEmpty()) {
        self.blockParentIndices.remove(self.blockParentIndices.size() - 1);
      }
      self.firstBlockParentIndex =
          !self.blockParentIndices.isEmpty()
              ? self.blockParentIndices.get(self.blockParentIndices.size() - 1)
              : (self.stack.size() == 0 ? -1 : 0);
    }

    if (nodeCollapses && !nodeExcluded) {
      if (nodeTagId == Consts.TAG_SPAN) {
        self.collapseSpanDepth = Math.max(0, self.collapseSpanDepth - 1);
      } else {
        self.collapseNonSpanDepth = Math.max(0, self.collapseNonSpanDepth - 1);
      }
    }

    if (self.hasIsolateMain
        && nodeTagId == Consts.TAG_MAIN
        && !nodeExcluded
        && self.isolateMainFound
        && !self.isolateMainClosed) {
      self.isolateMainClosed = true;
    }

    if (self.hasFrontmatter
        && nodeTagId == Consts.TAG_HEAD
        && !nodeExcluded
        && self.frontmatterInHead) {
      self.frontmatterInHead = false;
      generateFrontmatterYaml(self);
    }

    if (nodeTagId == Consts.TAG_A
        && nodeChildTextIndex == 0
        && !nodeExcluded
        && !nodeEnterSkipped) {
      String title = self.stack.retired().attributes.getBit(Consts.ATTR_TITLE);
      String aria = self.stack.retired().attributes.getBit(Consts.ATTR_ARIA_LABEL);
      String prefix = title != null ? title : aria != null ? aria : "";
      if (!prefix.isEmpty()) {
        ElementNode modifiedNode = self.stack.takeRetired();
        modifiedNode.childTextNodeIndex = 1;
        int textDepth = nodeDepth + 1;
        self.stack.push(modifiedNode);
        OutputOps.emitText(self, prefix, false, textDepth, 0);
        for (int i = 0; i < self.stack.size(); i++) {
          self.stack.get(i).childTextNodeIndex++;
        }
        ElementNode popped = self.stack.pop();
        if (popped == null) {
          return;
        }
        OutputOps.emitExitElement(self, popped);
        self.stack.restoreRetired(popped);
        self.stack.releaseRetired();
        if (nodeTagId >= 0) {
          if (nodeTagId < Consts.MAX_TAG_ID) {
            self.depthMap[nodeTagId] = Math.max(0, self.depthMap[nodeTagId] - 1);
          }
          self.refreshRawHtmlBlock(nodeTagId);
          self.updateInPreOnClose(nodeTagId);
          if (nodeTagId == Consts.TAG_OL) {
            popOrderedList(self);
          } else if (nodeTagId == Consts.TAG_UL) {
            popList(self);
          }
          if (nodeTagId == Consts.TAG_LI) {
            popListIndent(self);
          }
        }
        self.depth--;
        self.hasEncodedHtmlEntity = false;
        self.justClosedTag = true;
        self.inNonNesting =
            self.stack.size() > 0 && self.stack.get(self.stack.size() - 1).isNonNesting;
        return;
      }
    }

    if (OutputOps.elementFastPathOk(self, nodeTagId)) {
      OutputOps.emitExitSimple(self, false);
    } else if (OutputOps.linkFastPathOk(self, nodeTagId)) {
      OutputOps.emitExitSimple(self, true);
    } else {
      ElementNode popped = self.stack.takeRetired();
      OutputOps.emitExitElement(self, popped);
      self.stack.restoreRetired(popped);
    }
    self.stack.releaseRetired();

    if (nodeTagId >= 0) {
      if (nodeTagId < Consts.MAX_TAG_ID) {
        self.depthMap[nodeTagId] = Math.max(0, self.depthMap[nodeTagId] - 1);
      }
      self.refreshRawHtmlBlock(nodeTagId);
      self.updateInPreOnClose(nodeTagId);
      if (nodeTagId == Consts.TAG_OL) {
        popOrderedList(self);
      } else if (nodeTagId == Consts.TAG_UL) {
        popList(self);
      }
      if (nodeTagId == Consts.TAG_LI) {
        popListIndent(self);
      }
    }

    self.inNonNesting =
        self.stack.size() > 0 && self.stack.get(self.stack.size() - 1).isNonNesting;
    self.depth--;
    self.hasEncodedHtmlEntity = false;
    self.justClosedTag = true;

    if (nodeTagId == Consts.TAG_BLOCKQUOTE) {
      boolean noIndent = true;
      for (ConvertState.BlockquoteFrame frame : self.blockquotes) {
        if (frame.listIndent.length() != 0) {
          noIndent = false;
          break;
        }
      }
      if (noIndent) {
        OutputOps.flushSettledBlockquoteLines(self);
      }
    }
  }

  /** Pops one list level's item counter, shared by ordered and unordered lists. */
  private static void popList(ConvertState self) {
    if (!self.listItemCounts.isEmpty()) {
      self.listItemCounts.remove(self.listItemCounts.size() - 1);
    }
  }

  private static void popOrderedList(ConvertState self) {
    if (!self.orderedStarts.isEmpty()) {
      self.orderedStarts.remove(self.orderedStarts.size() - 1);
    }
    popList(self);
  }

  private static void popListIndent(ConvertState self) {
    if (!self.listIndentWidths.isEmpty()) {
      int width = self.listIndentWidths.remove(self.listIndentWidths.size() - 1);
      int newLen = Math.max(0, self.listIndent.length() - width);
      self.listIndent.setLength(newLen);
    }
  }

  /** Byte-ordered, stable insertion sort; both collections stay small and bounded. */
  static <T> void sortFieldsByKey(List<T> fields, Function<T, String> key) {
    for (int index = 1; index < fields.size(); index++) {
      int position = index;
      while (position > 0
          && Chars.compareCodePoints(
                  key.apply(fields.get(position)), key.apply(fields.get(position - 1)))
              < 0) {
        T previous = fields.get(position - 1);
        fields.set(position - 1, fields.get(position));
        fields.set(position, previous);
        position--;
      }
    }
  }

  private static void generateFrontmatterYaml(ConvertState self) {
    if (!self.isMarkdown()) {
      return;
    }

    Plugins plugins = self.options.plugins();
    Frontmatter frontmatter = plugins == null ? null : plugins.frontmatter();

    List<String> yamlOut = new ArrayList<>();
    if (self.frontmatterTitle != null) {
      yamlOut.add("title: " + yamlScalar(self.frontmatterTitle, true));
    }

    if (frontmatter != null && !frontmatter.additionalFields().isEmpty()) {
      List<Map.Entry<String, String>> sorted =
          new ArrayList<>(frontmatter.additionalFields().entrySet());
      sortFieldsByKey(sorted, Map.Entry::getKey);
      for (Map.Entry<String, String> field : sorted) {
        String key = field.getKey();
        if (!"title".equals(key) && !"description".equals(key)) {
          yamlOut.add(key + ": " + yamlScalar(field.getValue(), false));
        }
      }
    }

    if (!self.frontmatterMeta.isEmpty()) {
      yamlOut.add("meta:");
      sortFieldsByKey(self.frontmatterMeta, field -> field[0]);
      for (String[] field : self.frontmatterMeta) {
        String key = field[0].indexOf(':') >= 0 ? '"' + field[0] + '"' : field[0];
        yamlOut.add("  " + key + ": " + yamlScalar(field[1], true));
      }
    }

    if (!yamlOut.isEmpty()) {
      OutputOps.emitFrontmatter(self, "---\n" + String.join("\n", yamlOut) + "\n---\n\n");
    }
  }

  /** Words a YAML 1.1 or 1.2 reader resolves to null or a boolean. */
  private static final String[] YAML_IMPLICIT_WORDS = {
    "null", "true", "false", "yes", "no", "on", "off", "y", "n", "~"
  };

  /**
   * Formats a value as a YAML scalar, quoting only when a reader would not take it literally.
   *
   * <p>Text read from the page is a string, so with {@code asString} a value a reader would
   * resolve to a number, date, boolean or null is quoted too; configured fields keep that typing.
   */
  private static String yamlScalar(String value, boolean asString) {
    boolean quote;
    if (value.isEmpty()) {
      quote = true;
    } else {
      char first = value.charAt(0);
      quote =
          YAML_INDICATOR_FIRST.indexOf(first) >= 0
              || (value.length() == 1 && (first == '-' || first == '?'))
              || value.charAt(value.length() - 1) == '\t'
              || value.indexOf(':') >= 0
              || value.indexOf('#') >= 0
              || value.indexOf(' ') >= 0
              || value.indexOf('"') >= 0
              || hasYamlUnprintable(value)
              || (asString
                  && ((first >= '0' && first <= '9') || first == '.' || first == '+'
                      || (first == '-'
                          && value.length() > 1
                          && ((value.charAt(1) >= '0' && value.charAt(1) <= '9')
                              || value.charAt(1) == '.'))
                      || equalsAsciiIgnoreCaseAny(value, YAML_IMPLICIT_WORDS)));
    }
    if (!quote) {
      return value;
    }
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (Character.isISOControl(ch)
              || ch == '\ufffe'
              || ch == '\uffff'
              || ch == '\u2028'
              || ch == '\u2029') {
            if (ch < 0x100) {
              out.append("\\x").append(hexDigit(ch >> 4)).append(hexDigit(ch & 0xF));
            } else {
              out.append("\\u")
                  .append(hexDigit(ch >> 12))
                  .append(hexDigit((ch >> 8) & 0xF))
                  .append(hexDigit((ch >> 4) & 0xF))
                  .append(hexDigit(ch & 0xF));
            }
          } else {
            out.append(ch);
          }
      }
    }
    return out.append('"').toString();
  }

  /** First characters that start YAML syntax rather than a plain scalar. */
  private static final String YAML_INDICATOR_FIRST = ",[]{}&*!|>'%@`\t";

  private static boolean hasYamlUnprintable(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (Character.isISOControl(ch)
          || ch == '\ufffe'
          || ch == '\uffff'
          || ch == '\u2028'
          || ch == '\u2029') {
        return true;
      }
    }
    return false;
  }

  private static boolean equalsAsciiIgnoreCaseAny(String value, String[] words) {
    for (String word : words) {
      if (Chars.asciiEqualsIgnoreCase(value, word)) {
        return true;
      }
    }
    return false;
  }

  private static char hexDigit(int value) {
    return "0123456789abcdef".charAt(value & 0xF);
  }

  static CloseTagResult processClosingTag(ConvertState self, String chunk, int position) {
    int i = position + 2;
    int tagNameStart = i;
    int chunkLength = chunk.length();
    int tagNameEnd = chunkLength;
    char quote = 0;
    boolean foundClose = false;
    int nameEnd = Scan.tagNameEnd(chunk, i);
    if (nameEnd < chunkLength && chunk.charAt(nameEnd) == '>') {
      tagNameEnd = nameEnd;
      i = nameEnd;
      foundClose = true;
    }
    while (!foundClose && i < chunkLength) {
      char c = chunk.charAt(i);
      if (tagNameEnd == chunkLength && (Scan.isWhitespace(c) || c == '/' || c == '>')) {
        tagNameEnd = i;
      }
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (tagNameEnd != chunkLength && (c == '"' || c == '\'')) {
        quote = c;
      } else if (c == '>') {
        foundClose = true;
        break;
      }
      i++;
    }

    if (!foundClose) {
      return new CloseTagResult(
          false,
          position,
          tagNameEnd == chunkLength && chunkLength - tagNameStart <= Consts.MAX_BUILTIN_TAG_NAME,
          tagNameEnd != chunkLength);
    }

    String tagNameRaw = chunk.substring(tagNameStart, tagNameEnd);
    if (!processClosingTagName(self, tagNameRaw)) {
      return new CloseTagResult(false, position, false, false);
    }
    return new CloseTagResult(true, i + 1, false, false);
  }

  static boolean processClosingTagName(ConvertState self, String tagNameRaw) {
    Integer builtinBoxed = Consts.get_tag_id_ci_bytes(utf8(tagNameRaw));
    int builtin = builtinBoxed == null ? -1 : builtinBoxed;
    String tagName;
    if (builtin >= 0) {
      tagName = tagNameRaw;
    } else if (hasAsciiUpper(tagNameRaw)) {
      tagName = Chars.asciiLower(tagNameRaw);
    } else {
      tagName = tagNameRaw;
    }
    String name = tagName;
    int tagId = builtin;
    boolean isBuiltin = builtin >= 0;
    boolean needsNameMatch = !isBuiltin;
    if (self.hasTagOverrides) {
      needsNameMatch = true;
      int alias = self.aliasTagId(name, builtin);
      if (alias >= 0) {
        if (builtin >= 0) {
          name = Consts.TAG_NAMES[builtin];
        }
        tagId = alias;
        isBuiltin = false;
      }
    }

    if (self.overflowOpaqueDepth > 0) {
      if (self.overflowRawName != null) {
        boolean closesOpaqueRoot = self.matchesOpaqueTag(name);
        self.overflowRawName = null;
        self.overflowRawExcludesText = false;
        if (closesOpaqueRoot) {
          self.clearOpaqueOverflow();
        }
      } else if (self.matchesOpaqueTag(name)) {
        self.overflowOpaqueDepth--;
        if (self.overflowOpaqueDepth == 0) {
          self.clearOpaqueOverflow();
        }
      }
      self.justClosedTag = true;
      return true;
    }

    if (self.overflowSameNameDepth > 0) {
      TagHandler handler = tagId >= 0 ? Tags.getTagHandler(tagId) : null;
      boolean isSelfClosing = handler != null && handler.isSelfClosing;
      if (isSelfClosing) {
        self.justClosedTag = true;
        return true;
      }
      if (self.overflowRawName != null) {
        boolean closesRoot = self.matchesOverflowTag(name, tagId, isBuiltin);
        self.overflowRawName = null;
        self.overflowRawExcludesText = false;
        if (closesRoot) {
          self.clearOverflow();
        }
        self.justClosedTag = true;
        return true;
      }
      if (self.matchesOverflowTag(name, tagId, isBuiltin)) {
        self.overflowSameNameDepth--;
        if (self.overflowSameNameDepth == 0) {
          self.clearOverflow();
        }
        self.justClosedTag = true;
        return true;
      }
      if (tagId >= 0 && self.depthMap[tagId] > 0) {
        self.clearOverflow();
      } else {
        self.justClosedTag = true;
        return true;
      }
    }

    ElementNode top = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
    if (top != null && top.isNonNesting && top.tagId != tagId) {
      return false;
    }

    final String closeName = !isBuiltin ? name : null;
    boolean matched = false;
    if (self.stack.size() > 0) {
      ElementNode topNode = self.stack.get(self.stack.size() - 1);
      if (matchesClosing(topNode, tagId, needsNameMatch, closeName)) {
        matched = true;
        closeNode(self);
      } else {
        int popCount = 0;
        boolean foundMatch = false;
        for (int j = self.stack.size() - 1; j >= 0; j--) {
          ElementNode candidate = self.stack.get(j);
          if (tagId != Consts.TAG_TEMPLATE && candidate.tagId == Consts.TAG_TEMPLATE) {
            break;
          }
          popCount++;
          if (matchesClosing(candidate, tagId, needsNameMatch, closeName)) {
            foundMatch = true;
            break;
          }
        }
        if (foundMatch) {
          matched = true;
          for (int k = 0; k < popCount; k++) {
            closeNode(self);
          }
        }
      }
    }

    if (!matched) {
      self.lastNodeIsInline = true;
    }
    self.justClosedTag = true;
    return true;
  }

  private static boolean matchesClosing(
      ElementNode node, int tagId, boolean needsNameMatch, String closeName) {
    if (node.tagId != tagId) {
      return false;
    }
    if (!needsNameMatch) {
      return true;
    }
    String custom = node.customName();
    if (closeName == null) {
      return custom == null;
    }
    return closeName.equals(custom);
  }

  static void processCdataSection(ConvertState self, String content) {
    if (!self.hasTagOverrides) {
      return;
    }
    TagOverride override = self.overrideForName("#cdata-section", -1);
    if (override == null) {
      return;
    }
    // An override with only enter/exit still surfaces the section; its alias is optional.
    Integer alias = override.alias() == null ? null : Consts.get_tag_id(override.alias());
    int tagId = alias == null ? -1 : alias;
    OpeningTagResult result = processOpeningTag(self, "#cdata-section", tagId, false, ">", 0);
    if (!result.complete) {
      return;
    }
    if (result.skip) {
      if (self.matchesOverflowTag("#cdata-section", tagId, false)) {
        self.clearOverflow();
      }
      return;
    }
    if (!result.selfClosing && !content.isEmpty()) {
      ElementNode parent = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
      boolean excluded =
          parent != null && (parent.excludedFromMarkdown || parent.excludesTextNodes);
      if (!excluded) {
        int depth = self.depth;
        int index = parent != null ? parent.currentWalkIndex : 0;
        OutputOps.emitText(self, content, false, depth, index);
      }
      if (parent != null) {
        parent.currentWalkIndex++;
        parent.childTextNodeIndex++;
      }
    }
    if (!result.selfClosing) {
      closeNode(self);
    }
  }

  // === Main scanner ===

  static int processHtml(ConvertState self, String chunk) {
    if (self.heldOutputExceeded) {
      self.parseTextBuffer.setLength(0);
      return chunk.length();
    }
    self.rawtextEndTagPending = false;
    StringBuilder textBuffer = self.parseTextBuffer;
    int chunkLength = chunk.length();
    int maxNodeBytes = self.maxNodeBytesInt;
    int i = 0;
    int runStart = 0;
    boolean carry = false;
    boolean carryBounded = false;
    boolean discardEndTag = false;

    if (self.discard.kind != ConvertState.Discard.KIND_NONE) {
      int end = resumeDiscard(self, chunk);
      if (end < 0) {
        return chunkLength;
      }
      i = end;
    }

    if (self.pendingStart != null) {
      ConvertState.PendingStartTag pending = self.pendingStart;
      self.pendingStart = null;
      TagHandler handler = pending.tagId >= 0 ? Tags.getTagHandler(pending.tagId) : null;
      Scan.TagResume resume =
          Scan.resumeTagAttributes(
              chunk,
              handler != null && handler.isSelfClosing,
              self.attrMask(handler),
              pending.carry,
              pending.attrs);
      if (!resume.complete) {
        self.pendingStart = pending;
        return chunkLength;
      }
      i = resume.newPosition;
      self.attrScratch = pending.attrs;
      OpeningTagResult result =
          applyOpeningTag(
              self,
              pending.name(),
              pending.tagId,
              pending.isBuiltin,
              i,
              resume.selfClosing,
              pending.carry.budget.overflow);
      if (!result.skip) {
        if (result.selfClosing) {
          closeNode(self);
          self.justClosedTag = true;
        } else {
          self.isFirstTextInElement = true;
        }
      }
    }

    if (self.stack.size() > 0) {
      ElementNode scriptTop = self.stack.get(self.stack.size() - 1);
      if (scriptTop.tagId == Consts.TAG_SCRIPT
          && (scriptTop.customName() == null || scriptTop.customName().equals("script"))) {
        int close = processScriptChunk(self, chunk, i);
        if (close >= 0) {
          i = close;
        } else {
          runStart = -close - 1;
          carry = true;
          carryBounded = true;
          i = chunkLength;
        }
      }
    }

    while (i < chunkLength) {
      if (textBuffer.length() == 0) {
        runStart = i;
      }
      char cc = chunk.charAt(i);

      if (maxNodeBytes != 0
          && cc != '<'
          && (self.textNodeExhausted || Scan.utf8Length(textBuffer) >= maxNodeBytes)) {
        int next = chunk.indexOf('<', i);
        i = next < 0 ? chunkLength : next;
        self.textNodeExhausted = true;
        self.truncated = true;
        continue;
      }

      if (cc != '<') {
        if (cc < 128
            && ConvertState.BATCHABLE_TEXT[cc]
            && !self.inNonNesting
            && (!self.inPre || maxNodeBytes == 0)) {
          int start = i;
          int limit = Math.min(chunkLength, i + textRunRoom(self, textBuffer));
          i++;
          boolean hadSpace = false;
          while (true) {
            while (i < limit
                && chunk.charAt(i) < 128
                && ConvertState.BATCHABLE_TEXT[chunk.charAt(i)]) {
              i++;
            }
            if (i + 1 < limit
                && chunk.charAt(i) == ' '
                && chunk.charAt(i + 1) < 128
                && ConvertState.BATCHABLE_TEXT[chunk.charAt(i + 1)]) {
              hadSpace = true;
              i += 2;
              continue;
            }
            break;
          }
          int beforeLen = textBuffer.length();
          self.truncated |=
              pushCappedTextNode(textBuffer, chunk.substring(start, i), maxNodeBytes, self);
          if (!self.inPre) {
            self.textBufferBatchableLen += textBuffer.length() - beforeLen;
          }
          self.textBufferContainsNonWhitespace = true;
          if (hadSpace) {
            self.textBufferContainsWhitespace = true;
          }
          self.lastCharWasWhitespace = false;
          self.justClosedTag = false;
          continue;
        }

        if (self.inNonNesting && dropsRawText(self)) {
          int next = chunk.indexOf('<', i);
          i = next < 0 ? chunkLength : next;
          self.lastCharWasWhitespace = false;
          self.justClosedTag = false;
          continue;
        }

        if (self.inNonNesting
            && (self.depthMap[Consts.TAG_SCRIPT] > 0 || self.depthMap[Consts.TAG_STYLE] > 0)) {
          int start = i;
          int next = chunk.indexOf('<', i);
          i = next < 0 ? chunkLength : next;
          self.truncated |=
              pushCappedTextNode(textBuffer, chunk.substring(start, i), maxNodeBytes, self);
          self.textBufferContainsNonWhitespace = true;
          self.lastCharWasWhitespace = false;
          self.justClosedTag = false;
          continue;
        }

        if (cc == '&') {
          self.hasEncodedHtmlEntity = true;
        }
        if (cc < 256 && (ConvertState.GFM_BYTE_FLAGS[cc] & ConvertState.GFM_HAZARD_BIT) != 0) {
          self.textBufferHasInlineGfmHazard = true;
        }

        if (Scan.isWhitespace(cc)) {
          if (self.justClosedTag) {
            self.justClosedTag = false;
            self.lastCharWasWhitespace = false;
          }
          if (!self.inPre && self.lastCharWasWhitespace) {
            i++;
            continue;
          }
          if (self.inPre) {
            textBuffer.append(cc);
          } else if (cc == ' ' || !self.lastCharWasWhitespace) {
            textBuffer.append(' ');
            self.textBufferBatchableLen++;
          }
          self.lastCharWasWhitespace = true;
          self.textBufferContainsWhitespace = true;
        } else {
          int beforeLen = textBuffer.length();
          if (cc < 128) {
            textBuffer.append(cc);
          } else {
            int limit = Math.min(chunkLength, i + textRunRoom(self, textBuffer));
            int end = i + 1;
            while (end < limit && chunk.charAt(end) >= 128) {
              end++;
            }
            self.truncated |=
                pushCappedTextNode(textBuffer, chunk.substring(i, end), maxNodeBytes, self);
            i = end;
          }
          if (textBuffer.length() != beforeLen) {
            self.textBufferContainsNonWhitespace = true;
            self.lastCharWasWhitespace = false;
            self.justClosedTag = false;
          }
          if (cc >= 128) {
            self.textBufferBatchableLen += textBuffer.length() - beforeLen;
            continue;
          }
        }
        i++;
        continue;
      }

      // '<' handling
      if (i + 1 >= chunkLength) {
        runStart = i;
        carry = true;
        break;
      }

      if (self.overflowRawName != null) {
        String rawName = self.overflowRawName;
        char next = chunk.charAt(i + 1);
        if (next == '/') {
          int peekStart = i + 2;
          int peekEnd = peekStart;
          while (peekEnd < chunkLength) {
            char c = chunk.charAt(peekEnd);
            if (c == '>' || c == '/' || Scan.isWhitespace(c)) {
              break;
            }
            peekEnd++;
          }
          String peekName = chunk.substring(peekStart, peekEnd);
          if (peekEnd == chunkLength && canCompleteRawEndTag(peekName, rawName)) {
            runStart = i;
            carry = true;
            carryBounded = true;
            break;
          }
          if (Chars.asciiEqualsIgnoreCase(rawName, peekName)) {
            completeTextNode(self, textBuffer);
            runStart = i;
            CloseTagResult result = processClosingTag(self, chunk, i);
            if (result.complete) {
              i = result.newPosition;
            } else {
              self.rawtextEndTagPending = true;
              carry = true;
              carryBounded = result.boundedPrefix;
              discardEndTag = result.nameEnded;
              break;
            }
            continue;
          }
        }
        int beforeLen = textBuffer.length();
        self.truncated |= pushCappedTextNode(textBuffer, "<", maxNodeBytes, self);
        if (textBuffer.length() != beforeLen) {
          self.textBufferContainsNonWhitespace = true;
          self.textBufferHasInlineGfmHazard = true;
        }
        self.lastCharWasWhitespace = false;
        self.justClosedTag = false;
        i++;
        continue;
      }

      if (self.inNonNesting) {
        char next = chunk.charAt(i + 1);
        if (next == '/') {
          int peekStart = i + 2;
          int peekEnd = peekStart;
          while (peekEnd < chunkLength) {
            char c = chunk.charAt(peekEnd);
            if (c == '>' || c == '/' || Scan.isWhitespace(c)) {
              break;
            }
            peekEnd++;
          }
          String peekName = chunk.substring(peekStart, peekEnd);
          ElementNode lastNode =
              self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
          String currentName = lastNode == null ? null : lastNode.name();
          if (peekEnd == chunkLength
              && currentName != null
              && canCompleteRawEndTag(peekName, currentName)) {
            runStart = i;
            carry = true;
            carryBounded = true;
            break;
          }
          if (currentName != null && Chars.asciiEqualsIgnoreCase(currentName, peekName)) {
            completeTextNode(self, textBuffer);
            runStart = i;
            CloseTagResult result = processClosingTag(self, chunk, i);
            if (result.complete) {
              i = result.newPosition;
            } else {
              self.rawtextEndTagPending = true;
              carry = true;
              carryBounded = result.boundedPrefix;
              discardEndTag = result.nameEnded;
              break;
            }
            continue;
          }
        }
        if (!dropsRawText(self)) {
          int beforeLen = textBuffer.length();
          self.truncated |= pushCappedTextNode(textBuffer, "<", maxNodeBytes, self);
          if (textBuffer.length() != beforeLen) {
            self.textBufferContainsNonWhitespace = true;
            self.textBufferHasInlineGfmHazard = true;
          }
        }
        self.lastCharWasWhitespace = false;
        self.justClosedTag = false;
        i++;
        continue;
      }

      char next = chunk.charAt(i + 1);

      if (next == '!') {
        String remaining = chunk.substring(i);
        // CDATA is dropped by default but can be surfaced via a #cdata-section
        // override. Handle it before the generic comment/doctype scan, which
        // would otherwise stop at the first `>` inside `]]>`.
        String cdataAfterOpen =
            i + 2 <= chunkLength ? chunk.substring(i + 2) : "";
        if (cdataAfterOpen.startsWith("[CDATA[")) {
          String afterOpen = cdataAfterOpen.substring(7);
          int rel = afterOpen.indexOf("]]>");
          if (rel >= 0) {
            int tokenLen = 9 + rel + 3;
            completeTextNode(self, textBuffer);
            runStart = i;
            if (maxNodeBytes == 0 || tokenLen <= maxNodeBytes) {
              processCdataSection(self, afterOpen.substring(0, rel));
            } else if (hasSurfacedCdata(self)) {
              self.truncated = true;
            }
            i += tokenLen;
            continue;
          }
          if (hasSurfacedCdata(self)) {
            runStart = i;
            carry = true;
            break;
          }
        } else if (remaining.length() < 9 && "<![CDATA[".startsWith(remaining)) {
          runStart = i;
          carry = true;
          carryBounded = true;
          break;
        }
        completeTextNode(self, textBuffer);
        runStart = i;
        if (cdataAfterOpen.startsWith("[CDATA[")) {
          discardDeclaration(self, remaining);
          break;
        }
        Scan.CommentResult result = Scan.processCommentOrDoctype(chunk, i);
        if (result.complete) {
          i = result.newPosition;
        } else if (remaining.length() < 4 && "<!--".startsWith(remaining)) {
          carry = true;
          carryBounded = true;
          break;
        } else {
          discardDeclaration(self, remaining);
          break;
        }
      } else if (next == '/') {
        completeTextNode(self, textBuffer);
        runStart = i;
        CloseTagResult result = processClosingTag(self, chunk, i);
        if (result.complete) {
          i = result.newPosition;
        } else {
          carry = true;
          carryBounded = result.boundedPrefix;
          discardEndTag = result.nameEnded;
          break;
        }
      } else if (next == '?') {
        completeTextNode(self, textBuffer);
        runStart = i;
        int end = Scan.discardedGt(chunk.substring(i));
        if (end < 0) {
          discardDeclaration(self, chunk.substring(i));
          break;
        }
        i += end;
      } else if (!Chars.isAsciiAlphabetic(next)) {
        int beforeLen = textBuffer.length();
        self.truncated |= pushCappedTextNode(textBuffer, "<", maxNodeBytes, self);
        if (textBuffer.length() != beforeLen) {
          self.textBufferContainsNonWhitespace = true;
          self.textBufferHasInlineGfmHazard = true;
        }
        self.lastCharWasWhitespace = false;
        self.justClosedTag = false;
        i++;
      } else {
        int i2 = i + 1;
        int tagNameStart = i2;
        int nameStop = Scan.tagNameEnd(chunk, i2);
        if (nameStop >= chunkLength) {
          runStart = i;
          carry = true;
          carryBounded = chunkLength - tagNameStart <= Consts.MAX_BUILTIN_TAG_NAME;
          break;
        }
        int tagNameEnd = nameStop;
        String tagNameRaw = chunk.substring(tagNameStart, tagNameEnd);

        Integer builtinBoxed = Consts.get_tag_id_ci_bytes(utf8(tagNameRaw));
        int builtin = builtinBoxed == null ? -1 : builtinBoxed;
        String tagName;
        if (builtin >= 0) {
          tagName = tagNameRaw;
        } else if (hasAsciiUpper(tagNameRaw)) {
          tagName = Chars.asciiLower(tagNameRaw);
        } else {
          tagName = tagNameRaw;
        }
        String name = tagName;
        int tagId = builtin;
        boolean isBuiltin = builtin >= 0;
        if (self.hasTagOverrides) {
          int alias = self.aliasTagId(name, builtin);
          if (alias >= 0) {
            if (builtin >= 0) {
              name = Consts.TAG_NAMES[builtin];
            }
            tagId = alias;
            isBuiltin = false;
          }
        }
        i2 = tagNameEnd;

        completeTextNode(self, textBuffer);
        runStart = i;

        OpeningTagResult result = processOpeningTag(self, name, tagId, isBuiltin, chunk, i2);
        if (result.skip) {
          i = result.newPosition;
        } else if (result.complete) {
          i = result.newPosition;
          if (result.selfClosing) {
            closeNode(self);
            self.justClosedTag = true;
          } else {
            self.isFirstTextInElement = true;
            if (builtin == Consts.TAG_SCRIPT && tagId == Consts.TAG_SCRIPT) {
              int close = processScriptChunk(self, chunk, i);
              if (close >= 0) {
                i = close;
              } else {
                runStart = -close - 1;
                carry = true;
                carryBounded = true;
                break;
              }
            }
          }
        } else {
          break;
        }
      }
    }

    int consumed;
    if (carry) {
      if (discardEndTag
          || (maxNodeBytes != 0
              && chunkLength - runStart > maxNodeBytes
              && !carryBounded)) {
        completeTextNode(self, textBuffer);
        startDiscard(self, chunk, runStart);
        consumed = chunkLength;
      } else {
        consumed = runStart;
      }
    } else {
      consumed = chunkLength;
    }
    if (consumed == chunkLength && !carry) {
      // The text buffer is retained across chunks; keep the same object.
    }
    return consumed;
  }

  private static int textRunRoom(ConvertState self, StringBuilder textBuffer) {
    return Math.max(1, self.textRunNextFlush - Scan.utf8Length(textBuffer));
  }

  private static boolean dropsRawText(ConvertState self) {
    if (!self.extractionTracked.isEmpty()) {
      return false;
    }
    ElementNode last = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
    return last != null && last.excludesTextNodes;
  }

  private static boolean hasSurfacedCdata(ConvertState self) {
    if (!self.hasTagOverrides) {
      return false;
    }
    for (ConvertState.ParsedSelectorEntry entry : self.tagOverrides) {
      if (entry.selector.equals("#cdata-section")) {
        return true;
      }
    }
    return false;
  }

  private static void startDiscard(ConvertState self, String chunk, int runStart) {
    self.pendingTag = null;
    String rest = chunk.substring(runStart);
    if (rest.startsWith("<!")) {
      self.truncated = true;
      discardDeclaration(self, rest);
      return;
    }
    if (rest.startsWith("</")) {
      String body = rest.substring(2);
      Scan.DiscardedCloseTag state = new Scan.DiscardedCloseTag();
      Scan.discardedCloseTagEnd(body, state);
      if (state.nameEnded) {
        int nameEnd = 0;
        while (nameEnd < body.length()) {
          char c = body.charAt(nameEnd);
          if (Scan.isWhitespace(c) || c == '/' || c == '>') {
            break;
          }
          nameEnd++;
        }
        processClosingTagName(self, body.substring(0, nameEnd));
      } else {
        self.lastNodeIsInline = true;
        self.justClosedTag = true;
      }
      self.discard = ConvertState.Discard.closeTag(state);
    } else {
      self.truncated = true;
      Scan.PendingTagScan pending = new Scan.PendingTagScan();
      Scan.tagIsComplete(chunk, runStart, pending);
      self.discard = ConvertState.Discard.tag(pending);
    }
  }

  private static void discardDeclaration(ConvertState self, String rest) {
    if (rest.startsWith("<!--")) {
      Scan.DiscardedComment state = new Scan.DiscardedComment();
      Scan.discardedCommentEnd(rest.substring(4), state);
      self.discard = ConvertState.Discard.comment(state);
    } else if (rest.startsWith("<![CDATA[")) {
      int[] brackets = new int[1];
      Scan.discardedCdataEnd(rest.substring(9), brackets);
      self.discard = ConvertState.Discard.cdata(brackets[0]);
    } else {
      self.discard = ConvertState.Discard.doctype();
    }
  }

  private static int resumeDiscard(ConvertState self, String chunk) {
    ConvertState.Discard discard = self.discard;
    int end = -1;
    switch (discard.kind) {
      case ConvertState.Discard.KIND_TAG:
        discard.tag.restart();
        int gt = Scan.tagIsComplete(chunk, 0, discard.tag);
        end = gt < 0 ? -1 : gt + 1;
        break;
      case ConvertState.Discard.KIND_COMMENT:
        end = Scan.discardedCommentEnd(chunk, discard.comment);
        break;
      case ConvertState.Discard.KIND_CLOSETAG:
        end = Scan.discardedCloseTagEnd(chunk, discard.closeTag);
        break;
      case ConvertState.Discard.KIND_DOCTYPE:
        end = Scan.discardedGt(chunk);
        break;
      case ConvertState.Discard.KIND_CDATA:
        end = Scan.discardedCdataEnd(chunk, discard.brackets);
        break;
      default:
        end = 0;
        break;
    }
    if (end < 0) {
      return -1;
    }
    self.discard = ConvertState.Discard.none();
    return end;
  }

  static void completeTextNode(ConvertState self, StringBuilder textBuffer) {
    self.textRunNextFlush = ConvertState.TEXT_RUN_FLUSH_THRESHOLD;
    boolean exhausted = self.textNodeExhausted;
    if (textBuffer.length() > 0) {
      processTextBuffer(self, textBuffer);
      textBuffer.setLength(0);
    } else if (exhausted) {
      self.textBufferContainsWhitespace = false;
      self.textBufferContainsNonWhitespace = false;
      self.textBufferBatchableLen = 0;
      self.textBufferHasInlineGfmHazard = false;
      self.hasEncodedHtmlEntity = false;
    }
    if (exhausted) {
      self.textNodeExhausted = false;
    }
    if (self.emptyItemHazard) {
      OutputOps.settleItemMarker(self);
    }
  }

  static void finalizeState(ConvertState self, String leftover) {
    String rest = leftover == null ? "" : leftover;
    ElementNode last = self.stack.size() == 0 ? null : self.stack.get(self.stack.size() - 1);
    boolean inScript =
        last != null
            && last.tagId == Consts.TAG_SCRIPT
            && (last.customName() == null || last.customName().equals("script"));
    boolean rawtextText =
        !inScript
            && !rest.isEmpty()
            && rest.charAt(0) == '<'
            && self.inNonNesting
            && !self.rawtextEndTagPending;
    boolean dropped = rawtextText && dropsRawText(self);

    if (inScript) {
      pushScriptText(self, rest);
      flushScriptText(self);
      self.scriptDataState = ConvertState.SCRIPT_DATA;
    } else {
      if (rawtextText && !dropped) {
        int beforeLen = self.parseTextBuffer.length();
        self.truncated |= pushCappedTextNode(self.parseTextBuffer, rest, self.maxNodeBytesInt, self);
        String kept = rest.substring(0, self.parseTextBuffer.length() - beforeLen);
        self.textBufferContainsNonWhitespace |= !kept.isEmpty();
        for (int k = 0; k < kept.length(); k++) {
          char c = kept.charAt(k);
          if (c < 256 && (ConvertState.GFM_BYTE_FLAGS[c] & ConvertState.GFM_HAZARD_BIT) != 0) {
            self.textBufferHasInlineGfmHazard = true;
          }
        }
        if (self.depthMap[Consts.TAG_STYLE] == 0 && kept.indexOf('&') >= 0) {
          self.hasEncodedHtmlEntity = true;
        }
        if (!kept.isEmpty()) {
          self.lastCharWasWhitespace = false;
        }
      }
      if (self.parseTextBuffer.length() > 0 || self.textNodeExhausted) {
        StringBuilder buf = self.parseTextBuffer;
        completeTextNode(self, buf);
      }
      if (!rawtextText && !rest.isEmpty() && rest.charAt(0) != '<') {
        StringBuilder buf = new StringBuilder();
        self.truncated |= pushCappedTextNode(buf, rest, self.maxNodeBytesInt, self);
        completeTextNode(self, buf);
      }
    }
    while (self.stack.size() != 0) {
      closeNode(self);
    }
  }

  private static void pushScriptText(ConvertState self, String text) {
    if (text.isEmpty()) {
      return;
    }
    if (!self.extractionTracked.isEmpty()) {
      self.truncated |= pushCappedTextNode(self.scriptTextBuffer, text, self.maxNodeBytesInt, self);
      self.textBufferContainsNonWhitespace = true;
    }
    self.lastCharWasWhitespace = false;
    self.justClosedTag = false;
  }

  private static void flushScriptText(ConvertState self) {
    if (self.scriptTextBuffer.length() == 0 && !self.textNodeExhausted) {
      return;
    }
    StringBuilder scriptText = self.scriptTextBuffer;
    completeTextNode(self, scriptText);
  }

  /** One `findScriptEndTag` result: what boundary was hit and the tokenizer state to keep. */
  private static final class ScriptScan {
    static final int COMPLETE = 0;
    static final int CLOSE = 1;
    static final int PENDING = 2;

    final int boundary;
    final int index;
    final int state;

    ScriptScan(int boundary, int index, int state) {
      this.boundary = boundary;
      this.index = index;
      this.state = state;
    }
  }

  /**
   * Matches {@code </name} case-insensitively and returns the index past its delimiter.
   *
   * <p>Returns {@link #SCRIPT_SEQUENCE_INCOMPLETE} when the chunk ends inside the candidate, so
   * the caller carries the raw tail into the next chunk instead of treating it as text.
   */
  private static final int SCRIPT_SEQUENCE_NO_MATCH = -1;
  private static final int SCRIPT_SEQUENCE_INCOMPLETE = -2;
  private static final String SCRIPT_NAME = "script";

  private static int scriptSequenceEnd(String chunk, int nameStart) {
    for (int offset = 0; offset < SCRIPT_NAME.length(); offset++) {
      int index = nameStart + offset;
      if (index >= chunk.length()) {
        return SCRIPT_SEQUENCE_INCOMPLETE;
      }
      if ((chunk.charAt(index) | 32) != SCRIPT_NAME.charAt(offset)) {
        return SCRIPT_SEQUENCE_NO_MATCH;
      }
    }
    int delimiterIndex = nameStart + SCRIPT_NAME.length();
    if (delimiterIndex >= chunk.length()) {
      return SCRIPT_SEQUENCE_INCOMPLETE;
    }
    char delimiter = chunk.charAt(delimiterIndex);
    if (delimiter == '>' || delimiter == '/' || Scan.isWhitespace(delimiter)) {
      return delimiterIndex + 1;
    }
    return SCRIPT_SEQUENCE_NO_MATCH;
  }

  /**
   * Finds the first script end tag the HTML tokenizer would emit.
   *
   * <p>The script-data escaped states keep a {@code </script>} inside a comment-looking run from
   * closing the element early, matching the tokenizer's {@code script data escaped} rules.
   */
  private static ScriptScan findScriptEndTag(String chunk, int start, int initialState) {
    int state = initialState;
    int i = start;
    int length = chunk.length();

    while (i < length) {
      if (state == ConvertState.SCRIPT_DATA) {
        int next = chunk.indexOf('<', i);
        i = next < 0 ? length : next;
      } else if (state == ConvertState.SCRIPT_DATA_ESCAPED
          || state == ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED) {
        while (i < length) {
          char b = chunk.charAt(i);
          if (b == '<' || b == '-') {
            break;
          }
          i++;
        }
      }
      if (i == length) {
        break;
      }

      char c = chunk.charAt(i);

      if (c == '<') {
        if (i + 1 == length) {
          return new ScriptScan(ScriptScan.PENDING, i, state);
        }

        char next = chunk.charAt(i + 1);
        if (state == ConvertState.SCRIPT_DATA) {
          if (next == '/') {
            int end = scriptSequenceEnd(chunk, i + 2);
            if (end >= 0) {
              return new ScriptScan(ScriptScan.CLOSE, i, state);
            }
            if (end == SCRIPT_SEQUENCE_INCOMPLETE) {
              return new ScriptScan(ScriptScan.PENDING, i, state);
            }
          } else if (next == '!') {
            int available = Math.min(length - i, 4);
            if (chunk.regionMatches(i, "<!--", 0, available)) {
              if (available < 4) {
                return new ScriptScan(ScriptScan.PENDING, i, state);
              }
              state = ConvertState.SCRIPT_DATA_ESCAPED_DASH_DASH;
              i += 4;
              continue;
            }
          }
        } else {
          boolean escaped = state < ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED;
          state =
              escaped
                  ? ConvertState.SCRIPT_DATA_ESCAPED
                  : ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED;
          boolean isEndTag = next == '/';

          if (isEndTag || escaped) {
            int end = scriptSequenceEnd(chunk, i + (isEndTag ? 2 : 1));
            if (end >= 0) {
              if (isEndTag) {
                if (escaped) {
                  return new ScriptScan(ScriptScan.CLOSE, i, state);
                }
                state = ConvertState.SCRIPT_DATA_ESCAPED;
              } else {
                state = ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED;
              }
              i = end;
              continue;
            }
            if (end == SCRIPT_SEQUENCE_INCOMPLETE) {
              return new ScriptScan(ScriptScan.PENDING, i, state);
            }
          }
        }
      } else {
        switch (state) {
          case ConvertState.SCRIPT_DATA_ESCAPED:
            if (c == '-') {
              state = ConvertState.SCRIPT_DATA_ESCAPED_DASH;
            }
            break;
          case ConvertState.SCRIPT_DATA_ESCAPED_DASH:
            if (c == '-') {
              state = ConvertState.SCRIPT_DATA_ESCAPED_DASH_DASH;
            } else {
              state = ConvertState.SCRIPT_DATA_ESCAPED;
            }
            break;
          case ConvertState.SCRIPT_DATA_ESCAPED_DASH_DASH:
            if (c == '>') {
              state = ConvertState.SCRIPT_DATA;
            } else if (c != '-') {
              state = ConvertState.SCRIPT_DATA_ESCAPED;
            }
            break;
          case ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED:
            if (c == '-') {
              state = ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED_DASH;
            }
            break;
          case ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED_DASH:
            if (c == '-') {
              state = ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH;
            } else {
              state = ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED;
            }
            break;
          case ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH:
            if (c == '>') {
              state = ConvertState.SCRIPT_DATA;
            } else if (c != '-') {
              state = ConvertState.SCRIPT_DATA_DOUBLE_ESCAPED;
            }
            break;
          default:
            break;
        }
      }

      i++;
    }

    return new ScriptScan(ScriptScan.COMPLETE, length, state);
  }

  /**
   * Scans script data from {@code start}.
   *
   * <p>Returns the index of the close tag's {@code <} and resets the tokenizer state, or a
   * negative {@code -(carryFrom + 1)} when the chunk ended inside script data and the raw tail
   * from {@code carryFrom} must be carried into the next chunk.
   */
  private static int processScriptChunk(ConvertState self, String chunk, int start) {
    ScriptScan scan = findScriptEndTag(chunk, start, self.scriptDataState);
    self.scriptDataState = scan.state;
    if (scan.boundary == ScriptScan.CLOSE) {
      pushScriptText(self, chunk.substring(start, scan.index));
      flushScriptText(self);
      self.scriptDataState = ConvertState.SCRIPT_DATA;
      return scan.index;
    }
    pushScriptText(self, chunk.substring(start, scan.index));
    int carryFrom = scan.boundary == ScriptScan.PENDING ? scan.index : chunk.length();
    return -(carryFrom + 1);
  }

  static boolean pushCappedTextNode(
      StringBuilder buffer, String text, int cap, ConvertState self) {
    if (cap == 0) {
      buffer.append(text);
      return false;
    }
    if (text.isEmpty()) {
      return false;
    }
    if (self.textNodeExhausted) {
      return true;
    }
    int used = Scan.utf8Length(buffer);
    int room = Math.max(0, cap - used);
    int end = 0;
    int bytes = 0;
    while (end < text.length()) {
      int cp = text.codePointAt(end);
      int len = utf8Length(cp);
      if (bytes + len > room) {
        break;
      }
      bytes += len;
      end += Character.charCount(cp);
    }
    buffer.append(text, 0, end);
    boolean truncated = end != text.length();
    self.textNodeExhausted |= truncated;
    return truncated;
  }

  private static int utf8Length(int cp) {
    if (cp < 0x80) {
      return 1;
    }
    if (cp < 0x800) {
      return 2;
    }
    return cp < 0x10000 ? 3 : 4;
  }

  static boolean canCompleteRawEndTag(String candidate, String expected) {
    return expected.length() >= candidate.length()
        && expected.regionMatches(0, candidate, 0, candidate.length());
  }

  static boolean hasAsciiUpper(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        return true;
      }
    }
    return false;
  }

  static byte[] utf8(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
