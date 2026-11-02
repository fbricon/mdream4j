/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/**
 * Built-in tag handler table.
 *
 * <p>Mirrors Rust {@code tags.rs}. The table is built once at class load; lookups are O(1) and
 * allocation-free.
 */
final class Tags {

  private static final TagHandler NONE =
      new TagHandler(
          false, false, false, false, null, false, Consts.ATTR_NONE);

  private static final TagHandler BLOCK = NONE;

  private static final TagHandler BLOCK_NO_SPACING =
      new TagHandler(false, false, false, false, Consts.NO_SPACING, false, Consts.ATTR_NONE);

  private static final TagHandler INLINE_COLLAPSE =
      new TagHandler(false, false, true, true, Consts.NO_SPACING, false, Consts.ATTR_NONE);

  private static final TagHandler SELF_CLOSING_INLINE =
      new TagHandler(true, false, true, true, Consts.NO_SPACING, false, Consts.ATTR_NONE);

  private static final TagHandler SELF_CLOSING_BLOCK =
      new TagHandler(true, false, false, false, Consts.NO_SPACING, false, Consts.ATTR_NONE);

  private static final TagHandler NON_NESTING_NO_SPACING =
      new TagHandler(false, true, false, false, Consts.NO_SPACING, false, Consts.ATTR_NONE);

  // Fallback content a browser with the feature enabled does not render. Both
  // flags are needed: `is_non_nesting` alone emits the raw text,
  // `excludes_text_nodes` alone still parses descendant markup into elements.
  private static final TagHandler INERT_RAWTEXT =
      new TagHandler(false, true, false, false, Consts.NO_SPACING, true, Consts.ATTR_NONE);

  private static final TagHandler[] TAG_HANDLERS = buildTable();

  private Tags() {}

  /** Get tag handler by ID from the static lookup table. O(1), no allocation. */
  static TagHandler getTagHandler(int tagId) {
    return tagId >= 0 && tagId < Consts.MAX_TAG_ID ? TAG_HANDLERS[tagId] : null;
  }

  private static TagHandler[] buildTable() {
    TagHandler[] t = new TagHandler[Consts.MAX_TAG_ID];

    // HEAD
    t[Consts.TAG_HEAD] = new TagHandler(false, false, true, false, Consts.NO_SPACING, false, 0);
    t[Consts.TAG_DETAILS] = BLOCK;
    t[Consts.TAG_SUMMARY] = BLOCK;
    t[Consts.TAG_TITLE] = new TagHandler(false, true, true, false, Consts.NO_SPACING, false, 0);
    t[Consts.TAG_SCRIPT] = new TagHandler(false, true, false, false, Consts.NO_SPACING, true, 0);
    t[Consts.TAG_STYLE] = new TagHandler(false, true, false, false, Consts.NO_SPACING, true, 0);
    t[Consts.TAG_META] =
        new TagHandler(
            true,
            false,
            true,
            false,
            Consts.NO_SPACING,
            false,
            Consts.ATTR_NAME | Consts.ATTR_PROPERTY | Consts.ATTR_CONTENT);
    t[Consts.TAG_BR] = new TagHandler(true, false, true, true, Consts.NO_SPACING, false, 0);

    // Headings
    t[Consts.TAG_H1] = new TagHandler(false, false, true, false, null, false, 0);
    t[Consts.TAG_H2] = new TagHandler(false, false, true, false, null, false, 0);
    t[Consts.TAG_H3] = new TagHandler(false, false, true, false, null, false, 0);
    t[Consts.TAG_H4] = new TagHandler(false, false, true, false, null, false, 0);
    t[Consts.TAG_H5] = new TagHandler(false, false, true, false, null, false, 0);
    t[Consts.TAG_H6] = new TagHandler(false, false, true, false, null, false, 0);

    t[Consts.TAG_HR] = new TagHandler(true, false, false, false, null, false, 0);

    // Inline formatting
    t[Consts.TAG_STRONG] = INLINE_COLLAPSE;
    t[Consts.TAG_B] = INLINE_COLLAPSE;
    t[Consts.TAG_EM] = INLINE_COLLAPSE;
    t[Consts.TAG_I] = INLINE_COLLAPSE;
    t[Consts.TAG_DEL] = INLINE_COLLAPSE;
    t[Consts.TAG_S] = INLINE_COLLAPSE;
    t[Consts.TAG_STRIKE] = INLINE_COLLAPSE;
    t[Consts.TAG_SUB] = INLINE_COLLAPSE;
    t[Consts.TAG_SUP] = INLINE_COLLAPSE;
    t[Consts.TAG_INS] = INLINE_COLLAPSE;

    t[Consts.TAG_BLOCKQUOTE] =
        new TagHandler(false, false, false, false, Consts.BLOCKQUOTE_SPACING, false, 0);
    t[Consts.TAG_CODE] =
        new TagHandler(
            false,
            false,
            true,
            true,
            Consts.NO_SPACING,
            false,
            Consts.ATTR_CLASS | Consts.ATTR_DATA_LANGUAGE | Consts.ATTR_DATA_LANG);

    // Lists
    t[Consts.TAG_UL] = BLOCK;
    t[Consts.TAG_OL] = new TagHandler(false, false, false, false, null, false, Consts.ATTR_START);
    t[Consts.TAG_LI] =
        new TagHandler(false, false, false, false, Consts.LIST_ITEM_SPACING, false, 0);

    // Links & images
    t[Consts.TAG_A] =
        new TagHandler(
            false,
            false,
            true,
            true,
            Consts.NO_SPACING,
            false,
            Consts.ATTR_HREF | Consts.ATTR_TITLE | Consts.ATTR_ARIA_LABEL);
    t[Consts.TAG_IMG] =
        new TagHandler(
            true,
            false,
            true,
            true,
            Consts.NO_SPACING,
            false,
            Consts.ATTR_SRC | Consts.ATTR_ALT | Consts.ATTR_TITLE);

    // Tables
    t[Consts.TAG_TABLE] = BLOCK;
    t[Consts.TAG_THEAD] =
        new TagHandler(false, false, false, false, Consts.TABLE_ROW_SPACING, true, 0);
    t[Consts.TAG_TR] =
        new TagHandler(false, false, false, false, Consts.TABLE_ROW_SPACING, true, 0);
    t[Consts.TAG_TH] =
        new TagHandler(
            false,
            false,
            true,
            false,
            Consts.NO_SPACING,
            false,
            Consts.ATTR_ALIGN | Consts.ATTR_COLSPAN);
    t[Consts.TAG_TD] =
        new TagHandler(false, false, true, false, Consts.NO_SPACING, false, Consts.ATTR_COLSPAN);
    t[Consts.TAG_TBODY] =
        new TagHandler(false, false, false, false, Consts.NO_SPACING, true, 0);
    t[Consts.TAG_TFOOT] =
        new TagHandler(false, false, false, false, Consts.TABLE_ROW_SPACING, true, 0);

    // Block elements
    t[Consts.TAG_P] = BLOCK;
    t[Consts.TAG_DIV] = BLOCK;
    t[Consts.TAG_NAV] = BLOCK;
    t[Consts.TAG_ARTICLE] = BLOCK;
    t[Consts.TAG_SECTION] = BLOCK;
    t[Consts.TAG_HEADER] = BLOCK;
    t[Consts.TAG_MAIN] = BLOCK;
    t[Consts.TAG_FIGURE] = BLOCK;
    // Language metadata is kept for both a bare <pre> and a code child that owns
    // the deferred fence (issue #97).
    t[Consts.TAG_PRE] =
        new TagHandler(
            false,
            false,
            false,
            false,
            null,
            false,
            Consts.ATTR_CLASS | Consts.ATTR_DATA_LANGUAGE | Consts.ATTR_DATA_LANG);

    // Inline elements
    t[Consts.TAG_SPAN] = INLINE_COLLAPSE;
    t[Consts.TAG_LABEL] = INLINE_COLLAPSE;
    t[Consts.TAG_KBD] = INLINE_COLLAPSE;
    t[Consts.TAG_MARK] = INLINE_COLLAPSE;
    t[Consts.TAG_Q] = INLINE_COLLAPSE;
    t[Consts.TAG_SAMP] = INLINE_COLLAPSE;
    t[Consts.TAG_VAR] = INLINE_COLLAPSE;
    t[Consts.TAG_ABBR] = INLINE_COLLAPSE;
    t[Consts.TAG_SMALL] = INLINE_COLLAPSE;
    t[Consts.TAG_TIME] = INLINE_COLLAPSE;
    t[Consts.TAG_BDO] = INLINE_COLLAPSE;
    t[Consts.TAG_RUBY] = INLINE_COLLAPSE;
    t[Consts.TAG_RT] = INLINE_COLLAPSE;
    t[Consts.TAG_RP] = INLINE_COLLAPSE;
    t[Consts.TAG_U] = INLINE_COLLAPSE;
    t[Consts.TAG_CITE] = INLINE_COLLAPSE;
    t[Consts.TAG_DFN] = INLINE_COLLAPSE;
    t[Consts.TAG_FIGCAPTION] = INLINE_COLLAPSE;

    t[Consts.TAG_BUTTON] = INLINE_COLLAPSE;

    // Block no-spacing
    t[Consts.TAG_BODY] = BLOCK_NO_SPACING;
    t[Consts.TAG_CENTER] = BLOCK_NO_SPACING;
    t[Consts.TAG_FOOTER] = BLOCK_NO_SPACING;
    t[Consts.TAG_FORM] = BLOCK_NO_SPACING;
    t[Consts.TAG_ASIDE] = BLOCK_NO_SPACING;
    t[Consts.TAG_DL] = BLOCK_NO_SPACING;
    t[Consts.TAG_ADDRESS] =
        new TagHandler(false, false, true, false, Consts.NO_SPACING, false, 0);

    // Definition list items
    t[Consts.TAG_DT] =
        new TagHandler(false, false, true, false, new int[] {0, 1}, false, 0);
    t[Consts.TAG_DD] = new TagHandler(false, false, false, false, new int[] {0, 1}, false, 0);

    // Self-closing block elements
    t[Consts.TAG_COL] = SELF_CLOSING_BLOCK;
    t[Consts.TAG_EMBED] = SELF_CLOSING_BLOCK;
    t[Consts.TAG_PARAM] = SELF_CLOSING_BLOCK;
    t[Consts.TAG_SOURCE] = SELF_CLOSING_BLOCK;
    t[Consts.TAG_TRACK] = SELF_CLOSING_BLOCK;

    // Self-closing inline elements
    t[Consts.TAG_LINK] = SELF_CLOSING_INLINE;
    t[Consts.TAG_AREA] = SELF_CLOSING_INLINE;
    t[Consts.TAG_BASE] =
        new TagHandler(true, false, true, true, Consts.NO_SPACING, false, Consts.ATTR_HREF);
    t[Consts.TAG_WBR] = SELF_CLOSING_INLINE;
    t[Consts.TAG_INPUT] = SELF_CLOSING_INLINE;
    t[Consts.TAG_KEYGEN] = SELF_CLOSING_INLINE;

    // Media/interactive (block no-spacing)
    t[Consts.TAG_SVG] = BLOCK_NO_SPACING;
    t[Consts.TAG_SELECT] = BLOCK_NO_SPACING;
    t[Consts.TAG_OPTION] = BLOCK_NO_SPACING;
    t[Consts.TAG_OPTGROUP] = BLOCK_NO_SPACING;
    t[Consts.TAG_FIELDSET] = BLOCK_NO_SPACING;
    t[Consts.TAG_LEGEND] = BLOCK_NO_SPACING;
    t[Consts.TAG_AUDIO] = BLOCK_NO_SPACING;
    t[Consts.TAG_VIDEO] = BLOCK_NO_SPACING;
    t[Consts.TAG_CANVAS] = BLOCK_NO_SPACING;
    t[Consts.TAG_MAP] = BLOCK_NO_SPACING;
    t[Consts.TAG_DIALOG] = BLOCK_NO_SPACING;
    t[Consts.TAG_METER] = BLOCK_NO_SPACING;
    t[Consts.TAG_PROGRESS] = BLOCK_NO_SPACING;
    // <template> content is parsed (including nested templates) but remains
    // inert, so its subtree is excluded from Markdown by the converter.
    t[Consts.TAG_TEMPLATE] =
        new TagHandler(false, false, false, false, Consts.NO_SPACING, true, 0);

    // Non-nesting elements
    // `textarea`, `xmp`, and `plaintext` are rendered, so their raw text is kept.
    t[Consts.TAG_TEXTAREA] = NON_NESTING_NO_SPACING;
    t[Consts.TAG_XMP] = NON_NESTING_NO_SPACING;
    t[Consts.TAG_PLAINTEXT] = NON_NESTING_NO_SPACING;

    t[Consts.TAG_IFRAME] = INERT_RAWTEXT;
    t[Consts.TAG_NOFRAMES] = INERT_RAWTEXT;
    t[Consts.TAG_NOEMBED] = INERT_RAWTEXT;
    t[Consts.TAG_NOSCRIPT] = INERT_RAWTEXT;
    // <datalist> holds <option> autocomplete data browsers never render; treat
    // the whole body as inert and drop it, mirroring <template>.
    t[Consts.TAG_DATALIST] =
        new TagHandler(false, true, false, false, Consts.NO_SPACING, true, 0);

    return t;
  }
}
