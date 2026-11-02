/*
 * Copyright (c) 2026 mdream4j contributors.
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */
package io.github.fbricon.mdream4j.internal;

/** Pre-parsed CSS selector. Mirrors Rust {@code ParsedSelector}. */
final class ParsedSelector {

  enum Kind {
    TAG,
    CLASS,
    ID,
    ATTRIBUTE,
    COMPOUND,
    LIST
  }

  final Kind kind;

  /** Tag, class or id text. */
  final String text;

  /** Attribute name. */
  final String name;

  /** Attribute operator, {@code null} when the selector checks presence only. */
  final String operator;

  /** Attribute value, {@code null} when the selector checks presence only. */
  final String value;

  /** Compound parts or list members. */
  final ParsedSelector[] parts;

  private ParsedSelector(
      Kind kind, String text, String name, String operator, String value, ParsedSelector[] parts) {
    this.kind = kind;
    this.text = text;
    this.name = name;
    this.operator = operator;
    this.value = value;
    this.parts = parts;
  }

  static ParsedSelector tag(String text) {
    return new ParsedSelector(Kind.TAG, text, null, null, null, null);
  }

  static ParsedSelector clazz(String text) {
    return new ParsedSelector(Kind.CLASS, text, null, null, null, null);
  }

  static ParsedSelector id(String text) {
    return new ParsedSelector(Kind.ID, text, null, null, null, null);
  }

  static ParsedSelector attribute(String name, String operator, String value) {
    return new ParsedSelector(Kind.ATTRIBUTE, null, name, operator, value, null);
  }

  static ParsedSelector compound(ParsedSelector[] parts) {
    return new ParsedSelector(Kind.COMPOUND, null, null, null, null, parts);
  }

  static ParsedSelector list(ParsedSelector[] parts) {
    return new ParsedSelector(Kind.LIST, null, null, null, null, parts);
  }
}
