/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.nio.charset.StandardCharsets;

/**
 * Constants ported from {@code crates/core/src/consts.rs}.
 *
 * <p>Tag IDs are {@code int} (Rust {@code u8}), attribute bitmasks are {@code int} (Rust
 * {@code u16}), strings stay {@code String} and byte slices become {@code byte[]}. Optional tag
 * lookups return a boxed {@link Integer} ({@code null} for Rust's {@code None}).
 */
public final class Consts {

  public static final int TAG_HTML = 0;
  public static final int TAG_HEAD = 1;
  public static final int TAG_DETAILS = 2;
  public static final int TAG_SUMMARY = 3;
  public static final int TAG_TITLE = 4;
  public static final int TAG_META = 5;
  public static final int TAG_BR = 6;
  public static final int TAG_H1 = 7;
  public static final int TAG_H2 = 8;
  public static final int TAG_H3 = 9;
  public static final int TAG_H4 = 10;
  public static final int TAG_H5 = 11;
  public static final int TAG_H6 = 12;
  public static final int TAG_HR = 13;
  public static final int TAG_STRONG = 14;
  public static final int TAG_B = 15;
  public static final int TAG_EM = 16;
  public static final int TAG_I = 17;
  public static final int TAG_DEL = 18;
  public static final int TAG_SUB = 19;
  public static final int TAG_SUP = 20;
  public static final int TAG_INS = 21;
  public static final int TAG_BLOCKQUOTE = 22;
  public static final int TAG_CODE = 23;
  public static final int TAG_UL = 24;
  public static final int TAG_LI = 25;
  public static final int TAG_A = 26;
  public static final int TAG_IMG = 27;
  public static final int TAG_TABLE = 28;
  public static final int TAG_THEAD = 29;
  public static final int TAG_TR = 30;
  public static final int TAG_TH = 31;
  public static final int TAG_TD = 32;
  public static final int TAG_OL = 33;
  public static final int TAG_PRE = 34;
  public static final int TAG_P = 35;
  public static final int TAG_DIV = 36;
  public static final int TAG_SPAN = 37;
  public static final int TAG_TBODY = 38;
  public static final int TAG_TFOOT = 39;
  public static final int TAG_FORM = 40;
  public static final int TAG_NAV = 41;
  public static final int TAG_LABEL = 42;
  public static final int TAG_BUTTON = 43;
  public static final int TAG_BODY = 44;
  public static final int TAG_CENTER = 45;
  public static final int TAG_KBD = 46;
  public static final int TAG_FOOTER = 47;
  public static final int TAG_PATH = 48;
  public static final int TAG_SVG = 49;
  public static final int TAG_ARTICLE = 50;
  public static final int TAG_SECTION = 51;
  public static final int TAG_SCRIPT = 52;
  public static final int TAG_STYLE = 53;
  public static final int TAG_LINK = 54;
  public static final int TAG_AREA = 55;
  public static final int TAG_BASE = 56;
  public static final int TAG_COL = 57;
  public static final int TAG_EMBED = 58;
  public static final int TAG_INPUT = 59;
  public static final int TAG_KEYGEN = 60;
  public static final int TAG_PARAM = 61;
  public static final int TAG_SOURCE = 62;
  public static final int TAG_TRACK = 63;
  public static final int TAG_WBR = 64;
  public static final int TAG_SELECT = 65;
  public static final int TAG_TEXTAREA = 66;
  public static final int TAG_OPTION = 67;
  public static final int TAG_FIELDSET = 68;
  public static final int TAG_LEGEND = 69;
  public static final int TAG_AUDIO = 70;
  public static final int TAG_VIDEO = 71;
  public static final int TAG_CANVAS = 72;
  public static final int TAG_IFRAME = 73;
  public static final int TAG_MAP = 74;
  public static final int TAG_DIALOG = 75;
  public static final int TAG_METER = 76;
  public static final int TAG_PROGRESS = 77;
  public static final int TAG_TEMPLATE = 78;
  public static final int TAG_ABBR = 79;
  public static final int TAG_MARK = 80;
  public static final int TAG_Q = 81;
  public static final int TAG_SAMP = 82;
  public static final int TAG_SMALL = 83;
  public static final int TAG_NOSCRIPT = 84;
  public static final int TAG_NOFRAMES = 85;
  public static final int TAG_XMP = 86;
  public static final int TAG_PLAINTEXT = 87;
  public static final int TAG_ASIDE = 88;
  public static final int TAG_U = 89;
  public static final int TAG_CITE = 90;
  public static final int TAG_DFN = 91;
  public static final int TAG_VAR = 92;
  public static final int TAG_TIME = 93;
  public static final int TAG_BDO = 94;
  public static final int TAG_RUBY = 95;
  public static final int TAG_RT = 96;
  public static final int TAG_RP = 97;
  public static final int TAG_DD = 98;
  public static final int TAG_DT = 99;
  public static final int TAG_ADDRESS = 100;
  public static final int TAG_DL = 101;
  public static final int TAG_FIGURE = 102;
  public static final int TAG_OBJECT = 103;
  public static final int TAG_MAIN = 104;
  public static final int TAG_HEADER = 105;
  public static final int TAG_FIGCAPTION = 106;
  public static final int TAG_CAPTION = 107;
  public static final int TAG_DATALIST = 108;
  public static final int TAG_OPTGROUP = 109;
  public static final int TAG_S = 110;
  public static final int TAG_STRIKE = 111;
  public static final int TAG_NOEMBED = 112;

  public static final int MAX_TAG_ID = 113;

  /**
   * Reverse lookup: tag ID to static tag name string. Unused slots hold the empty string, exactly
   * like the Rust table.
   */
  public static final String[] TAG_NAMES = {
    "html",
    "head",
    "details",
    "summary",
    "title",
    "meta",
    "br",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "hr",
    "strong",
    "b",
    "em",
    "i",
    "del",
    "sub",
    "sup",
    "ins",
    "blockquote",
    "code",
    "ul",
    "li",
    "a",
    "img",
    "table",
    "thead",
    "tr",
    "th",
    "td",
    "ol",
    "pre",
    "p",
    "div",
    "span",
    "tbody",
    "tfoot",
    "form",
    "nav",
    "label",
    "button",
    "body",
    "center",
    "kbd",
    "footer",
    "path",
    "svg",
    "article",
    "section",
    "script",
    "style",
    "link",
    "area",
    "base",
    "col",
    "embed",
    "input",
    "keygen",
    "param",
    "source",
    "track",
    "wbr",
    "select",
    "textarea",
    "option",
    "fieldset",
    "legend",
    "audio",
    "video",
    "canvas",
    "iframe",
    "map",
    "dialog",
    "meter",
    "progress",
    "template",
    "abbr",
    "mark",
    "q",
    "samp",
    "small",
    "noscript",
    "noframes",
    "xmp",
    "plaintext",
    "aside",
    "u",
    "cite",
    "dfn",
    "var",
    "time",
    "bdo",
    "ruby",
    "rt",
    "rp",
    "dd",
    "dt",
    "address",
    "dl",
    "figure",
    "object",
    "main",
    "header",
    "figcaption",
    "caption",
    "datalist",
    "optgroup",
    "s",
    "strike",
    "noembed",
  };

  /**
   * Longest name in {@link #TAG_NAMES}. A {@code <name} prefix longer than this cannot be a
   * builtin, so it is a custom name and falls under the cap.
   */
  public static final int MAX_BUILTIN_TAG_NAME;

  static {
    int max = 0;
    int i = 0;
    while (i < MAX_TAG_ID) {
      int len = TAG_NAMES[i].length();
      if (len > max) {
        max = len;
      }
      i++;
    }
    MAX_BUILTIN_TAG_NAME = max;
  }

  /**
   * Attribute bitmask: which attribute names a tag's rendering actually reads. Each stored value
   * costs an owned {@code String}, the dominant allocation during parsing, yet a built-in tag reads
   * at most three.
   */
  public static final int ATTR_NONE = 0;
  public static final int ATTR_HREF = 1 << 0;
  public static final int ATTR_TITLE = 1 << 1;
  public static final int ATTR_ARIA_LABEL = 1 << 2;
  public static final int ATTR_SRC = 1 << 3;
  public static final int ATTR_ALT = 1 << 4;
  public static final int ATTR_CLASS = 1 << 5;
  public static final int ATTR_ALIGN = 1 << 6;
  public static final int ATTR_NAME = 1 << 7;
  public static final int ATTR_PROPERTY = 1 << 8;
  public static final int ATTR_CONTENT = 1 << 9;
  public static final int ATTR_START = 1 << 10;
  public static final int ATTR_COLSPAN = 1 << 11;
  public static final int ATTR_DATA_LANGUAGE = 1 << 12;
  public static final int ATTR_DATA_LANG = 1 << 13;
  public static final int ATTR_ALL = 0xFFFF;

  /**
   * Reverse of {@link #attr_bit(byte[])}, for re-serialisation and extraction output.
   *
   * @param bit the single-bit attribute mask
   * @return the canonical attribute name, or the empty string for {@link #ATTR_NONE}, combinations
   *     and unknown bits
   */
  public static String attr_name(int bit) {
    switch (bit) {
      case ATTR_HREF:
        return "href";
      case ATTR_TITLE:
        return "title";
      case ATTR_ARIA_LABEL:
        return "aria-label";
      case ATTR_SRC:
        return "src";
      case ATTR_ALT:
        return "alt";
      case ATTR_CLASS:
        return "class";
      case ATTR_ALIGN:
        return "align";
      case ATTR_NAME:
        return "name";
      case ATTR_PROPERTY:
        return "property";
      case ATTR_CONTENT:
        return "content";
      case ATTR_START:
        return "start";
      case ATTR_COLSPAN:
        return "colspan";
      case ATTR_DATA_LANGUAGE:
        return "data-language";
      case ATTR_DATA_LANG:
        return "data-lang";
      default:
        return "";
    }
  }

  /**
   * Longest name any mask bit matches, derived from {@link #attr_name(int)} so a new attribute
   * cannot outgrow the length at which a filtered scan stops retaining a name.
   */
  public static final int MAX_WANTED_ATTR_NAME;

  static {
    int max = 0;
    // Mirrors Rust's `while bit != 0 { ... bit <<= 1 }` over a u16: the shift stays 16-bit wide.
    int bit = 1;
    while (bit != 0) {
      int len = attr_name(bit).length();
      if (len > max) {
        max = len;
      }
      bit = (bit << 1) & 0xFFFF;
    }
    MAX_WANTED_ATTR_NAME = max;
  }

  /**
   * Case-insensitive, length-first so the common miss costs one compare.
   *
   * @param name raw attribute-name bytes
   * @return the matching single-bit mask, or {@link #ATTR_NONE}
   */
  public static int attr_bit(byte[] name) {
    switch (name.length) {
      case 3:
        if (eqIgnoreAsciiCase(name, "alt")) {
          return ATTR_ALT;
        } else if (eqIgnoreAsciiCase(name, "src")) {
          return ATTR_SRC;
        } else {
          return ATTR_NONE;
        }
      case 4:
        if (eqIgnoreAsciiCase(name, "href")) {
          return ATTR_HREF;
        } else if (eqIgnoreAsciiCase(name, "name")) {
          return ATTR_NAME;
        } else {
          return ATTR_NONE;
        }
      case 5:
        if (eqIgnoreAsciiCase(name, "title")) {
          return ATTR_TITLE;
        } else if (eqIgnoreAsciiCase(name, "class")) {
          return ATTR_CLASS;
        } else if (eqIgnoreAsciiCase(name, "align")) {
          return ATTR_ALIGN;
        } else if (eqIgnoreAsciiCase(name, "start")) {
          return ATTR_START;
        } else {
          return ATTR_NONE;
        }
      case 7:
        if (eqIgnoreAsciiCase(name, "content")) {
          return ATTR_CONTENT;
        } else if (eqIgnoreAsciiCase(name, "colspan")) {
          return ATTR_COLSPAN;
        } else {
          return ATTR_NONE;
        }
      case 8:
        if (eqIgnoreAsciiCase(name, "property")) {
          return ATTR_PROPERTY;
        } else {
          return ATTR_NONE;
        }
      case 9:
        if (eqIgnoreAsciiCase(name, "data-lang")) {
          return ATTR_DATA_LANG;
        } else {
          return ATTR_NONE;
        }
      case 10:
        if (eqIgnoreAsciiCase(name, "aria-label")) {
          return ATTR_ARIA_LABEL;
        } else {
          return ATTR_NONE;
        }
      case 13:
        if (eqIgnoreAsciiCase(name, "data-language")) {
          return ATTR_DATA_LANGUAGE;
        } else {
          return ATTR_NONE;
        }
      default:
        return ATTR_NONE;
    }
  }

  public static final String MARKDOWN_STRONG = "**";
  public static final String MARKDOWN_EMPHASIS = "*";
  public static final String MARKDOWN_STRIKETHROUGH = "~~";
  public static final String MARKDOWN_CODE_BLOCK = "```";
  public static final String MARKDOWN_INLINE_CODE = "`";
  public static final String MARKDOWN_HORIZONTAL_RULE = "---";

  /**
   * Rule sharing a line with a list marker: {@code - ---} is dashes and spaces only, so the whole
   * line is a thematic break and the item is lost. {@code *} cannot collide.
   */
  public static final String MARKDOWN_HORIZONTAL_RULE_ALT = "***";

  /** Rust {@code [u8; 2]}, widened to {@code int} per the port's numeric convention. */
  public static final int[] NO_SPACING = {0, 0};

  public static final int[] DEFAULT_BLOCK_SPACING = {2, 2};
  public static final int[] BLOCKQUOTE_SPACING = {2, 2};
  public static final int[] LIST_ITEM_SPACING = {1, 0};
  public static final int[] TABLE_ROW_SPACING = {0, 1};

  /**
   * Longest built-in HTML tag name ({@code blockquote}, {@code figcaption}); also the size of the
   * stack buffer used for the case-insensitive lookup. Private in Rust, public here so the porter
   * classes in this package can size their own buffers from it.
   */
  public static final int MAX_BUILTIN_TAG_LEN = 10;

  private Consts() {}

  /**
   * Resolve a lowercase built-in tag name to its ID.
   *
   * <p>Case-sensitive, byte-for-byte: {@code get_tag_id("EM")} is {@code null}, matching Rust's
   * {@code get_tag_id(name.as_bytes())}.
   *
   * @param name the tag name
   * @return the tag ID, or {@code null} if the name is not a built-in tag
   */
  public static Integer get_tag_id(String name) {
    return get_tag_id_bytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Case-insensitive built-in tag lookup over raw bytes.
   *
   * <p>Lowercases ASCII into a stack-allocated {@link #MAX_BUILTIN_TAG_LEN} buffer, avoiding the
   * allocation {@code name.to_ascii_lowercase()} would incur when the input contains uppercase
   * bytes. Returns {@code null} for any tag not in the built-in set.
   *
   * @param name raw tag-name bytes
   * @return the tag ID, or {@code null} if the name is empty, too long, or not a built-in tag
   */
  public static Integer get_tag_id_ci_bytes(byte[] name) {
    int len = name.length;
    if (len == 0 || len > MAX_BUILTIN_TAG_LEN) {
      return null;
    }
    // Nearly all real-world HTML already uses lowercase tag names. Avoid copying and lowercasing
    // the common case; retain the stack-buffer fallback for valid mixed/uppercase HTML.
    Integer id = get_tag_id_bytes(name);
    if (id != null) {
      return id;
    }
    // Rust reuses its fixed-size MAX_BUILTIN_TAG_LEN stack buffer and re-slices it to `len`; a
    // right-sized array is the same bytes for the strict lookup below.
    byte[] buf = new byte[len];
    int i = 0;
    while (i < len) {
      buf[i] = (byte) toAsciiLower(name[i]);
      i++;
    }
    return get_tag_id_bytes(buf);
  }

  /**
   * Strict, case-sensitive tag name to ID lookup.
   *
   * <p>Equivalent to Rust's exhaustive byte-string match: length first, then exact byte equality
   * against the built-in literals in the same order. Returns {@code null} for any tag not in the
   * built-in set; callers can opt those tags into a rendering via tag overrides.
   *
   * @param bytes raw tag-name bytes
   * @return the tag ID, or {@code null} if the bytes are not a built-in tag
   */
  public static Integer get_tag_id_bytes(byte[] bytes) {
    switch (bytes.length) {
      case 1:
        if (asciiEquals(bytes, "u")) {
          return TAG_U;
        } else if (asciiEquals(bytes, "s")) {
          return TAG_S;
        } else if (asciiEquals(bytes, "q")) {
          return TAG_Q;
        } else if (asciiEquals(bytes, "p")) {
          return TAG_P;
        } else if (asciiEquals(bytes, "i")) {
          return TAG_I;
        } else if (asciiEquals(bytes, "b")) {
          return TAG_B;
        } else if (asciiEquals(bytes, "a")) {
          return TAG_A;
        }
        return null;
      case 2:
        if (asciiEquals(bytes, "ul")) {
          return TAG_UL;
        } else if (asciiEquals(bytes, "tr")) {
          return TAG_TR;
        } else if (asciiEquals(bytes, "th")) {
          return TAG_TH;
        } else if (asciiEquals(bytes, "td")) {
          return TAG_TD;
        } else if (asciiEquals(bytes, "rt")) {
          return TAG_RT;
        } else if (asciiEquals(bytes, "rp")) {
          return TAG_RP;
        } else if (asciiEquals(bytes, "ol")) {
          return TAG_OL;
        } else if (asciiEquals(bytes, "li")) {
          return TAG_LI;
        } else if (asciiEquals(bytes, "hr")) {
          return TAG_HR;
        } else if (asciiEquals(bytes, "h6")) {
          return TAG_H6;
        } else if (asciiEquals(bytes, "h5")) {
          return TAG_H5;
        } else if (asciiEquals(bytes, "h4")) {
          return TAG_H4;
        } else if (asciiEquals(bytes, "h3")) {
          return TAG_H3;
        } else if (asciiEquals(bytes, "h2")) {
          return TAG_H2;
        } else if (asciiEquals(bytes, "h1")) {
          return TAG_H1;
        } else if (asciiEquals(bytes, "em")) {
          return TAG_EM;
        } else if (asciiEquals(bytes, "dt")) {
          return TAG_DT;
        } else if (asciiEquals(bytes, "dl")) {
          return TAG_DL;
        } else if (asciiEquals(bytes, "dd")) {
          return TAG_DD;
        } else if (asciiEquals(bytes, "br")) {
          return TAG_BR;
        }
        return null;
      case 3:
        if (asciiEquals(bytes, "xmp")) {
          return TAG_XMP;
        } else if (asciiEquals(bytes, "wbr")) {
          return TAG_WBR;
        } else if (asciiEquals(bytes, "var")) {
          return TAG_VAR;
        } else if (asciiEquals(bytes, "svg")) {
          return TAG_SVG;
        } else if (asciiEquals(bytes, "sup")) {
          return TAG_SUP;
        } else if (asciiEquals(bytes, "sub")) {
          return TAG_SUB;
        } else if (asciiEquals(bytes, "pre")) {
          return TAG_PRE;
        } else if (asciiEquals(bytes, "nav")) {
          return TAG_NAV;
        } else if (asciiEquals(bytes, "map")) {
          return TAG_MAP;
        } else if (asciiEquals(bytes, "kbd")) {
          return TAG_KBD;
        } else if (asciiEquals(bytes, "ins")) {
          return TAG_INS;
        } else if (asciiEquals(bytes, "img")) {
          return TAG_IMG;
        } else if (asciiEquals(bytes, "div")) {
          return TAG_DIV;
        } else if (asciiEquals(bytes, "dfn")) {
          return TAG_DFN;
        } else if (asciiEquals(bytes, "del")) {
          return TAG_DEL;
        } else if (asciiEquals(bytes, "col")) {
          return TAG_COL;
        } else if (asciiEquals(bytes, "bdo")) {
          return TAG_BDO;
        }
        return null;
      case 4:
        if (asciiEquals(bytes, "time")) {
          return TAG_TIME;
        } else if (asciiEquals(bytes, "span")) {
          return TAG_SPAN;
        } else if (asciiEquals(bytes, "samp")) {
          return TAG_SAMP;
        } else if (asciiEquals(bytes, "ruby")) {
          return TAG_RUBY;
        } else if (asciiEquals(bytes, "path")) {
          return TAG_PATH;
        } else if (asciiEquals(bytes, "meta")) {
          return TAG_META;
        } else if (asciiEquals(bytes, "mark")) {
          return TAG_MARK;
        } else if (asciiEquals(bytes, "main")) {
          return TAG_MAIN;
        } else if (asciiEquals(bytes, "link")) {
          return TAG_LINK;
        } else if (asciiEquals(bytes, "html")) {
          return TAG_HTML;
        } else if (asciiEquals(bytes, "head")) {
          return TAG_HEAD;
        } else if (asciiEquals(bytes, "form")) {
          return TAG_FORM;
        } else if (asciiEquals(bytes, "code")) {
          return TAG_CODE;
        } else if (asciiEquals(bytes, "cite")) {
          return TAG_CITE;
        } else if (asciiEquals(bytes, "body")) {
          return TAG_BODY;
        } else if (asciiEquals(bytes, "base")) {
          return TAG_BASE;
        } else if (asciiEquals(bytes, "area")) {
          return TAG_AREA;
        } else if (asciiEquals(bytes, "abbr")) {
          return TAG_ABBR;
        }
        return null;
      case 5:
        if (asciiEquals(bytes, "video")) {
          return TAG_VIDEO;
        } else if (asciiEquals(bytes, "track")) {
          return TAG_TRACK;
        } else if (asciiEquals(bytes, "title")) {
          return TAG_TITLE;
        } else if (asciiEquals(bytes, "thead")) {
          return TAG_THEAD;
        } else if (asciiEquals(bytes, "tfoot")) {
          return TAG_TFOOT;
        } else if (asciiEquals(bytes, "tbody")) {
          return TAG_TBODY;
        } else if (asciiEquals(bytes, "table")) {
          return TAG_TABLE;
        } else if (asciiEquals(bytes, "style")) {
          return TAG_STYLE;
        } else if (asciiEquals(bytes, "small")) {
          return TAG_SMALL;
        } else if (asciiEquals(bytes, "param")) {
          return TAG_PARAM;
        } else if (asciiEquals(bytes, "meter")) {
          return TAG_METER;
        } else if (asciiEquals(bytes, "label")) {
          return TAG_LABEL;
        } else if (asciiEquals(bytes, "input")) {
          return TAG_INPUT;
        } else if (asciiEquals(bytes, "embed")) {
          return TAG_EMBED;
        } else if (asciiEquals(bytes, "audio")) {
          return TAG_AUDIO;
        } else if (asciiEquals(bytes, "aside")) {
          return TAG_ASIDE;
        }
        return null;
      case 6:
        if (asciiEquals(bytes, "strong")) {
          return TAG_STRONG;
        } else if (asciiEquals(bytes, "source")) {
          return TAG_SOURCE;
        } else if (asciiEquals(bytes, "select")) {
          return TAG_SELECT;
        } else if (asciiEquals(bytes, "script")) {
          return TAG_SCRIPT;
        } else if (asciiEquals(bytes, "option")) {
          return TAG_OPTION;
        } else if (asciiEquals(bytes, "object")) {
          return TAG_OBJECT;
        } else if (asciiEquals(bytes, "legend")) {
          return TAG_LEGEND;
        } else if (asciiEquals(bytes, "keygen")) {
          return TAG_KEYGEN;
        } else if (asciiEquals(bytes, "iframe")) {
          return TAG_IFRAME;
        } else if (asciiEquals(bytes, "header")) {
          return TAG_HEADER;
        } else if (asciiEquals(bytes, "footer")) {
          return TAG_FOOTER;
        } else if (asciiEquals(bytes, "figure")) {
          return TAG_FIGURE;
        } else if (asciiEquals(bytes, "dialog")) {
          return TAG_DIALOG;
        } else if (asciiEquals(bytes, "center")) {
          return TAG_CENTER;
        } else if (asciiEquals(bytes, "canvas")) {
          return TAG_CANVAS;
        } else if (asciiEquals(bytes, "button")) {
          return TAG_BUTTON;
        } else if (asciiEquals(bytes, "strike")) {
          return TAG_STRIKE;
        }
        return null;
      case 7:
        if (asciiEquals(bytes, "summary")) {
          return TAG_SUMMARY;
        } else if (asciiEquals(bytes, "section")) {
          return TAG_SECTION;
        } else if (asciiEquals(bytes, "details")) {
          return TAG_DETAILS;
        } else if (asciiEquals(bytes, "caption")) {
          return TAG_CAPTION;
        } else if (asciiEquals(bytes, "article")) {
          return TAG_ARTICLE;
        } else if (asciiEquals(bytes, "address")) {
          return TAG_ADDRESS;
        } else if (asciiEquals(bytes, "noembed")) {
          return TAG_NOEMBED;
        }
        return null;
      case 8:
        if (asciiEquals(bytes, "textarea")) {
          return TAG_TEXTAREA;
        } else if (asciiEquals(bytes, "template")) {
          return TAG_TEMPLATE;
        } else if (asciiEquals(bytes, "progress")) {
          return TAG_PROGRESS;
        } else if (asciiEquals(bytes, "noscript")) {
          return TAG_NOSCRIPT;
        } else if (asciiEquals(bytes, "noframes")) {
          return TAG_NOFRAMES;
        } else if (asciiEquals(bytes, "fieldset")) {
          return TAG_FIELDSET;
        } else if (asciiEquals(bytes, "datalist")) {
          return TAG_DATALIST;
        } else if (asciiEquals(bytes, "optgroup")) {
          return TAG_OPTGROUP;
        }
        return null;
      case 9:
        if (asciiEquals(bytes, "plaintext")) {
          return TAG_PLAINTEXT;
        }
        return null;
      case 10:
        if (asciiEquals(bytes, "figcaption")) {
          return TAG_FIGCAPTION;
        } else if (asciiEquals(bytes, "blockquote")) {
          return TAG_BLOCKQUOTE;
        }
        return null;
      default:
        return null;
    }
  }

  /** Exact byte equality against an ASCII literal, matching Rust's {@code &[u8] == b".."}. */
  private static boolean asciiEquals(byte[] bytes, String literal) {
    if (bytes.length != literal.length()) {
      return false;
    }
    for (int i = 0; i < bytes.length; i++) {
      if ((bytes[i] & 0xFF) != literal.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** Rust's {@code u8::eq_ignore_ascii_case}: only {@code A-Z} fold, every other byte is exact. */
  private static boolean eqIgnoreAsciiCase(byte[] name, String literal) {
    if (name.length != literal.length()) {
      return false;
    }
    for (int i = 0; i < name.length; i++) {
      if (toAsciiLower(name[i]) != literal.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** ASCII-only lowercase, returned unsigned so byte comparisons stay in {@code 0..255}. */
  private static int toAsciiLower(byte b) {
    int c = b & 0xFF;
    if (c >= 'A' && c <= 'Z') {
      return c + ('a' - 'A');
    }
    return c;
  }
}
