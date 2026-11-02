/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import io.github.fbricon.mdream4j.Clean;
import io.github.fbricon.mdream4j.Frontmatter;
import io.github.fbricon.mdream4j.Plugins;
import io.github.fbricon.mdream4j.TagOverride;

/**
 * HTML output mode. Mirrors {@code convert/html_output.rs}.
 *
 * <p>Only an allowlist of tags is rendered; text and attribute values are escaped. Headings and
 * preformatted blocks buffer their content in a {@link ConvertState.HtmlFrame} so the exit can add
 * a heading slug or a {@code <pre><code>} wrapper.
 */
final class HtmlOutputOps {

  private HtmlOutputOps() {}

  static String htmlElementOutput(
      ConvertState self, ElementNode node, boolean enter, boolean protectMarkdown) {
    int tagId = node.tagId;
    if (tagId < 0) {
      return null;
    }
    String name = htmlTagName(tagId);
    if (name == null) {
      return null;
    }
    if (!enter) {
      if (tagId == Consts.TAG_A) {
        String href = node.attributes.getBit(Consts.ATTR_HREF);
        if (href == null || resolveHtmlUrl(self, href, false) == null) {
          return null;
        }
      }
      return "</" + name + ">";
    }

    StringBuilder output = new StringBuilder(name.length() + 24);
    output.append('<').append(name);
    switch (tagId) {
      case Consts.TAG_A:
        {
          String href = node.attributes.getBit(Consts.ATTR_HREF);
          if (href == null) {
            return null;
          }
          String resolved = resolveHtmlUrl(self, href, false);
          if (resolved == null) {
            return null;
          }
          output.append(" href=\"");
          if (protectMarkdown) {
            pushRawAnchorAttribute(output, resolved);
          } else {
            pushEscaped(output, resolved, true);
          }
          output.append('"');
          String title = node.attributes.getBit(Consts.ATTR_TITLE);
          if (title != null) {
            output.append(" title=\"");
            if (protectMarkdown) {
              pushRawAnchorAttribute(output, title);
            } else {
              pushEscaped(output, title, true);
            }
            output.append('"');
          }
          break;
        }
      case Consts.TAG_OL:
        {
          String value = node.attributes.getBit(Consts.ATTR_START);
          Long start =
              value == null ? null : ParseOps.parseBoundedU32(value, 0xFFFF_FFFFL);
          if (start != null) {
            output.append(" start=\"").append(start).append('"');
          }
          break;
        }
      case Consts.TAG_CODE:
        {
          String language = OutputOps.getLanguageFromAttributes(node);
          if (!language.isEmpty()) {
            output.append(" class=\"language-");
            pushEscaped(output, language, true);
            output.append('"');
          }
          break;
        }
      case Consts.TAG_TH:
      case Consts.TAG_TD:
        {
          String value = node.attributes.getBit(Consts.ATTR_COLSPAN);
          Long colspan =
              value == null ? null : ParseOps.parseBoundedU32(value, 0xFFFF_FFFFL);
          if (colspan != null && colspan > 0) {
            output.append(" colspan=\"").append(colspan).append('"');
          }
          if (tagId == Consts.TAG_TH) {
            String align = node.attributes.getBit(Consts.ATTR_ALIGN);
            if (align != null) {
              String normalized = "";
              if (Chars.asciiEqualsIgnoreCase(align, "left")) {
                normalized = "left";
              } else if (Chars.asciiEqualsIgnoreCase(align, "center")) {
                normalized = "center";
              } else if (Chars.asciiEqualsIgnoreCase(align, "right")) {
                normalized = "right";
              }
              if (!normalized.isEmpty()) {
                output.append(" align=\"").append(normalized).append('"');
              }
            }
          }
          break;
        }
      default:
        break;
    }
    output.append('>');
    return output.toString();
  }

  private static String htmlTagName(int tagId) {
    switch (tagId) {
      case Consts.TAG_B:
        return "strong";
      case Consts.TAG_I:
        return "em";
      case Consts.TAG_S:
      case Consts.TAG_STRIKE:
        return "del";
      default:
        break;
    }
    boolean safe =
        tagId == Consts.TAG_DETAILS
            || tagId == Consts.TAG_SUMMARY
            || (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_A)
            || (tagId >= Consts.TAG_TABLE && tagId <= Consts.TAG_TFOOT)
            || tagId == Consts.TAG_NAV
            || tagId == Consts.TAG_KBD
            || tagId == Consts.TAG_FOOTER
            || tagId == Consts.TAG_ARTICLE
            || tagId == Consts.TAG_SECTION
            || (tagId >= Consts.TAG_ABBR && tagId <= Consts.TAG_SMALL)
            || (tagId >= Consts.TAG_ASIDE && tagId <= Consts.TAG_TIME)
            || (tagId >= Consts.TAG_RUBY && tagId <= Consts.TAG_FIGURE)
            || (tagId >= Consts.TAG_MAIN && tagId <= Consts.TAG_CAPTION);
    return safe ? Consts.TAG_NAMES[tagId] : null;
  }

  private static String resolveHtmlUrl(ConvertState self, String url, boolean image) {
    if (!Url.isSafeHtmlUrl(url, image)) {
      return null;
    }
    String resolved = Url.resolveUrl(url, self.origin, self.cleanUrls);
    return Url.isSafeHtmlUrl(resolved, image) ? resolved : null;
  }

  private static void pushEscaped(StringBuilder output, String value, boolean attribute) {
    pushEscapedInner(output, value, attribute, false);
  }

  private static void pushRawAnchorAttribute(StringBuilder output, String value) {
    pushEscapedInner(output, value, true, true);
  }

  private static void pushEscapedInner(
      StringBuilder output, String value, boolean attribute, boolean escapeBrackets) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&':
          output.append("&amp;");
          break;
        case '<':
          output.append("&lt;");
          break;
        case '>':
          output.append("&gt;");
          break;
        case '"':
          if (attribute) {
            output.append("&quot;");
          } else {
            output.append(c);
          }
          break;
        case '[':
          if (escapeBrackets) {
            output.append("&#91;");
          } else {
            output.append(c);
          }
          break;
        case ']':
          if (escapeBrackets) {
            output.append("&#93;");
          } else {
            output.append(c);
          }
          break;
        default:
          output.append(c);
          break;
      }
    }
  }

  // === Emission ===

  /** The buffer an HTML write lands in: the open heading/pre frame, else the main buffer. */
  private static StringBuilder htmlOutput(ConvertState self) {
    if (!self.htmlFrames.isEmpty()) {
      return self.htmlFrames.get(self.htmlFrames.size() - 1).output;
    }
    return self.buffer;
  }

  private static void pushHtml(ConvertState self, String value) {
    htmlOutput(self).append(value);
  }

  private static void pushHtmlText(ConvertState self, String value) {
    if (!self.htmlFrames.isEmpty()) {
      ConvertState.HtmlFrame top = self.htmlFrames.get(self.htmlFrames.size() - 1);
      if (top.kind == ConvertState.HTML_FRAME_PRE
          && top.languagePending
          && Chars.hasVisible(Chars.trimAscii(value))) {
        top.languagePending = false;
      }
    }
    for (int i = self.htmlFrames.size() - 1; i >= 0; i--) {
      ConvertState.HtmlFrame frame = self.htmlFrames.get(i);
      if (frame.kind == ConvertState.HTML_FRAME_HEADING) {
        frame.text.append(value);
        break;
      }
    }
    pushEscaped(htmlOutput(self), value, false);
  }

  /** Mirrors {@code emit_html_enter}: render the element on top of the stack. */
  static void emitHtmlEnter(ConvertState self) {
    int stackLen = self.stack.size();
    if (stackLen == 0) {
      return;
    }
    ElementNode node = self.stack.get(stackLen - 1);
    int tagId = node.tagId;

    if (!self.htmlFrames.isEmpty()) {
      ConvertState.HtmlFrame frame = self.htmlFrames.get(self.htmlFrames.size() - 1);
      if (frame.kind == ConvertState.HTML_FRAME_PRE) {
        if (frame.languagePending
            && tagId != Consts.TAG_PRE
            && !(tagId >= 0 && OutputOps.suppressesFormattingInPre(tagId))) {
          frame.languagePending = false;
          if (stackLen >= 2
              && codeOwnsPendingPreFence(self.stack)
              && self.stack.get(stackLen - 2).depth == frame.depth) {
            String codeLanguage = OutputOps.getLanguageFromAttributes(node);
            if (!codeLanguage.isEmpty()) {
              frame.language = codeLanguage;
            }
          }
        }
        if (tagId == Consts.TAG_BR) {
          frame.output.append('\n');
        }
        return;
      }
    }

    if (tagId >= Consts.TAG_H1 && tagId <= Consts.TAG_H6) {
      ConvertState.HtmlFrame frame = new ConvertState.HtmlFrame(ConvertState.HTML_FRAME_HEADING);
      frame.level = tagId - Consts.TAG_H1 + 1;
      self.htmlFrames.add(frame);
      return;
    }
    if (tagId == Consts.TAG_PRE) {
      ConvertState.HtmlFrame frame = new ConvertState.HtmlFrame(ConvertState.HTML_FRAME_PRE);
      frame.depth = node.depth;
      frame.language = OutputOps.getLanguageFromAttributes(node);
      frame.languagePending = true;
      self.htmlFrames.add(frame);
      return;
    }
    if (tagId == Consts.TAG_BR) {
      pushHtml(self, "<br>");
      return;
    }
    if (tagId == Consts.TAG_HR) {
      pushHtml(self, "<hr>");
      return;
    }
    if (tagId == Consts.TAG_IMG) {
      String rendered = renderHtmlImage(self, node);
      if (rendered != null) {
        pushHtml(self, rendered);
        OutputOps.markRenderedChildContent(self);
      }
      return;
    }
    String rendered = htmlElementOutput(self, node, true, false);
    if (rendered != null) {
      pushHtml(self, rendered);
    }
  }

  private static String renderHtmlImage(ConvertState self, ElementNode node) {
    String src = node.attributes.getBit(Consts.ATTR_SRC);
    if (src == null) {
      return null;
    }
    String resolved = resolveHtmlUrl(self, src, true);
    if (resolved == null) {
      return null;
    }
    StringBuilder output = new StringBuilder(resolved.length() + 32);
    output.append("<img src=\"");
    pushEscaped(output, resolved, true);
    output.append("\" alt=\"");
    String alt = node.attributes.getBit(Consts.ATTR_ALT);
    pushEscaped(output, alt == null ? "" : alt, true);
    output.append('"');
    String title = node.attributes.getBit(Consts.ATTR_TITLE);
    if (title != null) {
      output.append(" title=\"");
      pushEscaped(output, title, true);
      output.append('"');
    }
    output.append('>');
    return output.toString();
  }

  /** Mirrors {@code emit_html_exit}: the node has already been popped from the stack. */
  static void emitHtmlExit(ConvertState self, ElementNode node) {
    if (!self.htmlFrames.isEmpty()) {
      ConvertState.HtmlFrame frame = self.htmlFrames.get(self.htmlFrames.size() - 1);
      boolean inPre = frame.kind == ConvertState.HTML_FRAME_PRE;
      boolean closesFrame =
          frame.kind == ConvertState.HTML_FRAME_HEADING
              ? node.tagId >= Consts.TAG_H1 && node.tagId <= Consts.TAG_H6
              : frame.depth == node.depth && node.tagId == Consts.TAG_PRE;
      if (closesFrame) {
        self.htmlFrames.remove(self.htmlFrames.size() - 1);
        pushHtml(self, renderHtmlFrame(frame));
        return;
      }
      if (inPre) {
        return;
      }
    }
    if (node.tagId == Consts.TAG_BR
        || node.tagId == Consts.TAG_HR
        || node.tagId == Consts.TAG_IMG
        || node.tagId == Consts.TAG_PRE) {
      return;
    }
    String rendered = htmlElementOutput(self, node, false, false);
    if (rendered != null) {
      pushHtml(self, rendered);
    }
  }

  private static String renderHtmlFrame(ConvertState.HtmlFrame frame) {
    if (frame.kind == ConvertState.HTML_FRAME_HEADING) {
      String slug = Url.slugifyHeading(frame.text.toString());
      String open = "<h" + frame.level;
      String close = "</h" + frame.level + ">";
      if (slug.isEmpty()) {
        return open + ">" + frame.output + close;
      }
      return open + " id=\"" + slug + "\">" + frame.output + close;
    }
    StringBuilder rendered = new StringBuilder(frame.output.length() + frame.language.length() + 48);
    rendered.append("<pre tabindex=\"0\"><code");
    if (!frame.language.isEmpty()) {
      rendered.append(" class=\"language-");
      pushEscaped(rendered, frame.language, true);
      rendered.append('"');
    }
    rendered.append('>').append(frame.output).append("</code></pre>");
    return rendered.toString();
  }

  static void emitHtmlText(ConvertState self, String text) {
    if (!text.isEmpty()) {
      pushHtmlText(self, text);
    }
  }

  private static boolean codeOwnsPendingPreFence(NodeStack stack) {
    int size = stack.size();
    return size >= 2
        && stack.get(size - 1).tagId == Consts.TAG_CODE
        && stack.get(size - 2).tagId == Consts.TAG_PRE;
  }
}
