/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * URL normalisation and heading-slug helpers.
 *
 * <p>Direct port of {@code crates/core/src/url.rs}. Pure functions:
 * tracking-parameter stripping, relative-URL resolution, GFM autolink
 * detection, and heading slugs.
 *
 * <p>Rust returns {@code Cow<str>} from some helpers to avoid allocation. In
 * Java a {@code String} is immutable, so the same instance is returned when the
 * Rust code would have returned {@code Cow::Borrowed}; callers can compare with
 * {@code ==} to observe that.
 */
public final class Url {

  /** Tracking query parameter prefixes stripped by {@code CleanConfig::urls}. */
  private static final String[] TRACKING_PREFIXES = {
    "utm_", "fbclid", "gclid", "mc_eid", "msclkid", "oly_"
  };

  private Url() {}

  /**
   * Whether {@code s} looks like a bare absolute URI suitable for GFM autolink
   * shorthand ({@code <http://…>}). Conservative: only common web/mail schemes,
   * no whitespace or angle brackets that would break the autolink syntax.
   */
  public static boolean isAutolinkUri(String s) {
    if (s.isEmpty()) {
      return false;
    }
    // First-byte dispatch: the common no-scheme href rejects without a prefix compare.
    char first = s.charAt(0);
    boolean hasScheme;
    if (first == 'h') {
      hasScheme = s.startsWith("http://") || s.startsWith("https://");
    } else if (first == 'f') {
      hasScheme = s.startsWith("ftp://");
    } else if (first == 'm') {
      hasScheme = s.startsWith("mailto:");
    } else {
      hasScheme = false;
    }
    if (!hasScheme) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char b = s.charAt(i);
      if (b == ' ' || b == '<' || b == '>' || b == '\n' || b == '\r' || b == '\t') {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the significant bytes of {@code rest} start with {@code scheme},
   * ignoring ASCII case only. Tab, LF and CR are skipped: the URL parser removes
   * them before the scheme, so {@code java\tscript:} is the {@code javascript:}
   * scheme.
   */
  private static boolean schemeMatches(String rest, String scheme) {
    int matched = 0;
    for (int i = 0; i < rest.length(); i++) {
      char b = rest.charAt(i);
      if (b == '\t' || b == '\n' || b == '\r') {
        continue;
      }
      if (!Chars.asciiEqualsIgnoreCase(b, scheme.charAt(matched))) {
        return false;
      }
      matched++;
      if (matched == scheme.length()) {
        return true;
      }
    }
    return false;
  }

  /** Strips leading/trailing C0 controls and spaces, as the URL parser does. */
  private static String trimUrlC0(String href) {
    int len = href.length();
    int start = 0;
    while (start < len && href.charAt(start) <= ' ') {
      start++;
    }
    int end = len;
    while (end > start && href.charAt(end - 1) <= ' ') {
      end--;
    }
    return href.substring(start, end);
  }

  /**
   * Whether {@code href} cannot represent meaningful navigation: a bare
   * {@code #}, or a {@code javascript:}, {@code data:} or {@code vbscript:} URL.
   *
   * <p>Mirrors the URL parser's preprocessing, so {@code " javascript:"} and the
   * decoded form of {@code java&#9;script:} are recognised. An interior space is
   * <em>not</em> removed by the URL parser, so {@code java script:x} stays an
   * ordinary relative URL.
   */
  public static boolean isEmptyLinkHref(String href) {
    // Leading and trailing C0 controls and spaces are stripped. UTF-8
    // continuation bytes are all >= 0x80, so this cannot split a character.
    String rest = trimUrlC0(href);
    if (rest.isEmpty()) {
      return false;
    }
    char first = Chars.asciiLower(rest.charAt(0));
    if (first == '#') {
      return rest.length() == 1;
    }
    if (first == 'j') {
      return schemeMatches(rest, "javascript:");
    }
    if (first == 'd') {
      return schemeMatches(rest, "data:");
    }
    if (first == 'v') {
      return schemeMatches(rest, "vbscript:");
    }
    return false;
  }

  /** Whether {@code src} is a {@code data:} URL, with the same preprocessing as {@link #isEmptyLinkHref}. */
  public static boolean isDataUrl(String src) {
    return schemeMatches(trimUrlC0(src), "data:");
  }

  private static boolean isSchemeChar(char b) {
    return (b >= 'a' && b <= 'z')
        || (b >= 'A' && b <= 'Z')
        || (b >= '0' && b <= '9')
        || b == '+'
        || b == '-'
        || b == '.';
  }

  private static boolean isSchemeChar(byte b) {
    return (b >= 'a' && b <= 'z')
        || (b >= 'A' && b <= 'Z')
        || (b >= '0' && b <= '9')
        || b == '+'
        || b == '-'
        || b == '.';
  }

  /** Whether a URL is safe to emit into an HTML href or src attribute. */
  public static boolean isSafeHtmlUrl(String href, boolean image) {
    String rest = trimUrlC0(href);
    if (rest.isEmpty()) {
      return false;
    }
    for (int i = 0; i < rest.length(); i++) {
      char b = rest.charAt(i);
      if (b == '\t' || b == '\n' || b == '\r') {
        continue;
      }
      if (b == ':') {
        char first = Chars.asciiLower(rest.charAt(0));
        if (first == 'h') {
          return schemeMatches(rest, "http:") || schemeMatches(rest, "https:");
        }
        if (first == 'm') {
          return !image && schemeMatches(rest, "mailto:");
        }
        if (first == 't') {
          return !image && schemeMatches(rest, "tel:");
        }
        if (first == 'f') {
          return !image && schemeMatches(rest, "ftp:");
        }
        return false;
      }
      if (b == '/' || b == '?' || b == '#') {
        return true;
      }
      if (!isSchemeChar(b)) {
        return true;
      }
    }
    return true;
  }

  /** Check if a query parameter key is a tracking parameter. */
  public static boolean isTrackingParam(String key) {
    for (String prefix : TRACKING_PREFIXES) {
      if (key.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Strip tracking query parameters from a URL string.
   *
   * <p>Returns the same {@code String} instance when no tracking params are
   * found, avoiding an allocation.
   */
  public static String stripTrackingParams(String url) {
    int qmark = url.indexOf('?');
    if (qmark < 0) {
      return url;
    }
    int hashBeforeQuery = url.indexOf('#');
    if (hashBeforeQuery >= 0 && hashBeforeQuery < qmark) {
      return url;
    }
    int queryStart = qmark + 1;
    int hashAfterQuery = url.indexOf('#', queryStart);
    int queryEnd = hashAfterQuery < 0 ? url.length() : hashAfterQuery;

    // Fast check: does any param match a tracking prefix?
    int scan = queryStart;
    while (true) {
      int amp = url.indexOf('&', scan);
      int end = (amp < 0 || amp >= queryEnd) ? queryEnd : amp;
      int eq = url.indexOf('=', scan);
      int keyEnd = (eq < 0 || eq >= end) ? end : eq;
      if (isTrackingParam(url.substring(scan, keyEnd))) {
        return stripTrackingParamsOwned(url);
      }
      if (end == queryEnd) {
        break;
      }
      scan = end + 1;
    }
    return url;
  }

  /** Strip tracking query parameters from an already-owned URL string. */
  public static String stripTrackingParamsOwned(String url) {
    int qmark = url.indexOf('?');
    if (qmark < 0) {
      return url;
    }
    int hashBeforeQuery = url.indexOf('#');
    if (hashBeforeQuery >= 0 && hashBeforeQuery < qmark) {
      return url;
    }
    int queryStart = qmark + 1;
    int hashAfterQuery = url.indexOf('#', queryStart);
    int queryEnd = hashAfterQuery < 0 ? url.length() : hashAfterQuery;

    StringBuilder out = new StringBuilder(url);
    // Long queries go straight to compaction to avoid a second scan.
    if (Scan.utf8Length(out, queryStart, queryEnd) <= 1024) {
      int end = queryEnd;
      int removed = 0;
      int moveBudget = Scan.utf8Length(out, 0, out.length());
      while (true) {
        int amp = lastIndexOf(out, '&', queryStart, end);
        int start = amp < 0 ? queryStart : amp + 1;
        int eq = indexOf(out, '=', start, end);
        int keyEnd = eq < 0 ? end : eq;
        if (keyMatchesTracking(out, start, keyEnd)) {
          // Each drain moves the suffix: bound their count and bytes before compacting.
          if (removed == 8) {
            break;
          }
          int rangeStart;
          int rangeEnd;
          if (start > queryStart) {
            rangeStart = start - 1;
            rangeEnd = end;
          } else {
            rangeStart = start;
            rangeEnd = Math.min(end + 1, queryEnd);
          }
          int moved = Scan.utf8Length(out, 0, out.length()) - Scan.utf8Length(out, 0, rangeEnd);
          if (moved > moveBudget) {
            break;
          }
          moveBudget -= moved;
          removed++;
          queryEnd -= rangeEnd - rangeStart;
          out.delete(rangeStart, rangeEnd);
        }
        if (start == queryStart) {
          int leading = 0;
          while (queryStart + leading < queryEnd && out.charAt(queryStart + leading) == '&') {
            leading++;
          }
          if (leading > 0) {
            out.delete(queryStart, queryStart + leading);
          }
          if (queryEnd - leading == queryStart) {
            out.deleteCharAt(qmark);
          }
          return out.toString();
        }
        end = start - 1;
      }
    }

    // Compact retained fields once instead of shifting the suffix per removal.
    char[] bytes = out.toString().toCharArray();
    int read = queryStart;
    int write = queryStart;
    while (true) {
      int end = indexOf(bytes, '&', read, queryEnd);
      if (end < 0) {
        end = queryEnd;
      }
      int eq = indexOf(bytes, '=', read, end);
      int keyEnd = eq < 0 ? end : eq;
      if (!keyMatchesTracking(bytes, read, keyEnd)) {
        // Leading empty fields disappear; later empty fields keep their separator.
        if (write > queryStart) {
          bytes[write] = '&';
          write++;
        }
        if (write != read) {
          System.arraycopy(bytes, read, bytes, write, end - read);
        }
        write += end - read;
      }
      if (end == queryEnd) {
        break;
      }
      read = end + 1;
    }
    if (write == queryStart) {
      write = qmark;
    }
    int suffixLen = bytes.length - queryEnd;
    if (write != queryEnd) {
      System.arraycopy(bytes, queryEnd, bytes, write, suffixLen);
    }
    return new String(bytes, 0, write + suffixLen);
  }

  private static boolean headingIsEscaped(byte[] bytes, int index) {
    int end = index;
    while (index > 0 && bytes[index - 1] == '\\') {
      index--;
    }
    return ((end - index) & 1) != 0;
  }

  /** Returns the index just past a heading HTML tag starting at {@code start}, or -1. */
  private static int headingHtmlTagEnd(byte[] bytes, int start) {
    int len = bytes.length;
    int end = start + 1;
    if (end < len && bytes[end] == '/') {
      end += 1;
    }
    if (end >= len || !Chars.isAsciiAlphabetic((char) bytes[end])) {
      return -1;
    }
    end += 1;
    while (end < len && (Chars.isAsciiAlphanumeric((char) bytes[end]) || bytes[end] == '-')) {
      end += 1;
    }
    if (end >= len) {
      return -1;
    }
    int next = bytes[end];
    if (!(next == ' ' || next == '\t' || next == '\n' || next == '\r' || next == '/' || next == '>')) {
      return -1;
    }
    int quote = 0;
    while (end < len) {
      int b = bytes[end];
      if (quote != 0) {
        if (b == quote) {
          quote = 0;
        }
      } else if (b == '\'' || b == '"') {
        quote = b;
      } else if (b == '>') {
        return end + 1;
      } else if (b == '<') {
        return -1;
      }
      end += 1;
    }
    return -1;
  }

  /**
   * GFM-style slug from heading text: lowercase, collapse whitespace/- to -,
   * strip non-alnum except -_.
   */
  public static String slugifyHeading(String text) {
    // Strip inline markdown formatting from heading text
    // Remove [text](url) -> text, strip *_`~
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    int len = bytes.length;
    StringBuilder cleaned = new StringBuilder(len);
    int lastGt = -1;
    for (int i = len - 1; i >= 0; i--) {
      if (bytes[i] == '>') {
        lastGt = i;
        break;
      }
    }
    int firstTickLen = 0;
    int firstTickLast = 0;
    List<int[]> otherTickLasts = null;
    int scan = 0;
    while (scan < len) {
      if (bytes[scan] == '`') {
        int start = scan;
        scan += 1;
        while (scan < len && bytes[scan] == '`') {
          scan += 1;
        }
        int ticks = scan - start;
        if (firstTickLen == 0) {
          firstTickLen = ticks;
          firstTickLast = start;
        } else if (ticks == firstTickLen) {
          firstTickLast = start;
        } else {
          if (otherTickLasts == null) {
            otherTickLasts = new ArrayList<>();
          }
          int index = binarySearchByKey(otherTickLasts, ticks);
          if (index >= 0) {
            otherTickLasts.get(index)[1] = start;
          } else {
            otherTickLasts.add(-index - 1, new int[] {ticks, start});
          }
        }
      } else if (bytes[scan] == '<' && lastGt > scan && !headingIsEscaped(bytes, scan)) {
        int tagEnd = headingHtmlTagEnd(bytes, scan);
        scan = tagEnd >= 0 ? tagEnd : scan + 1;
      } else {
        scan += 1;
      }
    }

    int i = 0;
    int codeTicks = 0;
    while (i < len) {
      if (bytes[i] == '`') {
        int end = i + 1;
        while (end < len && bytes[end] == '`') {
          end += 1;
        }
        int ticks = end - i;
        if (codeTicks == 0) {
          int last;
          if (ticks == firstTickLen) {
            last = firstTickLast;
          } else {
            last = i;
            if (otherTickLasts != null) {
              int index = binarySearchByKey(otherTickLasts, ticks);
              if (index >= 0) {
                last = otherTickLasts.get(index)[1];
              }
            }
          }
          if (last > i) {
            codeTicks = ticks;
          }
        } else if (codeTicks == ticks) {
          codeTicks = 0;
        }
        i = end;
      } else if (codeTicks != 0) {
        cleaned.append((char) (bytes[i] & 0xff));
        i += 1;
      } else if (bytes[i] == '\\' && i + 1 < len) {
        cleaned.append((char) (bytes[i + 1] & 0xff));
        i += 2;
      } else if (bytes[i] == '<' && lastGt > i) {
        int tagEnd = headingHtmlTagEnd(bytes, i);
        if (tagEnd >= 0) {
          i = tagEnd;
          continue;
        }
        i += 1;
      } else if (bytes[i] == '[') {
        // Look for ](url) pattern
        int close = indexOf(bytes, ']', i + 1, len);
        if (close >= 0) {
          int closeAbs = close;
          if (closeAbs + 1 < len && bytes[closeAbs + 1] == '(') {
            int parenClose = indexOf(bytes, ')', closeAbs + 2, len);
            if (parenClose >= 0) {
              // Extract link text only
              cleaned.append(
                  new String(bytes, i + 1, closeAbs - (i + 1), StandardCharsets.UTF_8));
              i = parenClose + 1;
              continue;
            }
          }
        }
        i += 1;
      } else if (bytes[i] == '*' || bytes[i] == '_' || bytes[i] == '`' || bytes[i] == '~') {
        i += 1;
      } else {
        cleaned.append((char) (bytes[i] & 0xff));
        i += 1;
      }
    }

    String trimmed = Chars.trimUnicode(cleaned);
    StringBuilder slug = new StringBuilder(trimmed.length());
    boolean lastWasDash = false;
    for (int j = 0; j < trimmed.length(); j++) {
      char c = trimmed.charAt(j);
      if (c >= 'a' && c <= 'z') {
        slug.append(c);
        lastWasDash = false;
      } else if (c >= 'A' && c <= 'Z') {
        slug.append((char) (c + 32));
        lastWasDash = false;
      } else if (c >= '0' && c <= '9') {
        slug.append(c);
        lastWasDash = false;
      } else if (c == '_') {
        slug.append('_');
        lastWasDash = false;
      } else if ((c == ' ' || c == '\t' || c == '-') && !lastWasDash && slug.length() > 0) {
        slug.append('-');
        lastWasDash = true;
      }
    }
    if (lastWasDash) {
      slug.deleteCharAt(slug.length() - 1);
    }
    return slug.toString();
  }

  private static int binarySearchByKey(List<int[]> positions, int key) {
    int lo = 0;
    int hi = positions.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int midKey = positions.get(mid)[0];
      if (midKey < key) {
        lo = mid + 1;
      } else if (midKey > key) {
        hi = mid - 1;
      } else {
        return mid;
      }
    }
    return -(lo + 1);
  }

  /** Query and fragment take no part in resolution, so the split drops them. */
  private static final class BaseUrl {

    private final String root;
    private final String path;

    private BaseUrl(String root, String path) {
      this.root = root;
      this.path = path;
    }

    static BaseUrl split(String origin) {
      int schemeEnd = origin.indexOf("://");
      if (schemeEnd < 0) {
        return null;
      }
      int i = schemeEnd + 3;
      int rootEnd = -1;
      int len = origin.length();
      while (i < len) {
        char b = origin.charAt(i);
        if (b == '?' || b == '#') {
          break;
        }
        if (b == '/' && rootEnd < 0) {
          rootEnd = i;
        }
        i += 1;
      }
      if (rootEnd < 0) {
        rootEnd = i;
      }
      return new BaseUrl(origin.substring(0, rootEnd), origin.substring(rootEnd, i));
    }

    String dir() {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? "/" : path.substring(0, slash + 1);
    }
  }

  private static boolean isDotSegment(byte[] bytes, int from, int to) {
    int n = to - from;
    if (n == 1) {
      return bytes[from] == '.';
    }
    return n == 2 && bytes[from] == '.' && bytes[from + 1] == '.';
  }

  /**
   * One pass over a reference: its scheme, where its path ends (first {@code ?}
   * or {@code #}, else its length), and whether that path holds a dot segment.
   */
  private static final class RefScan {

    private final boolean scheme;
    private final int pathEnd;
    private final boolean hasDot;

    private RefScan(boolean scheme, int pathEnd, boolean hasDot) {
      this.scheme = scheme;
      this.pathEnd = pathEnd;
      this.hasDot = hasDot;
    }
  }

  private static RefScan scanReference(String url) {
    byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
    int i = 0;
    int segmentStart = 0;
    boolean dot = false;
    // A scheme opens with a letter; every byte so far could still belong to one.
    boolean schemeChars = bytes.length > 0 && Chars.isAsciiAlphabetic((char) bytes[0]);
    while (i < bytes.length) {
      byte b = bytes[i];
      if (b == '?' || b == '#') {
        break;
      }
      if (b == ':' && schemeChars && i > 0) {
        return new RefScan(true, i, false);
      }
      if (b == '/') {
        dot |= isDotSegment(bytes, segmentStart, i);
        segmentStart = i + 1;
        schemeChars = false;
      } else if (schemeChars && !isSchemeChar(b)) {
        schemeChars = false;
      }
      i += 1;
    }
    return new RefScan(false, i, dot || isDotSegment(bytes, segmentStart, i));
  }

  /** {@code rootLen} marks where the path starts; a {@code ..} never climbs past it. */
  private static void popPathSegment(StringBuilder out, int rootLen) {
    int end = out.length() - 1; // the trailing `/` is not part of the segment
    if (end <= rootLen) {
      return;
    }
    int prev = out.substring(rootLen, end).lastIndexOf('/');
    int index = prev < 0 ? rootLen : rootLen + prev;
    out.setLength(index + 1);
  }

  /** {@code out} must end with {@code /}, and does so again after every segment. */
  private static void pushPathSegments(StringBuilder out, String path, int rootLen) {
    int start = 0;
    int len = path.length();
    while (true) {
      int slash = path.indexOf('/', start);
      boolean hasNext = slash >= 0;
      String segment = hasNext ? path.substring(start, slash) : path.substring(start);
      if (segment.equals(".")) {
        // Writes no segment, so `out` keeps the `/` it ends with.
      } else if (segment.equals("..")) {
        popPathSegment(out, rootLen);
      } else {
        out.append(segment);
        if (hasNext) {
          out.append('/');
        }
      }
      if (!hasNext) {
        break;
      }
      start = slash + 1;
    }
  }

  /** Resolves {@code url} against {@code base} the way a browser would. */
  private static String joinBaseUrl(BaseUrl base, String url, RefScan scan) {
    // Only the path resolves; query and fragment carry across.
    byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
    String path = new String(bytes, 0, scan.pathEnd, StandardCharsets.UTF_8);
    String suffix =
        new String(bytes, scan.pathEnd, bytes.length - scan.pathEnd, StandardCharsets.UTF_8);
    StringBuilder resolved =
        new StringBuilder(base.root.length() + base.path.length() + url.length() + 1);
    resolved.append(base.root);
    if (path.isEmpty()) {
      resolved.append(base.path);
      resolved.append(suffix);
      return resolved.toString();
    }
    String dir;
    String rest;
    if (path.startsWith("/")) {
      dir = "/";
      rest = path.substring(1);
    } else {
      dir = base.dir();
      rest = path;
    }
    resolved.append(dir);
    if (scan.hasDot) {
      pushPathSegments(resolved, rest, base.root.length());
    } else {
      resolved.append(rest);
    }
    resolved.append(suffix);
    return resolved.toString();
  }

  /** No {@code scheme://authority}, so no base path to resolve against. */
  private static String joinOriginPrefix(String origin, String url) {
    int end = origin.length();
    while (end > 0 && origin.charAt(end - 1) == '/') {
      end--;
    }
    String trimmedOrigin = origin.substring(0, end);
    String suffix = url.startsWith("./") ? url.substring(2) : url;
    StringBuilder resolved = new StringBuilder(trimmedOrigin.length() + 1 + suffix.length());
    resolved.append(trimmedOrigin);
    if (!suffix.startsWith("/")) {
      resolved.append('/');
    }
    resolved.append(suffix);
    return resolved.toString();
  }

  /**
   * Resolve a {@code <base href>} against {@code origin}. Only an
   * {@code http(s)://host} result is usable as a base; anything else is
   * {@code null}, so resolution stays on {@code origin}.
   */
  public static String resolveBaseHref(String href, String origin) {
    String resolved = resolveUrl(trimUrlC0(href), origin, false);
    BaseUrl base = BaseUrl.split(resolved);
    if (base == null) {
      return null;
    }
    int schemeSep = base.root.indexOf("://");
    if (schemeSep < 0) {
      return null;
    }
    String scheme = base.root.substring(0, schemeSep);
    String authority = base.root.substring(schemeSep + 3);
    // The host follows any userinfo and precedes any port; an empty host is no origin.
    int at = authority.lastIndexOf('@');
    String host = at < 0 ? authority : authority.substring(at + 1);
    int colon = host.indexOf(':');
    if (colon >= 0) {
      host = host.substring(0, colon);
    }
    boolean usable =
        (Chars.asciiEqualsIgnoreCase(scheme, "http") || Chars.asciiEqualsIgnoreCase(scheme, "https"))
            && !host.isEmpty();
    return usable ? resolved : null;
  }

  private static String cleaned(String resolved, boolean needsClean) {
    return needsClean ? stripTrackingParamsOwned(resolved) : resolved;
  }

  /**
   * Resolves {@code url} against {@code origin}, optionally stripping tracking
   * parameters. Returns the same instance when the URL passes through.
   *
   * @param origin nullable base URL
   */
  public static String resolveUrl(String url, String origin, boolean clean) {
    if (url.isEmpty() || url.charAt(0) == '#') {
      return url;
    }

    // Fast path: check if cleaning needed before any allocation
    boolean needsClean = clean && url.indexOf('?') >= 0;
    if (url.startsWith("//")) {
      // A network-path reference inherits only the base scheme.
      String scheme = "https";
      if (origin != null) {
        BaseUrl base = BaseUrl.split(origin);
        if (base != null) {
          int colon = base.root.indexOf(':');
          scheme = base.root.substring(0, colon < 0 ? 0 : colon);
        }
      }
      StringBuilder resolved = new StringBuilder(scheme.length() + 1 + url.length());
      resolved.append(scheme);
      resolved.append(':');
      resolved.append(url);
      return cleaned(resolved.toString(), needsClean);
    }
    if (origin != null) {
      // An explicit scheme (`mailto:`, `https:`, …) means absolute.
      RefScan scan = scanReference(url);
      if (!scan.scheme) {
        BaseUrl base = BaseUrl.split(origin);
        String resolved =
            base != null ? joinBaseUrl(base, url, scan) : joinOriginPrefix(origin, url);
        return cleaned(resolved, needsClean);
      }
    }
    if (needsClean) {
      return stripTrackingParams(url);
    }
    return url;
  }

  private static int indexOf(CharSequence s, char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (s.charAt(i) == c) {
        return i;
      }
    }
    return -1;
  }

  private static int lastIndexOf(CharSequence s, char c, int from, int to) {
    for (int i = to - 1; i >= from; i--) {
      if (s.charAt(i) == c) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOf(char[] s, char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (s[i] == c) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOf(byte[] s, char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (s[i] == c) {
        return i;
      }
    }
    return -1;
  }

  private static boolean keyMatchesTracking(CharSequence s, int start, int end) {
    for (String prefix : TRACKING_PREFIXES) {
      if (regionStartsWith(s, start, end, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean keyMatchesTracking(char[] s, int start, int end) {
    for (String prefix : TRACKING_PREFIXES) {
      if (regionStartsWith(s, start, end, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean regionStartsWith(CharSequence s, int start, int end, String prefix) {
    int n = prefix.length();
    if (end - start < n) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (s.charAt(start + i) != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static boolean regionStartsWith(char[] s, int start, int end, String prefix) {
    int n = prefix.length();
    if (end - start < n) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (s[start + i] != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
