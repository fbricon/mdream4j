/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntitiesTest {

  @Test
  void decodesCanonicalNamesUsingTheLongestMatch() {
    assertEquals("\u00A9 \u2209 \u03D1", Entities.decodeHtmlEntities("&copy; &notin; &thetasym;"));
    assertEquals("\u00ACit;", Entities.decodeHtmlEntities("&notit;"));
  }

  @Test
  void decodesEveryNamedEntityWithASemicolon() {
    for (int i = 0; i < ENTITY_NAMES.length; i++) {
      String input = "&" + ENTITY_NAMES[i] + ";";
      assertEquals(ENTITY_VALUES[i], Entities.decodeHtmlEntities(input), input);
    }
  }

  @Test
  void decodesEveryLegacyNameWithoutASemicolon() {
    for (int i = 0; i < LEGACY_NAMES.length; i++) {
      String input = "&" + LEGACY_NAMES[i];
      assertEquals(LEGACY_VALUES[i], Entities.decodeHtmlEntities(input), input);
      String embedded = "x" + input + "!";
      assertEquals("x" + LEGACY_VALUES[i] + "!", Entities.decodeHtmlEntities(embedded), embedded);
    }
  }

  @Test
  void decodesLegacyNamesWithinText() {
    assertEquals("\u00A9 & \u00A0!", Entities.decodeHtmlEntities("&copy &AMP &nbsp!"));
    assertEquals("\u00A9cat", Entities.decodeHtmlEntities("&copycat"));
    assertEquals("\u00A0", Entities.decodeHtmlEntities("&nbsp;"));
    assertEquals("\u00A9", Entities.decodeHtmlEntities("&COPY;"));
  }

  @Test
  void decodesNumericDecimalAndHexReferences() {
    assertEquals("A A A", Entities.decodeHtmlEntities("&#65; &#x41; &#X41;"));
    assertEquals("A A Acopy; Azz;", Entities.decodeHtmlEntities("&#65 &#x41; &#65copy; &#x41zz;"));
    assertEquals("\uD83D\uDE00", Entities.decodeHtmlEntities("&#x1F600;"));
    assertEquals("\uDBFF\uDFFF", Entities.decodeHtmlEntities("&#x10FFFF;"));
    assertEquals("A", Entities.decodeHtmlEntities("&#0000065;"));
    assertEquals("A", Entities.decodeHtmlEntities("&#65"));
  }

  @Test
  void replacesInvalidAndOverflowingNumericReferences() {
    assertEquals(
        "\u20AC \uFFFD \uFFFD \uFFFD \uFFFD",
        Entities.decodeHtmlEntities("&#x80; &#0; &#xD800; &#x110000; &#999999999999999999999;"));
    assertEquals("\uFFFD", Entities.decodeHtmlEntities("&#xDFFF;"));
    assertEquals("\uFFFD", Entities.decodeHtmlEntities("&#0"));
  }

  @Test
  void appliesWindows1252C1Replacements() {
    int[] expected = {
      0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160,
      0x2039, 0x0152, 0x008D, 0x017D, 0x008F, 0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
      0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    };
    for (int i = 0; i < expected.length; i++) {
      int codePoint = 0x80 + i;
      String ref = "&#x" + Integer.toHexString(codePoint) + ";";
      assertEquals(
          new String(Character.toChars(expected[i])),
          Entities.decodeHtmlEntities(ref),
          ref);
    }
    assertEquals("\u0081", Entities.decodeHtmlEntities("&#129;"));
    assertEquals("\u009D", Entities.decodeHtmlEntities("&#x9d;"));
  }

  @Test
  void appliesAttributeAmbiguityOnlyToLegacyNames() {
    StringBuilder out = new StringBuilder();
    assertTrue(
        Entities.decodeInto("&copycat &copy=1 &copy! &copy;cat", true, false, out));
    assertEquals("&copycat &copy=1 \u00A9! \u00A9cat", out.toString());
    assertEquals("\u00A9cat", Entities.decodeHtmlEntities("&copycat"));

    assertFalse(Entities.decodeInto("&amp=1", true, false, out));
    assertEquals(0, out.length());

    assertFalse(Entities.decodeInto("&ampx", true, false, out));
    assertEquals(0, out.length());

    Entities.decodeAttributeEntitiesInto("&amp=1", out);
    assertEquals("&amp=1", out.toString());
    Entities.decodeAttributeEntitiesInto("&ampx", out);
    assertEquals("&ampx", out.toString());
    Entities.decodeAttributeEntitiesInto("&amp x", out);
    assertEquals("& x", out.toString());

    assertTrue(Entities.decodeInto("&amp;x", true, false, out));
    assertEquals("&x", out.toString());

    Entities.decodeAttributeEntitiesInto("title=\"&copycat\"", out);
    assertEquals("title=\"&copycat\"", out.toString());
    Entities.decodeAttributeEntitiesInto("a &copy b", out);
    assertEquals("a \u00A9 b", out.toString());
  }

  @Test
  void handlesMissingSemicolonsAndUnknownNames() {
    assertEquals("&", Entities.decodeHtmlEntities("&amp"));
    assertEquals("&x", Entities.decodeHtmlEntities("&ampx"));
    assertEquals("&bogus A", Entities.decodeHtmlEntities("&bogus &#65;"));
    assertEquals("&#nope; &#xnope;", Entities.decodeHtmlEntities("&#nope; &#xnope;"));
    assertEquals("&nonexistent;", Entities.decodeHtmlEntities("&nonexistent;"));
    // "not" is a legacy name, so an unknown longer name still decodes its prefix.
    assertEquals("\u00ACanentity;", Entities.decodeHtmlEntities("&notanentity;"));
    StringBuilder attribute = new StringBuilder();
    Entities.decodeAttributeEntitiesInto("&notanentity;", attribute);
    assertEquals("&notanentity;", attribute.toString());
  }

  @Test
  void decodesOnlyOncePerPass() {
    assertEquals("&amp;", Entities.decodeHtmlEntities("&amp;amp;"));
    assertEquals("&copy;", Entities.decodeHtmlEntities("&amp;copy;"));
    assertEquals(
        "<div> & 'quotes'",
        Entities.decodeHtmlEntities("&lt;div&gt; &amp; &apos;quotes&apos;"));
  }

  @Test
  void protectsDecodedEntityReferences() {
    StringBuilder out = new StringBuilder();

    assertTrue(Entities.decodeInto("&amp;copy;", false, true, out));
    assertEquals("\\&copy;", out.toString());

    assertTrue(Entities.decodeInto("&amp;copy", false, true, out));
    assertEquals("&copy", out.toString());

    assertTrue(Entities.decodeInto("&#38;copy;", false, true, out));
    assertEquals("\\&copy;", out.toString());

    assertTrue(Entities.decodeInto("&amp;#65;", false, true, out));
    assertEquals("\\&#65;", out.toString());

    assertTrue(Entities.decodeInto("&amp;notanentity;", false, true, out));
    assertEquals("\\&notanentity;", out.toString());

    assertTrue(Entities.decodeInto("&amp;copy=1", false, true, out));
    assertEquals("&copy=1", out.toString());

    assertTrue(Entities.decodeInto("&amp;copy;", false, false, out));
    assertEquals("&copy;", out.toString());
  }

  @Test
  void returnsFalseAndClearsOutWhenNothingDecodes() {
    StringBuilder out = new StringBuilder("stale");
    assertFalse(Entities.decodeInto("plain text", false, false, out));
    assertEquals(0, out.length());

    out.append("stale");
    assertFalse(Entities.decodeInto("&bogus;", false, false, out));
    assertEquals(0, out.length());

    out.append("stale");
    Entities.decodeAttributeEntitiesInto("plain text", out);
    assertEquals("plain text", out.toString());
  }

  @Test
  void acceptsAnyCharSequence() {
    assertEquals("a \u00A9 b", Entities.decodeHtmlEntities(new StringBuilder("a &copy; b")));
    assertEquals("plain", Entities.decodeHtmlEntities("plain"));
  }

  private static final String[] ENTITY_NAMES = {
    "AElig", "AMP", "Aacute", "Acirc", "Agrave",
    "Alpha", "Aring", "Atilde", "Auml", "Beta",
    "COPY", "Ccedil", "Chi", "Dagger", "Delta",
    "ETH", "Eacute", "Ecirc", "Egrave", "Epsilon",
    "Eta", "Euml", "GT", "Gamma", "Iacute",
    "Icirc", "Igrave", "Iota", "Iuml", "Kappa",
    "LT", "Lambda", "Mu", "Ntilde", "Nu",
    "OElig", "Oacute", "Ocirc", "Ograve", "Omega",
    "Omicron", "Oslash", "Otilde", "Ouml", "Phi",
    "Pi", "Prime", "Psi", "QUOT", "REG",
    "Rho", "Scaron", "Sigma", "THORN", "Tau",
    "Theta", "Uacute", "Ucirc", "Ugrave", "Upsilon",
    "Uuml", "Xi", "Yacute", "Yuml", "Zeta",
    "aacute", "acirc", "acute", "aelig", "agrave",
    "alefsym", "alpha", "amp", "and", "ang",
    "apos", "aring", "asymp", "atilde", "auml",
    "bdquo", "beta", "brvbar", "bull", "cap",
    "ccedil", "cedil", "cent", "chi", "circ",
    "clubs", "cong", "copy", "crarr", "cup",
    "curren", "dArr", "dagger", "darr", "deg",
    "delta", "diams", "divide", "eacute", "ecirc",
    "egrave", "empty", "epsilon", "equiv", "eta",
    "eth", "euml", "euro", "exist", "fnof",
    "forall", "frac12", "frac14", "frac34", "frasl",
    "gamma", "ge", "gt", "hArr", "harr",
    "hearts", "hellip", "iacute", "icirc", "iexcl",
    "igrave", "image", "infin", "int", "iota",
    "iquest", "isin", "iuml", "kappa", "lArr",
    "lambda", "lang", "laquo", "larr", "lceil",
    "ldquo", "le", "lfloor", "lowast", "loz",
    "lsaquo", "lsquo", "lt", "macr", "mdash",
    "micro", "middot", "minus", "mu", "nabla",
    "nbsp", "ndash", "ne", "ni", "not",
    "notin", "nsub", "ntilde", "nu", "oacute",
    "ocirc", "oelig", "ograve", "oline", "omega",
    "omicron", "oplus", "or", "ordf", "ordm",
    "oslash", "otilde", "otimes", "ouml", "para",
    "part", "permil", "perp", "phi", "pi",
    "piv", "plusmn", "pound", "prime", "prod",
    "prop", "psi", "quot", "rArr", "radic",
    "rang", "raquo", "rarr", "rceil", "rdquo",
    "real", "reg", "rfloor", "rho", "rsaquo",
    "rsquo", "sbquo", "scaron", "sdot", "sect",
    "shy", "sigma", "sigmaf", "sim", "spades",
    "sub", "sube", "sum", "sup", "sup1",
    "sup2", "sup3", "supe", "szlig", "tau",
    "there4", "theta", "thetasym", "thorn", "tilde",
    "times", "trade", "uArr", "uacute", "uarr",
    "ucirc", "ugrave", "uml", "upsih", "upsilon",
    "uuml", "weierp", "xi", "yacute", "yen",
    "yuml", "zeta",
  };

  private static final String[] ENTITY_VALUES = {
    "\u00C6", "&", "\u00C1", "\u00C2", "\u00C0",
    "\u0391", "\u00C5", "\u00C3", "\u00C4", "\u0392",
    "\u00A9", "\u00C7", "\u03A7", "\u2021", "\u0394",
    "\u00D0", "\u00C9", "\u00CA", "\u00C8", "\u0395",
    "\u0397", "\u00CB", ">", "\u0393", "\u00CD",
    "\u00CE", "\u00CC", "\u0399", "\u00CF", "\u039A",
    "<", "\u039B", "\u039C", "\u00D1", "\u039D",
    "\u0152", "\u00D3", "\u00D4", "\u00D2", "\u03A9",
    "\u039F", "\u00D8", "\u00D5", "\u00D6", "\u03A6",
    "\u03A0", "\u2033", "\u03A8", "\"", "\u00AE",
    "\u03A1", "\u0160", "\u03A3", "\u00DE", "\u03A4",
    "\u0398", "\u00DA", "\u00DB", "\u00D9", "\u03A5",
    "\u00DC", "\u039E", "\u00DD", "\u0178", "\u0396",
    "\u00E1", "\u00E2", "\u00B4", "\u00E6", "\u00E0",
    "\u2135", "\u03B1", "&", "\u2227", "\u2220",
    "'", "\u00E5", "\u2248", "\u00E3", "\u00E4",
    "\u201E", "\u03B2", "\u00A6", "\u2022", "\u2229",
    "\u00E7", "\u00B8", "\u00A2", "\u03C7", "\u02C6",
    "\u2663", "\u2245", "\u00A9", "\u21B5", "\u222A",
    "\u00A4", "\u21D3", "\u2020", "\u2193", "\u00B0",
    "\u03B4", "\u2666", "\u00F7", "\u00E9", "\u00EA",
    "\u00E8", "\u2205", "\u03B5", "\u2261", "\u03B7",
    "\u00F0", "\u00EB", "\u20AC", "\u2203", "\u0192",
    "\u2200", "\u00BD", "\u00BC", "\u00BE", "\u2044",
    "\u03B3", "\u2265", ">", "\u21D4", "\u2194",
    "\u2665", "\u2026", "\u00ED", "\u00EE", "\u00A1",
    "\u00EC", "\u2111", "\u221E", "\u222B", "\u03B9",
    "\u00BF", "\u2208", "\u00EF", "\u03BA", "\u21D0",
    "\u03BB", "\u27E8", "\u00AB", "\u2190", "\u2308",
    "\u201C", "\u2264", "\u230A", "\u2217", "\u25CA",
    "\u2039", "\u2018", "<", "\u00AF", "\u2014",
    "\u00B5", "\u00B7", "\u2212", "\u03BC", "\u2207",
    "\u00A0", "\u2013", "\u2260", "\u220B", "\u00AC",
    "\u2209", "\u2284", "\u00F1", "\u03BD", "\u00F3",
    "\u00F4", "\u0153", "\u00F2", "\u203E", "\u03C9",
    "\u03BF", "\u2295", "\u2228", "\u00AA", "\u00BA",
    "\u00F8", "\u00F5", "\u2297", "\u00F6", "\u00B6",
    "\u2202", "\u2030", "\u22A5", "\u03C6", "\u03C0",
    "\u03D6", "\u00B1", "\u00A3", "\u2032", "\u220F",
    "\u221D", "\u03C8", "\"", "\u21D2", "\u221A",
    "\u27E9", "\u00BB", "\u2192", "\u2309", "\u201D",
    "\u211C", "\u00AE", "\u230B", "\u03C1", "\u203A",
    "\u2019", "\u201A", "\u0161", "\u22C5", "\u00A7",
    "\u00AD", "\u03C3", "\u03C2", "\u223C", "\u2660",
    "\u2282", "\u2286", "\u2211", "\u2283", "\u00B9",
    "\u00B2", "\u00B3", "\u2287", "\u00DF", "\u03C4",
    "\u2234", "\u03B8", "\u03D1", "\u00FE", "\u02DC",
    "\u00D7", "\u2122", "\u21D1", "\u00FA", "\u2191",
    "\u00FB", "\u00F9", "\u00A8", "\u03D2", "\u03C5",
    "\u00FC", "\u2118", "\u03BE", "\u00FD", "\u00A5",
    "\u00FF", "\u03B6",
  };

  private static final String[] LEGACY_NAMES = {
    "AElig", "AMP", "Aacute", "Acirc", "Agrave",
    "Aring", "Atilde", "Auml", "COPY", "Ccedil",
    "ETH", "Eacute", "Ecirc", "Egrave", "Euml",
    "GT", "Iacute", "Icirc", "Igrave", "Iuml",
    "LT", "Ntilde", "Oacute", "Ocirc", "Ograve",
    "Oslash", "Otilde", "Ouml", "QUOT", "REG",
    "THORN", "Uacute", "Ucirc", "Ugrave", "Uuml",
    "Yacute", "aacute", "acirc", "acute", "aelig",
    "agrave", "amp", "aring", "atilde", "auml",
    "brvbar", "ccedil", "cedil", "cent", "copy",
    "curren", "deg", "divide", "eacute", "ecirc",
    "egrave", "eth", "euml", "frac12", "frac14",
    "frac34", "gt", "iacute", "icirc", "iexcl",
    "igrave", "iquest", "iuml", "laquo", "lt",
    "macr", "micro", "middot", "nbsp", "not",
    "ntilde", "oacute", "ocirc", "ograve", "ordf",
    "ordm", "oslash", "otilde", "ouml", "para",
    "plusmn", "pound", "quot", "raquo", "reg",
    "sect", "shy", "sup1", "sup2", "sup3",
    "szlig", "thorn", "times", "uacute", "ucirc",
    "ugrave", "uml", "uuml", "yacute", "yen",
    "yuml",
  };

  private static final String[] LEGACY_VALUES = {
    "\u00C6", "&", "\u00C1", "\u00C2", "\u00C0",
    "\u00C5", "\u00C3", "\u00C4", "\u00A9", "\u00C7",
    "\u00D0", "\u00C9", "\u00CA", "\u00C8", "\u00CB",
    ">", "\u00CD", "\u00CE", "\u00CC", "\u00CF",
    "<", "\u00D1", "\u00D3", "\u00D4", "\u00D2",
    "\u00D8", "\u00D5", "\u00D6", "\"", "\u00AE",
    "\u00DE", "\u00DA", "\u00DB", "\u00D9", "\u00DC",
    "\u00DD", "\u00E1", "\u00E2", "\u00B4", "\u00E6",
    "\u00E0", "&", "\u00E5", "\u00E3", "\u00E4",
    "\u00A6", "\u00E7", "\u00B8", "\u00A2", "\u00A9",
    "\u00A4", "\u00B0", "\u00F7", "\u00E9", "\u00EA",
    "\u00E8", "\u00F0", "\u00EB", "\u00BD", "\u00BC",
    "\u00BE", ">", "\u00ED", "\u00EE", "\u00A1",
    "\u00EC", "\u00BF", "\u00EF", "\u00AB", "<",
    "\u00AF", "\u00B5", "\u00B7", "\u00A0", "\u00AC",
    "\u00F1", "\u00F3", "\u00F4", "\u00F2", "\u00AA",
    "\u00BA", "\u00F8", "\u00F5", "\u00F6", "\u00B6",
    "\u00B1", "\u00A3", "\"", "\u00BB", "\u00AE",
    "\u00A7", "\u00AD", "\u00B9", "\u00B2", "\u00B3",
    "\u00DF", "\u00FE", "\u00D7", "\u00FA", "\u00FB",
    "\u00F9", "\u00A8", "\u00FC", "\u00FD", "\u00A5",
    "\u00FF",
  };

}
