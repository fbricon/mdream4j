/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

import java.nio.charset.StandardCharsets;

/**
 * HTML character reference decoder.
 *
 * <p>Ported one-to-one from mdream's {@code entities.rs} and
 * {@code entities_generated.rs}. The generated lookup keeps the upstream shape:
 * 252 names in 512 open-addressed slots, plus a packed data block of
 * {@code [name length][name][value length][UTF-8 value]} records. The legacy
 * flag marks the 106 names that may be referenced without a trailing semicolon.
 */
public final class Entities {

  private static final int MAX_ENTITY_NAME_LENGTH = 8;
  private static final int MAX_LEGACY_ENTITY_NAME_LENGTH = 6;
  private static final int LEGACY_FLAG = 0x8000;

  /** No encoded offset reaches 0xFFFF: offsets stay below 0x8000 and the flag adds 0x8000. */
  private static final int ENTITY_SLOT_EMPTY = 0xFFFF;

  /** Undefined Windows-1252 slots intentionally retain their C1 controls. */
  private static final int[] C1_REPLACEMENTS = {
    0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039,
    0x0152, 0x008D, 0x017D, 0x008F, 0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
  };

  private static final char[] ENTITY_SLOTS = {
    0xFFFF, 0xFFFF, 0x88B3, 0xFFFF, 0xFFFF, 0x006A, 0x03B8, 0xFFFF, 0xFFFF, 0xFFFF, 0x803E, 0x8048,
    0x010C, 0x0426, 0x087E, 0x0333, 0x8217, 0xFFFF, 0x04FF, 0x8166, 0x066F, 0x86AA, 0x82C9, 0xFFFF,
    0x800F, 0x8000, 0x8275, 0x0372, 0xFFFF, 0xFFFF, 0xFFFF, 0x8362, 0xFFFF, 0x0369, 0xFFFF, 0x0852,
    0xFFFF, 0xFFFF, 0xFFFF, 0x8060, 0x05EE, 0x060B, 0x0513, 0x8667, 0xFFFF, 0xFFFF, 0x8476, 0xFFFF,
    0xFFFF, 0x85E5, 0xFFFF, 0xFFFF, 0x0601, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x01D8,
    0x015B, 0x8564, 0x05A3, 0xFFFF, 0xFFFF, 0xFFFF, 0x0715, 0xFFFF, 0x0318, 0x8321, 0x8292, 0x06CF,
    0xFFFF, 0xFFFF, 0xFFFF, 0x002C, 0x81E1, 0x0654, 0x07FA, 0xFFFF, 0xFFFF, 0xFFFF, 0x86A0, 0xFFFF,
    0xFFFF, 0xFFFF, 0x078B, 0xFFFF, 0xFFFF, 0x0835, 0xFFFF, 0xFFFF, 0xFFFF, 0x071F, 0x049D, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0x03F3, 0x87D9, 0x02FF, 0x877B, 0x0525, 0x865F, 0x029B, 0x0538, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x837C, 0xFFFF, 0xFFFF, 0x80DB, 0x0782, 0xFFFF, 0xFFFF, 0xFFFF,
    0xFFFF, 0x8009, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0678, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0x0509, 0x068C, 0xFFFF, 0xFFFF, 0x81A0, 0x01D1, 0x833B, 0x05AA, 0x85B1, 0x86F9, 0x83FE, 0x041C,
    0xFFFF, 0xFFFF, 0xFFFF, 0x8244, 0xFFFF, 0x80E4, 0x01B5, 0x0116, 0x82EE, 0x070B, 0x080E, 0x0805,
    0xFFFF, 0xFFFF, 0x83D0, 0xFFFF, 0xFFFF, 0x8399, 0x0530, 0xFFFF, 0xFFFF, 0xFFFF, 0x027B, 0xFFFF,
    0x00FE, 0x06DD, 0x00C8, 0x823B, 0x07B9, 0x089C, 0xFFFF, 0xFFFF, 0xFFFF, 0x074C, 0x8630, 0x8773,
    0x8864, 0x81A7, 0x882C, 0x051A, 0xFFFF, 0xFFFF, 0xFFFF, 0x034E, 0x808C, 0x0683, 0x0306, 0x8096,
    0x80D1, 0x007C, 0x07C1, 0x083F, 0x08BB, 0xFFFF, 0xFFFF, 0x00B4, 0x02C1, 0x076A, 0x0211, 0xFFFF,
    0x0699, 0x8256, 0xFFFF, 0x81EB, 0x82F7, 0x813F, 0x84C0, 0x846D, 0xFFFF, 0x84ED, 0xFFFF, 0x88AC,
    0x0741, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x811C, 0xFFFF, 0x0229, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0x0199, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x854D, 0x8209, 0x03A3,
    0x072F, 0xFFFF, 0x80F6, 0xFFFF, 0x055A, 0xFFFF, 0xFFFF, 0xFFFF, 0x8231, 0x03D8, 0x8591, 0xFFFF,
    0x83C9, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x061F, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x82E4,
    0x04C8, 0x0702, 0xFFFF, 0xFFFF, 0x05D5, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0189, 0x0283,
    0x01BF, 0x07A8, 0xFFFF, 0xFFFF, 0x8170, 0x01AE, 0x85DB, 0xFFFF, 0x8085, 0x03AD, 0x03E1, 0x8390,
    0x018F, 0xFFFF, 0x0221, 0x04D1, 0x0182, 0xFFFF, 0x043B, 0x03C2, 0x02D3, 0xFFFF, 0xFFFF, 0xFFFF,
    0x0126, 0x8848, 0x8412, 0x07E1, 0x885B, 0x886E, 0xFFFF, 0x079D, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0xFFFF, 0xFFFF, 0x8135, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x81C8, 0x06F0,
    0x8058, 0xFFFF, 0xFFFF, 0x0493, 0x86D6, 0xFFFF, 0xFFFF, 0x8019, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0x07B0, 0x05C2, 0xFFFF, 0x0329, 0xFFFF, 0x04E4, 0x028B, 0x0756, 0xFFFF, 0x824D, 0xFFFF, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0x0543, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x8107, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    0x06BD, 0x847F, 0x8436, 0x0581, 0x03EB, 0x073A, 0x0359, 0x0760, 0xFFFF, 0x0587, 0xFFFF, 0xFFFF,
    0x06B3, 0xFFFF, 0x04F6, 0xFFFF, 0x0629, 0xFFFF, 0x0444, 0xFFFF, 0x817A, 0x87EA, 0x87C9, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0795, 0x05B8, 0x8728, 0x0458,
    0xFFFF, 0xFFFF, 0x0260, 0xFFFF, 0x8386, 0x0614, 0xFFFF, 0xFFFF, 0x8463, 0xFFFF, 0x881A, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0x012C, 0xFFFF, 0x0823, 0xFFFF, 0x026C, 0xFFFF, 0x01FE, 0x04B7, 0x8552,
    0x044D, 0x0577, 0xFFFF, 0xFFFF, 0x8408, 0xFFFF, 0xFFFF, 0x8638, 0xFFFF, 0xFFFF, 0xFFFF, 0x02DC,
    0xFFFF, 0x00A9, 0x80BB, 0x85CB, 0x856D, 0x809F, 0xFFFF, 0xFFFF, 0xFFFF, 0x0891, 0xFFFF, 0x85F7,
    0x0152, 0x0599, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x8889, 0xFFFF, 0x06E6, 0x81F4, 0x02B7, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x042F, 0x030E, 0x0071, 0x0345, 0x04A5, 0xFFFF, 0xFFFF,
    0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x8035, 0x0050, 0x8148, 0x07F3, 0x82A5, 0x82AF,
    0x04DA, 0x8022, 0xFFFF, 0xFFFF, 0x88A2, 0xFFFF, 0x0693, 0x8640, 0xFFFF, 0xFFFF, 0x80C3, 0x84AD,
    0x864A, 0x06C6, 0x00EE, 0x0489, 0x87D1, 0x0875, 0xFFFF, 0xFFFF,
  };

  /** Packed entity records; every char holds one unsigned byte of the Rust table. */
  private static final String ENTITY_DATA =
      "\u0005AElig\u0002\u00C3\u0086\u0003AMP\u0001&\u0006Aacute\u0002\u00C3\u0081\u0005Acirc\u0002"
      + "\u00C3\u0082\u0006Agrave\u0002\u00C3\u0080\u0005Alpha\u0002\u00CE\u0091\u0005Aring\u0002\u00C3\u0085\u0006A"
      + "tilde\u0002\u00C3\u0083\u0004Auml\u0002\u00C3\u0084\u0004Beta\u0002\u00CE\u0092\u0004COPY\u0002\u00C2\u00A9"
      + "\u0006Ccedil\u0002\u00C3\u0087\u0003Chi\u0002\u00CE\u00A7\u0006Dagger\u0003\u00E2\u0080\u00A1\u0005Del"
      + "ta\u0002\u00CE\u0094\u0003ETH\u0002\u00C3\u0090\u0006Eacute\u0002\u00C3\u0089\u0005Ecirc\u0002\u00C3\u008A\u0006"
      + "Egrave\u0002\u00C3\u0088\u0007Epsilon\u0002\u00CE\u0095\u0003Eta\u0002\u00CE\u0097\u0004Euml"
      + "\u0002\u00C3\u008B\u0002GT\u0001>\u0005Gamma\u0002\u00CE\u0093\u0006Iacute\u0002\u00C3\u008D\u0005Icir"
      + "c\u0002\u00C3\u008E\u0006Igrave\u0002\u00C3\u008C\u0004Iota\u0002\u00CE\u0099\u0004Iuml\u0002\u00C3\u008F\u0005K"
      + "appa\u0002\u00CE\u009A\u0002LT\u0001<\u0006Lambda\u0002\u00CE\u009B\u0002Mu\u0002\u00CE\u009C\u0006Nti"
      + "lde\u0002\u00C3\u0091\u0002Nu\u0002\u00CE\u009D\u0005OElig\u0002\u00C5\u0092\u0006Oacute\u0002\u00C3\u0093\u0005"
      + "Ocirc\u0002\u00C3\u0094\u0006Ograve\u0002\u00C3\u0092\u0005Omega\u0002\u00CE\u00A9\u0007Omic"
      + "ron\u0002\u00CE\u009F\u0006Oslash\u0002\u00C3\u0098\u0006Otilde\u0002\u00C3\u0095\u0004Ouml\u0002"
      + "\u00C3\u0096\u0003Phi\u0002\u00CE\u00A6\u0002Pi\u0002\u00CE\u00A0\u0005Prime\u0003\u00E2\u0080\u00B3\u0003Psi\u0002\u00CE\u00A8"
      + "\u0004QUOT\u0001\"\u0003REG\u0002\u00C2\u00AE\u0003Rho\u0002\u00CE\u00A1\u0006Scaron\u0002\u00C5\u00A0\u0005"
      + "Sigma\u0002\u00CE\u00A3\u0005THORN\u0002\u00C3\u009E\u0003Tau\u0002\u00CE\u00A4\u0005Theta\u0002\u00CE"
      + "\u0098\u0006Uacute\u0002\u00C3\u009A\u0005Ucirc\u0002\u00C3\u009B\u0006Ugrave\u0002\u00C3\u0099\u0007U"
      + "psilon\u0002\u00CE\u00A5\u0004Uuml\u0002\u00C3\u009C\u0002Xi\u0002\u00CE\u009E\u0006Yacute\u0002\u00C3"
      + "\u009D\u0004Yuml\u0002\u00C5\u00B8\u0004Zeta\u0002\u00CE\u0096\u0006aacute\u0002\u00C3\u00A1\u0005acir"
      + "c\u0002\u00C3\u00A2\u0005acute\u0002\u00C2\u00B4\u0005aelig\u0002\u00C3\u00A6\u0006agrave\u0002\u00C3\u00A0"
      + "\u0007alefsym\u0003\u00E2\u0084\u00B5\u0005alpha\u0002\u00CE\u00B1\u0003amp\u0001&\u0003and\u0003"
      + "\u00E2\u0088\u00A7\u0003ang\u0003\u00E2\u0088\u00A0\u0004apos\u0001'\u0005aring\u0002\u00C3\u00A5\u0005asym"
      + "p\u0003\u00E2\u0089\u0088\u0006atilde\u0002\u00C3\u00A3\u0004auml\u0002\u00C3\u00A4\u0005bdquo\u0003\u00E2\u0080"
      + "\u009E\u0004beta\u0002\u00CE\u00B2\u0006brvbar\u0002\u00C2\u00A6\u0004bull\u0003\u00E2\u0080\u00A2\u0003cap"
      + "\u0003\u00E2\u0088\u00A9\u0006ccedil\u0002\u00C3\u00A7\u0005cedil\u0002\u00C2\u00B8\u0004cent\u0002\u00C2\u00A2\u0003"
      + "chi\u0002\u00CF\u0087\u0004circ\u0002\u00CB\u0086\u0005clubs\u0003\u00E2\u0099\u00A3\u0004cong\u0003\u00E2\u0089"
      + "\u0085\u0004copy\u0002\u00C2\u00A9\u0005crarr\u0003\u00E2\u0086\u00B5\u0003cup\u0003\u00E2\u0088\u00AA\u0006curr"
      + "en\u0002\u00C2\u00A4\u0004dArr\u0003\u00E2\u0087\u0093\u0006dagger\u0003\u00E2\u0080\u00A0\u0004darr\u0003\u00E2"
      + "\u0086\u0093\u0003deg\u0002\u00C2\u00B0\u0005delta\u0002\u00CE\u00B4\u0005diams\u0003\u00E2\u0099\u00A6\u0006div"
      + "ide\u0002\u00C3\u00B7\u0006eacute\u0002\u00C3\u00A9\u0005ecirc\u0002\u00C3\u00AA\u0006egrave"
      + "\u0002\u00C3\u00A8\u0005empty\u0003\u00E2\u0088\u0085\u0007epsilon\u0002\u00CE\u00B5\u0005equiv\u0003\u00E2"
      + "\u0089\u00A1\u0003eta\u0002\u00CE\u00B7\u0003eth\u0002\u00C3\u00B0\u0004euml\u0002\u00C3\u00AB\u0004euro\u0003\u00E2\u0082"
      + "\u00AC\u0005exist\u0003\u00E2\u0088\u0083\u0004fnof\u0002\u00C6\u0092\u0006forall\u0003\u00E2\u0088\u0080\u0006f"
      + "rac12\u0002\u00C2\u00BD\u0006frac14\u0002\u00C2\u00BC\u0006frac34\u0002\u00C2\u00BE\u0005fra"
      + "sl\u0003\u00E2\u0081\u0084\u0005gamma\u0002\u00CE\u00B3\u0002ge\u0003\u00E2\u0089\u00A5\u0002gt\u0001>\u0004hArr"
      + "\u0003\u00E2\u0087\u0094\u0004harr\u0003\u00E2\u0086\u0094\u0006hearts\u0003\u00E2\u0099\u00A5\u0006hellip\u0003"
      + "\u00E2\u0080\u00A6\u0006iacute\u0002\u00C3\u00AD\u0005icirc\u0002\u00C3\u00AE\u0005iexcl\u0002\u00C2\u00A1\u0006"
      + "igrave\u0002\u00C3\u00AC\u0005image\u0003\u00E2\u0084\u0091\u0005infin\u0003\u00E2\u0088\u009E\u0003in"
      + "t\u0003\u00E2\u0088\u00AB\u0004iota\u0002\u00CE\u00B9\u0006iquest\u0002\u00C2\u00BF\u0004isin\u0003\u00E2\u0088\u0088"
      + "\u0004iuml\u0002\u00C3\u00AF\u0005kappa\u0002\u00CE\u00BA\u0004lArr\u0003\u00E2\u0087\u0090\u0006lambd"
      + "a\u0002\u00CE\u00BB\u0004lang\u0003\u00E2\u009F\u00A8\u0005laquo\u0002\u00C2\u00AB\u0004larr\u0003\u00E2\u0086\u0090\u0005"
      + "lceil\u0003\u00E2\u008C\u0088\u0005ldquo\u0003\u00E2\u0080\u009C\u0002le\u0003\u00E2\u0089\u00A4\u0006lfloo"
      + "r\u0003\u00E2\u008C\u008A\u0006lowast\u0003\u00E2\u0088\u0097\u0003loz\u0003\u00E2\u0097\u008A\u0006lsaquo\u0003"
      + "\u00E2\u0080\u00B9\u0005lsquo\u0003\u00E2\u0080\u0098\u0002lt\u0001<\u0004macr\u0002\u00C2\u00AF\u0005mdash"
      + "\u0003\u00E2\u0080\u0094\u0005micro\u0002\u00C2\u00B5\u0006middot\u0002\u00C2\u00B7\u0005minus\u0003\u00E2\u0088"
      + "\u0092\u0002mu\u0002\u00CE\u00BC\u0005nabla\u0003\u00E2\u0088\u0087\u0004nbsp\u0002\u00C2\u00A0\u0005ndash\u0003"
      + "\u00E2\u0080\u0093\u0002ne\u0003\u00E2\u0089\u00A0\u0002ni\u0003\u00E2\u0088\u008B\u0003not\u0002\u00C2\u00AC\u0005notin\u0003\u00E2"
      + "\u0088\u0089\u0004nsub\u0003\u00E2\u008A\u0084\u0006ntilde\u0002\u00C3\u00B1\u0002nu\u0002\u00CE\u00BD\u0006oacu"
      + "te\u0002\u00C3\u00B3\u0005ocirc\u0002\u00C3\u00B4\u0005oelig\u0002\u00C5\u0093\u0006ograve\u0002\u00C3"
      + "\u00B2\u0005oline\u0003\u00E2\u0080\u00BE\u0005omega\u0002\u00CF\u0089\u0007omicron\u0002\u00CE\u00BF\u0005"
      + "oplus\u0003\u00E2\u008A\u0095\u0002or\u0003\u00E2\u0088\u00A8\u0004ordf\u0002\u00C2\u00AA\u0004ordm\u0002\u00C2\u00BA"
      + "\u0006oslash\u0002\u00C3\u00B8\u0006otilde\u0002\u00C3\u00B5\u0006otimes\u0003\u00E2\u008A\u0097\u0004"
      + "ouml\u0002\u00C3\u00B6\u0004para\u0002\u00C2\u00B6\u0004part\u0003\u00E2\u0088\u0082\u0006permil\u0003"
      + "\u00E2\u0080\u00B0\u0004perp\u0003\u00E2\u008A\u00A5\u0003phi\u0002\u00CF\u0086\u0002pi\u0002\u00CF\u0080\u0003piv\u0002\u00CF\u0096"
      + "\u0006plusmn\u0002\u00C2\u00B1\u0005pound\u0002\u00C2\u00A3\u0005prime\u0003\u00E2\u0080\u00B2\u0004pr"
      + "od\u0003\u00E2\u0088\u008F\u0004prop\u0003\u00E2\u0088\u009D\u0003psi\u0002\u00CF\u0088\u0004quot\u0001\"\u0004rA"
      + "rr\u0003\u00E2\u0087\u0092\u0005radic\u0003\u00E2\u0088\u009A\u0004rang\u0003\u00E2\u009F\u00A9\u0005raquo\u0002"
      + "\u00C2\u00BB\u0004rarr\u0003\u00E2\u0086\u0092\u0005rceil\u0003\u00E2\u008C\u0089\u0005rdquo\u0003\u00E2\u0080\u009D\u0004"
      + "real\u0003\u00E2\u0084\u009C\u0003reg\u0002\u00C2\u00AE\u0006rfloor\u0003\u00E2\u008C\u008B\u0003rho\u0002\u00CF"
      + "\u0081\u0006rsaquo\u0003\u00E2\u0080\u00BA\u0005rsquo\u0003\u00E2\u0080\u0099\u0005sbquo\u0003\u00E2\u0080\u009A"
      + "\u0006scaron\u0002\u00C5\u00A1\u0004sdot\u0003\u00E2\u008B\u0085\u0004sect\u0002\u00C2\u00A7\u0003shy\u0002"
      + "\u00C2\u00AD\u0005sigma\u0002\u00CF\u0083\u0006sigmaf\u0002\u00CF\u0082\u0003sim\u0003\u00E2\u0088\u00BC\u0006sp"
      + "ades\u0003\u00E2\u0099\u00A0\u0003sub\u0003\u00E2\u008A\u0082\u0004sube\u0003\u00E2\u008A\u0086\u0003sum\u0003\u00E2\u0088"
      + "\u0091\u0003sup\u0003\u00E2\u008A\u0083\u0004sup1\u0002\u00C2\u00B9\u0004sup2\u0002\u00C2\u00B2\u0004sup3\u0002\u00C2"
      + "\u00B3\u0004supe\u0003\u00E2\u008A\u0087\u0005szlig\u0002\u00C3\u009F\u0003tau\u0002\u00CF\u0084\u0006there"
      + "4\u0003\u00E2\u0088\u00B4\u0005theta\u0002\u00CE\u00B8\u0008thetasym\u0002\u00CF\u0091\u0005thorn"
      + "\u0002\u00C3\u00BE\u0005tilde\u0002\u00CB\u009C\u0005times\u0002\u00C3\u0097\u0005trade\u0003\u00E2\u0084\u00A2\u0004"
      + "uArr\u0003\u00E2\u0087\u0091\u0006uacute\u0002\u00C3\u00BA\u0004uarr\u0003\u00E2\u0086\u0091\u0005ucir"
      + "c\u0002\u00C3\u00BB\u0006ugrave\u0002\u00C3\u00B9\u0003uml\u0002\u00C2\u00A8\u0005upsih\u0002\u00CF\u0092\u0007u"
      + "psilon\u0002\u00CF\u0085\u0004uuml\u0002\u00C3\u00BC\u0006weierp\u0003\u00E2\u0084\u0098\u0002xi\u0002"
      + "\u00CE\u00BE\u0006yacute\u0002\u00C3\u00BD\u0003yen\u0002\u00C2\u00A5\u0004yuml\u0002\u00C3\u00BF\u0004zeta"
      + "\u0002\u00CE\u00B6";

  /** Replacement text, indexed by the slot that holds its entry. */
  private static final String[] ENTITY_VALUES = decodeEntityValues();

  private Entities() {}

  /**
   * Decodes character references from {@code text} into {@code out}.
   *
   * <p>{@code out} is cleared first. When nothing decodes it is left empty and
   * this method returns {@code false}; otherwise it holds the decoded text and
   * this method returns {@code true}. Delaying copies keeps literal ampersands
   * allocation-free.
   *
   * @param text source text
   * @param inAttribute whether legacy references followed by {@code =} or an
   *     alphanumeric character must stay undecoded
   * @param protectDecodedEntityReferences whether a decoded {@code &} that
   *     starts another character reference is written as a backslash followed
   *     by {@code &}
   * @param out destination owned by the caller
   * @return {@code true} when at least one reference was decoded
   */
  public static boolean decodeInto(
      CharSequence text,
      boolean inAttribute,
      boolean protectDecodedEntityReferences,
      StringBuilder out) {
    int len = text.length();
    out.setLength(0);
    int copied = 0;
    int i = 0;

    while (i < len) {
      if (text.charAt(i) != '&') {
        i = indexOf(text, '&', i);
        continue;
      }

      // Consume numeric references through the first non-digit. The semicolon
      // is optional; overflow saturates while all remaining digits are consumed.
      if (i + 2 < len && text.charAt(i + 1) == '#') {
        int end = i + 2;
        boolean isHex = text.charAt(end) == 'x' || text.charAt(end) == 'X';
        if (isHex) {
          end++;
        }
        int digitStart = end;
        int radix = isHex ? 16 : 10;
        int codePoint = 0;

        while (end < len) {
          char c = text.charAt(end);
          int digit;
          if (c >= '0' && c <= '9') {
            digit = c - '0';
          } else if (isHex && c >= 'A' && c <= 'F') {
            digit = c - 'A' + 10;
          } else if (isHex && c >= 'a' && c <= 'f') {
            digit = c - 'a' + 10;
          } else {
            break;
          }
          if (codePoint < 0x110000) {
            codePoint = Math.min(codePoint * radix + digit, 0x110000);
          }
          end++;
        }

        if (end > digitStart) {
          if (copied == 0) {
            out.ensureCapacity(len);
          }
          out.append(text, copied, i);
          int replacement = decodeNumericRef(codePoint);
          int next = (end < len && text.charAt(end) == ';') ? end + 1 : end;
          if (replacement == '&'
              && protectDecodedEntityReferences
              && isCharacterReferenceTail(text, next)) {
            out.append("\\&");
          } else {
            out.appendCodePoint(replacement);
          }
          if (end < len && text.charAt(end) == ';') {
            end++;
          }
          i = end;
          copied = i;
          continue;
        }
      }

      // A canonical name includes a semicolon. If that misses, find the longest
      // legacy name prefix; only those 106 names may omit their semicolon.
      int nameStart = i + 1;
      int nameEnd = nameStart;
      int scanEnd = Math.min(len, nameStart + MAX_ENTITY_NAME_LENGTH);
      while (nameEnd < scanEnd && isAsciiAlphanumeric(text.charAt(nameEnd))) {
        nameEnd++;
      }

      if (nameEnd > nameStart && nameEnd < len && text.charAt(nameEnd) == ';') {
        String replacement = lookupNamedEntity(text, nameStart, nameEnd);
        if (replacement != null) {
          if (copied == 0) {
            out.ensureCapacity(len);
          }
          out.append(text, copied, i);
          pushDecodedReference(
              out, replacement, text, nameEnd + 1, protectDecodedEntityReferences);
          i = nameEnd + 1;
          copied = i;
          continue;
        }
      }

      int legacyEnd = Math.min(nameEnd, nameStart + MAX_LEGACY_ENTITY_NAME_LENGTH);
      boolean decodedLegacy = false;
      while (legacyEnd > nameStart) {
        String replacement = lookupLegacyNamedEntity(text, nameStart, legacyEnd);
        if (replacement != null) {
          boolean hasNext = legacyEnd < len;
          char next = hasNext ? text.charAt(legacyEnd) : 0;
          boolean ambiguousAttribute =
              inAttribute && hasNext && (next == '=' || isAsciiAlphanumeric(next));
          if (!ambiguousAttribute) {
            if (copied == 0) {
              out.ensureCapacity(len);
            }
            out.append(text, copied, i);
            pushDecodedReference(
                out, replacement, text, legacyEnd, protectDecodedEntityReferences);
            i = legacyEnd;
            copied = i;
            decodedLegacy = true;
          }
          break;
        }
        legacyEnd--;
      }
      if (decodedLegacy) {
        continue;
      }

      i++;
    }

    if (copied == 0) {
      return false;
    }
    out.append(text, copied, len);
    return true;
  }

  /** Overwrites {@code out} with the decoded attribute value. */
  public static void decodeAttributeEntitiesInto(CharSequence text, StringBuilder out) {
    // decodeInto leaves out empty when nothing decodes.
    if (!decodeInto(text, true, false, out)) {
      out.setLength(0);
      out.append(text);
    }
  }

  /** Decodes text without attribute or double-decoding protection. */
  public static String decodeHtmlEntities(CharSequence text) {
    return decodeHtmlEntitiesInContext(text, false, false);
  }

  private static String decodeHtmlEntitiesInContext(
      CharSequence text, boolean inAttribute, boolean protectDecodedEntityReferences) {
    if (indexOf(text, '&', 0) == text.length()) {
      return text.toString();
    }
    StringBuilder result = new StringBuilder(text.length());
    if (decodeInto(text, inAttribute, protectDecodedEntityReferences, result)) {
      return result.toString();
    }
    return text.toString();
  }

  /** Resolves a numeric character reference value per the HTML standard. */
  private static int decodeNumericRef(int code) {
    int codePoint =
        (code >= 0x80 && code <= 0x9F) ? C1_REPLACEMENTS[code - 0x80] : code;
    if (codePoint == 0 || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
      return 0xFFFD;
    }
    return codePoint;
  }

  private static boolean isCharacterReferenceTail(CharSequence text, int start) {
    int len = text.length();
    int index = start;
    if (index < len && text.charAt(index) == '#') {
      index++;
      boolean hex =
          index < len && (text.charAt(index) == 'x' || text.charAt(index) == 'X');
      if (hex) {
        index++;
      }
      int digitStart = index;
      while (index < len) {
        char c = text.charAt(index);
        if (isAsciiDigit(c) || (hex && isAsciiHexDigit(c))) {
          index++;
        } else {
          break;
        }
      }
      return index > digitStart && index < len && text.charAt(index) == ';';
    }

    int nameStart = index;
    while (index < len && isAsciiAlphanumeric(text.charAt(index))) {
      index++;
    }
    return index > nameStart && index < len && text.charAt(index) == ';';
  }

  private static void pushDecodedReference(
      StringBuilder result,
      String replacement,
      CharSequence text,
      int next,
      boolean protectDecodedEntityReferences) {
    if (replacement.equals("&")
        && protectDecodedEntityReferences
        && isCharacterReferenceTail(text, next)) {
      result.append("\\&");
    } else {
      result.append(replacement);
    }
  }

  private static int indexOf(CharSequence text, char target, int from) {
    int len = text.length();
    for (int i = from; i < len; i++) {
      if (text.charAt(i) == target) {
        return i;
      }
    }
    return len;
  }

  private static boolean isAsciiAlphanumeric(int c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  private static boolean isAsciiDigit(int c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isAsciiHexDigit(int c) {
    return isAsciiDigit(c) || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
  }

  /** FNV-1a over the name characters, matching the generator's name hash. */
  private static int entityNameHash(CharSequence name, int start, int end) {
    int hash = 0x811C9DC5;
    for (int i = start; i < end; i++) {
      hash = (hash ^ name.charAt(i)) * 0x01000193;
    }
    return hash;
  }

  /** Linear probing; returns the slot index or {@code -1}. */
  private static int findEntitySlot(CharSequence name, int start, int end) {
    int slotMask = ENTITY_SLOTS.length - 1;
    int slot = entityNameHash(name, start, end) & slotMask;
    int nameLength = end - start;
    while (true) {
      int encoded = ENTITY_SLOTS[slot];
      if (encoded == ENTITY_SLOT_EMPTY) {
        return -1;
      }
      int offset = encoded & ~LEGACY_FLAG;
      if (ENTITY_DATA.charAt(offset) == nameLength) {
        int entryNameStart = offset + 1;
        int matched = 0;
        while (matched < nameLength
            && ENTITY_DATA.charAt(entryNameStart + matched) == name.charAt(start + matched)) {
          matched++;
        }
        if (matched == nameLength) {
          return slot;
        }
      }
      slot = (slot + 1) & slotMask;
    }
  }

  private static String lookupNamedEntity(CharSequence name, int start, int end) {
    int slot = findEntitySlot(name, start, end);
    return slot < 0 ? null : ENTITY_VALUES[slot];
  }

  private static String lookupLegacyNamedEntity(CharSequence name, int start, int end) {
    int slot = findEntitySlot(name, start, end);
    if (slot < 0 || (ENTITY_SLOTS[slot] & LEGACY_FLAG) == 0) {
      return null;
    }
    return ENTITY_VALUES[slot];
  }

  private static String[] decodeEntityValues() {
    String[] values = new String[ENTITY_SLOTS.length];
    for (int slot = 0; slot < ENTITY_SLOTS.length; slot++) {
      int encoded = ENTITY_SLOTS[slot];
      if (encoded == ENTITY_SLOT_EMPTY) {
        continue;
      }
      int offset = encoded & ~LEGACY_FLAG;
      int valueLengthOffset = offset + 1 + ENTITY_DATA.charAt(offset);
      int valueLength = ENTITY_DATA.charAt(valueLengthOffset);
      byte[] valueBytes = new byte[valueLength];
      for (int i = 0; i < valueLength; i++) {
        valueBytes[i] = (byte) ENTITY_DATA.charAt(valueLengthOffset + 1 + i);
      }
      values[slot] = new String(valueBytes, StandardCharsets.UTF_8);
    }
    return values;
  }
}
